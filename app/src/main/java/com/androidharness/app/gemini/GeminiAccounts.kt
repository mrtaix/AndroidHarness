package com.androidharness.app.gemini

import android.content.Context
import com.androidharness.app.core.ImageRef
import com.androidharness.app.data.DevProvisioner
import com.androidharness.app.data.ProotManager
import com.androidharness.app.data.ProxyManager
import com.androidharness.app.llm.GeminiProtocol
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderHeaders
import com.androidharness.app.llm.ProviderProxy
import com.androidharness.app.llm.HttpError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encoding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Manages Google account connections for Antigravity access.
 * Handles OAuth PKCE flow, token refresh, project discovery, and quota queries.
 */
class GeminiAccounts(
    private val context: Context,
    private val store: DevProvisioner,
    private val keys: com.androidharness.app.data.KeyStoreManager,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { prettyPrint = false }

    /** Saved account data. */
    private data class Account(
        val id: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val project: String = "",
        val models: List<String> = emptyList(),
        val modelsAt: Long = 0,
    )

    private object Store {
        private const val FILE = "gemini_accounts.json"

        fun load(context: Context): List<Account> {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return emptyList()
            return runCatching {
                val text = file.readText()
                json.decodeFromString(text)
            }.getOrDefault(emptyList())
        }

        fun save(context: Context, accounts: List<Account>) {
            val file = File(context.filesDir, FILE)
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(accounts))
        }
    }

    private val accounts = Store.load(context).toMutableList()

    private fun entry(id: String): Account = accounts.first { it.id == id }

    private fun persist(accounts: List<Account>) {
        this.accounts.clear()
        this.accounts.addAll(accounts)
        Store.save(context, accounts)
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
                .header("Authorization", "[redacted]
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
        } ?: loaded["projectId"]?.jsonPrimitive?.contentOrNull
        // A new consumer account has no project yet; onboarding creates one.
        val project = server?.takeIf { it.isNotBlank() } ?: run {
            val tiers = (loaded["allowedTiers"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            val tier = tiers.firstOrNull { it.contains("free") } ?: tiers.firstOrNull()
                ?: loaded["currentTier"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            val onboarded = runCatching {
                if (tier == null) null else post(GeminiProtocol.CODE_ASSIST, "onboardUser", buildJsonObject {
                    put("tierId", tier)
                    put("metadata", buildJsonObject { put("ideType", "ANTIGRAVITY") })
                })
            }.getOrNull()
            onboarded?.let { op ->
                (op["response"] as? JsonObject)?.get("cloudaicompanionProject")?.let { element ->
                    (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: element.jsonPrimitive.contentOrNull
                }
            }?.takeIf { it.isNotBlank() }
        } ?: run {
            // The Antigravity hub uses this fixed project when loadCodeAssist
            // returns none (business/workspace accounts). A fabricated UUID is
            // rejected with 403 #3501; this one is accepted by the service.
            val fixed = GeminiProtocol.DEFAULT_PROJECT
            persist(store.copy(accounts = store.accounts.map { if (it.id == id) it.copy(project = fixed) else it }))
            projectCache[providerId] = fixed
            fixed
        }
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
        val project = found.project.takeIf { it.isNotBlank() } ?: project(providerId)
        val body = buildJsonObject { put("project", project) }
        val request = Request.Builder().url("${GeminiProtocol.CODE_ASSIST}/v1internal:fetchAvailableModels")
            .header("Authorization", "[redacted]
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
            if (!response.isSuccessful) throw IOException("Token exchange failed: HTTP ${response.code} $text")
            val jsonElement = json.parseToJsonElement(text)
            val access = jsonElement.jsonObject.get("access_token")?.jsonPrimitive?.contentOrNull ?: throw IOException("No access token")
            val refresh = jsonElement.jsonObject.get("refresh_token")?.jsonPrimitive?.contentOrNull ?: ""
            val expires = jsonElement.jsonObject.get("expires_in")?.jsonPrimitive?.longOrNull ?: 3600L
            Tokens(access, refresh, expires)
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { prettyPrint = false }

    companion object {
        const val TOKEN = "https://oauth2.googleapis.com/token"
        const val USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo"
    }
}
