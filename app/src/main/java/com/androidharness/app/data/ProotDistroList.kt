package com.androidharness.app.data

/**
 * Reads `proot-distro list`. The layout differs between releases (and between
 * "no containers" and a populated list), so the parse is defensive: only lines
 * under an "Installed …" heading count, and a missing heading yields nothing.
 */
object ProotDistroList {
    private val entry = Regex("""^\s{1,4}([a-z0-9][a-z0-9._-]*)(\s+\(([^)]*)\))?\s*$""")

    fun parse(text: String): List<String> {
        if (text.contains("No containers are installed", ignoreCase = true)) return emptyList()
        val names = LinkedHashSet<String>()
        var inInstalled = false
        for (line in text.lineSequence()) {
            val lower = line.trim().lowercase()
            when {
                lower.startsWith("installed") && lower.contains(":") -> { inInstalled = true; continue }
                lower.startsWith("supported") && lower.contains(":") -> { inInstalled = false; continue }
                lower.startsWith("no containers") -> { inInstalled = false; continue }
            }
            if (!inInstalled) continue
            entry.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { names += it }
        }
        return names.toList()
    }
}
