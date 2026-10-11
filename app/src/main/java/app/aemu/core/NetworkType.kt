package app.aemu.core

/**
 * Radio technology the fake modem reports to the guest: the values of ServiceState.RIL_RADIO_TECHNOLOGY_*, which is
 * what the status bar and TelephonyManager.getNetworkType() are derived from. GSM-family only (the stub is a GSM modem).
 * Default is 2G: not every firmware copes with a 3G/4G registration state.
 */
object NetworkType {
    const val GPRS = 1
    const val EDGE = 2
    const val UMTS = 3
    const val HSDPA = 9
    const val HSUPA = 10
    const val HSPA = 11
    const val LTE = 14
    const val HSPAP = 15
    const val DEFAULT = EDGE

    /** [minApi]: HSPA+ and LTE exist in the framework since 4.0; 2.3 throws on those values */
    data class Entry(val tech: Int, val label: String, val minApi: Int)

    val ALL = listOf(
        Entry(GPRS, "2G · GPRS", 9),
        Entry(EDGE, "2G · EDGE", 9),
        Entry(UMTS, "3G · UMTS", 9),
        Entry(HSDPA, "3G · HSDPA", 9),
        Entry(HSUPA, "3G · HSUPA", 9),
        Entry(HSPA, "3G · HSPA", 9),
        Entry(HSPAP, "3.5G · HSPA+", 14),
        Entry(LTE, "4G · LTE", 14),
    )

    fun available(api: Int): List<Entry> = ALL.filter { api >= it.minApi }

    /** unknown value → default; a value the firmware's API level cannot represent → HSPA */
    fun sanitize(tech: Int, api: Int = Int.MAX_VALUE): Int = when {
        ALL.none { it.tech == tech } -> DEFAULT
        available(api).any { it.tech == tech } -> tech
        else -> HSPA
    }

    fun label(tech: Int): String = ALL.firstOrNull { it.tech == tech }?.label ?: tech.toString()
}
