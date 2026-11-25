package com.androidharness.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileOutputStream

/** Durable, per-session JSON snapshot of a [ChiefTree], written atomically. */
class ChiefTreeStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(snapshot: ChiefSnapshot) {
        file.parentFile?.mkdirs()
        val next = File(file.path + ".next")
        val text = buildJsonObject {
            put("sequence", snapshot.sequence)
            putJsonArray("nodes") {
                snapshot.nodes.forEach { node ->
                    addJsonObject {
                        put("id", node.id)
                        node.parentId?.let { put("parentId", it) }
                        put("depth", node.depth)
                        put("title", node.title)
                        put("prompt", node.prompt)
                        putJsonArray("acceptance") { node.acceptance.forEach { add(it) } }
                        putJsonArray("scope") { node.scope.forEach { add(it) } }
                        putJsonObject("budget") {
                            put("maxTokens", node.budget.maxTokens)
                            put("maxCostUsd", node.budget.maxCostUsd)
                            node.budget.deadlineMs?.let { put("deadlineMs", it) }
                        }
                        putJsonArray("permissions") { node.permissions.forEach { add(it) } }
                        put("status", node.status.name)
                        put("retries", node.retries)
                        putJsonArray("feedback") { node.feedback.forEach { add(it) } }
                        put("report", node.report)
                        node.delegateId?.let { put("delegateId", it) }
                        put("role", node.role.name)
                        put("evidence", node.evidence)
                    }
                }
            }
        }.toString()
        FileOutputStream(next).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(next.renameTo(file)) { "Could not save chief orchestration tree" }
    }

    fun load(): ChiefSnapshot? {
        if (!file.exists()) return null
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val nodes = root.array("nodes").mapNotNull { it as? JsonObject }.map { node ->
            val budget = node.obj("budget")
            ChiefNode(
                id = node.str("id"),
                parentId = node["parentId"]?.jsonPrimitive?.contentOrNull,
                depth = node.int("depth"),
                title = node.str("title"),
                prompt = node.str("prompt"),
                acceptance = node.strings("acceptance"),
                scope = node.strings("scope"),
                budget = ChiefBudget(
                    maxTokens = budget.int("maxTokens", 20_000),
                    maxCostUsd = budget.double("maxCostUsd", 0.5),
                    deadlineMs = budget.longOrNull("deadlineMs"),
                ),
                permissions = node.strings("permissions").toSet().ifEmpty { setOf("read", "write", "run") },
                status = runCatching { ChiefStatus.valueOf(node.str("status", "QUEUED")) }
                    .getOrDefault(ChiefStatus.QUEUED),
                retries = node.int("retries"),
                feedback = node.strings("feedback"),
                report = node.str("report"),
                delegateId = node["delegateId"]?.jsonPrimitive?.contentOrNull,
                role = runCatching { ChiefRole.valueOf(node.str("role", "WORKER")) }
                    .getOrDefault(ChiefRole.WORKER),
                evidence = node.str("evidence"),
            )
        }
        val snapshot = ChiefSnapshot(nodes, root.int("sequence"))
        val recovered = snapshot.copy(nodes = snapshot.nodes.map { node ->
            if (node.status.active) node.copy(
                status = ChiefStatus.INTERRUPTED,
                feedback = node.feedback + "Interrupted when the app process stopped.",
            ) else node
        })
        if (recovered != snapshot) save(recovered)
        return recovered
    }

    private fun JsonObject.str(key: String, default: String = ""): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: default
    private fun JsonObject.int(key: String, default: Int = 0): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: default
    private fun JsonObject.double(key: String, default: Double = 0.0): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: default
    private fun JsonObject.longOrNull(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.strings(key: String): List<String> =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    private fun JsonObject.array(key: String) =
        this[key]?.jsonArray ?: buildJsonArray { }
    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: JsonObject(emptyMap())
}
