package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders search results (text search, symbol search, find references) as
 * grouped file cards with line numbers and match highlights.
 *
 * Handles output from: search_text, search_symbols, find_references.
 *
 * Formats:
 * - `path:line: content`           (search_text)
 * - `path:line [type] name`        (search_symbols, find_references)
 */
internal object SearchResultRenderer : ToolResultRenderer {

    private val LINE_REF_PATTERN = Regex("""^(.+?):(\d+):\s+(.+)$""")
    private val LOCATION_PATTERN = Regex("""^(.+?):(\d+)\s+\[([^\]]+)]\s+(.+)$""")
    private val COUNT_HEADER = Regex("""^(\d+)\s+(matches|results?|references?|symbols?)\b""", RegexOption.IGNORE_CASE)
    private val NO_MATCHES = Regex("""^No\s+(matches|results|references|symbols)\s+found""", RegexOption.IGNORE_CASE)

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        // "No matches found" case
        if (NO_MATCHES.containsMatchIn(lines.first())) {
            return renderEmpty(lines.first())
        }

        val results = mutableListOf<SearchResult>()
        var headerLine: String? = null

        for (line in lines) {
            val locMatch = LOCATION_PATTERN.matchEntire(line.trim())
            val lineRefMatch = LINE_REF_PATTERN.matchEntire(line.trim())
            if (locMatch != null) {
                results.add(
                    SearchResult(
                        path = locMatch.groupValues[1],
                        line = locMatch.groupValues[2].toIntOrNull() ?: 0,
                        badge = locMatch.groupValues[3],
                        content = locMatch.groupValues[4],
                    )
                )
            } else if (lineRefMatch != null) {
                results.add(
                    SearchResult(
                        path = lineRefMatch.groupValues[1],
                        line = lineRefMatch.groupValues[2].toIntOrNull() ?: 0,
                        badge = "",
                        content = lineRefMatch.groupValues[3],
                    )
                )
            } else if (COUNT_HEADER.containsMatchIn(line) || headerLine == null && line.isNotBlank()) {
                headerLine = line
            }
        }

        if (results.isEmpty()) return null

        val sb = StringBuilder("<div class='search-result'>")
        appendHeader(sb, headerLine, results.size)
        appendGroupedResults(sb, results)
        sb.append("</div>")
        return sb.toString()
    }

    private data class SearchResult(
        val path: String,
        val line: Int,
        val badge: String,
        val content: String,
    )

    private fun renderEmpty(line: String): String {
        return "<div class='search-result'>" +
                "<div class='search-header search-empty'>" +
                "<span class='search-icon'>∅</span>" +
                "<span class='search-status'>${esc(line)}</span>" +
                "</div></div>"
    }

    private fun appendHeader(sb: StringBuilder, headerLine: String?, count: Int) {
        val match = headerLine?.let { COUNT_HEADER.find(it) }
        val displayCount = match?.groupValues?.get(1)?.toIntOrNull() ?: count
        val kind = match?.groupValues?.get(2) ?: "results"

        sb.append("<div class='search-header'>")
        sb.append("<span class='search-count'>$displayCount</span>")
        sb.append("<span class='search-label'>$kind</span>")
        sb.append("</div>")
    }

    private fun appendGroupedResults(sb: StringBuilder, results: List<SearchResult>) {
        val grouped = results.groupBy { it.path }
        sb.append("<div class='search-files'>")
        for ((path, fileResults) in grouped) {
            sb.append("<div class='search-file'>")
            sb.append("<div class='search-file-header'>")
            val fileName = path.substringAfterLast('/')
            sb.append("<span class='git-file-path' title='${esc(path)}'>${esc(fileName)}</span>")
            sb.append("<span class='search-file-count'>${fileResults.size}</span>")
            sb.append("</div>")
            sb.append("<div class='search-file-matches'>")
            for (r in fileResults) {
                sb.append("<div class='search-match'>")
                sb.append("<span class='search-line'>:${r.line}</span>")
                if (r.badge.isNotEmpty()) {
                    sb.append(" <span class='search-badge'>${esc(r.badge)}</span>")
                }
                sb.append(" <span class='search-content'>${esc(r.content)}</span>")
                sb.append("</div>")
            }
            sb.append("</div></div>")
        }
        sb.append("</div>")
    }
}
