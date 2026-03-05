package com.github.catatafishen.ideagentforcopilot.ui.renderers

import java.util.Base64

/**
 * Interface for custom tool-result renderers in the chat panel.
 * Each implementation transforms raw tool output text into styled HTML.
 */
internal fun interface ToolResultRenderer {
    /**
     * Render tool output as HTML, or return null to fall back to default `<pre><code>`.
     */
    fun render(output: String): String?
}

/**
 * Registry of tool-result renderers and shared HTML utilities.
 */
internal object ToolRenderers {

    private val registry: Map<String, ToolResultRenderer> = mapOf(
        "git_commit" to GitCommitRenderer,
        "git_status" to GitStatusRenderer,
        "git_diff" to GitDiffRenderer,
        "git_log" to GitLogRenderer,
        "build_project" to BuildResultRenderer,
        "run_inspections" to InspectionResultRenderer,
        "get_compilation_errors" to InspectionResultRenderer,
        "get_highlights" to InspectionResultRenderer,
        "search_text" to SearchResultRenderer,
        "search_symbols" to SearchResultRenderer,
        "find_references" to SearchResultRenderer,
    )

    fun get(toolName: String): ToolResultRenderer? = registry[toolName]

    fun hasRenderer(toolName: String): Boolean = toolName in registry

    // ── Shared HTML utilities ─────────────────────────────────

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

    fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    data class DiffStats(val files: String, val insertions: String, val deletions: String)

    fun parseDiffStats(line: String): DiffStats {
        val filesMatch = Regex("""\d+ files? changed""").find(line)
        val insMatch = Regex("""(\d+) insertions?\(\+\)""").find(line)
        val delMatch = Regex("""(\d+) deletions?\(-\)""").find(line)
        return DiffStats(
            files = filesMatch?.value ?: line,
            insertions = insMatch?.groupValues?.get(1) ?: "",
            deletions = delMatch?.groupValues?.get(1) ?: "",
        )
    }
}
