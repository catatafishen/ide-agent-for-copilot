package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InfrastructureTool#formatRunOutput(String, String, int)}.
 * <p>
 * Uses a minimal concrete subclass to invoke the protected method directly.
 * The method uses no instance state, so passing {@code null} as the project is safe.
 */
class InfrastructureToolFormatTest {

    private TestableInfrastructureTool tool;

    @BeforeEach
    void setUp() {
        tool = new TestableInfrastructureTool();
    }

    @Test
    void shortText_notTruncated() {
        String text = "Hello, world!";
        String result = tool.callFormatRunOutput("MyTab", text, 100);

        assertTrue(result.contains(text), "Short text should appear in full");
        assertFalse(result.contains("truncated"), "Should not contain truncation notice");
    }

    @Test
    void textExactlyAtMax_notTruncated() {
        String text = "abcdefghij"; // 10 chars
        String result = tool.callFormatRunOutput("Tab", text, 10);

        assertTrue(result.contains(text), "Exact-length text should appear in full");
        assertFalse(result.contains("truncated"), "Should not contain truncation notice");
    }

    @Test
    void longText_tailTruncated() {
        String text = "abcdefghijklmnopqrstuvwxyz"; // 26 chars
        String result = tool.callFormatRunOutput("Tab", text, 10);

        assertTrue(result.contains("truncated"), "Should contain truncation notice");
        assertTrue(result.endsWith("qrstuvwxyz"), "Should show last maxChars chars");
        assertFalse(result.contains("abcdefg"), "Should not contain beginning of text");
    }

    @Test
    void headerShowsDisplayName() {
        String result = tool.callFormatRunOutput("MyRunTab", "text", 100);

        assertTrue(result.startsWith("Tab: MyRunTab\n"), "Header should start with Tab: displayName");
    }

    @Test
    void headerShowsTotalLength() {
        String text = "hello"; // 5 chars
        String result = tool.callFormatRunOutput("Tab", text, 100);

        assertTrue(result.contains("Total length: 5 chars"), "Header should show total character count");
    }

    @Test
    void truncationNoticeShowsCharCounts() {
        String text = "a".repeat(200);
        String result = tool.callFormatRunOutput("Tab", text, 50);

        assertTrue(result.contains("showing last 50 of 200 chars"),
            "Truncation notice should show maxChars and total");
    }

    @Test
    void emptyText_handledGracefully() {
        String result = tool.callFormatRunOutput("Tab", "", 100);

        assertNotNull(result);
        assertTrue(result.contains("Total length: 0 chars"), "Should report zero length");
        assertFalse(result.contains("truncated"), "Empty text should not trigger truncation");
    }

    @Test
    void veryLargeMaxChars_noTruncation() {
        String text = "short text";
        String result = tool.callFormatRunOutput("Tab", text, 1_000_000);

        assertTrue(result.contains(text), "Text should appear in full");
        assertFalse(result.contains("truncated"), "Should not contain truncation notice");
    }

    // ── Concrete test subclass ──────────────────────────────

    /**
     * Minimal concrete subclass that exposes {@link #formatRunOutput} for testing.
     * Passes {@code null} as the project since {@code formatRunOutput} uses no instance state.
     */
    private static class TestableInfrastructureTool extends InfrastructureTool {

        TestableInfrastructureTool() {
            super(null);
        }

        String callFormatRunOutput(String displayName, String text, int maxChars) {
            return formatRunOutput(displayName, text, maxChars);
        }

        @Override
        public @NotNull String id() {
            return "test";
        }

        @Override
        public @NotNull String displayName() {
            return "Test";
        }

        @Override
        public @NotNull String description() {
            return "test tool";
        }

        @Override
        public @NotNull Kind kind() {
            return Kind.READ;
        }

        @Override
        public @NotNull String execute(@NotNull JsonObject args) {
            return "";
        }
    }
}
