package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders file outline results as a structured list with type badges
 * and line numbers.
 */
internal object FileOutlineRenderer : ToolResultRenderer {

    private val ENTRY_PATTERN = Regex("""^\s*(\d+):\s+(\w+)\s+(.+)$""")
    private val HEADER_PATTERN = Regex("""^Outline of (.+):$""")

    private val CLASS_COLOR = JBColor(Color(0x08, 0x69, 0xDA), Color(0x58, 0xA6, 0xFF))
    private val INTERFACE_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val METHOD_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))
    private val FIELD_COLOR = JBColor(Color(0x8E, 0x44, 0xAD), Color(0xBB, 0x6B, 0xD9))

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val headerMatch = HEADER_PATTERN.find(lines.first()) ?: return null
        val filePath = headerMatch.groupValues[1]
        val fileName = filePath.substringAfterLast('/')

        val entries = lines.drop(1).mapNotNull { ENTRY_PATTERN.find(it.trim()) }
        if (entries.isEmpty()) return null

        val panel = ToolRenderers.listPanel()

        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(ToolRenderers.monoLabel(fileName).apply {
            font = font.deriveFont(Font.BOLD)
            toolTipText = filePath
        })
        headerRow.add(ToolRenderers.mutedLabel("${entries.size}"))
        panel.add(headerRow)

        for (entry in entries) {
            val lineNum = entry.groupValues[1]
            val type = entry.groupValues[2]
            val name = entry.groupValues[3]
            val (badge, color) = typeBadge(type)

            val row = ToolRenderers.rowPanel()
            row.add(ToolRenderers.badgeLabel(badge, color))
            row.add(JBLabel(name))
            row.add(ToolRenderers.mutedLabel(":$lineNum"))
            panel.add(row)
        }

        return panel
    }

    private fun typeBadge(type: String): Pair<String, Color> = when (type.lowercase()) {
        "class" -> "C" to CLASS_COLOR
        "interface" -> "I" to INTERFACE_COLOR
        "enum" -> "E" to CLASS_COLOR
        "method", "function" -> "M" to METHOD_COLOR
        "field", "property" -> "F" to FIELD_COLOR
        else -> type.take(1).uppercase() to UIUtil.getLabelForeground()
    }
}
