package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class KiroClientTest {

    // ── isPanicLine (private static) ────────────────────────────────────

    @Test
    void isPanicLine_panickedAt() throws Exception {
        assertTrue(invokeIsPanicLine("thread 'main' panicked at 'index out of bounds', src/main.rs:42:5"));
    }

    @Test
    void isPanicLine_applicationPanicked() throws Exception {
        assertTrue(invokeIsPanicLine("The application panicked (crash handler installed)"));
    }

    @Test
    void isPanicLine_normalLine() throws Exception {
        assertFalse(invokeIsPanicLine("Starting Kiro server on port 3000"));
    }

    @Test
    void isPanicLine_emptyLine() throws Exception {
        assertFalse(invokeIsPanicLine(""));
    }

    @Test
    void isPanicLine_containsPanickedAtMiddle() throws Exception {
        assertTrue(invokeIsPanicLine("error: thread 'tokio-runtime' panicked at core/event.rs:128"));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static boolean invokeIsPanicLine(String line) throws Exception {
        Method m = KiroClient.class.getDeclaredMethod("isPanicLine", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, line);
    }
}
