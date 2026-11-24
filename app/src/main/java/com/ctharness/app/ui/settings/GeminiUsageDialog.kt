package com.ctharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ctharness.app.R
import com.ctharness.app.gemini.GeminiUsage
import com.ctharness.app.llm.ModelPrices

/** Plan, remaining quota and list prices for one Google account. */
@Composable
internal fun GeminiUsageDialog(
    usage: GeminiUsage?,
    models: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_google_usage_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (usage == null) {
                    Text(stringResource(R.string.settings_google_usage_loading), style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                usage.plan?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.settings_google_usage_plan), style = MaterialTheme.typography.bodyMedium)
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                usage.credits?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.settings_google_usage_credits), style = MaterialTheme.typography.bodyMedium)
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                usage.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                usage.quotas.forEach { quota ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${quota.group} · ${quota.bucket}", style = MaterialTheme.typography.bodySmall)
                            Text("${(quota.remaining * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        LinearProgressIndicator(
                            progress = { quota.remaining.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        quota.resetsAt?.let {
                            Text("${stringResource(R.string.settings_google_usage_resets)} $it",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (models.isNotEmpty()) {
                    Text(stringResource(R.string.settings_google_usage_prices), style = MaterialTheme.typography.titleSmall)
                    models.take(8).forEach { model ->
                        val cost = ModelPrices.costFor(model, "google")
                        Text("$model — $${cost.input} / $${cost.output} ${stringResource(R.string.settings_google_usage_per_million)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}
