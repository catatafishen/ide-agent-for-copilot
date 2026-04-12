package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for private static methods in {@link McpHttpServer} via reflection.
 */
class McpHttpServerStaticMethodsTest {

    private static Method truncateForLog;

    @BeforeAll
    static void setup() throws Exception {
        truncateForLog = McpHttpServer.class.getDeclaredMethod("truncateForLog", String.class);
        truncateForLog.setAccessible(true);
    }

    private String callTruncateForLog(String s) throws Exception {
        return (String) truncateForLog.invoke(null, s);
    }

    @Test
    void nullReturnsNull() throws Exception {
        assertNull(callTruncateForLog(null));
    }

    @Test
    void emptyStringUnchanged() throws Exception {
        assertEquals("", callTruncateForLog(""));
    }

    @Test
    void shortStringUnchanged() throws Exception {
        assertEquals("hello", callTruncateForLog("hello"));
    }

    @Test
    void exactlyAtLimitUnchanged() throws Exception {
        String atLimit = "x".repeat(2000);
        assertEquals(atLimit, callTruncateForLog(atLimit));
    }

    @Test
    void overLimitTruncatesWithSuffix() throws Exception {
        String overLimit = "a".repeat(2500);
        String result = callTruncateForLog(overLimit);
        assertTrue(result.startsWith("a".repeat(2000)));
        assertTrue(result.contains("[truncated 500 chars]"));
    }

    @Test
    void truncationCountIsAccurate() throws Exception {
        String input = "b".repeat(3000);
        String result = callTruncateForLog(input);
        assertTrue(result.contains("[truncated 1000 chars]"));
    }

    @Test
    void onePastLimitTruncates() throws Exception {
        String input = "c".repeat(2001);
        String result = callTruncateForLog(input);
        assertTrue(result.contains("[truncated 1 chars]"));
        assertTrue(result.startsWith("c".repeat(2000)));
    }
}
