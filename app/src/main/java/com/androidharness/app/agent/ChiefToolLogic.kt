package com.androidharness.app.agent

/** One delegation request handed to the real subagent runner. */
data class SpawnRequest(val node: ChiefNode, val prompt: String)

/** Narrow seam so the tool contract can be tested without coroutine/UI plumbing. */
interface ChiefDelegate {
    suspend fun spawn(request: SpawnRequest): String
    suspend fun waitFor(delegateId: String, timeoutSeconds: Int): String?
    suspend fun stop(delegateId: String)
}

/** Business logic behind the `chief_*` tools; no Android dependencies. */
class ChiefToolLogic(
    private val tree: ChiefTree,
    private val store: ChiefTreeStore? = null,
    private val delegate: ChiefDelegate,
) {
    suspend fun plan(goal: String): String {
        val node = tree.plan(goal)
        persist()
        return node.id
    }

    suspend fun assign(
        parentId: String,
        title: String,
        prompt: String,
        acceptance: List<String> = emptyList(),
        scope: List<String> = emptyList(),
        maxTokens: Int = 20_000,
        permissions: Set<String> = setOf("read", "write", "run"),
    ): String {
        val node = tree.assign(
            parentId = parentId,
            title = title,
            prompt = prompt,
            acceptance = acceptance,
            scope = scope,
            budget = ChiefBudget(maxTokens = maxTokens),
            permissions = permissions,
        )
        val fullPrompt = buildString {
            append(prompt)
            if (acceptance.isNotEmpty()) {
                append("\n\nAcceptance criteria:\n")
                acceptance.forEach { append("- ").append(it).append('\n') }
            }
        }
        val delegateId = delegate.spawn(SpawnRequest(node, fullPrompt))
        tree.setDelegate(node.id, delegateId)
        persist()
        return node.id
    }

    fun status(nodeId: String? = null): String {
        if (nodeId == null) return tree.status()
        val node = tree.get(nodeId) ?: error("Unknown chief node $nodeId")
        return buildString {
            append(node.title).append(" [").append(node.status.name.lowercase()).append("]\n")
            append("Task: ").append(node.prompt).append('\n')
            if (node.acceptance.isNotEmpty()) {
                append("Acceptance:\n")
                node.acceptance.forEach { append("- ").append(it).append('\n') }
            }
            if (node.feedback.isNotEmpty()) {
                append("Feedback:\n")
                node.feedback.forEach { append("- ").append(it).append('\n') }
            }
            if (node.report.isNotBlank()) append("Report: ").append(node.report)
        }.trimEnd()
    }

    suspend fun wait(nodeId: String, timeoutSeconds: Int): String {
        val node = tree.get(nodeId) ?: error("Unknown chief node $nodeId")
        val id = node.delegateId ?: error("Node has no running delegate")
        return delegate.waitFor(id, timeoutSeconds) ?: "No result yet for ${node.title}"
    }

    suspend fun review(nodeId: String, approved: Boolean, feedback: String = ""): String {
        val node = tree.review(nodeId, approved, feedback)
        persist()
        return "${node.title}: ${node.status.name.lowercase()}"
    }

    suspend fun merge(nodeId: String): String {
        val node = tree.merge(nodeId)
        persist()
        return "Merged ${node.title}"
    }

    suspend fun stop(nodeId: String): String {
        tree.get(nodeId) ?: error("Unknown chief node $nodeId")
        val targets = listOf(nodeId) + tree.descendantIds(nodeId)
        targets.forEach { id ->
            tree.get(id)?.delegateId?.let { delegate.stop(it) }
        }
        tree.stop(nodeId)
        persist()
        return "Stopped ${targets.size} node(s)"
    }

    /** Called by the engine when a delegated run finishes. */
    suspend fun complete(nodeId: String, report: String) {
        tree.complete(nodeId, report)
        persist()
    }

    private fun persist() {
        store?.save(tree.snapshot())
    }
}
