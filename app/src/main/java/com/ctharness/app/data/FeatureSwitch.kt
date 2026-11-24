package com.ctharness.app.data

/** Every optional capability is off, automatic, or forced on; automatic is the default. */
enum class FeatureSwitch {
    OFF, AUTO, ON;

    companion object {
        fun parse(raw: String?): FeatureSwitch =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/** The capabilities a switch controls, with what "auto" means for each. */
enum class FeatureKey(val id: String, val needsDownload: Boolean) {
    HTTP_CLIENT("http_client", false),
    TERMINALS("terminals", false),
    WATCH_MODE("watch_mode", false),
    TEST_EXPLORER("test_explorer", false),
    PROOT("proot", true),
    LSP("lsp", true),
    DAP("dap", true);

    companion object {
        fun parse(raw: String?): FeatureKey? = entries.firstOrNull { it.id == raw }
    }
}

/** What the device can afford right now; filled from the app so the policy stays testable. */
data class FeatureSignals(
    val ramGb: Int = 8,
    val batteryPercent: Int = 100,
    val charging: Boolean = true,
    val installed: Set<FeatureKey> = emptySet(),
)

/**
 * "Automatic" is a policy, not a synonym for on. A download-backed feature stays
 * off until its payload exists, and background pollers pause on a low battery.
 */
object FeaturePolicy {
    /** Whether the feature may run now. [background] is true while no UI is visible. */
    fun enabled(key: FeatureKey, mode: FeatureSwitch, signals: FeatureSignals, background: Boolean = false): Boolean =
        when (mode) {
            FeatureSwitch.OFF -> false
            FeatureSwitch.ON -> true
            FeatureSwitch.AUTO -> when (key) {
                FeatureKey.HTTP_CLIENT, FeatureKey.TEST_EXPLORER -> true
                FeatureKey.TERMINALS -> !background || signals.batteryPercent > 20
                FeatureKey.WATCH_MODE -> (signals.charging || signals.batteryPercent > 30) && signals.batteryPercent > 15
                FeatureKey.PROOT, FeatureKey.LSP, FeatureKey.DAP -> key in signals.installed && signals.batteryPercent > 15
            }
        }

    /** Sessions an automatic terminal may keep alive. */
    fun sessionLimit(mode: FeatureSwitch, signals: FeatureSignals): Int = when (mode) {
        FeatureSwitch.OFF -> 0
        FeatureSwitch.ON -> 4
        FeatureSwitch.AUTO -> if (signals.ramGb >= 6) 4 else 1
    }

    /** One line for the settings row, so "automatic" is never a mystery. */
    fun status(key: FeatureKey, mode: FeatureSwitch, signals: FeatureSignals): String = when (mode) {
        FeatureSwitch.OFF -> "Off"
        FeatureSwitch.ON -> "On"
        else -> if (enabled(key, mode, signals)) "Auto · running" else when {
            key in setOf(FeatureKey.PROOT, FeatureKey.LSP, FeatureKey.DAP) && key !in signals.installed -> "Auto · needs download"
            signals.batteryPercent <= 15 -> "Auto · paused, battery ${signals.batteryPercent}%"
            else -> "Auto · idle"
        }
    }
}
