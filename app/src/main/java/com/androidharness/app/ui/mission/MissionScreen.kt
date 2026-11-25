package com.androidharness.app.ui.mission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.MissionState
import com.androidharness.app.agent.MissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings page for persistent overnight missions. No nested scroll: settings owns the scroller. */
@Composable
fun MissionScreen(container: AppContainer) {
    var missions by remember { mutableStateOf(emptyList<MissionState>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            missions = withContext(Dispatchers.IO) { container.missionManager.list() }
            delay(2_000)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.missions_desc), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (missions.isEmpty()) {
            Text(stringResource(R.string.missions_empty), style = MaterialTheme.typography.bodyMedium)
        }
        missions.forEach { mission ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(mission.goal, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text("${mission.status.name} · turn ${mission.turns} · retry ${mission.retries}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = { (mission.turns.toFloat() / mission.limits.turnLimit).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    mission.lastReason?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                    mission.prUrl?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (mission.status != MissionStatus.DONE && mission.status != MissionStatus.CANCELLED) {
                            TextButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { container.missionManager.pause(mission.id) } }
                            }) { Text(stringResource(R.string.missions_pause)) }
                            TextButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { container.missionManager.resume(mission.id) } }
                            }) { Text(stringResource(R.string.missions_resume)) }
                        }
                        TextButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { container.missionManager.cancel(mission.id) } }
                        }) { Text(stringResource(R.string.missions_cancel)) }
                    }
                }
            }
        }
    }
}
