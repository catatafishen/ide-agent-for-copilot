package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Displays keyboard shortcut hints in a horizontal, non-wrapping row. */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>() {

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty()
        alignmentY = CENTER_ALIGNMENT
    }

    fun setShortcuts(shortcuts: List<Pair<KeyStroke, String>>) {
        removeAll()
        shortcuts.forEach { (stroke, label) ->
            add(shortcutCell(stroke, label))
        }
        revalidate()
        repaint()
    }

    private fun shortcutCell(stroke: KeyStroke, label: String): JPanel {
        val cell = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(JBUI.scale(8))
            alignmentY = CENTER_ALIGNMENT
        }
        KeyBadge.keystrokeTokens(stroke).forEach { cell.add(KeyBadge(it)) }
        cell.add(JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(2)
        })
        return cell
    }
}
