package com.github.catatafishen.ideagentforcopilot.ui.renderers

/**
 * Renderer for list_run_configurations output.
 * Input: "N run configurations:\nName [Type][ (temporary)]"
 */
internal object RunConfigRenderer : ToolResultRenderer {

    private val CONFIG_LINE = Regex("""^(.+?)\s+\[(.+?)](.*)?$""")
    private val HEADER = Regex("""^(\d+)\s+run\s+configurations?:""")

    override fun render(output: String): String? {
        val lines = output.lines()
        val headerMatch = lines.firstOrNull()?.let { HEADER.find(it.trim()) }
        val configLines = lines.drop(if (headerMatch != null) 1 else 0)
        val configs = configLines.mapNotNull { CONFIG_LINE.find(it.trim()) }
        if (configs.isEmpty()) return null

        val e = ToolRenderers::esc
        val sb = StringBuilder()
        sb.append("<div class='outline-result'>")

        // Header
        sb.append("<div class='outline-header'>")
        sb.append("<span class='search-icon'>▶</span> ")
        sb.append("<span class='search-count'>${configs.size}</span> ")
        sb.append("<span class='search-label'>run ${if (configs.size == 1) "configuration" else "configurations"}</span>")
        sb.append("</div>")

        // Configs
        sb.append("<div class='outline-section-items'>")
        for (m in configs) {
            val name = m.groupValues[1].trim()
            val type = m.groupValues[2].trim()
            val suffix = m.groupValues[3].trim()
            val isTemp = suffix.contains("temporary")

            val badge = when {
                type.contains("Application") -> "▶"
                type.contains("JUnit") || type.contains("Test") -> "✓"
                type.contains("Gradle") -> "🔧"
                else -> "◆"
            }
            val badgeClass = when {
                type.contains("Application") -> "badge-method"
                type.contains("JUnit") || type.contains("Test") -> "badge-interface"
                type.contains("Gradle") -> "badge-enum"
                else -> "badge-field"
            }

            sb.append("<div class='outline-item'>")
            sb.append("<span class='git-file-badge $badgeClass'>$badge</span> ")
            sb.append("<strong>${e(name)}</strong>")
            sb.append(" <span class='inspection-file-count'>${e(type)}</span>")
            if (isTemp) sb.append(" <span class='inspection-file-count'>temp</span>")
            sb.append("</div>")
        }
        sb.append("</div>")
        sb.append("</div>")
        return sb.toString()
    }
}
