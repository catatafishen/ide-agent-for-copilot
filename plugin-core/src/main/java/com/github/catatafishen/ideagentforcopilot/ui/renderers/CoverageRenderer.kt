package com.github.catatafishen.ideagentforcopilot.ui.renderers

/**
 * Renderer for get_coverage output.
 * Input: "ClassName: 85.2% covered (42 / 49 lines)" per line, or error message.
 */
internal object CoverageRenderer : ToolResultRenderer {

    private val COVERAGE_LINE = Regex("""^(.+?):\s+([\d.]+)%\s+covered\s+\((\d+)\s*/\s*(\d+)\s+lines\)""")

    override fun render(output: String): String? {
        val lines = output.lines()
        val entries = lines.mapNotNull { COVERAGE_LINE.find(it.trim()) }
        if (entries.isEmpty()) return null

        val totalCovered = entries.sumOf { it.groupValues[3].toInt() }
        val totalLines = entries.sumOf { it.groupValues[4].toInt() }
        val overallPct = if (totalLines > 0) (totalCovered * 100.0 / totalLines) else 0.0

        val e = ToolRenderers::esc
        val sb = StringBuilder()
        sb.append("<div class='coverage-result'>")

        // Header
        sb.append("<div class='outline-header'>")
        sb.append("<span class='search-icon'>📊</span> ")
        sb.append("<span class='search-count'>${entries.size}</span> ")
        sb.append("<span class='search-label'>${if (entries.size == 1) "class" else "classes"} — ")
        sb.append("${String.format("%.1f", overallPct)}% overall</span>")
        sb.append("</div>")

        // Entries
        sb.append("<div class='coverage-entries'>")
        for (m in entries) {
            val name = m.groupValues[1]
            val pct = m.groupValues[2].toDouble()
            val covered = m.groupValues[3]
            val total = m.groupValues[4]
            val barColor = when {
                pct >= 80 -> "var(--success)"
                pct >= 50 -> "var(--warning)"
                else -> "var(--danger)"
            }
            sb.append("<div class='coverage-entry'>")
            sb.append("<div class='coverage-entry-header'>")
            sb.append("<span class='coverage-name'>${e(name)}</span>")
            sb.append("<span class='coverage-pct' style='color:$barColor'>${e(m.groupValues[2])}%</span>")
            sb.append("</div>")
            sb.append("<div class='coverage-bar'>")
            sb.append("<div class='coverage-bar-fill' style='width:${pct}%;background:$barColor'></div>")
            sb.append("</div>")
            sb.append("<div class='coverage-detail'>${e(covered)} / ${e(total)} lines</div>")
            sb.append("</div>")
        }
        sb.append("</div>")
        sb.append("</div>")
        return sb.toString()
    }
}
