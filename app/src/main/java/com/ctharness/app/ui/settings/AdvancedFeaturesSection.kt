package com.ctharness.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ctharness.app.AppContainer
import com.ctharness.app.data.AppSettings
import com.ctharness.app.data.FeatureKey
import com.ctharness.app.data.FeatureSignals
import com.ctharness.app.data.FeatureSwitch
import kotlinx.coroutines.launch

/**
 * One place for everything advanced: the download buttons for optional binaries
 * and the Off / Automatic / On switches. Both used to be buried in other pages,
 * which made them hard to find.
 */
@Composable
internal fun AdvancedFeaturesSection(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val signals = remember(context) {
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
        FeatureSignals(
            ramGb = ((Runtime.getRuntime().maxMemory() / (1024L * 1024 * 1024)).toInt()).coerceAtLeast(1),
            batteryPercent = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100,
            charging = battery?.isCharging == true,
        )
    }
    Column(Modifier.fillMaxWidth()) {
        DevPayloadsSection(container.devProvisioner)
        FeatureSwitchesSection(
            settings = settings,
            signals = signals,
            onChange = { key: FeatureKey, mode: FeatureSwitch ->
                scope.launch { container.settings.setFeatureSwitch(key, mode) }
            },
        )
    }
}
