package com.earhop.logger

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import java.util.concurrent.Executors

/**
 * Runs the HandoffCore decisions on this device: connect and release through Claimer (hidden Bluetooth API),
 * pause and resume with explicit media keys, the switching stages for the screen, and the transition log.
 */
class HandoffController(
    private val ctx: Context,
    private val name: String,
    private val claimer: Claimer,
    /** Name filter of the active earbuds; changes when the earbuds switch (automatic detection). */
    @Volatile var filter: String,
    private val isOwner: () -> Boolean,
) {
    private val prefs = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    val core = HandoffCore(HandoffSettings(deviceName = name))
    private var lastPhase: HandoffPhase? = null
    private var lastHold: String? = null
    private val lock = Any()

    private val auto get() = prefs.getBoolean("auto", true)
    private var wasOff = false
    private val buds get() = Earbuds.short(ctx)

    init { EhState.controller = this }

    private fun refreshSettings(fast: Boolean) {
        core.settings.priority = RuleStore.priority(ctx)
        core.settings.requireEar = prefs.getBoolean("requireEar", false)
        core.settings.pauseWhenOneOut = prefs.getBoolean("pauseOnOne", true)
        // A full second of steady playback before switching: reliability over speed.
        core.settings.minPlayMs = if (fast) 300L else 1000L
        core.settings.live = true
    }

    fun testPause() {
        EhLog.w("handoff: test pause")
        pausePlayback()
    }

    fun clearSafeMode() {
        synchronized(lock) { core.clearSafeMode() }
        EhState.safeMode = false
        EhLog.w("handoff: auto switching resumed")
    }

    /** Pause whatever is playing: the explicit pause key plus a brief audio focus grab so players like YouTube stop. */
    private fun pausePlayback() {
        mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener { }.build()
            am.requestAudioFocus(req)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { am.abandonAudioFocusRequest(req) } catch (x: Exception) { }
            }, 1500)
        } catch (e: Exception) { EhLog.w("handoff: focus pause failed ${e.message}") }
    }

    /** Resume with the explicit play key. Never a toggle, so it can never pause audio that is already playing. */
    private fun resumePlayback() {
        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!am.isMusicActive) EhLog.w("handoff: play key sent but nothing plays yet (the player may ignore media keys)")
        }, 2000)
    }

    private fun mediaKey(code: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) { EhLog.w("handoff: media key failed ${e.message}") }
    }

    private fun ownerPeer(): String? {
        val now = System.currentTimeMillis()
        return EhState.peers.values.firstOrNull { it.owner && now - it.seenAt < 5000 }?.name
    }

    private fun run(list: List<HandoffAction>) {
        for (a in list) when (a) {
            is HandoffAction.Claim -> claim(a.reason, a.manual)
            is HandoffAction.Release -> release(a.reason)
            is HandoffAction.Pause -> { EhLog.w("pause: ${a.reason}"); pausePlayback() }
            is HandoffAction.Resume -> { EhLog.w("resume: ${a.reason}"); resumePlayback() }
            is HandoffAction.Info -> EhLog.w("info: ${a.text}")
            is HandoffAction.SafeMode -> {
                EhState.safeMode = a.on
                EhLog.w(if (a.on) "safe: auto switching paused after ${core.failures} failed switches" else "info: auto switching resumed")
            }
        }
    }

    private fun claim(reason: String, manual: Boolean) {
        EhLog.w("claim: taking the $buds ($reason)")
        // Galaxy Buds can take several seconds to accept a connection after leaving another device.
        val waits = if (Earbuds.isBuds(ctx)) 80 else 40
        worker.execute {
            var ok = false
            var blocked = false
            try {
                // Connecting while another device still holds the earbuds is slow and often fails, so give it up to 8 s to let go.
                val holder = ownerPeer()
                if (!manual && holder != null && !isOwner()) {
                    EhState.stage = "Waiting for $holder to let go"
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 8000 && ownerPeer() != null && !isOwner()) Thread.sleep(150)
                    EhLog.w("claim: waited ${System.currentTimeMillis() - t0} ms for $holder to let go")
                }
                for (attempt in 1..2) {
                    if (isOwner()) { ok = true; break }
                    EhState.stage = "Connecting here"
                    val r = claimer.take(filter)
                    if (r == null) blocked = true
                    EhState.stage = "Confirming connection"
                    for (i in 0 until waits) { Thread.sleep(150); if (isOwner()) { ok = true; break } }
                    if (ok) break
                    if (attempt == 1) EhLog.w("claim: not connected yet, retrying once")
                }
            } catch (e: Exception) { EhLog.w("claim: error ${e.message}") }
            val holder = ownerPeer()
            val failure = if (ok) null else when {
                blocked -> "Bluetooth connection was blocked by the phone. Connect from Bluetooth settings"
                holder != null -> "$holder is still connected to the $buds"
                else -> "the $buds did not accept the connection. Connect from Bluetooth settings"
            }
            EhState.stage = null
            EhState.lastFailure = failure
            val acts = synchronized(lock) { core.claimFinished(ok, manual, failure, System.currentTimeMillis()) }
            if (ok) {
                EhLog.w("claim: $buds connected here ($reason)")
                EhState.record("claim", reason)
                RuleStore.applyVolume(ctx)
            }
            run(acts)
            if (manual) { manualDone?.invoke(ok); manualDone = null }
        }
    }

    private fun release(reason: String) {
        EhLog.w("release: letting go of the $buds ($reason)")
        // An open Galaxy Buds status channel keeps the link busy and slows the disconnect, so close it first.
        EhState.budsClient?.suspend(5000)
        worker.execute {
            EhState.stage = "Releasing $buds"
            val t0 = System.currentTimeMillis()
            try { claimer.release(filter); Thread.sleep(300) } catch (e: Exception) { EhLog.w("release: error ${e.message}") }
            var still = isOwner()
            var waited = 0
            while (still && waited < 3000) { Thread.sleep(150); waited += 150; still = isOwner() }
            EhLog.w("release: took ${System.currentTimeMillis() - t0} ms, still connected=$still")
            EhState.lastFailure = if (still) "the $buds are still connected to this device" else null
            if (!still) EhState.record("release", reason)
            EhState.stage = null
            synchronized(lock) { core.releaseFinished(System.currentTimeMillis()) }
            // Only a manual release reports back to the screen, tile or widget that asked for it.
            if (reason == "manual") { manualDone?.invoke(!still); manualDone = null }
        }
    }

    @Volatile private var manualDone: ((Boolean) -> Unit)? = null

    /** Take or Release from the screen, a tile or the widget. Always allowed, even in safe mode or while gated. */
    fun manual(take: Boolean, done: ((Boolean) -> Unit)? = null) {
        val acts = synchronized(lock) { if (take) core.manualClaim(System.currentTimeMillis()) else core.manualRelease(System.currentTimeMillis()) }
        if (acts.isEmpty()) { done?.invoke(false); return }
        manualDone = done
        EhLog.w("handoff: manual ${if (take) "take" else "release"}")
        run(acts)
    }

    fun tick(playing: Boolean, ear: EarReading, owner: Boolean, call: Boolean, peers: Collection<PeerState>, fast: Boolean = false) {
        val paused = System.currentTimeMillis() < prefs.getLong("autoPausedUntil", 0)
        if (!auto || paused) {
            if (!wasOff) EhLog.w("handoff: automatic switching ${if (paused && auto) "paused" else "off"}")
            wasOff = true
            EhState.phase = if (owner) HandoffPhase.OWNER_IDLE else HandoffPhase.IDLE
            EhState.holdReason = "automatic switching is ${if (paused && auto) "paused" else "off"}"
            return
        }
        if (wasOff) { wasOff = false; EhLog.w("handoff: automatic switching on") }
        refreshSettings(fast)
        val gate = if (!owner) RuleStore.gate(ctx) else null
        val input = HandoffInput(System.currentTimeMillis(), playing, ear, owner, call, peers, gate)
        val acts = synchronized(lock) { core.tick(input) }
        if (core.phase != lastPhase || acts.isNotEmpty()) {
            val names = acts.joinToString("; ") { it.toString() }
            EhLog.w("sm: ${lastPhase?.name?.lowercase() ?: "start"} -> ${core.phase.name.lowercase()} ${core.describe(input, if (Earbuds.isBuds(ctx)) "buds" else "airpods")}" +
                if (names.isEmpty()) "" else " actions=[$names]")
            lastPhase = core.phase
        }
        if (core.holdReason != lastHold) {
            core.holdReason?.let { EhLog.w("hold: not taking the $buds: $it") }
            lastHold = core.holdReason
        }
        EhState.phase = core.phase
        EhState.holdReason = core.holdReason
        EhState.safeMode = core.safeMode
        if (owner) EhState.lastFailure = null
        run(acts)
    }
}
