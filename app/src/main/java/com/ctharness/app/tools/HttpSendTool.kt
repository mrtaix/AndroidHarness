package com.ctharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A real HTTP request from the agent's own stack, so testing an API does not
 * depend on a shell, curl, or the browser preview. Bodies are capped: a tool
 * result that cannot fit the context is not an answer.
 */
class HttpSendTool : Tool {
    override val name = "http_send"
    override val description =
        "Send an HTTP request and return the status, response headers and body (body truncated " +
            "at 20 KB). Use it to verify an API, a local dev server or a webhook without curl. " +
            "Variables written as {{name}} are replaced from the 'variables' argument."
    override val parametersSchema = Schema.obj(
        mapOf(
            "url" to Schema.string("Absolute http:// or https:// URL."),
            "method" to Schema.string("GET (default), POST, PUT, PATCH or DELETE."),
            "headers" to Schema.obj(emptyMap()),
            "body" to Schema.string("Optional request body; a JSON string is sent as application/json."),
            "variables" to Schema.obj(emptyMap()),
        ),
        required = listOf("url"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val variables = (args["variables"] as? JsonObject).orEmpty()
            .mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
        val rawUrl = args["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val url = substitute(rawUrl, variables)
        require(url.startsWith("http://") || url.startsWith("https://")) { "Enter an absolute http(s) URL." }
        val method = (args["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported method $method." }
        val headers = (args["headers"] as? JsonObject).orEmpty()
            .mapValues { substitute(it.value.jsonPrimitive.contentOrNull.orEmpty(), variables) }
        val bodyText = args["body"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { substitute(it, variables) }

        val builder = Request.Builder().url(url).header("User-Agent", "AndroidHarness-HttpClient")
        headers.forEach { (name, value) -> runCatching { builder.header(name, value) } }
        if (bodyText != null && method != "GET") {
            val mediaType = if (bodyText.trimStart().startsWith("{") || bodyText.trimStart().startsWith("["))
                "application/json; charset=utf-8" else "text/plain; charset=utf-8"
            builder.method(method, bodyText.toRequestBody(mediaType.toMediaType()))
        } else {
            builder.method(method, if (method == "GET" || method == "DELETE") null else "".toRequestBody(null))
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val started = System.nanoTime()
        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrElse { "<${bytes.size} bytes of binary>" }
            val summary = buildString {
                append(method).append(' ').append(url).append('\n')
                append("HTTP ").append(response.code).append(' ').append(response.message).append(" · ").append(elapsed).append(" ms\n")
                response.headers.names().sorted().forEach { name ->
                    append(name).append(": ").append(response.header(name)).append('\n')
                }
                append('\n').append(text.take(20_000))
                if (text.length > 20_000) append("\n… truncated ${text.length - 20_000} characters")
            }
            ToolResult(response.isSuccessful, summary)
        }
    }

    internal fun substitute(input: String, variables: Map<String, String>): String =
        Regex("\\{\\{\\s*([A-Za-z0-9_.-]{1,60})\\s*}}").replace(input) { match ->
            variables[match.groupValues[1]] ?: match.value
        }
}
