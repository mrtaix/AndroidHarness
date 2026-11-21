package com.androidharness.app.gemini

/**
 * The installed-application OAuth client published in the open-source Gemini
 * CLI (`packages/core/src/code_assist/oauth2.ts`). Google treats a client
 * secret embedded in an installed app as public, and the CLI documents it as
 * such; it only identifies the app to Google's consent screen.
 */
internal object GeminiCredentials {
    const val CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
    const val CLIENT_SECRET = "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"
}
