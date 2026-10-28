package com.earhop.logger

/** Decides whether automatic claiming should hold off. Pure, so it can be unit tested. Manual Take always works. */
object AutoGate {
    class Rules(
        val quietEnabled: Boolean = false,
        val quietStartMin: Int = 22 * 60,
        val quietEndMin: Int = 7 * 60,
        val wifiOnly: Boolean = false,
        val wifiNames: Set<String> = emptySet(),
        val respectDnd: Boolean = false,
    )

    /** minuteOfDay 0..1439, ssid null when unknown, dndOn true when Do Not Disturb filters sounds. Returns a reason, or null to allow. */
    fun reason(r: Rules, minuteOfDay: Int, ssid: String?, dndOn: Boolean): String? {
        if (r.quietEnabled) {
            val a = r.quietStartMin
            val b = r.quietEndMin
            val inside = if (a <= b) minuteOfDay in a until b else (minuteOfDay >= a || minuteOfDay < b)
            if (inside) return "quiet hours"
        }
        if (r.wifiOnly && r.wifiNames.isNotEmpty() && ssid != null && ssid !in r.wifiNames) return "not on a listed Wi-Fi ($ssid)"
        if (r.respectDnd && dndOn) return "Do Not Disturb is on"
        return null
    }
}
