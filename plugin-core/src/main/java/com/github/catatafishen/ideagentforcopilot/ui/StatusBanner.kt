package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.util.Alarm
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * Transient status banner that shows error/warning/info messages above the chat panel.
 *
 * Uses [InlineBanner] for native JetBrains look and feel. Info messages auto-dismiss
 * after [INFO_DISMISS_MS] (paused while hovered); warning messages after [WARNING_DISMISS_MS];
 * error messages stay until the user clicks the close button.
 *
 * Uses [Alarm] for dismiss scheduling (lifecycle-aware, EDT-native).
 */
class StatusBanner(parentDisposable: Disposable) :
    com.intellij.ui.components.JBPanel<StatusBanner>(BorderLayout()) {

    private companion object {
        const val INFO_DISMISS_MS = 10_000
        const val WARNING_DISMISS_MS = 15_000
    }

    private val dismissAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    private var currentBanner: InlineBanner? = null
    private var hovered = false

    init {
        isOpaque = false
        addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(e: AncestorEvent) { /* no-op */
            }

            override fun ancestorMoved(e: AncestorEvent) { /* no-op */
            }

            override fun ancestorRemoved(e: AncestorEvent) {
                dismissAlarm.cancelAllRequests()
            }
        })
    }

    fun showError(message: String) = show(message, EditorNotificationPanel.Status.Error)

    fun showWarning(message: String) = show(message, EditorNotificationPanel.Status.Warning)

    fun showInfo(message: String) = show(message, EditorNotificationPanel.Status.Info)

    private fun show(message: String, status: EditorNotificationPanel.Status) {
        SwingUtilities.invokeLater {
            dismiss()
            val banner = InlineBanner(message, status)
            banner.showCloseButton(true)
            banner.setCloseAction { dismiss() }
            banner.accessibleContext.accessibleName = when (status) {
                EditorNotificationPanel.Status.Error -> "Error: $message"
                EditorNotificationPanel.Status.Warning -> "Warning: $message"
                else -> "Info: $message"
            }

            val dismissMs = when (status) {
                EditorNotificationPanel.Status.Error -> 0
                EditorNotificationPanel.Status.Warning -> WARNING_DISMISS_MS
                else -> INFO_DISMISS_MS
            }

            if (dismissMs > 0) {
                banner.addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        dismissAlarm.cancelAllRequests()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        scheduleDismiss(dismissMs)
                    }
                })
                scheduleDismiss(dismissMs)
            }

            currentBanner = banner
            add(banner, BorderLayout.CENTER)
            revalidate()
            repaint()
        }
    }

    private fun scheduleDismiss(delayMs: Int) {
        dismissAlarm.cancelAllRequests()
        dismissAlarm.addRequest(::dismiss, delayMs.toLong())
    }

    private fun dismiss() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        dismissAlarm.cancelAllRequests()
        hovered = false
        currentBanner?.let { banner ->
            currentBanner = null
            banner.close()
            // Ensure parent relayouts after InlineBanner's close animation
            SwingUtilities.invokeLater {
                revalidate()
                repaint()
            }
        }
    }
}
