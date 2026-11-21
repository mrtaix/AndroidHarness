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

data class GeminiAccount(val providerId: String, val email: String, val connected: Boolean, val expiresAt: Long)

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
    private val mutableState = MutableStateFlow(GeminiAccountState())
    val state = mutableState.asStateFlow()
    private var signInJob: Job? = null
    private var listener: Loopback? = null

    init {
        publish()
        scope.launch { store.accounts.filter { it.connected }.forEach { runCatching { accessToken(GeminiProtocol.providerId(it.id)) } } }
    }

    private fun persist(updated: GeminiProtocol.Store) {
        write(json.encodeToString(updated))
        store = updated
        publish()
    }

    private fun publish(error: String? = mutableState.value.error) {
        mutableState.update {
            it.copy(accounts = store.accounts.map { entry ->
                GeminiAccount(GeminiProtocol.providerId(entry.id), entry.email.ifBlank { "Google account" }, entry.connected, entry.expiresAt)
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
                        lock.withLock { persist(store.copy(accounts = store.accounts + account)) }
                        mutableState.update { it.copy(access = probe(access)) }
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
        val codeAssist = call("${GeminiProtocol.CODE_ASSIST}:loadCodeAssist", JsonObject(emptyMap()))
        "Gemini API: $models · Code Assist: $codeAssist"
    }

    /** Code Assist project for the account, discovered once and then cached. */
    suspend fun project(providerId: String): String = withContext(Dispatchers.IO) {
        projectCache[providerId]?.let { return@withContext it }
        val token = accessToken(providerId)
        val metadata = buildJsonObject {
            put("ideType", "IDE_UNSPECIFIED")
            put("platform", "PLATFORM_UNSPECIFIED")
            put("pluginType", "GEMINI")
            put("pluginVersion", "1.0.0")
        }
        fun post(path: String, body: JsonObject): JsonObject {
            val request = Request.Builder().url("${GeminiProtocol.CODE_ASSIST}:$path")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("Code Assist $path failed: HTTP ${response.code} $text")
                return json.parseToJsonElement(text).jsonObject
            }
        }
        val loaded = post("loadCodeAssist", buildJsonObject { put("metadata", metadata) })
        val direct = loaded["cloudaicompanionProject"]?.jsonPrimitive?.contentOrNull
        val tier = loaded["currentTier"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: loaded["allowedTiers"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val project = direct?.takeIf { it.isNotBlank() } ?: run {
            checkNotNull(tier) { "This Google account has no Code Assist tier available." }
            val started = post("onboardUser", buildJsonObject {
                put("tierId", tier)
                put("metadata", metadata)
            })
            var operation = started
            var waited = 0L
            while (operation["done"]?.jsonPrimitive?.contentOrNull != "true" && waited < 30_000) {
                kotlinx.coroutines.delay(1_000)
                waited += 1_000
                val name = operation["name"]?.jsonPrimitive?.contentOrNull ?: break
                operation = post("onboardUser", buildJsonObject { put("name", name) })
            }
            operation["response"]?.jsonObject?.get("cloudaicompanionProject")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?: error("Google did not create a Code Assist project for this account.")
        }
        projectCache[providerId] = project
        project
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
