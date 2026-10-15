package app.wifiautoswitch.core

/** Which of the two user-selected networks we are talking about. */
enum class Role(val label: String) {
    UPSTAIRS("Upstairs"),
    DOWNSTAIRS("Downstairs");

    val other: Role get() = if (this == UPSTAIRS) DOWNSTAIRS else UPSTAIRS
}

object Ssid {
    private const val UNKNOWN = "<unknown ssid>"

    /** Android stores UTF-8 SSIDs wrapped in quotes and reports "<unknown ssid>" when hidden from us. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s == UNKNOWN) return null
        return if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
    }
}

/** Plain-language signal quality used by the UI. */
object SignalQuality {
    fun bars(rssi: Int?): Int = when {
        rssi == null || rssi <= -127 -> 0
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }

    fun label(rssi: Int?): String = when (bars(rssi)) {
        4 -> "Excellent"
        3 -> "Good"
        2 -> "Fair"
        1 -> "Weak"
        else -> if (rssi == null || rssi <= -127) "Unknown" else "Very weak"
    }
}
