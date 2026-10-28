package com.earhop.logger

/**
 * Merges the copies of one device's status that arrive over Wi-Fi and Bluetooth LE, the same rule as PeerOrder in
 * Protocol.swift. Both links carry the same sequence number (Wi-Fi in full, Bluetooth LE only its low 16 bits), so the
 * newest copy wins whichever link brought it. Pure, so it is unit tested.
 */
object PeerOrder {
    /** A number this far below the last one means the device restarted and counts from 1 again. */
    const val RESTART_GAP = 300

    /** The full sequence number closest to [last] whose low 16 bits are [low16]. */
    fun widen(low16: Int, last: Int): Int {
        val base = (last and 0xFFFF.inv()) or (low16 and 0xFFFF)
        return listOf(base - 0x10000, base, base + 0x10000).minByOrNull { Math.abs(it - last) }!!
    }

    /** Whether to take this copy, and its full sequence number. A copy that is not newer only shows the device is alive. */
    fun accept(lastSeq: Int?, seq: Int, ble: Boolean): Pair<Boolean, Int> {
        val last = lastSeq ?: return Pair(true, seq)
        val full = if (ble) widen(seq, last) else seq
        if (full > last) return Pair(true, full)
        if (last - full > RESTART_GAP) return Pair(true, seq)
        return Pair(false, full)
    }
}
