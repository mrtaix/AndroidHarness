package com.androidharness.app.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Scope inherited by every tool call inside a chief subagent. */
class ChiefScopeContext(val paths: List<String>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChiefScopeContext>
}

/** Path allowlist for chief workers. An empty scope is unrestricted. */
object ChiefScope {
    private val ABSOLUTE = Regex("^(/|[A-Za-z]:/)")

    fun allows(scope: List<String>, path: String): Boolean {
        if (scope.isEmpty()) return true
        val raw = path.replace('\\', '/').trim()
        if (ABSOLUTE.matches(raw)) return false
        val parts = segments(raw)
        if (parts.isEmpty()) return false
        return scope.any { entry ->
            val allowed = segments(entry)
            allowed.isNotEmpty() &&
                (parts == allowed || (parts.size > allowed.size && parts.take(allowed.size) == allowed))
        }
    }

    fun allows(scope: List<String>, args: JsonObject): Boolean {
        if (scope.isEmpty()) return true
        val keys = listOf("path", "file_path")
        if (keys.any { key ->
            val value = args[key]?.jsonPrimitive?.contentOrNull
            value != null && !allows(scope, value)
        }) return false
        val paths = args["paths"]?.jsonArray ?: return true
        return paths.all { element ->
            val value = element.jsonPrimitive.contentOrNull
            value == null || allows(scope, value)
        }
    }

    private fun segments(raw: String): List<String> = raw
        .replace('\\', '/')
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
}
