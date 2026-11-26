package com.androidharness.app.ui.buildmgr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.env.AndroidBuildToolchain
import com.androidharness.app.data.env.BuildHistoryEntry
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dedicated build manager. No nested Scaffold/scroller: the settings screen
 * owns the scroll container. State lives here because this screen is transient.
 */
@Composable
fun BuildManagerScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val ok = LocalStatusColors.current.success

    var project by remember { mutableStateOf("") }
    var route by remember { mutableStateOf("auto") }
    var doInstall by remember { mutableStateOf(false) }
    var doOpen by remember { mutableStateOf(false) }
    var doCrash by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(emptyList<BuildHistoryEntry>()) }
    val toolchain = remember { AndroidBuildToolchain.status(container.linuxEnv.prefix,
        container.linuxEnv.installedPackages(), container.linuxEnv.prefix.absolutePath) }

    fun refresh() {
        scope.launch { history = withContext(Dispatchers.IO) { container.buildHistory.list() } }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Text(stringResource(R.string.build_mgr_desc), style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant)

        // Toolchain status -------------------------------------------------
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Android, null, Modifier.size(20.dp), tint = scheme.tertiary)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.build_mgr_toolchain), style = MaterialTheme.typography.titleSmall)
                }
                Text(toolchain, style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant)
            }
        }

        // Build form -------------------------------------------------------
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = project, onValueChange = { project = it },
                    label = { Text(stringResource(R.string.build_mgr_project)) },
                    placeholder = { Text(stringResource(R.string.build_mgr_project_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.build_mgr_route), style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to R.string.build_mgr_route_auto,
                        "gradle" to R.string.build_mgr_route_gradle,
                        "script" to R.string.build_mgr_route_script).forEach { (value, label) ->
                        FilterChip(selected = route == value, onClick = { route = value },
                            label = { Text(stringResource(label)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = doInstall, onCheckedChange = { doInstall = it })
                    Text(stringResource(R.string.build_mgr_install), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = doOpen, onCheckedChange = { doOpen = it })
                    Text(stringResource(R.string.build_mgr_open), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = doCrash, onCheckedChange = { doCrash = it })
                    Text(stringResource(R.string.build_mgr_crash), style = MaterialTheme.typography.bodyMedium)
                }
                val okPrefix = stringResource(R.string.build_mgr_result_ok).substringBefore(" · ")
                val startMsg = stringResource(R.string.build_mgr_start)
                val buildingMsg = stringResource(R.string.build_mgr_building)
                Button(onClick = {
                    if (project.isBlank() || running) return@Button
                    running = true; result = null
                    scope.launch {
                        val msg = try {
                            val root = File(project.trim()).let { f ->
                                if (f.isAbsolute) f else File(container.linuxEnv.shellFallbackRoot, f.path)
                            }
                            val outcome = withContext(Dispatchers.IO) {
                                container.buildManager.build(root, route,
                                    install = doInstall, open = doOpen && doInstall, crashCheck = doCrash)
                            }
                            if (outcome.ok) ctx.getString(R.string.build_mgr_result_ok, outcome.report?.sha256?.take(8).orEmpty())
                            else ctx.getString(R.string.build_mgr_result_fail, outcome.summary.take(160))
                        } catch (e: Exception) {
                            ctx.getString(R.string.build_mgr_result_fail, e.message ?: e::class.simpleName.orEmpty())
                        }
                        result = msg
                        refresh()
                        running = false
                    }
                }, enabled = !running && project.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(buildingMsg)
                    } else {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(startMsg)
                    }
                }
                result?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith(okPrefix)) ok else scheme.error)
                }
            }
        }

        // History ----------------------------------------------------------
        Text(stringResource(R.string.build_mgr_history), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { container.buildHistory.clear() } }; refresh() }) {
                Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.build_mgr_clear))
            }
        }
        if (history.isEmpty()) {
            Text(stringResource(R.string.build_mgr_history_empty), style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant)
        } else {
            history.forEach { entry -> BuildHistoryCard(entry, container, ok, ::refresh) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BuildHistoryCard(entry: BuildHistoryEntry, container: AppContainer, ok: androidx.compose.ui.graphics.Color,
                             refresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val r = entry.report
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = scheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Inventory2, null, Modifier.size(18.dp), tint = scheme.primary)
                Spacer(Modifier.size(6.dp))
                Text(r.packageName, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                when (entry.status) {
                    BuildHistoryEntry.Status.INSTALLED -> StatusChip(stringResource(R.string.build_mgr_installed), ok)
                    BuildHistoryEntry.Status.LAUNCHED -> StatusChip(stringResource(R.string.build_mgr_launched), ok)
                    else -> {}
                }
            }
            Text("${r.route} · ${stringResource(R.string.build_mgr_size, (r.sizeBytes / 1024).toInt())}" +
                if (r.incremental) " · ${stringResource(R.string.build_mgr_cached)}" else "",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text("SHA256 ${r.sha256.take(12)}", style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.apk, style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.project.isNotBlank()) Text(entry.project, style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val apk = File(entry.project, r.apk).absolutePath
                        val res = container.shellRouter.run("pm install -r -g '$apk'", File(entry.project), 120_000, 20_000)
                        if (res.exitCode == 0) container.buildHistory.mark(entry.id, BuildHistoryEntry.Status.INSTALLED)
                    }
                    refresh()
                }
            }) { Text(stringResource(R.string.build_mgr_reinstall)) }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
