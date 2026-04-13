package com.github.catatafishen.agentbridge.psi.tools.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pure static methods in {@link ReadFileTool}:
 * {@code extractLineRange} and {@code applyReadHintAndTruncate}.
 */
class ReadFileToolStaticMethodsTest {

    private static final String FIVE_LINES = "a\nb\nc\nd\ne";

    // ── extractLineRange ────────────────────────────────────

    @Test
    void extractLineRange_middleRange() {
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 2, 4);
        assertEquals("2: b\n3: c\n4: d\n", result);
    }

    @Test
    void extractLineRange_singleLine() {
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 1, 1);
        assertEquals("1: a\n", result);
    }

    @Test
    void extractLineRange_startOnlyNoEnd() {
        // endLine = -1 means no end specified → returns all lines from start
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 1, -1);
        assertEquals("1: a\n2: b\n3: c\n4: d\n5: e\n", result);
    }

    @Test
    void extractLineRange_noRange() {
        // Both -1 → returns all lines from the beginning
        String result = ReadFileTool.extractLineRange(FIVE_LINES, -1, -1);
        assertEquals("1: a\n2: b\n3: c\n4: d\n5: e\n", result);
    }

    @Test
    void extractLineRange_emptyContent() {
        // Empty content with range beyond file → empty result
        String result = ReadFileTool.extractLineRange("", 2, 4);
        assertEquals("", result);
    }

    @Test
    void extractLineRange_beyondFileEnd() {
        // start=3, end=10 but file only has 5 lines → returns lines 3 to end
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 3, 10);
        assertEquals("3: c\n4: d\n5: e\n", result);
    }

    // ── applyReadHintAndTruncate ────────────────────────────

    @Test
    void applyReadHintAndTruncate_shortContentNoHint() {
        String content = "a\nb\nc";
        String result = ReadFileTool.applyReadHintAndTruncate(content, null);
        assertTrue(result.startsWith("[3 lines total]\n"),
                "Should start with line count header, got: " + result);
        assertTrue(result.contains("a\nb\nc"));
    }

    @Test
    void applyReadHintAndTruncate_shortContentWithHint() {
        String content = "a\nb\nc";
        String result = ReadFileTool.applyReadHintAndTruncate(content, "[source]");
        assertTrue(result.startsWith("[3 lines total]\n[source]\n"),
                "Should start with line count and hint, got: " + result);
        assertTrue(result.contains("a\nb\nc"));
    }

    @Test
    void applyReadHintAndTruncate_longContentIsTruncated() {
        // Build content with more than MAX_READ_LINES lines
        int totalLines = ReadFileTool.MAX_READ_LINES + 500;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= totalLines; i++) {
            if (i > 1) sb.append("\n");
            sb.append("line ").append(i);
        }
        String content = sb.toString();

        String result = ReadFileTool.applyReadHintAndTruncate(content, null);
        assertTrue(result.contains("[Showing first " + ReadFileTool.MAX_READ_LINES + " lines"),
                "Should contain truncation notice, got start: " + result.substring(0, Math.min(200, result.length())));
        assertTrue(result.contains("[" + totalLines + " lines total]"),
                "Should contain total line count");
        // Verify it contains the first line but not the last
        assertTrue(result.contains("line 1"));
        assertFalse(result.contains("line " + totalLines));
    }

    @Test
    void applyReadHintAndTruncate_singleLineNullHint() {
        String content = "hello";
        String result = ReadFileTool.applyReadHintAndTruncate(content, null);
        assertTrue(result.startsWith("[1 lines total]\n"),
                "Should start with [1 lines total], got: " + result);
        assertTrue(result.endsWith("hello"));
    }
}
