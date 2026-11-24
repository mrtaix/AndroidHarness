package com.androidharness.app.tools

import com.androidharness.app.agent.ChiefDelegate
import com.androidharness.app.agent.ChiefSnapshot
import com.androidharness.app.agent.ChiefToolLogic
import com.androidharness.app.agent.ChiefTree
import com.androidharness.app.agent.ChiefTreeStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Seven tools that expose the bounded chief hierarchy to the model. */
fun chiefTools(
    tree: ChiefTree,
    store: ChiefTreeStore?,
    delegate: ChiefDelegate,
    onChange: (ChiefSnapshot) -> Unit = {},
): List<Tool> {
    val logic = ChiefToolLogic(tree, store, delegate, onChange)
    val string = Schema.string("")
    val strings = Schema.array(Schema.string(""), "")
    return listOf(
        chiefTool(
            name = "chief_plan",
            description = "Create the root plan for a hierarchical run. Returns the plan node id.",
            schema = Schema.obj(mapOf("goal" to Schema.string("The goal the chief agent must deliver.")), listOf("goal")),
            readOnly = false,
        ) { args -> logic.plan(args.str("goal")) },
        chiefTool(
            name = "chief_assign",
            description = "Assign one bounded task to a subordinate agent. The chief owns plan, review and merge; subordinates only read/write/run by default.",
            schema = Schema.obj(mapOf(
                "parent_id" to Schema.string("Node id of the chief or lead assigning this work."),
                "title" to Schema.string("Short name shown in the agent tree."),
                "prompt" to Schema.string("Task given to the subordinate; it does not see this chat."),
                "acceptance" to strings,
                "scope" to strings,
                "max_tokens" to Schema.integer("Token budget for this node, default 20000."),
            ), listOf("parent_id", "title", "prompt")),
            readOnly = false,
        ) { args -> logic.assign(
            parentId = args.str("parent_id"),
            title = args.str("title"),
            prompt = args.str("prompt"),
            acceptance = args.strings("acceptance"),
            scope = args.strings("scope"),
            maxTokens = args.int("max_tokens", 20_000),
        ).let { "Assigned $it" } },
        chiefTool(
            name = "chief_status",
            description = "Show the agent tree with depth, status and latest reports. Omit node_id for the whole tree.",
            schema = Schema.obj(mapOf("node_id" to string)),
            readOnly = true,
        ) { args -> logic.status(args.str("node_id").ifBlank { null }) },
        chiefTool(
            name = "chief_wait",
            description = "Wait up to 30 seconds for one subordinate node and return its answer.",
            schema = Schema.obj(mapOf(
                "node_id" to Schema.string("Chief node id."),
                "timeout_seconds" to Schema.integer("1..30, default 15."),
            ), listOf("node_id")),
            readOnly = true,
        ) { args -> logic.wait(args.str("node_id"), args.int("timeout_seconds", 15)) },
        chiefTool(
            name = "chief_review",
            description = "Approve or reject a completed node. Rejection resumes the same node with feedback; it never spawns a new one.",
            schema = Schema.obj(mapOf(
                "node_id" to Schema.string("Chief node id."),
                "approved" to Schema.boolean("True to accept, false to send feedback."),
                "feedback" to Schema.string("What must change; required when rejected."),
            ), listOf("node_id", "approved")),
            readOnly = false,
        ) { args -> logic.review(args.str("node_id"), args.bool("approved"), args.str("feedback")) },
        chiefTool(
            name = "chief_merge",
            description = "Merge an approved node's report into the parent. Refuses unreviewed nodes.",
            schema = Schema.obj(mapOf("node_id" to Schema.string("Approved chief node id.")), listOf("node_id")),
            readOnly = false,
        ) { args -> logic.merge(args.str("node_id")) },
        chiefTool(
            name = "chief_stop",
            description = "Stop one node and all of its descendants, including their live subagents.",
            schema = Schema.obj(mapOf("node_id" to Schema.string("Chief node id.")), listOf("node_id")),
            readOnly = false,
        ) { args -> logic.stop(args.str("node_id")) },
    )
}

private fun chiefTool(
    name: String,
    description: String,
    schema: JsonObject,
    readOnly: Boolean,
    run: suspend (JsonObject) -> String,
): Tool = object : Tool {
    override val name = name
    override val description = description
    override val parametersSchema = schema
    override val isReadOnly = readOnly
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = try {
        ToolResult(true, run(args))
    } catch (e: Exception) {
        ToolResult(false, e.message ?: "Chief tool failed")
    }
}

private fun JsonObject.str(key: String, default: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: default
private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default
private fun JsonObject.int(key: String, default: Int): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default
private fun JsonObject.strings(key: String): List<String> =
    this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
