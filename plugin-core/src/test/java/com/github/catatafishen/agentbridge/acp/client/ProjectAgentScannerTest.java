package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ProjectAgentScannerTest {

    // ── stripYamlValue (private static) ─────────────────────────────────

    @Test
    void stripYamlValue_doubleQuoted() throws Exception {
        assertEquals("hello world", invokeStripYamlValue("\"hello world\""));
    }

    @Test
    void stripYamlValue_singleQuoted() throws Exception {
        assertEquals("hello world", invokeStripYamlValue("'hello world'"));
    }

    @Test
    void stripYamlValue_unquoted() throws Exception {
        assertEquals("hello world", invokeStripYamlValue("hello world"));
    }

    @Test
    void stripYamlValue_trailingWhitespace() throws Exception {
        assertEquals("value", invokeStripYamlValue("  value  "));
    }

    @Test
    void stripYamlValue_quotedWithWhitespace() throws Exception {
        assertEquals("value", invokeStripYamlValue("  \"value\"  "));
    }

    @Test
    void stripYamlValue_emptyString() throws Exception {
        assertEquals("", invokeStripYamlValue(""));
    }

    @Test
    void stripYamlValue_justQuotes() throws Exception {
        assertEquals("", invokeStripYamlValue("\"\""));
    }

    @Test
    void stripYamlValue_mismatchedQuotesNotStripped() throws Exception {
        assertEquals("\"hello'", invokeStripYamlValue("\"hello'"));
    }

    @Test
    void stripYamlValue_singleChar() throws Exception {
        assertEquals("x", invokeStripYamlValue("x"));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static String invokeStripYamlValue(String raw) throws Exception {
        Method m = ProjectAgentScanner.class.getDeclaredMethod("stripYamlValue", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }
}
