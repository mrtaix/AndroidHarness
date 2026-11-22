package com.androidharness.app.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * Everything a reviewer said on one pull request, gathered in a single call:
 * the conversation comments, the inline review comments with their file and
 * line, and the review verdicts. Read-only; replying goes through the app's
 * own PR screen or a write-enabled tool.
 */
class GitHubPullRequestCommentsTool(
    client: OkHttpClient,
    token: suspend () -> String? = { null },
) : Tool {
    private val api = GitHubReadClient(client, token)
    override val name = NAME
    override val description =
        "Read the discussion on a pull request: conversation comments, inline review comments with file and line, " +
            "and review verdicts (approved, changes requested). Give repository as owner/name and the PR number. " +
            "Read-only."
    override val isReadOnly = true
    override val parametersSchema = Schema.obj(
        mapOf(
            "repository" to Schema.string("owner/name, for example Sanuu7/AndroidHarness."),
            "number" to Schema.integer("Pull request number."),
            "include" to Schema.string("all (default), comments, reviews or inline."),
            "limit" to Schema.integer("How many entries per section (default 30)."),
        ),
        required = listOf("repository", "number"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val repository = args.string("repository")?.takeIf { it.contains('/') }
            ?: return ToolResult(false, "Give repository as owner/name.")
        val number = args.int("number") ?: return ToolResult(false, "Give the pull request number.")
        val include = (args.string("include") ?: "all").lowercase()
        val limit = (args.int("limit") ?: 30).coerceIn(1, 100)
        val base = "/repos/$repository"
        val out = StringBuilder()

        val response = runCatching { api.get("$base/pulls/$number") }
            .getOrElse { return ToolResult(false, "Could not read pull request #$number: ${it.message}") }
        val pr = response.jsonObjectOrNull()
        if (pr == null) return ToolResult(false, "Pull request #$number did not return an object.")

        out.append("# ").append(pr.string("title").orEmpty())
            .append("  #").append(number).append('\n')
        val state = pr.string("state").orEmpty()
        val draft = pr["draft"]?.jsonPrimitive?.contentOrNull == "true"
        out.append(state).append(if (draft) " (draft)" else "").append(" · ")
            .append(pr["head"]?.jsonObject?.string("ref").orEmpty()).append(" → ")
            .append(pr["base"]?.jsonObject?.string("ref").orEmpty()).append('\n')
        pr.string("html_url")?.let { out.append(it).append('\n') }

        if (include == "all" || include == "reviews") {
            val reviews = runCatching { api.get("$base/pulls/$number/reviews").jsonArrayOrNull() }
                .getOrDefault(JsonArray(emptyList()))
            if (reviews.isNotEmpty()) {
                out.append("\n## Reviews (").append(reviews.size).append(")\n")
                reviews.takeLast(limit).forEach { element ->
                    val review = element.jsonObject
                    val who = review["user"]?.jsonObject?.string("login") ?: "someone"
                    val verdict = review.string("state") ?: "COMMENTED"
                    out.append("- ").append(who).append(": ").append(verdict.lowercase().replace('_', ' '))
                    review.string("body")?.takeIf { it.isNotBlank() }
                        ?.let { out.append(" — ").append(it.lineSequence().first().take(160)) }
                    out.append('\n')
                }
            }
        }

        if (include == "all" || include == "inline") {
            val inline = runCatching { api.get("$base/pulls/$number/comments").jsonArrayOrNull() }
                .getOrDefault(JsonArray(emptyList()))
            if (inline.isNotEmpty()) {
                out.append("\n## Inline comments (").append(inline.size).append(")\n")
                inline.takeLast(limit).forEach { element ->
                    val comment = element.jsonObject
                    val who = comment["user"]?.jsonObject?.string("login") ?: "someone"
                    val file = comment.string("path").orEmpty()
                    val line = comment["line"]?.jsonPrimitive?.intOrNull
                        ?: comment["original_line"]?.jsonPrimitive?.intOrNull
                    out.append("- ").append(file)
                    if (line != null) out.append(':').append(line)
                    out.append(" (").append(who).append("): ")
                        .append(comment.string("body")?.lineSequence()?.first()?.take(220).orEmpty())
                    out.append('\n')
                }
            }
        }

        if (include == "all" || include == "comments") {
            val comments = runCatching { api.get("$base/issues/$number/comments").jsonArrayOrNull() }
                .getOrDefault(JsonArray(emptyList()))
            if (comments.isNotEmpty()) {
                out.append("\n## Conversation (").append(comments.size).append(")\n")
                comments.takeLast(limit).forEach { element ->
                    val comment = element.jsonObject
                    val who = comment["user"]?.jsonObject?.string("login") ?: "someone"
                    out.append("- ").append(who).append(": ")
                        .append(comment.string("body")?.take(300)?.replace("\n", " ").orEmpty())
                    out.append('\n')
                }
            }
        }

        return ToolResult(true, out.toString().trimEnd().ifBlank { "Nothing to show for #$number." })
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun GitHubReadResponse.jsonElementOrNull(): JsonElement? =
        runCatching { Json.parseToJsonElement(body) }.getOrNull()

    private fun GitHubReadResponse.jsonObjectOrNull(): JsonObject? = jsonElementOrNull() as? JsonObject

    private fun GitHubReadResponse.jsonArrayOrNull(): JsonArray? = jsonElementOrNull() as? JsonArray

    companion object { const val NAME = "github_pr_comments" }
}
