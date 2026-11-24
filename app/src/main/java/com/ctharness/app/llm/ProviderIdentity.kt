package com.ctharness.app.llm

/**
 * The client identity sent to provider endpoints. Some relay gateways accept
 * only well-known coding CLIs and answer 403 `client_not_allowed` to anything
 * else, so one global choice can present a preset identity, or the user's own
 * header rows, on every provider request.
 *
 * [DEFAULT] keeps the transport's own User-Agent: nothing is added.
 */
object ProviderIdentity {
    const val DEFAULT = ""
    const val CUSTOM = "custom"

    data class Preset(val id: String, val label: String, val userAgent: String)

    /** Presets only name the client; the rows below are the whole request change. */
    val presets = listOf(
        Preset(DEFAULT, "CT Harness", ""),
        Preset("claude-code", "Claude Code", "claude-cli/2.0.0 (external, cli)"),
        Preset("codex", "Codex CLI", "codex_cli_rs/0.44.0 (Linux; x86_64)"),
        Preset("cursor", "Cursor", "cursor-agent/1.0.0"),
        Preset("opencode", "OpenCode", "opencode/0.9.14"),
        Preset("gemini-cli", "Gemini CLI", "GeminiCLI/0.11.0"),
        Preset(CUSTOM, "Custom headers", ""),
    )

    @Volatile private var current: Pair<String, List<ProviderHeader>> = DEFAULT to emptyList()

    /** Called by the settings flow so request builders need no suspend access. */
    fun update(id: String, custom: List<ProviderHeader>) {
        current = id to custom
    }

    /** Header rows for one provider request; blank and disabled rows are skipped. */
    fun headers(id: String = current.first, custom: List<ProviderHeader> = current.second): List<ProviderHeader> {
        val preset = presets.firstOrNull { it.id == id }?.userAgent.orEmpty()
        val rows = if (id == CUSTOM) custom else listOf(ProviderHeader("User-Agent", preset)).filter { it.value.isNotBlank() }
        return rows.filter { it.enabled && it.name.isNotBlank() && it.value.isNotBlank() }.map { it.copy(name = it.name.trim()) }
    }

    /** Custom rows follow the provider-header rules, so credentials stay app-managed. */
    fun error(id: String, custom: List<ProviderHeader>): String? =
        if (presets.none { it.id == id }) "Choose a known client identity." else ProviderHeaders.error(custom)
}
