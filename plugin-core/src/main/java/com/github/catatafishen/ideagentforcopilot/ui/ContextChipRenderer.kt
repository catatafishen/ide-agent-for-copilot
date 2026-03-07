package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.UIManager

class ContextChipRenderer(val contextData: ContextItemData) : EditorCustomElementRenderer {

    private companion object {
        private const val H_PAD = 4
        private const val ICON_GAP = 3
        private const val ARC = 6
        private const val ICON_SIZE = 10
    }

    private val label: String = contextData.name

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(chipFont(editor))
        return H_PAD + ICON_SIZE + ICON_GAP + metrics.stringWidth(label) + H_PAD
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val linkColor = UIManager.getColor("Link.activeForeground")
                ?: UIManager.getColor("link.foreground")
                ?: JBColor(Color(0x58, 0x9D, 0xF6), Color(0x58, 0x9D, 0xF6))
            val codeBg = UIManager.getColor("EditorPane.background")
                ?: JBColor(Color(0xF2, 0xF2, 0xF2), Color(0x2B, 0x2B, 0x2B))

            val y = targetRegion.y + 1
            val h = targetRegion.height - 2
            val bgColor = Color(codeBg.red, codeBg.green, codeBg.blue, 40)
            g2.color = bgColor
            g2.fill(
                RoundRectangle2D.Float(
                    targetRegion.x.toFloat(), y.toFloat(),
                    targetRegion.width.toFloat(), h.toFloat(),
                    ARC.toFloat(), ARC.toFloat()
                )
            )

            val font = chipFont(inlay.editor)
            g2.font = font
            val metrics = g2.fontMetrics
            val textY = y + (h + metrics.ascent - metrics.descent) / 2

            var x = targetRegion.x + H_PAD

            // Draw file icon in link color
            val iconColor = Color(linkColor.red, linkColor.green, linkColor.blue, 180)
            g2.color = iconColor
            drawFileIcon(g2, x, y + (h - ICON_SIZE) / 2, ICON_SIZE)
            x += ICON_SIZE + ICON_GAP

            // Draw filename in link color
            g2.color = linkColor
            g2.drawString(label, x, textY)
        } finally {
            g2.dispose()
        }
    }

    private fun chipFont(editor: com.intellij.openapi.editor.Editor): Font {
        val editorFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return editorFont.deriveFont(editorFont.size2D * 0.85f)
    }

    /** Draws a small document icon (page with folded corner) at the given position. */
    private fun drawFileIcon(g2: Graphics2D, x: Int, y: Int, size: Int) {
        val fold = size / 3
        val xPoints = intArrayOf(x, x + size - fold, x + size, x + size, x)
        val yPoints = intArrayOf(y, y, y + fold, y + size, y + size)
        g2.fillPolygon(xPoints, yPoints, 5)
        // Folded corner
        val prevColor = g2.color
        g2.color = Color(prevColor.red, prevColor.green, prevColor.blue, 100)
        g2.fillPolygon(
            intArrayOf(x + size - fold, x + size - fold, x + size),
            intArrayOf(y, y + fold, y + fold),
            3
        )
        g2.color = prevColor
    }
}

/**
 * Data associated with an inline context chip.
 * Mirrors the fields of the old `ContextItem` but is a standalone data class
 * so the renderer can carry it without depending on the tool window's private types.
 */
data class ContextItemData(
    val path: String,
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val fileTypeName: String?,
    val isSelection: Boolean
)
