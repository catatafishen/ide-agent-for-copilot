package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.Regex;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests constants, regex patterns, and {@code parseLines()} in {@link GitShowRenderer}.
 * Does not construct any Swing components.
 */
class GitShowRendererTest {

    // ── COMMIT_PREFIX ───────────────────────────────────────

    @Test
    void commitPrefix_isCorrectString() {
        assertEquals("commit ", GitShowRenderer.COMMIT_PREFIX);
    }

    // ── SUMMARY_PATTERN ─────────────────────────────────────

    @Test
    void summaryPattern_matchesFullStats() {
        Regex pattern = GitShowRenderer.INSTANCE.getSUMMARY_PATTERN();
        assertTrue(pattern.containsMatchIn(" 3 files changed, 10 insertions(+), 5 deletions(-)"));
    }

    @Test
    void summaryPattern_matchesSingleFileDeletion() {
        Regex pattern = GitShowRenderer.INSTANCE.getSUMMARY_PATTERN();
        assertTrue(pattern.containsMatchIn("1 file changed, 1 deletion(-)"));
    }

    @Test
    void summaryPattern_doesNotMatchPlainText() {
        Regex pattern = GitShowRenderer.INSTANCE.getSUMMARY_PATTERN();
        assertFalse(pattern.containsMatchIn("no stats here"));
    }

    // ── parseLines() ────────────────────────────────────────

    @Test
    void parseLines_realisticGitShowOutput() {
        // Note: no blank line between Date and message — this is how the parser
        // expects the message lines to immediately follow the Date line.
        List<String> lines = Arrays.asList(
                "commit abc1234567890abcdef1234567890abcdef12345678",
                "Author: John Doe <john@example.com>",
                "Date:   Mon Jan 1 12:00:00 2024 +0000",
                "    Fix the bug in parser",
                "",
                " src/parser.java | 5 ++---",
                " 1 file changed, 2 insertions(+), 3 deletions(-)",
                "",
                "diff --git a/src/parser.java b/src/parser.java",
                "--- a/src/parser.java",
                "+++ b/src/parser.java",
                "@@ -1,3 +1,3 @@"
        );

        GitShowRenderer.ParsedShow parsed = GitShowRenderer.INSTANCE.parseLines(lines);

        assertEquals("abc12345", parsed.getHash());
        assertEquals("John Doe <john@example.com>", parsed.getAuthor());
        assertEquals("Mon Jan 1 12:00:00 2024 +0000", parsed.getDate());
        assertTrue(parsed.getMessageLines().contains("Fix the bug in parser"),
                "messageLines should contain 'Fix the bug in parser'");
        assertTrue(parsed.getStatLines().contains(" src/parser.java | 5 ++---"),
                "statLines should contain the file stat line");
        assertEquals("1 file changed, 2 insertions(+), 3 deletions(-)",
                parsed.getSummaryLine());
    }

    @Test
    void parseLines_hashTruncatedToEightChars() {
        List<String> lines = Arrays.asList(
                "commit 1234567890abcdef1234567890abcdef12345678",
                "Author: Test <test@test.com>"
        );
        GitShowRenderer.ParsedShow parsed = GitShowRenderer.INSTANCE.parseLines(lines);
        assertEquals("12345678", parsed.getHash());
    }

    @Test
    void parseLines_diffContentIsSkipped() {
        List<String> lines = Arrays.asList(
                "commit abcdef1234567890abcdef1234567890abcdef12",
                "Author: Test <test@test.com>",
                "Date:   Mon Jan 1 12:00:00 2024 +0000",
                "    Initial commit",
                "",
                "diff --git a/file.java b/file.java",
                " file.java | 10 ++++------",
                " 1 file changed, 4 insertions(+), 6 deletions(-)"
        );
        GitShowRenderer.ParsedShow parsed = GitShowRenderer.INSTANCE.parseLines(lines);
        // Lines after diff --git should be skipped
        assertTrue(parsed.getStatLines().isEmpty(),
                "stat lines after diff header should be skipped");
        assertEquals("", parsed.getSummaryLine(),
                "summary line after diff header should be skipped");
    }
}
