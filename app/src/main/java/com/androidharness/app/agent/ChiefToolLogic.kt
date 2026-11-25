package com.androidharness.app.agent

/** One delegation request handed to the real subagent runner. */
data class SpawnRequest(val node: ChiefNode, val prompt: String)

/** Narrow seam so the tool contract can be tested without coroutine/UI plumbing. */
interface ChiefDelegate {
    suspend fun spawn(request: SpawnRequest): String
    suspend fun verify(node: ChiefNode): ChiefVerdict
    suspend fun resume(node: ChiefNode, feedback: String): String
    suspend fun waitFor(delegateId: String, timeoutSeconds: Int): String?
    suspend fun stop(delegateId: String)
}

/** Business logic behind the `chief_*` tools; no Android dependencies. */
class ChiefToolLogic(
    private val tree: ChiefTree,
    private val store: ChiefTreeStore? = null,
    private val delegate: ChiefDelegate,
    private val onChange: (ChiefSnapshot) -> Unit = {},
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
        role: String = "WORKER",
    ): String {
        val node = tree.assign(
            parentId = parentId,
            title = title,
            prompt = prompt,
            acceptance = acceptance,
            scope = scope,
            budget = ChiefBudget(maxTokens = maxTokens),
            permissions = permissions,
            role = runCatching { ChiefRole.valueOf(role.uppercase()) }.getOrDefault(ChiefRole.WORKER),
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
        val root = tree.get(nodeId) ?: error("Unknown chief node $nodeId")
        val all = tree.all()
        return buildString {
            fun render(node: ChiefNode, depth: Int) {
                append("  ".repeat(depth)).append(node.title)
                    .append(" [").append(node.status.name.lowercase()).append("]\n")
                append("  ".repeat(depth)).append("Task: ").append(node.prompt).append('\n')
                if (node.acceptance.isNotEmpty()) {
                    append("  ".repeat(depth)).append("Acceptance:\n")
                    node.acceptance.forEach { append("  ".repeat(depth)).append("- ").append(it).append('\n') }
                }
                if (node.feedback.isNotEmpty()) {
                    append("  ".repeat(depth)).append("Feedback:\n")
                    node.feedback.forEach { append("  ".repeat(depth)).append("- ").append(it).append('\n') }
                }
                if (node.evidence.isNotBlank()) {
                    append("  ".repeat(depth)).append("Evidence: ").append(node.evidence).append('\n')
                }
                node.verification?.let { v ->
                    append("  ".repeat(depth)).append("Verification: ")
                        .append(if (v.pass) "PASS" else "FAIL").append(" · ").append(v.reason).append('\n')
                }
                if (node.report.isNotBlank()) {
                    append("  ".repeat(depth)).append("Report: ").append(node.report).append('\n')
                }
                all.filter { it.parentId == node.id }.forEach { render(it, depth + 1) }
            }
            render(root, 0)
        }.trimEnd()
    }

    suspend fun wait(nodeId: String, timeoutSeconds: Int): String {
        val node = tree.get(nodeId) ?: error("Unknown chief node $nodeId")
        val id = node.delegateId ?: error("Node has no running delegate")
        return delegate.waitFor(id, timeoutSeconds) ?: "No result yet for ${node.title}"
    }

    suspend fun review(
        nodeId: String,
        approved: Boolean,
        feedback: String = "",
        evidence: String = "",
    ): String {
        val before = tree.get(nodeId) ?: error("Unknown chief node $nodeId")
        if (approved && before.acceptance.isNotEmpty() && before.verification?.pass != true) {
            error("Approval requires a PASS verdict from chief_verify for ${before.acceptance.size} acceptance criterion/criteria")
        }
        val node = tree.review(nodeId, approved, feedback)
        if (approved && evidence.isNotBlank()) tree.setEvidence(nodeId, evidence)
        if (!approved) tree.clearVerification(nodeId)
        if (!approved && node.status == ChiefStatus.RUNNING && feedback.isNotBlank() && before.delegateId != null) {
            tree.setDelegate(nodeId, delegate.resume(before, feedback))
        }
        persist()
        return "${node.title}: ${tree.get(nodeId)?.status?.name?.lowercase()}"
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

    suspend fun verify(nodeId: String): ChiefVerdict {
        val node = tree.get(nodeId) ?: error("Unknown chief node $nodeId")
        val verdict = delegate.verify(node)
        tree.setVerification(nodeId, verdict)
        persist()
        return verdict
    }

    /** Called by the engine when a delegated run finishes. */
    suspend fun complete(nodeId: String, report: String) {
        tree.complete(nodeId, report)
        persist()
    }

    /** Finds the node owning a finished subagent and attaches its report for review. */
    suspend fun attachFinished(delegateId: String, report: String): ChiefNode? {
        val node = tree.findByDelegate(delegateId) ?: return null
        complete(node.id, report)
        return tree.get(node.id)
    }

    private fun persist() {
        val snapshot = tree.snapshot()
        store?.save(snapshot)
        onChange(snapshot)
    }
}
