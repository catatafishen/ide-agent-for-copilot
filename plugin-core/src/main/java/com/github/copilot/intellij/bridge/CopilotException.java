package com.github.copilot.intellij.bridge;

/**
 * Exception thrown when Copilot ACP operations fail.
 */
public class CopilotException extends Exception {
    private final boolean recoverable;

    public CopilotException(String message) {
        this(message, null, true);
    }

    public CopilotException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public CopilotException(String message, Throwable cause, boolean recoverable) {
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
