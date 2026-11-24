package com.ctharness.app.debug

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A Debug Adapter Protocol session: the adapter runs as a child process and the
 * two sides exchange `Content-Length` framed JSON. One client stays alive for
 * the whole debugging session, so breakpoints and stack frames survive between
 * tool calls.
 */
class DapClient(
    private val shell: (String) -> ProcessBuilder,
    private val command: List<String>,
    private val workingDir: File,
    private val env: Map<String, String> = emptyMap(),
) : Closeable {
    private lateinit var process: Process
    private val sequence = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val events = Channel<JsonObject>(Channel.UNLIMITED)
    private val buffered = ArrayList<JsonObject>()
    private val lock = Any()
    @Volatile private var started = false
    @Volatile private var lastError: String? = null

    /** Last stop the adapter reported; the tool reuses it when an action omits thread or frame. */
    @Volatile var lastThread: Int? = null
    @Volatile var lastFrame: Int? = null

    val isRunning: Boolean get() = started && runCatching { process.isAlive }.getOrDefault(false)

    @Synchronized
    fun start() {
        if (started) return
        val line = command.joinToString(" ") { ProotQuote.quote(it) }
        val builder = runCatching { shell(line) }.getOrElse { ProcessBuilder(command) }
        builder.directory(workingDir)
        builder.redirectErrorStream(true)
        if (env.isNotEmpty()) builder.environment().putAll(env)
        process = builder.start()
        started = true
        thread(name = "dap-reader", isDaemon = true) {
            try {
                while (true) {
                    val text = DapFraming.read(process.inputStream) ?: break
                    val message = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                    dispatch(message)
                }
            } catch (error: Throwable) {
                lastError = error.message
            } finally {
                pending.values.forEach { it.completeExceptionally(IllegalStateException("The debug adapter exited.")) }
                pending.clear()
            }
        }
    }

    private fun dispatch(message: JsonObject) {
        when (message["type"]?.jsonPrimitive?.content) {
            "response" -> {
                val id = message["request_seq"]?.jsonPrimitive?.int ?: return
                pending.remove(id)?.complete(message)
            }
            "event" -> if (!events.trySend(message).isSuccess) buffered += message
            else -> Unit
        }
    }

    private fun send(message: JsonObject) {
        process.outputStream.write(DapFraming.encode(message.toString()))
        process.outputStream.flush()
    }

    suspend fun request(
        command: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 20_000,
    ): JsonObject? {
        start()
        val id = sequence.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val message = buildJsonObject {
            put("seq", id)
            put("type", "request")
            put("command", command)
            put("arguments", arguments)
        }
        send(message)
        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: throw IllegalStateException("The debug adapter did not answer \"$command\" within ${timeoutMs / 1000}s" +
                (lastError?.let { ": $it" }.orEmpty()))
        if (response["success"]?.jsonPrimitive?.content == "false") {
            val detail = response["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: response["body"]?.jsonObject?.get("error")?.jsonObject?.get("showUser")?.jsonPrimitive?.content
            throw IllegalStateException("$command failed: ${detail ?: "no detail"}")
        }
        return response["body"]?.let { if (it is JsonObject) it else null }
    }

    /** Waits for one event, remembering the ones that arrived earlier for the next call. */
    suspend fun waitEvent(name: String, timeoutMs: Long = 20_000): JsonObject? {
        synchronized(lock) {
            val index = buffered.indexOfFirst { it["event"]?.jsonPrimitive?.content == name }
            if (index >= 0) return buffered.removeAt(index)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val event = withTimeoutOrNull(left) { events.receive() } ?: return null
            if (event["event"]?.jsonPrimitive?.content == name) return event
            synchronized(lock) { if (buffered.size < 200) buffered += event }
        }
    }

    suspend fun takeEvent(timeoutMs: Long = 20_000): JsonObject? {
        synchronized(lock) { if (buffered.isNotEmpty()) return buffered.removeAt(0) }
        return withTimeoutOrNull(timeoutMs) { events.receive() }
    }

    suspend fun initialize(adapterId: String, clientName: String = "AndroidHarness") {
        request("initialize", buildJsonObject {
            put("clientID", "androidharness")
            put("clientName", clientName)
            put("adapterID", adapterId)
            put("pathFormat", "path")
            put("linesStartAt1", true)
            put("columnsStartAt1", true)
            put("supportsVariableType", true)
            put("supportsVariablePaging", false)
            put("supportsRunInTerminalRequest", false)
        })
    }

    suspend fun launch(program: String, args: List<String>, stopOnEntry: Boolean, extra: JsonObject = JsonObject(emptyMap())) {
        request("launch", buildJsonObject {
            put("program", program)
            put("args", kotlinx.serialization.json.buildJsonArray { args.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("stopOnEntry", stopOnEntry)
            put("console", "internalConsole")
            extra.forEach { (key, value) -> put(key, value) }
        })
    }

    suspend fun setBreakpoints(path: String, lines: List<Int>, verifiedOnly: Boolean = false) {
        request("setBreakpoints", buildJsonObject {
            put("source", buildJsonObject { put("path", path) })
            put("breakpoints", kotlinx.serialization.json.buildJsonArray {
                lines.forEach { line -> add(buildJsonObject { put("line", line) }) }
            })
            put("lines", kotlinx.serialization.json.buildJsonArray { lines.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("sourceModified", false)
            if (verifiedOnly) put("verified", true)
        })
    }

    override fun close() {
        if (!started) return
        // Fire-and-forget: close() is called from cleanup and cannot suspend.
        runCatching {
            send(buildJsonObject {
                put("seq", sequence.incrementAndGet())
                put("type", "request")
                put("command", "disconnect")
                put("arguments", buildJsonObject { put("terminateDebuggee", true) })
            })
        }
        runCatching { process.destroy() }
        started = false
        events.close()
    }

    /** `next`, `stepIn`, `stepOut`, `pause` or `continue`, all by thread id. */
    suspend fun step(command: String, threadId: Int, singleThread: Boolean = false) {
        val arguments = buildJsonObject {
            put("threadId", threadId)
            if (command != "continue") put("singleThread", singleThread)
        }
        val reply = request(command, arguments, timeoutMs = 10_000)
        if (command == "continue") reply?.get("allThreadsContinued")
    }

    companion object {
        /** Adapters this app can start without the user typing a command. */
        val PRESETS: Map<String, List<String>> = mapOf(
            "debugpy" to listOf("python", "-m", "debugpy.adapter"),
            "debugpy-adapter" to listOf("debugpy-adapter"),
        )
    }
}

/** Shell quoting shared by the debug and proot launch paths. */
object ProotQuote {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
