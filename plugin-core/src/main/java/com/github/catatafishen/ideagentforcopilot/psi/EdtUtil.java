package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.SlowOperations;

/**
 * Utility for safely running operations on the EDT that involve VFS/PSI lookups.
 * Wraps all EDT dispatches so that tool handlers can resolve files and perform
 * write actions without triggering "Slow operations are prohibited on EDT" assertions.
 */
@SuppressWarnings({"deprecation", "UnstableApiUsage"})
public final class EdtUtil {

    private EdtUtil() {
    }

    /**
     * Dispatch a runnable to the EDT, allowing slow operations (VFS, PSI, etc.).
     */
    public static void invokeLater(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() ->
            SlowOperations.allowSlowOperations(() -> {
                runnable.run();
                return null;
            })
        );
    }

    /**
     * Block the calling thread until the runnable completes on the EDT,
     * allowing slow operations inside the EDT block.
     */
    public static void invokeAndWait(Runnable runnable) {
        ApplicationManager.getApplication().invokeAndWait(() ->
            SlowOperations.allowSlowOperations(() -> {
                runnable.run();
                return null;
            })
        );
    }
}
