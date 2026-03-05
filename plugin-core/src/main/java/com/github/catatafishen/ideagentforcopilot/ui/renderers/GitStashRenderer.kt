package com.github.catatafishen.ideagentforcopilot.ui.renderers

/**
 * Renderer for git_stash output.
 * Input (list): "stash@{0}: WIP on main: 1234567 Commit message" per line.
 * Input (push/pop/apply/drop): single-line result message.
 */
internal object GitStashRenderer : ToolResultRenderer {

    private val STASH_LINE = Regex("""^stash@\{(\d+)}:\s*(.*?)(?::\s+([0-9a-f]+)\s+(.*))?$""")

    override fun render(output: String): String? {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val stashes = lines.mapNotNull { STASH_LINE.find(it) }

        // For non-list outputs (push/pop/apply/drop), fall back to default
        if (stashes.isEmpty()) return null

        val e = ToolRenderers::esc
        val sb = StringBuilder()
        sb.append("<div class='outline-result'>")

        // Header
        sb.append("<div class='outline-header'>")
        sb.append("<span class='search-icon'>📦</span> ")
        sb.append("<span class='search-count'>${stashes.size}</span> ")
        sb.append("<span class='search-label'>${if (stashes.size == 1) "stash" else "stashes"}</span>")
        sb.append("</div>")

        // Stashes
        sb.append("<div class='outline-section-items'>")
        for (m in stashes) {
            val index = m.groupValues[1]
            val description = m.groupValues[2]
            val hash = m.groupValues[3]
            val message = m.groupValues[4]

            sb.append("<div class='outline-item'>")
            sb.append("<span class='git-file-badge badge-field'>$index</span> ")
            if (message.isNotEmpty()) {
                sb.append("<span>${e(message)}</span>")
                sb.append(" <span class='inspection-file-count'>${e(description)}</span>")
            } else {
                sb.append("<span>${e(description)}</span>")
            }
            if (hash.isNotEmpty()) {
                sb.append(" <code class='git-commit-hash'>${e(hash.take(7))}</code>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")
        sb.append("</div>")
        return sb.toString()
    }
}
