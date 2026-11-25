package com.androidharness.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileOutputStream

/** Durable per-mission snapshots, written atomically into a directory. */
class MissionStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(mission: MissionState) {
        directory.mkdirs()
        val target = file(mission.id)
        val tmp = File(directory, "${target.name}.tmp")
        val text = buildJsonObject {
            put("id", mission.id)
            put("goal", mission.goal)
            put("source", mission.source.name)
            put("status", mission.status.name)
            mission.branch?.let { put("branch", it) }
            mission.sessionId?.let { put("sessionId", it) }
            put("turns", mission.turns)
            put("costUsd", mission.costUsd)
            put("elapsedMs", mission.elapsedMs)
            put("retries", mission.retries)
            put("watchdogRetries", mission.watchdogRetries)
            mission.lastReason?.let { put("lastReason", it) }
            mission.prUrl?.let { put("prUrl", it) }
            mission.checkCommand?.let { put("checkCommand", it) }
            putJsonObject("checkpoint") {
                put("transcriptTail", mission.checkpoint.transcriptTail)
                put("diffSummary", mission.checkpoint.diffSummary)
            }
            putJsonObject("limits") {
                put("turnLimit", mission.limits.turnLimit)
                put("maxCostUsd", mission.limits.maxCostUsd)
                put("maxWallClockMs", mission.limits.maxWallClockMs)
                put("maxRetriesPerKind", mission.limits.maxRetriesPerKind)
            }
            put("createdAt", mission.createdAt)
            put("updatedAt", mission.updatedAt)
        }.toString()
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        check(tmp.renameTo(target)) { "Could not save mission ${mission.id}" }
    }

    fun load(id: String): MissionState? {
        val source = file(id)
        if (!source.exists()) return null
        return runCatching { decode(json.parseToJsonElement(source.readText()).jsonObject) }.getOrNull()
    }

    fun list(): List<MissionState> = (directory.listFiles { file ->
        file.isFile && file.extension == "json"
    } ?: emptyArray()).mapNotNull { file ->
        runCatching { decode(json.parseToJsonElement(file.readText()).jsonObject) }.getOrNull()
    }.sortedBy { it.id }

    fun delete(id: String) {
        file(id).delete()
    }

    private fun decode(obj: JsonObject): MissionState {
        val checkpoint = obj["checkpoint"]?.jsonObject
        val limits = obj["limits"]?.jsonObject
        return MissionState(
            id = obj.str("id"),
            goal = obj.str("goal"),
            source = enum(MissionSource.entries, obj.str("source"), MissionSource.TEXT),
            status = enum(MissionStatus.entries, obj.str("status"), MissionStatus.QUEUED),
            branch = obj.opt("branch"),
            sessionId = obj.opt("sessionId"),
            turns = obj.int("turns"),
            costUsd = obj.double("costUsd"),
            elapsedMs = obj.long("elapsedMs"),
            retries = obj.int("retries"),
            watchdogRetries = obj.int("watchdogRetries"),
            lastReason = obj.opt("lastReason"),
            prUrl = obj.opt("prUrl"),
            checkCommand = obj.opt("checkCommand"),
            checkpoint = MissionCheckpoint(
                transcriptTail = checkpoint?.str("transcriptTail") ?: "",
                diffSummary = checkpoint?.str("diffSummary") ?: "",
            ),
            limits = MissionLimits(
                turnLimit = limits?.int("turnLimit", MissionLimits().turnLimit) ?: MissionLimits().turnLimit,
                maxCostUsd = limits?.double("maxCostUsd", MissionLimits().maxCostUsd) ?: MissionLimits().maxCostUsd,
                maxWallClockMs = limits?.long("maxWallClockMs", MissionLimits().maxWallClockMs) ?: MissionLimits().maxWallClockMs,
                maxRetriesPerKind = limits?.int("maxRetriesPerKind", MissionLimits().maxRetriesPerKind) ?: MissionLimits().maxRetriesPerKind,
            ),
            createdAt = obj.long("createdAt"),
            updatedAt = obj.long("updatedAt"),
        )
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "invalid mission id" }
        return File(directory, "$id.json")
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.opt(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String, default: Int = 0): Int = this[key]?.jsonPrimitive?.intOrNull ?: default
    private fun JsonObject.double(key: String, default: Double = 0.0): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: default
    private fun JsonObject.long(key: String, default: Long = 0L): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: default

    private fun <T : Enum<T>> enum(values: Iterable<T>, raw: String, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback

    private fun buildJsonObject(init: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        kotlinx.serialization.json.buildJsonObject(init)
}
