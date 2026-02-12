package com.github.copilot.intellij.bridge;

/**
 * Exception thrown when sidecar operations fail.
 */
public class SidecarException extends Exception {
    private final boolean recoverable;

    public SidecarException(String message) {
        this(message, null, true);
    }

    public SidecarException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public SidecarException(String message, Throwable cause, boolean recoverable) {
        super(message, cause);
        this.recoverable = recoverable;
    }

    /**
     * Whether this error is recoverable (e.g., network timeout vs. invalid session).
     */
    public boolean isRecoverable() {
        return recoverable;
    }
}
