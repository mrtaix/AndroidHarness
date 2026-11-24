package com.androidharness.app.agent

import kotlinx.serialization.Serializable

/** State of one node in the orchestration tree. */
@Serializable
enum class ChiefRole { CHIEF, LEAD, WORKER }

@Serializable
enum class ChiefStatus {
    QUEUED, RUNNING, REVIEW, APPROVED, DONE, FAILED, CANCELLED, INTERRUPTED;
    val active: Boolean get() = this == QUEUED || this == RUNNING || this == REVIEW
}

@Serializable
data class ChiefBudget(
    val maxTokens: Int = 20_000,
    val maxCostUsd: Double = 0.5,
    val deadlineMs: Long? = null,
)

@Serializable
data class ChiefNode(
    val id: String,
    val parentId: String?,
    val depth: Int,
    val title: String,
    val prompt: String,
    val acceptance: List<String> = emptyList(),
    val scope: List<String> = emptyList(),
    val budget: ChiefBudget = ChiefBudget(),
    val permissions: Set<String> = setOf("read", "write", "run"),
    val status: ChiefStatus = ChiefStatus.QUEUED,
    val retries: Int = 0,
    val feedback: List<String> = emptyList(),
    val report: String = "",
    val delegateId: String? = null,
    val role: ChiefRole = ChiefRole.WORKER,
)

/**
 * Pure state machine for a bounded hierarchy of agents. No Android or coroutine
 * dependencies so the transition rules can be tested on the JVM.
 */
@Serializable
data class ChiefSnapshot(val nodes: List<ChiefNode>, val sequence: Int)

class ChiefTree(
    private val maxDepth: Int = 3,
    private val maxNodes: Int = 8,
    private val maxRetries: Int = 2,
    initial: ChiefSnapshot? = null,
) {
    private val nodes = LinkedHashMap<String, ChiefNode>()
    private var sequence = 0

    init {
        if (initial != null) {
            initial.nodes.forEach { nodes[it.id] = it }
            sequence = initial.sequence
        }
    }

    fun plan(goal: String): ChiefNode = synchronized(this) {
        val node = ChiefNode(
            id = "chief-${++sequence}",
            parentId = null,
            depth = 0,
            title = goal,
            prompt = goal,
            status = ChiefStatus.RUNNING,
            role = ChiefRole.CHIEF,
        )
        nodes[node.id] = node
        node
    }

    fun assign(
        parentId: String,
        title: String,
        prompt: String,
        acceptance: List<String> = emptyList(),
        scope: List<String> = emptyList(),
        budget: ChiefBudget = ChiefBudget(),
        permissions: Set<String> = setOf("read", "write", "run"),
        role: ChiefRole = ChiefRole.WORKER,
    ): ChiefNode = synchronized(this) {
        val parent = node(parentId)
        require(parent.role != ChiefRole.WORKER) { "worker cannot delegate" }
        require(role != ChiefRole.LEAD || parent.role == ChiefRole.CHIEF) { "role: only chief can create a lead" }
        require(parent.depth + 1 < maxDepth) { "depth limit reached" }
        require(nodes.values.count { it.status.active } < maxNodes) { "live node limit reached" }
        val childScope = scope.flatMap { it.trim('/').split('/') }.filter { it.isNotEmpty() }
        val overlaps = nodes.values.any { other ->
            other.status.active && other.scope.isNotEmpty() &&
                scopesOverlap(other.scope.flatMap { it.trim('/').split('/') }.filter { it.isNotEmpty() }, childScope)
        }
        require(!overlaps) { "scope overlaps an active node" }
        val node = ChiefNode(
            id = "chief-${++sequence}",
            parentId = parentId,
            depth = parent.depth + 1,
            title = title,
            prompt = prompt,
            acceptance = acceptance,
            scope = scope,
            budget = budget,
            permissions = permissions,
            role = role,
        )
        nodes[node.id] = node
        node
    }

    fun setDelegate(id: String, delegateId: String): ChiefNode = synchronized(this) {
        change(id) { it.copy(delegateId = delegateId) }
    }

    fun descendantIds(id: String): List<String> = synchronized(this) { descendants(id) }

    fun complete(id: String, report: String): ChiefNode = synchronized(this) {
        change(id) { it.copy(status = ChiefStatus.REVIEW, report = report) }
    }

    fun review(id: String, approved: Boolean, feedback: String = ""): ChiefNode = synchronized(this) {
        val current = node(id)
        require(current.status == ChiefStatus.REVIEW || current.status == ChiefStatus.RUNNING) {
            "Node is not awaiting review"
        }
        if (approved) change(id) { it.copy(status = ChiefStatus.APPROVED) }
        else if (current.retries >= maxRetries) change(id) {
            it.copy(status = ChiefStatus.FAILED, feedback = it.feedback + feedback)
        } else change(id) {
            it.copy(status = ChiefStatus.RUNNING, retries = it.retries + 1, feedback = it.feedback + feedback)
        }
    }

    fun merge(id: String): ChiefNode = synchronized(this) {
        require(node(id).status == ChiefStatus.APPROVED) { "Node has not been approved" }
        change(id) { it.copy(status = ChiefStatus.DONE) }
    }

    fun stop(id: String) = synchronized(this) {
        node(id)
        val targets = descendants(id) + id
        targets.forEach { target -> change(target) { it.copy(status = ChiefStatus.CANCELLED) } }
    }

    fun markBudgetExceeded(id: String): ChiefNode = synchronized(this) {
        change(id) { it.copy(status = ChiefStatus.CANCELLED) }
    }

    fun get(id: String): ChiefNode? = synchronized(this) { nodes[id] }
    fun findByDelegate(delegateId: String): ChiefNode? = synchronized(this) {
        nodes.values.firstOrNull { it.delegateId == delegateId }
    }
    fun all(): List<ChiefNode> = synchronized(this) { nodes.values.toList() }
    fun snapshot(): ChiefSnapshot = synchronized(this) { ChiefSnapshot(nodes.values.toList(), sequence) }

    fun status(): String = synchronized(this) {
        buildString {
            fun walk(id: String) {
                val node = nodes[id] ?: return
                append("  ".repeat(node.depth)).append(node.title)
                    .append(" [").append(node.status.name.lowercase()).append("]\n")
                nodes.values.filter { it.parentId == id }.forEach { walk(it.id) }
            }
            nodes.values.filter { it.parentId == null }.forEach { walk(it.id) }
        }.trimEnd()
    }

    private fun descendants(id: String): List<String> = buildList {
        nodes.values.filter { it.parentId == id }.forEach { child ->
            add(child.id)
            addAll(descendants(child.id))
        }
    }

    private fun change(id: String, transform: (ChiefNode) -> ChiefNode): ChiefNode {
        val current = node(id)
        val next = transform(current)
        nodes[id] = next
        return next
    }

    private fun node(id: String): ChiefNode = nodes[id] ?: error("Unknown chief node $id")

    private fun scopesOverlap(a: List<String>, b: List<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return true
        val shared = minOf(a.size, b.size)
        return (0 until shared).all { a[it] == b[it] }
    }
}
