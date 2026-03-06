package com.github.catatafishen.ideagentforcopilot.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Lightweight floating popup that shows tool call details (parameters and result).
 * Uses [JBPopupFactory] — movable, resizable, closable via Escape or click-away.
 * No bottom button bar; much lighter than DialogWrapper.
 */
internal object ToolCallPopup {

    private var currentPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    fun show(project: Project, title: String, arguments: String?, result: String?, status: String?) {
        currentPopup?.cancel()
        val content = buildContent(arguments, result, status)
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(false)
            .setCancelOnOtherWindowOpen(false)
            .setCancelKeyEnabled(true)
            .setMinSize(Dimension(JBUI.scale(300), JBUI.scale(150)))
            .createPopup()
        currentPopup = popup

        val frame = WindowManager.getInstance().getFrame(project)
        if (frame != null) {
            val center = Point(
                frame.x + (frame.width - JBUI.scale(520)) / 2,
                frame.y + (frame.height - JBUI.scale(400)) / 2
            )
            popup.showInScreenCoordinates(frame.rootPane, center)
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun buildContent(arguments: String?, result: String?, status: String?): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(400))

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        if (!arguments.isNullOrBlank()) {
            content.add(createSection("Parameters", prettyJson(arguments)))
        }

        val resultText = result ?: if (status == "failed") "✖ Failed" else "Completed"
        content.add(createSection("Result", resultText))

        val scrollPane = JBScrollPane(
            content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        )
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    private fun createSection(label: String, text: String): JPanel {
        val section = JPanel(BorderLayout(0, JBUI.scale(4)))
        section.alignmentX = JPanel.LEFT_ALIGNMENT
        section.border = JBUI.Borders.emptyBottom(8)

        val header = JBLabel(label).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(font.size2D - 1f)
        }
        section.add(header, BorderLayout.NORTH)

        val textArea = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("JetBrains Mono", JBUI.Fonts.smallFont().size)
            border = JBUI.Borders.empty(6)
            background = JBColor(
                java.awt.Color(0xF5, 0xF5, 0xF5),
                java.awt.Color(0x2B, 0x2D, 0x30)
            )
            rows = text.lines().size.coerceIn(2, 15)
        }

        val scroll = JBScrollPane(
            textArea,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        )
        scroll.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(300))
        section.add(scroll, BorderLayout.CENTER)
        return section
    }

    private fun prettyJson(json: String): String {
        return try {
            val el = JsonParser.parseString(json)
            GsonBuilder().setPrettyPrinting().create().toJson(el)
        } catch (_: Exception) {
            json
        }
    }
}
