package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.FeatureKey
import com.androidharness.app.data.FeaturePolicy
import com.androidharness.app.data.FeatureSignals
import com.androidharness.app.data.FeatureSwitch

/** Off / Automatic / On for every optional capability, with the policy's own status line. */
@Composable
internal fun FeatureSwitchesSection(
    settings: AppSettings,
    signals: FeatureSignals,
    onChange: (FeatureKey, FeatureSwitch) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_features_title), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp))
        Text(stringResource(R.string.settings_features_desc), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        FeatureKey.entries.forEach { key ->
            val mode = settings.featureSwitches[key.id] ?: FeatureSwitch.AUTO
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(featureLabel(key)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    FeatureSwitch.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { onChange(key, option) },
                            label = {
                                Text(stringResource(
                                    when (option) {
                                        FeatureSwitch.OFF -> R.string.settings_features_off
                                        FeatureSwitch.AUTO -> R.string.settings_features_auto
                                        else -> R.string.settings_features_on
                                    }
                                ), style = MaterialTheme.typography.labelMedium)
                            },
                        )
                    }
                }
                Text(FeaturePolicy.status(key, mode, signals), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun featureLabel(key: FeatureKey): Int = when (key) {
    FeatureKey.HTTP_CLIENT -> R.string.settings_features_http
    FeatureKey.TERMINALS -> R.string.settings_features_terminals
    FeatureKey.WATCH_MODE -> R.string.settings_features_watch
    FeatureKey.TEST_EXPLORER -> R.string.settings_features_tests
    FeatureKey.PROOT -> R.string.settings_features_proot
    FeatureKey.LSP -> R.string.settings_features_lsp
    FeatureKey.DAP -> R.string.settings_features_dap
}
