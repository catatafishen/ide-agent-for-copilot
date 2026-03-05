package com.github.catatafishen.ideagentforcopilot.ui.renderers

/**
 * Renderer for get_class_outline output.
 * Input: "class/interface QName [extends X] [implements Y]\n\nSection:\n  signature\n..."
 */
internal object ClassOutlineRenderer : ToolResultRenderer {

    private val HEADER = Regex("""^(class|interface|enum|record|annotation)\s+(\S+)(.*)""")
    private val SECTION = Regex("""^(Constructors|Methods|Fields|Inner classes):$""")

    override fun render(output: String): String? {
        val lines = output.lines()
        val headerMatch = lines.firstOrNull()?.let { HEADER.find(it.trim()) } ?: return null

        val kind = headerMatch.groupValues[1]
        val qName = headerMatch.groupValues[2]
        val rest = headerMatch.groupValues[3].trim()
        val simpleName = qName.substringAfterLast('.')
        val packageName = if (qName.contains('.')) qName.substringBeforeLast('.') else ""

        val e = ToolRenderers::esc
        val sb = StringBuilder()
        sb.append("<div class='outline-result'>")

        // Header
        val badge = when (kind) {
            "class" -> "C"
            "interface" -> "I"
            "enum" -> "E"
            "record" -> "R"
            "annotation" -> "@"
            else -> kind.first().uppercaseChar().toString()
        }
        val badgeClass = when (kind) {
            "class" -> "badge-class"
            "interface" -> "badge-interface"
            "enum" -> "badge-enum"
            else -> "badge-method"
        }
        sb.append("<div class='outline-header'>")
        sb.append("<span class='git-file-badge $badgeClass'>$badge</span> ")
        sb.append("<strong>${e(simpleName)}</strong>")
        if (packageName.isNotEmpty()) {
            sb.append(" <span class='inspection-file-count'>${e(packageName)}</span>")
        }
        sb.append("</div>")

        // Inheritance
        if (rest.isNotEmpty()) {
            sb.append("<div class='class-outline-inheritance'>${e(rest)}</div>")
        }

        // Sections
        var currentSection = ""
        val sectionItems = mutableListOf<String>()

        fun flushSection() {
            if (currentSection.isNotEmpty() && sectionItems.isNotEmpty()) {
                sb.append("<div class='outline-section'>")
                sb.append("<div class='outline-section-title'>${e(currentSection)}")
                sb.append(" <span class='inspection-file-count'>${sectionItems.size}</span>")
                sb.append("</div>")
                sb.append("<div class='outline-section-items'>")
                for (item in sectionItems) {
                    val sectionBadge = when (currentSection) {
                        "Constructors" -> "<span class='git-file-badge badge-method'>⊕</span>"
                        "Methods" -> "<span class='git-file-badge badge-method'>M</span>"
                        "Fields" -> "<span class='git-file-badge badge-field'>F</span>"
                        "Inner classes" -> "<span class='git-file-badge badge-class'>C</span>"
                        else -> ""
                    }
                    sb.append("<div class='outline-item'>$sectionBadge <code>${e(item)}</code></div>")
                }
                sb.append("</div></div>")
            }
            sectionItems.clear()
        }

        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val sectionMatch = SECTION.find(trimmed)
            if (sectionMatch != null) {
                flushSection()
                currentSection = sectionMatch.groupValues[1]
            } else if (currentSection.isNotEmpty() && trimmed.isNotEmpty()) {
                sectionItems.add(trimmed)
            }
        }
        flushSection()

        sb.append("</div>")
        return sb.toString()
    }
}
