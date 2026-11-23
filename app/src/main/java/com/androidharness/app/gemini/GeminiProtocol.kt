package com.androidharness.app.gemini

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google OAuth and Gemini endpoint details, kept in one place so the sign-in
 * flow, the token refresh and the model calls agree on them.
 */
object GeminiProtocol {
    const val PROVIDER_PREFIX = "gemini:"
    const val AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN = "https://oauth2.googleapis.com/token"
    const val USERINFO = "https://openidconnect.googleapis.com/v1/userinfo"
    const val API = "https://generativelanguage.googleapis.com/v1beta"

    /** Antigravity's consumer Cloud Code host; the second host is the CLI's own fallback. */
    const val CODE_ASSIST = "https://daily-cloudcode-pa.googleapis.com"
    const val CODE_ASSIST_FALLBACK = "https://cloudcode-pa.googleapis.com"

    /** The wire fingerprint `agy` sends; Google gates model catalog visibility on it. */
    const val USER_AGENT = "antigravity/hub/2.24.0 (aidev_client; os_type=darwin; arch=arm64; cl=1000335682)"

    /** Project the Antigravity hub itself uses when loadCodeAssist returns none. */
    const val DEFAULT_PROJECT = "rising-fact-p41fc"

    /** The scopes the Antigravity CLI requests. */
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/aicode",
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
    )

    /** Models offered by a Google account through Code Assist. */
    val ACCOUNT_MODELS = listOf("gemini-2.5-pro", "gemini-2.5-flash")

    fun providerId(accountId: String): String = PROVIDER_PREFIX + accountId
    fun isProvider(id: String): Boolean = id.startsWith(PROVIDER_PREFIX)
    fun accountId(providerId: String): String = providerId.removePrefix(PROVIDER_PREFIX)

    /**
     * Antigravity's fallback project id when loadCodeAssist names none: a stable,
     * UUID-shaped value derived from the account, as the CLI does.
     */
    fun stableProject(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest("antigravity:$seed".toByteArray()).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private val random = SecureRandom()

    /** A refusal from a Cloud Code endpoint, with the body kept for the licence check. */
    class HttpError(val status: Int, val body: String) : java.io.IOException("HTTP $status: ${body.take(400)}")

    /**
     * Google's licence refusal turned into something a person can act on.
     * Antigravity model access needs Google AI Pro, AI Ultra or a Gemini
     * Enterprise licence; a free account is refused with error #3501.
     */
    fun refusal(status: Int?, body: String): String? {
        if (status != 401 && status != 403) return null
        val text = body.take(4_000).lowercase()
        val needsLicence = "subscription_required" in text || "#3501" in text ||
            "license" in text || "licence" in text
        if (!needsLicence) return null
        return "Google refused this model request (HTTP $status, #3501). Either the account has no Antigravity licence " +
            "(Google AI Pro, AI Ultra or Gemini Enterprise), or its Code Assist project id went stale. " +
            "The cached project was just cleared: send the message again. If it repeats, the account really has no licence — " +
            "sign in with another Google account or use a Gemini API key from AI Studio."
    }

    /** RFC 7636 verifier: 43-128 characters from the unreserved set. */
    fun verifier(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** S256 code challenge for [verifier]. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun state(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun authorizeUrl(redirectUri: String, state: String, verifier: String): String =
        okhttp3.HttpUrl.Builder().scheme("https").host("accounts.google.com").addPathSegments("o/oauth2/v2/auth")
            .addQueryParameter("client_id", GeminiCredentials.CLIENT_ID)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            // Offline plus a forced consent screen is what returns a refresh token.
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .build().toString()

    @Serializable
    data class StoredAccount(
        val id: String,
        val email: String = "",
        val refreshToken: String = "",
        val accessToken: String = "",
        val expiresAt: Long = 0,
        val connected: Boolean = true,
        /** Runtime model ids reported by fetchAvailableModels. */
        val models: List<String> = emptyList(),
        val modelsAt: Long = 0,
        val project: String = "",
    )

    @Serializable
    data class Store(val hostId: String, val accounts: List<StoredAccount> = emptyList(), val version: Int = 0)
}
