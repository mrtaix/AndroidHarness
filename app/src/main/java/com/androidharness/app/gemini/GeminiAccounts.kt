package com.androidharness.app.gemini

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GeminiAccount(val providerId: String, val email: String, val connected: Boolean, val expiresAt: Long,
    val models: List<String> = emptyList())

data class GeminiAccountState(
    val accounts: List<GeminiAccount> = emptyList(),
    val signingIn: Boolean = false,
    val error: String? = null,
    /** Which endpoint the signed-in token could reach, shown so a refusal is diagnosable. */
    val access: String? = null,
)

/**
 * Google account credentials for Gemini, owned by the app like the ChatGPT
 * connection: providers only ask for a current access token.
 */
class GeminiAccounts(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(),
    private val browserDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var store = read()?.let { runCatching { json.decodeFromString<GeminiProtocol.Store>(it) }.getOrNull() }
        ?: GeminiProtocol.Store("urn:uuid:${UUID.randomUUID()}").also { persist(it) }

    init {
        // Older sign-ins carry the Gemini CLI scopes, which Antigravity rejects; one fresh sign-in is required.
        if (store.version < 2) persist(store.copy(accounts = emptyList(), version = 2))
    }
    private val mutableState = MutableStateFlow(GeminiAccountState())
    val state = mutableState.asStateFlow()
    private var signInJob: Job? = null
    private var listener: Loopback? = null

    init {
        publish()
        scope.launch {
            store.accounts.filter { it.connected }.forEach { account ->
                runCatching { accessToken(GeminiProtocol.providerId(account.id)) }
                runCatching { models(GeminiProtocol.providerId(account.id)) }
            }
        }
    }

    private fun persist(updated: GeminiProtocol.Store) {
        write(json.encodeToString(updated))
        store = updated
        publish()
    }

    private fun publish(error: String? = mutableState.value.error) {
        mutableState.update {
            it.copy(accounts = store.accounts.map { entry ->
                GeminiAccount(GeminiProtocol.providerId(entry.id), entry.email.ifBlank { "Google account" }, entry.connected,
                    entry.expiresAt, entry.models.ifEmpty { GeminiProtocol.ACCOUNT_MODELS })
            }, error = error)
        }
    }

    private fun entry(accountId: String): GeminiProtocol.StoredAccount =
        store.accounts.firstOrNull { it.id == accountId } ?: error("Sign in to a Google account in Settings > Connected accounts.")

    fun startSignIn(openBrowser: (String) -> Unit) {
        if (mutableState.value.signingIn) return
        mutableState.value = mutableState.value.copy(signingIn = true, error = null)
        signInJob = scope.launch {
            try {
                withTimeout(300_000) {
                    val state = GeminiProtocol.state()
                    val verifier = GeminiProtocol.verifier()
                    Loopback().use { receiver ->
                        listener = receiver
                        val url = GeminiProtocol.authorizeUrl(receiver.redirectUri, state, verifier)
                        withContext(browserDispatcher) { openBrowser(url) }
                        val code = receiver.receive(state)
                        val tokens = exchange(FormBody.Builder()
                            .add("grant_type", "authorization_code")
                            .add("client_id", GeminiCredentials.CLIENT_ID)
                            .add("client_secret", GeminiCredentials.CLIENT_SECRET)
                            .add("code", code)
                            .add("code_verifier", verifier)
                            .add("redirect_uri", receiver.redirectUri).build())
                        val access = tokens.accessToken ?: error("Google did not return an access token.")
                        val email = userInfo(access)
                        val account = GeminiProtocol.StoredAccount(id = UUID.randomUUID().toString(), email = email,
                            refreshToken = tokens.refreshToken.orEmpty(), accessToken = access,
                            expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000)
                        lock.withLock {
                            // One row per Google address: signing in again refreshes its scopes.
                            val others = store.accounts.filterNot { it.email.equals(email, ignoreCase = true) && email.isNotBlank() }
                            persist(store.copy(accounts = others + account))
                        }
                        val listed = runCatching { models(GeminiProtocol.providerId(account.id)) }.getOrNull().orEmpty()
                        mutableState.update {
                            it.copy(access = probe(access) + if (listed.isEmpty()) " · models: none" else " · models: ${listed.size}")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                publish("Google sign-in timed out. Please try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish("Could not connect this Google account: ${e.message}")
            } finally {
                listener = null
                mutableState.value = mutableState.value.copy(signingIn = false)
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        listener?.close()
    }

    fun signOut(providerId: String) {
        scope.launch {
            val id = GeminiProtocol.accountId(providerId)
            lock.withLock { persist(store.copy(accounts = store.accounts.filterNot { it.id == id })) }
            projectCache.remove(providerId)
            if (mutableState.value.accounts.isEmpty()) mutableState.update { it.copy(access = null) }
        }
    }

    /** A current access token for [providerId], refreshed when it is about to expire. */
    suspend fun accessToken(providerId: String): String = lock.withLock {
        val id = GeminiProtocol.accountId(providerId)
        val found = entry(id)
        if (found.accessToken.isNotBlank() && found.expiresAt - System.currentTimeMillis() > 60_000) return@withLock found.accessToken
        check(found.refreshToken.isNotBlank()) { "This Google account needs to be connected again." }
        val tokens = exchange(FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", GeminiCredentials.CLIENT_ID)
            .add("client_secret", GeminiCredentials.CLIENT_SECRET)
            .add("refresh_token", found.refreshToken).build())
        val updated = found.copy(accessToken = tokens.accessToken ?: found.accessToken,
            expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000)
        persist(store.copy(accounts = store.accounts.map { if (it.id == id) updated else it }))
        check(updated.accessToken.isNotBlank()) { "Google did not return a usable access token. Connect the account again." }
        updated.accessToken
    }

    /** One diagnostic line: which endpoint answers for this token. */
    suspend fun probe(accessToken: String): String = withContext(Dispatchers.IO) {
        fun call(url: String, body: JsonObject?): String = runCatching {
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $accessToken")
            val request = if (body == null) builder.get().build()
            else builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { response -> "HTTP ${response.code}" }
        }.getOrElse { "network error" }
        val models = call("${GeminiProtocol.API}/models", null)
        val codeAssist = call("${GeminiProtocol.CODE_ASSIST}/v1internal:loadCodeAssist", JsonObject(emptyMap()))
        "Gemini API: $models · Antigravity: $codeAssist"
    }

    /** Antigravity project for the account, discovered once and remembered. */
    suspend fun project(providerId: String): String = withContext(Dispatchers.IO) {
        projectCache[providerId]?.let { return@withContext it }
        val id = GeminiProtocol.accountId(providerId)
        val found = entry(id)
        found.project.takeIf { it.isNotBlank() }?.let { projectCache[providerId] = it; return@withContext it }
        val token = accessToken(providerId)
        fun post(host: String, path: String, body: JsonObject): JsonObject {
            val request = Request.Builder().url("$host/v1internal:$path")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", GeminiProtocol.USER_AGENT)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("Antigravity $path failed: HTTP ${response.code} $text")
                return json.parseToJsonElement(text).jsonObject
            }
        }
        val loaded = runCatching {
            post(GeminiProtocol.CODE_ASSIST, "loadCodeAssist",
                buildJsonObject { put("metadata", buildJsonObject { put("ideType", "ANTIGRAVITY") }) })
        }.getOrElse {
            post(GeminiProtocol.CODE_ASSIST_FALLBACK, "loadCodeAssist",
                buildJsonObject { put("metadata", buildJsonObject { put("ideType", "ANTIGRAVITY") }) })
        }
        val server = loaded["cloudaicompanionProject"]?.let { element ->
            (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: element.jsonPrimitive.contentOrNull
        }
        // Antigravity still answers when discovery names no project, so a missing
        // project must not block the account; the CLI derives one the same way.
        val project = server?.takeIf { it.isNotBlank() }
            ?: GeminiProtocol.stableProject(found.email.ifBlank { id })
        persist(store.copy(accounts = store.accounts.map { if (it.id == id) it.copy(project = project) else it }))
        projectCache[providerId] = project
        project
    }

    /** Runtime model ids for the account, refreshed at most every 30 minutes. */
    suspend fun models(providerId: String): List<String> = withContext(Dispatchers.IO) {
        val id = GeminiProtocol.accountId(providerId)
        val found = entry(id)
        if (found.models.isNotEmpty() && System.currentTimeMillis() - found.modelsAt < 30 * 60_000) return@withContext found.models
        val token = accessToken(providerId)
        val project = project(providerId)
        val body = buildJsonObject { put("project", project) }
        val request = Request.Builder().url("${GeminiProtocol.CODE_ASSIST}/v1internal:fetchAvailableModels")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", GeminiProtocol.USER_AGENT)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val text = http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Antigravity models failed: HTTP ${response.code} $raw")
            raw
        }
        val data = json.parseToJsonElement(text).jsonObject
        val map = data["models"] as? JsonObject
        val ids = map?.keys.orEmpty().filter { modelId ->
            val info = map?.get(modelId)?.jsonObject
            val disabled = info?.get("isInternal")?.jsonPrimitive?.contentOrNull == "true" ||
                info?.get("disabled")?.jsonPrimitive?.contentOrNull == "true"
            !disabled && modelId.isNotBlank() && !modelId.startsWith("MODEL_PLACEHOLDER")
        }
        val models = ids.ifEmpty { GeminiProtocol.ACCOUNT_MODELS }
        persist(store.copy(accounts = store.accounts.map {
            if (it.id == id) it.copy(models = models, modelsAt = System.currentTimeMillis()) else it
        }))
        models
    }

    private val projectCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private data class Tokens(val accessToken: String?, val refreshToken: String?, val expiresIn: Long)

    private suspend fun exchange(body: FormBody): Tokens = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(GeminiProtocol.TOKEN).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Google rejected the sign-in ($text)")
            val data = json.parseToJsonElement(text).jsonObject
            Tokens(
                accessToken = data["access_token"]?.jsonPrimitive?.contentOrNull,
                refreshToken = data["refresh_token"]?.jsonPrimitive?.contentOrNull,
                expiresIn = data["expires_in"]?.jsonPrimitive?.longOrNull ?: 3_600,
            )
        }
    }

    private suspend fun userInfo(accessToken: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(GeminiProtocol.USERINFO).header("Authorization", "Bearer $accessToken").build())
            .execute().use { response ->
                if (!response.isSuccessful) return@withContext ""
                val data = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                data["email"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
    }

    /** The redirect listener Google requires on the loopback interface. */
    private class Loopback : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
        val redirectUri = "http://127.0.0.1:${server.localPort}/oauth2callback"

        suspend fun receive(state: String): String = withContext(Dispatchers.IO) {
            while (true) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use {
                    it.soTimeout = 3_000
                    val input = it.getInputStream()
                    val line = StringBuilder()
                    while (line.length < 8_192) {
                        val ch = input.read()
                        if (ch == -1 || ch == 10) break
                        line.append(ch.toChar())
                    }
                    val parts = line.toString().trim().split(' ')
                    val path = parts.getOrNull(1).orEmpty()
                    val query = path.substringAfter('?', "")
                    val params = query.split('&').filter { '=' in it }.associate {
                        it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    val code = params["code"]
                    val valid = parts.getOrNull(0) == "GET" && path.startsWith("/oauth2callback?") && params["state"] == state
                    val answer = if (valid && code != null) {
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<h2>Return to AndroidHarness</h2>".toByteArray())
                        it.getOutputStream().flush()
                        return@withContext code
                    } else {
                        it.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<h2>Invalid sign-in response</h2>".toByteArray())
                        it.getOutputStream().flush()
                        error(params["error"] ?: "Google did not return an authorization code.")
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

        override fun close() = server.close()
    }
}
