package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.components.JBPanel
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.BoxLayout
import javax.swing.SwingUtilities

/**
 * Transient status banner that shows error/info messages above the chat panel.
 * Info messages auto-dismiss after [INFO_DISMISS_SECONDS]; error messages stay until
 * the user clicks the close button.
 */
class StatusBanner : JBPanel<StatusBanner>() {

    private companion object {
        const val INFO_DISMISS_SECONDS = 8L
    }

    private var currentBanner: InlineBanner? = null
    private var dismissFuture: ScheduledFuture<*>? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    fun showError(message: String) = show(message, EditorNotificationPanel.Status.Error)

    fun showInfo(message: String) = show(message, EditorNotificationPanel.Status.Info)

    private fun show(message: String, status: EditorNotificationPanel.Status) {
        SwingUtilities.invokeLater {
            dismiss()
            val banner = InlineBanner(message, status)
            banner.showCloseButton(true)
            banner.setCloseAction { dismiss() }
            currentBanner = banner
            add(banner)
            revalidate()
            repaint()

            if (status == EditorNotificationPanel.Status.Info) {
                dismissFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { SwingUtilities.invokeLater { dismiss() } },
                    INFO_DISMISS_SECONDS, TimeUnit.SECONDS,
                )
            }
        }
    }

    private fun dismiss() {
        dismissFuture?.cancel(false)
        dismissFuture = null
        currentBanner?.let {
            remove(it)
            currentBanner = null
            revalidate()
            repaint()
        }
    }
}
