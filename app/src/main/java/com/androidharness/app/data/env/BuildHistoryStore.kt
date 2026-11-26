package com.androidharness.app.data.env

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/** One recorded build with the extra state the UI needs. */
data class BuildHistoryEntry(
    val id: String,
    val report: AndroidBuildReport,
    val project: String,
    val status: Status = Status.BUILT,
) {
    enum class Status { BUILT, INSTALLED, LAUNCHED }
}

/**
 * Append-only history of build reports, capped at 20 entries. Private app
 * storage, atomic replacement, no credentials. Reports themselves never carry
 * a keystore password.
 */
class BuildHistoryStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Any()

    fun list(): List<BuildHistoryEntry> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        runCatching {
            json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
                val o = element.jsonObject
                val reportObj = o["report"]?.jsonObject ?: return@mapNotNull null
                val report = AndroidBuildReportParser.parse(reportObj.toString()) ?: return@mapNotNull null
                val id = str(o, "id") ?: return@mapNotNull null
                val status = runCatching {
                    BuildHistoryEntry.Status.valueOf(str(o, "status") ?: "BUILT")
                }.getOrDefault(BuildHistoryEntry.Status.BUILT)
                BuildHistoryEntry(id, report, str(o, "project").orEmpty(), status)
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.report.builtAt }
    }

    fun record(report: AndroidBuildReport, project: String = ""): String {
        val id = "build-${System.currentTimeMillis()}-${report.sha256.take(8)}"
        synchronized(lock) {
            val capped = (list() + BuildHistoryEntry(id, report, project))
                .sortedByDescending { it.report.builtAt }.take(MAX_ENTRIES)
            write(capped)
        }
        return id
    }

    fun mark(id: String, status: BuildHistoryEntry.Status) = synchronized(lock) {
        write(list().map { if (it.id == id) it.copy(status = status) else it })
    }

    fun clear() = synchronized(lock) { write(emptyList()) }

    private fun str(o: JsonObject, key: String): String? = o[key]?.jsonPrimitive?.contentOrNull

    private fun reportJson(r: AndroidBuildReport): String = buildString {
        append('{')
        append("\"route\":\"").append(r.route).append("\",")
        append("\"package\":\"").append(r.packageName).append("\",")
        append("\"apk\":\"").append(r.apk).append("\",")
        append("\"size_bytes\":").append(r.sizeBytes).append(',')
        append("\"sha256\":\"").append(r.sha256).append("\",")
        append("\"min_sdk\":").append(r.minSdk).append(',')
        append("\"target_sdk\":").append(r.targetSdk).append(',')
        append("\"version_code\":").append(r.versionCode).append(',')
        append("\"version_name\":\"").append(r.versionName).append("\",")
        append("\"keystore\":\"").append(r.keystore).append("\",")
        append("\"built_at\":\"").append(r.builtAt).append("\",")
        append("\"incremental\":").append(r.incremental).append(',')
        append("\"checks\":[").append(r.checks.joinToString { "\"$it\"" }).append(']')
        append('}')
    }

    private fun toJson(entry: BuildHistoryEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("status", entry.status.name)
        put("project", entry.project)
        put("report", json.parseToJsonElement(reportJson(entry.report)))
    }

    private fun write(entries: List<BuildHistoryEntry>) {
        file.parentFile?.mkdirs()
        val text = json.encodeToString(JsonArray(entries.map(::toJson)))
        val tmp = File(file.absolutePath + ".next")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(text)
        }
    }

    companion object { const val MAX_ENTRIES = 20 }
}
