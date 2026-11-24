package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.ChiefNode
import com.androidharness.app.agent.ChiefSnapshot
import com.androidharness.app.agent.ChiefStatus

/**
 * Read-only view of the experimental chief hierarchy. Actions prefill the
 * composer instead of calling tools directly, so the user reviews the command.
 */
@Composable
fun ChiefTreeCard(
    snapshot: ChiefSnapshot,
    steps: Map<String, List<String>> = emptyMap(),
    onAction: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.chief_tree_title),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
            )
            if (snapshot.nodes.isEmpty()) {
                Text(
                    stringResource(R.string.chief_tree_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                return@Card
            }
            snapshot.nodes.sortedBy { it.depth }.forEach { node ->
                ChiefTreeNode(node, steps[node.delegateId].orEmpty().lastOrNull(), onAction)
            }
        }
    }
}

@Composable
private fun ChiefTreeNode(node: ChiefNode, latestStep: String?, onAction: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (node.depth * 14).dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                node.title,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(node.role.name, node.status.name, node.delegateId?.let { "task" } ?: "local")
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            if (latestStep != null && node.status != ChiefStatus.REVIEW && node.status != ChiefStatus.DONE) {
                Text(
                    latestStep.take(160),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (node.report.isNotBlank()) {
                Text(
                    node.report.take(240),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (node.status == ChiefStatus.REVIEW || node.status == ChiefStatus.RUNNING) {
            TextButton(onClick = {
                onAction("chief_review(node_id=\"${node.id}\", approved=true)")
            }) {
                Text(stringResource(R.string.chief_tree_approve))
            }
            TextButton(onClick = {
                onAction("chief_review(node_id=\"${node.id}\", approved=false, feedback=)")
            }) {
                Text(stringResource(R.string.chief_tree_reject))
            }
        }
        TextButton(onClick = { onAction("chief_stop(node_id=\"${node.id}\")") }) {
            Text(stringResource(R.string.chief_tree_stop))
        }
    }
}
