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
    const val CODE_ASSIST = "https://cloudcode-pa.googleapis.com/v1internal"

    /** The scopes the Gemini CLI requests for Code Assist. */
    val SCOPES = listOf(
        "openid",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cloud-platform",
    )

    /** Models offered by a Google account through Code Assist. */
    val ACCOUNT_MODELS = listOf("gemini-2.5-pro", "gemini-2.5-flash")

    fun providerId(accountId: String): String = PROVIDER_PREFIX + accountId
    fun isProvider(id: String): Boolean = id.startsWith(PROVIDER_PREFIX)
    fun accountId(providerId: String): String = providerId.removePrefix(PROVIDER_PREFIX)

    private val random = SecureRandom()

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
    )

    @Serializable
    data class Store(val hostId: String, val accounts: List<StoredAccount> = emptyList())
}
