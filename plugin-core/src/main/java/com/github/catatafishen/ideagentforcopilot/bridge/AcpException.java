package com.github.catatafishen.ideagentforcopilot.bridge;

/**
 * Exception thrown when ACP (Agent Client Protocol) operations fail.
 */
public class AcpException extends Exception {
    private final boolean recoverable;
    private final int errorCode;
    private final String errorData;

    public AcpException(String message) {
        this(message, null, true, 0, null);
    }

    public AcpException(String message, Throwable cause) {
        this(message, cause, true, 0, null);
    }

    public AcpException(String message, Throwable cause, boolean recoverable) {
        this(message, cause, recoverable, 0, null);
    }

    public AcpException(String message, Throwable cause, boolean recoverable, int errorCode, String errorData) {
        super(message, cause);
        this.recoverable = recoverable;
        this.errorCode = errorCode;
        this.errorData = errorData;
    }

    /**
     * Whether this error is recoverable (e.g., network timeout vs. invalid session).
     */
    public boolean isRecoverable() {
        return recoverable;
    }

    /**
     * JSON-RPC error code, if applicable.
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * Extra error data (e.g. detailed API response) from JSON-RPC.
     */
    public String getErrorData() {
        return errorData;
    }
}
