package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.*

/**
 * Bottom-toolbar panel that lists the keyboard shortcuts relevant to the
 * current chat-input state, left-aligned alongside the model selector and
 * Send button.
 *
 *  - Idle  → Enter ▸ Send,  Shift+Enter ▸ New line
 *  - Busy  → Enter ▸ Nudge,  Ctrl+Enter ▸ Stop && send,  Ctrl+Shift+Enter ▸ Queue,  Shift+Enter ▸ New line
 *  - When a nudge or queued message is pending, an extra `↑ ▸ Edit last` hint
 *    is appended.
 *
 * Each entry renders as one or more [KeyBadge]s followed by a small action
 * label. Reads actual key bindings from the IntelliJ keymap so customized
 * shortcuts are reflected.
 *
 * Uses [BoxLayout] (X_AXIS) so entries clip from the right when space is
 * tight, rather than wrapping to a second row (which would change the panel
 * height and push the editor up).
 *
 * Visibility honours the *Show shortcut hints* toggle in
 * Settings → AgentBridge → Chat Input.
 */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>() {

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty()
    }

    /**
     * Replace the displayed hints with the given keystroke/label pairs. The
     * order of [shortcuts] is preserved, rendered left-to-right.
     */
    fun setShortcuts(shortcuts: List<Pair<KeyStroke, String>>) {
        removeAll()
        shortcuts.forEachIndexed { i, (stroke, label) ->
            if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(buildEntry(stroke, label))
        }
        revalidate()
        repaint()
    }

    private fun buildEntry(stroke: KeyStroke, label: String): JComponent {
        val cell = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            alignmentY = Component.CENTER_ALIGNMENT
        }
        KeyBadge.keystrokeTokens(stroke).forEach { cell.add(KeyBadge(it)) }
        val text = JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(2)
        }
        cell.add(text)
        return cell
    }
}
