package com.github.copilot.intellij.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.util.SlowOperations;

/**
 * Utility for safely running operations on the EDT that involve VFS/PSI lookups.
 * Wraps all EDT dispatches with a {@link SlowOperations} section so that
 * tool handlers can resolve files and perform write actions without triggering
 * "Slow operations are prohibited on EDT" assertions.
 */
public final class EdtUtil {

    private EdtUtil() {
    }

    /**
     * Dispatch a runnable to the EDT, allowing slow operations (VFS, PSI, etc.).
     */
    public static void invokeLater(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var ignored = SlowOperations.startSection(SlowOperations.ACTION_PERFORM)) {
                runnable.run();
            }
        });
    }

    /**
     * Dispatch a runnable to the EDT with a specific modality state,
     * allowing slow operations.
     */
    public static void invokeLater(Runnable runnable, ModalityState modalityState) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try (var ignored = SlowOperations.startSection(SlowOperations.ACTION_PERFORM)) {
                runnable.run();
            }
        }, modalityState);
    }

    /**
     * Block the calling thread until the runnable completes on the EDT,
     * allowing slow operations inside the EDT block.
     */
    public static void invokeAndWait(Runnable runnable) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try (var ignored = SlowOperations.startSection(SlowOperations.ACTION_PERFORM)) {
                runnable.run();
            }
        });
    }
}
