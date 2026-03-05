package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders inspection, compilation error, and highlight results as grouped
 * file cards with severity badges and clickable file paths.
 *
 * Handles output from: run_inspections, get_compilation_errors, get_highlights.
 *
 * Formats:
 * - `path:line [severity/toolId] description`   (run_inspections)
 * - `path:line [severity] description`           (get_compilation_errors, get_highlights)
 */
internal object InspectionResultRenderer : ToolResultRenderer {

    private val FINDING_PATTERN = Regex("""^(.+?):(\d+)\s+\[([^\]]+)]\s+(.+)$""")
    private val SUMMARY_PATTERN = Regex("""Found\s+(\d+)\s+(?:total\s+)?(?:problems?|compilation errors?)""")
    private val SUCCESS_PATTERN = Regex("""^[✅✓]\s+No\s+(?:compilation errors|inspection problems)""")

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        // Success case (no errors)
        if (SUCCESS_PATTERN.containsMatchIn(lines.first())) {
            return renderSuccess(lines.first())
        }

        val findings = mutableListOf<Finding>()
        val headerLines = mutableListOf<String>()
        for (line in lines) {
            val match = FINDING_PATTERN.matchEntire(line.trim())
            if (match != null) {
                findings.add(parseFinding(match))
            } else if (line.isNotBlank()) {
                headerLines.add(line)
            }
        }

        if (findings.isEmpty()) return null

        val sb = StringBuilder("<div class='inspection-result'>")
        appendSummaryHeader(sb, headerLines, findings.size)
        appendGroupedFindings(sb, findings)
        sb.append("</div>")
        return sb.toString()
    }

    private data class Finding(
        val path: String,
        val line: Int,
        val severity: String,
        val toolId: String,
        val description: String,
    )

    private fun parseFinding(match: MatchResult): Finding {
        val bracketContent = match.groupValues[3]
        val slashIdx = bracketContent.indexOf('/')
        val severity: String
        val toolId: String
        if (slashIdx >= 0) {
            severity = bracketContent.substring(0, slashIdx)
            toolId = bracketContent.substring(slashIdx + 1)
        } else {
            severity = bracketContent
            toolId = ""
        }
        return Finding(
            path = match.groupValues[1],
            line = match.groupValues[2].toIntOrNull() ?: 0,
            severity = severity.trim(),
            toolId = toolId.trim(),
            description = match.groupValues[4],
        )
    }

    private fun renderSuccess(line: String): String {
        val sb = StringBuilder("<div class='inspection-result'>")
        sb.append("<div class='inspection-header inspection-success'>")
        sb.append("<span class='inspection-icon'>✓</span>")
        sb.append("<span class='inspection-status'>${esc(line.removePrefix("✅").removePrefix("✓").trim())}</span>")
        sb.append("</div></div>")
        return sb.toString()
    }

    private fun appendSummaryHeader(sb: StringBuilder, headerLines: List<String>, totalFindings: Int) {
        val summaryMatch = headerLines.firstNotNullOfOrNull { SUMMARY_PATTERN.find(it) }
        val count = summaryMatch?.groupValues?.get(1)?.toIntOrNull() ?: totalFindings
        val hasErrors = headerLines.any { "error" in it.lowercase() }
        val cssClass = if (hasErrors) "inspection-errors" else "inspection-warnings"

        sb.append("<div class='inspection-header $cssClass'>")
        sb.append("<span class='inspection-icon'>${if (hasErrors) "✗" else "⚠"}</span>")
        sb.append("<span class='inspection-count'>$count</span>")
        sb.append("<span class='inspection-label'>${if (hasErrors) "problems" else "findings"}</span>")
        sb.append("</div>")
    }

    private fun appendGroupedFindings(sb: StringBuilder, findings: List<Finding>) {
        val grouped = findings.groupBy { it.path }
        sb.append("<div class='inspection-files'>")
        for ((path, fileFindings) in grouped) {
            sb.append("<div class='inspection-file'>")
            sb.append("<div class='inspection-file-header'>")
            val fileName = path.substringAfterLast('/')
            sb.append("<span class='git-file-path' title='${esc(path)}'>${esc(fileName)}</span>")
            sb.append("<span class='inspection-file-count'>${fileFindings.size}</span>")
            sb.append("</div>")
            sb.append("<div class='inspection-file-findings'>")
            for (f in fileFindings) {
                val severityClass = severityCss(f.severity)
                sb.append("<div class='inspection-finding'>")
                sb.append("<span class='inspection-severity $severityClass'>${esc(abbreviateSeverity(f.severity))}</span>")
                sb.append("<span class='inspection-line'>:${f.line}</span>")
                if (f.toolId.isNotEmpty()) {
                    sb.append(" <span class='inspection-tool'>${esc(f.toolId)}</span>")
                }
                sb.append(" <span class='inspection-desc'>${esc(f.description)}</span>")
                sb.append("</div>")
            }
            sb.append("</div></div>")
        }
        sb.append("</div>")
    }

    private fun severityCss(severity: String): String = when (severity.uppercase()) {
        "ERROR", "GENERIC_SERVER_ERROR_OR_WARNING" -> "severity-error"
        "WARNING" -> "severity-warning"
        "WEAK_WARNING", "LIKE_UNUSED_SYMBOL" -> "severity-weak"
        "INFORMATION", "INFO", "TEXT_ATTRIBUTES" -> "severity-info"
        else -> "severity-warning"
    }

    private fun abbreviateSeverity(severity: String): String = when (severity.uppercase()) {
        "ERROR", "GENERIC_SERVER_ERROR_OR_WARNING" -> "E"
        "WARNING" -> "W"
        "WEAK_WARNING", "LIKE_UNUSED_SYMBOL" -> "w"
        "INFORMATION", "INFO", "TEXT_ATTRIBUTES" -> "I"
        else -> severity.take(1).uppercase()
    }
}
