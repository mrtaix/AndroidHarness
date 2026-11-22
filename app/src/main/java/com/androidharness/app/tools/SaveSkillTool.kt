package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a conversation's proven workflow into a project skill, which is how a
 * one-off fix becomes repeatable without re-explaining it next time.
 */
class SaveSkillTool : Tool {
    override val name = "save_skill"
    override val description =
        "Save a reusable workflow from this conversation as a project skill at " +
            ".harness/skills/<name>/SKILL.md. Use the shape: a one-line description, then " +
            "when to use it, the steps in order, and how to verify the result. Keep it short " +
            "enough that another agent can follow it without this chat."
    override val parametersSchema = Schema.obj(
        mapOf(
            "name" to Schema.string("Lowercase skill name, hyphens allowed, e.g. ship-android-release."),
            "description" to Schema.string("One sentence: what it does and when to use it."),
            "body" to Schema.string("The full skill instructions in Markdown, without frontmatter."),
        ),
        required = listOf("name", "description", "body"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        val description = args["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val body = args["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(name.matches(Regex("[a-z0-9][a-z0-9-]{1,60}"))) {
            "Use a lowercase name of 2-61 letters, digits or hyphens."
        }
        require(description.length in 5..300) { "Describe the skill in one sentence." }
        require(body.length >= 20) { "The skill body is too short to be useful." }
        val folder = ctx.workspace.resolve(".harness/skills/$name")
        val file = ctx.workspace.resolve(".harness/skills/$name/SKILL.md")
        if (file.exists) return@withContext ToolResult(false,
            "A skill named '$name' already exists in this project. Choose another name or edit it directly.")
        folder.mkdirs()
        val escaped = description.replace("\"", "'")
        file.writeText("---\nname: $name\ndescription: $escaped\n---\n\n$body\n")
        ToolResult(true, "Saved the project skill at ${file.relPath}. It is available in the next chat.")
    }
}
