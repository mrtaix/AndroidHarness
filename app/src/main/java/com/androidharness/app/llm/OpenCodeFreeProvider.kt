package com.androidharness.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.androidharness.app.llm.jsonArrayOrAbsent
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

/**
 * A second built-in connection alongside [HarnessProvider]. It speaks the
 * OpenCode CLI wire to the free, keyless models at `opencode.ai`: the gate
 * accepts requests only when they carry an OpenCode-like User-Agent and the
 * client/session identity headers the real CLI sends. Because those headers
 * are fixed here, the regular [OpenAiCompatProvider] can do the streaming.
 */
object OpenCodeFreeProvider {
    const val ID = "opencode-free"
    const val BASE_URL = "https://opencode.ai/inference/openai/v1"
    const val MODELS_URL = "https://opencode.ai/inference/v1/models"
    const val DEFAULT_MODEL = "deepseek-v4.1-flash-free"
    const val CLI_UA = "opencode/latest/2.1.4/cli"
    val config = ProviderConfig(ID, "OpenCode Free", ProviderType.OPENAI_COMPAT, BASE_URL, DEFAULT_MODEL)

    /** Known zero-cost ids without a `-free` suffix (stealth models). */
    private val KNOWN_FREE = setOf("big-pickle")

    /**
     * Headers a real OpenCode CLI sends. [ProviderHeaders.apply] injects these
     * for this connection only, so the global client-identity preset cannot
     * overwrite the User-Agent and turn the request into a 403.
     */
    fun headers(sessionId: String = UUID.randomUUID().toString()): List<ProviderHeader> = listOf(
        ProviderHeader("User-Agent", CLI_UA),
        ProviderHeader("x-opencode-client", "cli"),
        ProviderHeader("x-opencode-session", sessionId),
        ProviderHeader("x-opencode-project", "default"),
        ProviderHeader("x-opencode-request", UUID.randomUUID().toString()),
    )

    /** Free models from the public catalog. No key is required for inference. */
    internal fun parseCatalog(body: String): List<ModelEntry> {
        val root = requireNotNull(Json.parseToJsonElement(body).jsonObjectOrAbsent()) { "Invalid OpenCode model catalog" }
        val data = requireNotNull(root["data"]?.jsonArrayOrAbsent()) { "Invalid OpenCode model catalog" }
        val entries = data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (!id.endsWith("-free") && id !in KNOWN_FREE) return@mapNotNull null
            if (id.startsWith("jev-")) return@mapNotNull null
            val caps = obj["capabilities"]?.jsonObjectOrAbsent()
            val tools = caps?.get("tools")?.jsonObjectOrAbsent()
                ?.get("supported")?.jsonPrimitive?.booleanOrNull ?: true
            if (!tools) return@mapNotNull null
            val reasoning = caps?.get("reasoning")?.jsonObjectOrAbsent()
                ?.get("supported")?.jsonPrimitive?.booleanOrNull
            val ctx = obj["limit"]?.jsonObjectOrAbsent()?.get("context")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            ModelEntry(id, reasoning = reasoning, contextTokens = ctx,
                displayName = name, note = "OpenCode Free · keyless")
        }.sortedBy { it.id }
        require(entries.isNotEmpty()) { "OpenCode has no free models available" }
        return entries
    }
}
