package com.earhop.logger

import android.content.ClipData
import android.net.LocalServerSocket
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The phone's clipboard helper. Android 10 and later do not let a background app read the clipboard, and on the phone
 * phone the system log does not say when a copy was refused, so the log trick used on the tablet cannot work there.
 * The shell user (what adb runs as) is allowed to read the clipboard in the background. This helper is started once
 * from a computer (tools/phone_clip.sh), runs as the shell user, watches the clipboard and hands each new text to the
 * EarHop app through its provider; the app checks that the caller is the shell user, then encrypts and sends it as
 * usual. It keeps running when the cable is unplugged and stops when the phone restarts.
 */
object ClipHelper {
    const val URI = "content://com.earhop.logger.helper"
    /** Longest text passed on a command line (Linux allows 128 KB per argument). */
    private const val MAX_ARG = 100_000
    private const val GUARD = "earhop.clip.helper"
    private const val PKG = "com.android.shell"
    const val VERSION = 1
    const val TYPE_HELLO = 0
    const val TYPE_TEXT = 1

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 2 && args[0] == "--copy") { copyForTest(args[1]); return }
        if (args.size == 1 && args[0] == "--describe") { describeForTest(); return }
        // Only one helper at a time: the second one exits.
        val guard = try { LocalServerSocket(GUARD) } catch (e: Exception) { println("EarHop clipboard helper is already running"); return }
        Looper.prepareMainLooper()
        val main = Handler(Looper.getMainLooper())
        val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
        val cb = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, service)!!
        val get = cb.javaClass.methods.first { it.name == "getPrimaryClip" }
        var last = 0

        fun read(): Pair<String, Boolean>? {
            val clip = try { get.invoke(cb, *args(get, null)) as ClipData? } catch (e: Exception) { println("read failed: ${e.cause ?: e}"); null }
            if (clip == null || clip.itemCount == 0) return null
            val item = clip.getItemAt(0)
            val text = item.text?.toString() ?: item.htmlText ?: return null
            val sensitive = clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
            return Pair(text, sensitive)
        }

        // Android shows "Shell pasted from your clipboard" when the helper reads a clip, so it reads only real new copies:
        // the description (which does not show that) says whether the clip changed and who put it there.
        val getDesc = cb.javaClass.methods.firstOrNull { it.name == "getPrimaryClipDescription" }
        fun describe(): android.content.ClipDescription? =
            try { getDesc?.invoke(cb, *args(getDesc, null)) as android.content.ClipDescription? } catch (e: Exception) { null }
        var lastStamp = describe()?.timestamp ?: -1L

        fun check() {
            val d = describe()
            if (d != null) {
                if (d.timestamp == lastStamp) return // nothing new
                lastStamp = d.timestamp
                if (d.label?.toString()?.startsWith("EarHop") == true) return // a clip EarHop put here from another device
                if (d.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return // passwords are never read
            }
            println("read: clip from ${d?.timestamp ?: "?"}") // each read can show Android's toast once
            val (text, sensitive) = read() ?: run { println("change: nothing readable"); return }
            val h = text.hashCode()
            if (h == last) return
            last = h
            println("change: text ${text.length} chars")
            deliver(TYPE_TEXT, sensitive, text.toByteArray())
        }

        // Some apps write the clipboard twice for one copy. Each read of a new clip shows "Shell pasted from your
        // clipboard", so wait until the clipboard has been quiet for a moment and read once.
        val checkLater = Runnable { check() }
        fun soon() { main.removeCallbacks(checkLater); main.postDelayed(checkLater, 800) }

        // What is on the clipboard already is not sent (lastStamp above); it is not read either.
        val listenerClass = Class.forName("android.content.IOnPrimaryClipChangedListener")
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.FIRST_CALL_TRANSACTION) { main.post { soon() }; return true }
                return super.onTransact(code, data, reply, flags)
            }
        }
        binder.attachInterface(null, listenerClass.name)
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, m, a ->
            when (m.name) {
                "asBinder" -> binder
                "dispatchPrimaryClipChanged" -> { main.post { soon() }; null }
                "hashCode" -> System.identityHashCode(binder)
                "equals" -> a?.getOrNull(0) === binder
                "toString" -> "EarHopClipListener"
                else -> null
            }
        }
        val add = cb.javaClass.methods.first { it.name == "addPrimaryClipChangedListener" }
        add.invoke(cb, *args(add, listener))
        println("EarHop clipboard helper running")
        // Says hello now and every 60 s, so the app knows the helper is there.
        val hello = object : Runnable {
            override fun run() { deliver(TYPE_HELLO, false, ByteArray(0)); main.postDelayed(this, 60_000) }
        }
        main.post(hello)
        // A safety net in case a change notification is missed.
        val poll = object : Runnable { override fun run() { if (!main.hasCallbacks(checkLater)) check(); main.postDelayed(this, 3_000) } } // reads only on a change
        main.postDelayed(poll, 3_000)
        Looper.loop()
        guard.close()
    }

    /** For testing from a computer: puts [text] on the clipboard as the shell user, like a copy in another app. */
    private fun copyForTest(text: String) {
        val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
        val cb = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, service)!!
        val set = cb.javaClass.methods.first { it.name == "setPrimaryClip" }
        set.invoke(cb, *args(set, ClipData.newPlainText("test", text)))
        println("copied")
    }

    /** For testing from a computer: prints what the helper sees of the clipboard without reading it. */
    private fun describeForTest() {
        val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
        val cb = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, service)!!
        val m = cb.javaClass.methods.firstOrNull { it.name == "getPrimaryClipDescription" }
        println("method: ${m?.parameterTypes?.joinToString { it.simpleName }}")
        val d = m?.invoke(cb, *args(m, null)) as android.content.ClipDescription?
        println("label=${d?.label} stamp=${d?.timestamp} mime=${d?.let { (0 until it.mimeTypeCount).map { i -> it.getMimeType(i) } }}")
    }

    /** Fills in a clipboard call whatever the Android version: package, then nulls, user 0, device 0. */
    private fun args(m: Method, listener: Any?): Array<Any?> {
        var strings = 0
        return m.parameterTypes.map { t ->
            when (t) {
                String::class.java -> if (strings++ == 0) PKG else null
                Int::class.javaPrimitiveType -> 0
                Boolean::class.javaPrimitiveType -> false
                else -> listener
            }
        }.toTypedArray()
    }

    /**
     * Tells the app through its provider (ClipHelperProvider), using Android's own content command. SELinux does not
     * let the shell user connect to an app's socket, but a provider call works, and the app sees the caller's uid.
     * Sensitive clips (passwords) are never passed on. Text too long for one command line makes the app read the
     * clipboard itself with its invisible send screen.
     */
    private fun deliver(type: Int, sensitive: Boolean, data: ByteArray) {
        if (sensitive) return
        val (method, arg) = when {
            type == TYPE_HELLO -> Pair("hello", null)
            data.size <= MAX_ARG -> Pair("text", String(data, Charsets.UTF_8))
            else -> Pair("big", null)
        }
        val cmd = arrayListOf("content", "call", "--uri", URI, "--method", method)
        if (arg != null) { cmd += "--arg"; cmd += arg }
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = String(p.inputStream.readBytes()).trim()
            if (p.waitFor() != 0 || out.contains("Exception")) println("deliver $method: ${out.take(200)}")
        } catch (e: Exception) { println("deliver failed: $e") }
    }
}

/** Where the helper hands over copies. Only the shell user (the helper) and root may call it. */
class ClipHelperProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) throw SecurityException("only the EarHop clipboard helper may call this")
        val ctx = context ?: return null
        ClipSync.onHelper(ctx.applicationContext, method, arg)
        return null
    }
    override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
    override fun getType(u: android.net.Uri): String? = null
    override fun insert(u: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(u: android.net.Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
}
