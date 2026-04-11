package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class BinaryDetectorTest {

    // ── parseVersion (private static) ───────────────────────────────────

    @Test
    void parseVersion_simpleVersion() throws Exception {
        assertEquals("0.22.0", invokeParseVersion("0.22.0"));
    }

    @Test
    void parseVersion_versionWithPrefix() throws Exception {
        assertEquals("copilot version 0.22.0", invokeParseVersion("copilot version 0.22.0"));
    }

    @Test
    void parseVersion_multiLineSkipsNoise() throws Exception {
        String output = """
            Welcome to Copilot CLI
            Loading configuration...
            0.22.0
            """;
        assertEquals("0.22.0", invokeParseVersion(output));
    }

    @Test
    void parseVersion_skipsWelcomeLine() throws Exception {
        String output = """
            Welcome to the CLI v2.0
            1.2.3
            """;
        assertEquals("1.2.3", invokeParseVersion(output));
    }

    @Test
    void parseVersion_skipsLoadingLine() throws Exception {
        String output = "loading modules...\n2.1.0";
        assertEquals("2.1.0", invokeParseVersion(output));
    }

    @Test
    void parseVersion_skipsInitializingLine() throws Exception {
        String output = "Initializing environment 1.0\n3.5.2-beta";
        assertEquals("3.5.2-beta", invokeParseVersion(output));
    }

    @Test
    void parseVersion_noVersionFound() throws Exception {
        assertNull(invokeParseVersion("no version here\njust text"));
    }

    @Test
    void parseVersion_emptyOutput() throws Exception {
        assertNull(invokeParseVersion(""));
    }

    @Test
    void parseVersion_blankLines() throws Exception {
        assertNull(invokeParseVersion("\n\n  \n"));
    }

    @Test
    void parseVersion_firstMatchReturned() throws Exception {
        String output = "1.0.0\n2.0.0";
        assertEquals("1.0.0", invokeParseVersion(output));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static String invokeParseVersion(String output) throws Exception {
        Method m = BinaryDetector.class.getDeclaredMethod("parseVersion", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, output);
    }
}
