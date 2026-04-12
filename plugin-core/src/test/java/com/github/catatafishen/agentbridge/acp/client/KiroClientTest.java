package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ── stripAnsi (package-private static) ────────────────────────────

    @Test
    void stripAnsi_removesColorCodes() {
        assertEquals(
            "The application panicked (crashed).",
            KiroClient.stripAnsi("\u001b[31mThe application panicked (crashed).\u001b[0m")
        );
    }

    @Test
    void stripAnsi_removesMultipleCodes() {
        assertEquals(
            "thread 'agent' panicked at src/main.rs:42",
            KiroClient.stripAnsi("\u001b[33mthread 'agent' panicked at \u001b[35msrc/main.rs\u001b[0m:\u001b[35m42\u001b[0m")
        );
    }

    @Test
    void stripAnsi_noOpForCleanString() {
        assertEquals("no ansi here", KiroClient.stripAnsi("no ansi here"));
    }

    @Test
    void stripAnsi_emptyString() {
        assertEquals("", KiroClient.stripAnsi(""));
    }

    @Test
    void stripAnsi_boldAndReset() {
        assertEquals("bold text", KiroClient.stripAnsi("\u001b[1mbold text\u001b[0m"));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static boolean invokeIsPanicLine(String line) throws Exception {
        Method m = KiroClient.class.getDeclaredMethod("isPanicLine", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, line);
    }
}
