package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders build results as a status card with success/fail indicator,
 * error/warning counts, duration, and colored error/warning message list.
 */
internal object BuildResultRenderer : ToolResultRenderer {

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val firstLine = lines.first()
        val status = classifyStatus(firstLine) ?: return null

        val sb = StringBuilder("<div class='build-result'>")
        appendHeader(sb, firstLine, status)
        appendMessages(sb, lines.drop(1))
        sb.append("</div>")
        return sb.toString()
    }

    private enum class Status(val css: String, val icon: String, val label: String) {
        SUCCESS("build-success", "✓", "Build succeeded"),
        FAILED("build-fail", "✗", "Build failed"),
        ABORTED("build-abort", "⚠", "Build aborted"),
    }

    private fun classifyStatus(line: String): Status? = when {
        line.startsWith("✓") -> Status.SUCCESS
        line.startsWith("✗") -> Status.FAILED
        line.startsWith("Build aborted") -> Status.ABORTED
        else -> null
    }

    private val COUNTS_PATTERN = Regex("""\((\d+) errors?,\s*(\d+) warnings?,\s*([\d.]+)s\)""")

    private fun appendHeader(sb: StringBuilder, line: String, status: Status) {
        val countsMatch = COUNTS_PATTERN.find(line)
        sb.append("<div class='build-header ${status.css}'>")
        sb.append("<span class='build-icon'>${status.icon}</span>")
        sb.append("<span class='build-status'>${status.label}</span>")
        if (countsMatch != null) {
            val errors = countsMatch.groupValues[1]
            val warnings = countsMatch.groupValues[2]
            val duration = countsMatch.groupValues[3]
            sb.append("<span class='build-meta'>${duration}s</span>")
            if (errors != "0") sb.append(" <span class='build-errors'>$errors errors</span>")
            if (warnings != "0") sb.append(" <span class='build-warnings'>$warnings warnings</span>")
        }
        sb.append("</div>")
    }

    private fun appendMessages(sb: StringBuilder, lines: List<String>) {
        val messages = lines.filter { it.isNotBlank() }
        if (messages.isEmpty()) return
        sb.append("<div class='build-messages'>")
        for (msg in messages) {
            val trimmed = msg.trim()
            val cls = when {
                trimmed.startsWith("ERROR") -> "build-msg-error"
                trimmed.startsWith("WARN") -> "build-msg-warn"
                else -> "build-msg-info"
            }
            sb.append("<div class='build-msg $cls'>${esc(trimmed)}</div>")
        }
        sb.append("</div>")
    }
}
