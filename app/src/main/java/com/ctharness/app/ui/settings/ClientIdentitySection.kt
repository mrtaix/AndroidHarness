package com.ctharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ctharness.app.R
import com.ctharness.app.data.AppSettings
import com.ctharness.app.llm.ProviderHeader
import com.ctharness.app.llm.ProviderIdentity
import com.ctharness.app.ui.common.ExpressiveDropdownMenu
import com.ctharness.app.ui.common.ExpressiveDropdownMenuItem

/** One global client identity, so relays that only accept known coding CLIs stop refusing requests. */
@Composable
internal fun ClientIdentitySection(settings: AppSettings, onSelect: (String, List<ProviderHeader>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by remember(settings.providerIdentityHeaders) {
        mutableStateOf(settings.providerIdentityHeaders.map { ProviderHeaderDraft(it) })
    }
    val current = ProviderIdentity.presets.firstOrNull { it.id == settings.providerIdentity }
        ?: ProviderIdentity.presets.first()
    val title = stringResource(R.string.settings_identity_title)
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_identity_summary),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Box {
                FilterChip(
                    selected = settings.providerIdentity != ProviderIdentity.DEFAULT,
                    onClick = { open = true },
                    label = { Text(current.label) },
                    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    modifier = Modifier.semantics { contentDescription = "$title: ${current.label}" },
                )
                ExpressiveDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    ProviderIdentity.presets.forEach { preset ->
                        ExpressiveDropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                open = false
                                onSelect(preset.id, custom.map { it.header })
                            },
                            trailingIcon = if (preset.id == settings.providerIdentity) ({ Icon(Icons.Rounded.Check, null) }) else null,
                        )
                    }
                }
            }
            if (settings.providerIdentity == ProviderIdentity.CUSTOM) {
                ProviderHeadersEditor(rows = custom, onChange = { rows ->
                    custom = rows
                    val headers = rows.map { it.header }
                    if (ProviderIdentity.error(ProviderIdentity.CUSTOM, headers) == null) {
                        onSelect(ProviderIdentity.CUSTOM, headers)
                    }
                })
            } else {
                Text(
                    stringResource(R.string.settings_identity_custom_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
