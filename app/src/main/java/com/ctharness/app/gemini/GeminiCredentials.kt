package com.ctharness.app.gemini

import java.util.Base64

/**
 * Google's public Antigravity desktop OAuth client, the same one the `agy`
 * CLI and the open-source pi-antigravity package use. Google treats an
 * installed application's client secret as public, and both values stay
 * base64-encoded exactly as upstream publishes them.
 */
internal object GeminiCredentials {
    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    val CLIENT_ID: String = decode("MTA3MTAwNjA2MDU5MS10bWhzc2luMmgyMWxjcmUyMzV2dG9sb2poNGc0MDNlcC5hcHBzLmdvb2dsZXVzZXJjb250ZW50LmNvbQ==")
    val CLIENT_SECRET: String = decode("R09DU1BYLUs1OEZXUjQ4NkxkTEoxbUxCOHNYQzR6NnFEQWY=")
}
