package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.data.DevProvisioner

/**
 * The downloads the advanced features need, each with its own button. A missing
 * payload is a visible row, never a silent failure inside a tool call.
 */
@Composable
internal fun DevPayloadsSection(provisioner: DevProvisioner) {
    val state by provisioner.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { provisioner.refresh() }
    SettingsHeader(stringResource(R.string.settings_features_title))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_features_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.ordered().forEach { payload ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(payload.label, style = MaterialTheme.typography.bodyLarge)
                        Text(payload.detail, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (payload.status) {
                        DevProvisioner.Status.INSTALLING ->
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        DevProvisioner.Status.READY ->
                            Text("Installed", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        else -> Button(onClick = { provisioner.install(payload.id) }) {
                            Text(payload.action.ifBlank { "Download" })
                        }
                    }
                }
            }
            if (state.loading && state.payloads.isEmpty()) {
                Text("Checking what is installed…", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
