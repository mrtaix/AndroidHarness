package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.gemini.GeminiAccount
import com.androidharness.app.gemini.GeminiAccountState
import com.androidharness.app.gemini.GeminiUsage
import com.androidharness.app.ui.common.TextButton

/** Google sign-in for Gemini, next to the ChatGPT connection. */
@Composable
internal fun GeminiAccountsCard(
    state: GeminiAccountState,
    error: String?,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: (GeminiAccount) -> Unit,
    onUsage: suspend (GeminiAccount) -> GeminiUsage,
) {
    val scheme = MaterialTheme.colorScheme
    var showing by remember { mutableStateOf<GeminiAccount?>(null) }
    var usage by remember { mutableStateOf<GeminiUsage?>(null) }
    Surface(color = scheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_google_account_title), style = MaterialTheme.typography.titleSmall)
            state.accounts.forEach { account ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountCircle, null, Modifier.size(20.dp), tint = scheme.primary)
                    Text(account.email, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { showing = account; usage = null }) {
                        Text(stringResource(R.string.settings_google_usage_action))
                    }
                    TextButton(onClick = { onSignOut(account) }) {
                        Text(stringResource(R.string.settings_google_sign_out))
                    }
                }
            }
            if (state.accounts.isNotEmpty()) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.signingIn) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Waiting for Google…", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelSignIn) { Text(stringResource(R.string.settings_action_cancel)) }
                } else {
                    TextButton(onClick = onSignIn) { Text(stringResource(R.string.settings_google_sign_in)) }
                }
            }
            state.access?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            (error ?: state.error)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }
        }
    }
    showing?.let { account ->
        LaunchedEffect(account) { usage = onUsage(account) }
        GeminiUsageDialog(usage, account.models) { showing = null }
    }
}
