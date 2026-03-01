package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.SlowOperations;

/**
 * Utility for safely running operations on the EDT that involve VFS/PSI lookups.
 * Wraps all EDT dispatches so that tool handlers can resolve files and perform
 * write actions without triggering "Slow operations are prohibited on EDT" assertions.
 */
@SuppressWarnings("removal")
public final class EdtUtil {

    private EdtUtil() {
    }

    /**
     * Dispatch a runnable to the EDT, allowing slow operations (VFS, PSI, etc.).
     */
    public static void invokeLater(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (AccessToken ignore = SlowOperations.allowSlowOperations("IdeAgentForCopilot")) {
                runnable.run();
            }
        });
    }

    /**
     * Block the calling thread until the runnable completes on the EDT,
     * allowing slow operations inside the EDT block.
     */
    public static void invokeAndWait(Runnable runnable) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try (AccessToken ignore = SlowOperations.allowSlowOperations("IdeAgentForCopilot")) {
                runnable.run();
            }
        });
    }
}
