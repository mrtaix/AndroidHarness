package com.androidharness.app.data.env

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull

/** Parsed `build/build-report.json` emitted by build-apk.sh. Never carries a secret. */
data class AndroidBuildReport(
    val route: String,
    val packageName: String,
    val apk: String,
    val sizeBytes: Long,
    val sha256: String,
    val minSdk: Int,
    val targetSdk: Int,
    val versionCode: Int,
    val versionName: String,
    val keystore: String,
    val builtAt: String,
    val incremental: Boolean,
    val checks: List<String>,
)

object AndroidBuildReportParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): AndroidBuildReport? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        fun str(k: String): String? = o[k]?.jsonPrimitive?.contentOrNull
        fun int(k: String): Int? = o[k]?.jsonPrimitive?.intOrNull
        fun long(k: String): Long? = o[k]?.jsonPrimitive?.longOrNull
            ?: o[k]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        AndroidBuildReport(
            route = str("route") ?: return@runCatching null,
            packageName = str("package") ?: return@runCatching null,
            apk = str("apk") ?: return@runCatching null,
            sizeBytes = long("size_bytes") ?: return@runCatching null,
            sha256 = str("sha256") ?: return@runCatching null,
            minSdk = int("min_sdk") ?: return@runCatching null,
            targetSdk = int("target_sdk") ?: return@runCatching null,
            versionCode = int("version_code") ?: return@runCatching null,
            versionName = str("version_name") ?: return@runCatching null,
            keystore = str("keystore") ?: return@runCatching null,
            builtAt = str("built_at") ?: return@runCatching null,
            incremental = o["incremental"]?.jsonPrimitive?.booleanOrNull ?: false,
            checks = (o["checks"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList(),
        )
    }.getOrNull()

    /** Short Vietnamese summary for chat; includes only non-sensitive fields. */
    fun summary(r: AndroidBuildReport): String = buildString {
        append("Build xong: ").append(r.packageName)
        append(" · route ").append(r.route)
        append(" · ").append(r.sizeBytes / 1024).append(" KiB")
        append(" · SDK ").append(r.minSdk).append('/').append(r.targetSdk)
        append(" · phiên bản ").append(r.versionCode).append(" (").append(r.versionName).append(')')
        if (r.incremental) append(" · dùng cache")
        append("\nAPK: ").append(r.apk)
        append("\nSHA256: ").append(r.sha256.take(12))
        if (r.checks.isNotEmpty()) append("\nĐã kiểm tra: ").append(r.checks.joinToString(", "))
    }
}
