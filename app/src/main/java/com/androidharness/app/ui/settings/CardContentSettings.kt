package com.androidharness.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings

/** What a tool card shows, with a live sample so each switch is visible before leaving Settings. */
@Composable
internal fun CardContentSettings(
    settings: AppSettings,
    onOutput: (Boolean) -> Unit,
    onArgs: (Boolean) -> Unit,
    onLabels: (Boolean) -> Unit,
    onStatus: (Boolean) -> Unit,
    onOneLine: (Boolean) -> Unit,
    onPreset: (Boolean, Boolean, Boolean, Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_chat_cards_title), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Presets replace six taps with one, and the whole set lands in a single settings write.
            PresetChip(R.string.settings_chat_cards_preset_compact, settings,
                compact = true, oneLine = true, output = false, arguments = false, onPreset = onPreset)
            PresetChip(R.string.settings_chat_cards_preset_balanced, settings,
                compact = true, oneLine = false, output = true, arguments = true, onPreset = onPreset)
            PresetChip(R.string.settings_chat_cards_preset_full, settings,
                compact = false, oneLine = false, output = true, arguments = true, onPreset = onPreset)
        }
        ToggleRow(R.string.settings_chat_cards_labels, settings.chatCardsShowLabels, onLabels)
        ToggleRow(R.string.settings_chat_cards_args, settings.chatCardsShowArgs, onArgs)
        ToggleRow(R.string.settings_chat_cards_output, settings.chatCardsShowOutput, onOutput)
        ToggleRow(R.string.settings_chat_cards_status, settings.chatCardsShowStatus, onStatus)
        ToggleRow(R.string.settings_chat_cards_one_line, settings.chatCardsOneLine, onOneLine)
        Text(stringResource(R.string.settings_chat_cards_preview), style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        val pad = if (settings.chatCompactCards) 8.dp else 12.dp
        Surface(
            color = scheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(if (settings.chatCompactCards) 4.dp else 8.dp)) {
                if (settings.chatCardsShowLabels) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Terminal, null, Modifier.size(16.dp), tint = scheme.primary)
                        Text(" shell", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
                if (settings.chatCardsShowArgs && !settings.chatCardsOneLine) {
                    Box(Modifier.fillMaxWidth().background(scheme.surfaceContainerLowest, RoundedCornerShape(12.dp)).padding(pad)) {
                        Text("\$ git status --short", fontFamily = FontFamily.Monospace,
                            fontSize = (12f * settings.chatTextScale).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (settings.chatCardsShowOutput && !settings.chatCardsOneLine) {
                    Box(Modifier.fillMaxWidth().background(scheme.surfaceContainerLowest, RoundedCornerShape(12.dp)).padding(pad)) {
                        Text("M app/src/Main.kt", fontFamily = FontFamily.Monospace,
                            fontSize = (12f * settings.chatTextScale).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!settings.chatCardsShowLabels && !settings.chatCardsShowArgs && !settings.chatCardsShowOutput) {
                    Text(stringResource(R.string.settings_chat_cards_empty), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PresetChip(label: Int, settings: AppSettings, compact: Boolean, oneLine: Boolean, output: Boolean,
    arguments: Boolean, onPreset: (Boolean, Boolean, Boolean, Boolean) -> Unit) {
    val active = settings.chatCompactCards == compact && settings.chatCardsOneLine == oneLine &&
        settings.chatCardsShowOutput == output && settings.chatCardsShowArgs == arguments
    androidx.compose.material3.FilterChip(
        selected = active,
        onClick = { onPreset(compact, oneLine, output, arguments) },
        label = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) },
    )
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}
