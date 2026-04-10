package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for pure utility methods in {@link ToolUtils} that do not require
 * an IntelliJ project context.
 */
class ToolUtilsTest {

    @Nested
    @DisplayName("relativize")
    class Relativize {
        @Test
        void stripsBasePathPrefix() {
            assertEquals("src/Main.java",
                ToolUtils.relativize("/home/user/project", "/home/user/project/src/Main.java"));
        }

        @Test
        void returnsOriginalWhenNoMatch() {
            assertEquals("/other/path/file.txt",
                ToolUtils.relativize("/home/user/project", "/other/path/file.txt"));
        }

        @Test
        void handlesBackslashes() {
            assertEquals("src/Main.java",
                ToolUtils.relativize("C:\\Users\\project", "C:\\Users\\project\\src\\Main.java"));
        }

        @Test
        void doesNotStripPartialMatch() {
            // "/project-extra" should NOT match base "/project"
            assertEquals("/project-extra/file.txt",
                ToolUtils.relativize("/project", "/project-extra/file.txt"));
        }
    }

    @Nested
    @DisplayName("doesNotMatchGlob")
    class DoesNotMatchGlob {
        @Test
        void matchesStar() {
            assertFalse(ToolUtils.doesNotMatchGlob("MyTest", "*Test"));
        }

        @Test
        void matchesExact() {
            assertFalse(ToolUtils.doesNotMatchGlob("MyTest", "MyTest"));
        }

        @Test
        void rejectsNonMatch() {
            assertTrue(ToolUtils.doesNotMatchGlob("MyService", "*Test"));
        }

        @Test
        void matchesQuestionMark() {
            assertFalse(ToolUtils.doesNotMatchGlob("Test1", "Test?"));
            assertTrue(ToolUtils.doesNotMatchGlob("Test12", "Test?"));
        }

        @Test
        void matchesDotLiteral() {
            assertFalse(ToolUtils.doesNotMatchGlob("file.java", "*.java"));
        }
    }

    @Nested
    @DisplayName("doesNotMatchGlob — path patterns")
    class PathGlob {
        @Test
        void doubleStarMatchesAcrossSegments() {
            // **/*.java should match any .java file regardless of depth
            assertFalse(ToolUtils.doesNotMatchGlob("src/main/Foo.java", "**/*.java"));
            assertFalse(ToolUtils.doesNotMatchGlob("src/main/java/com/example/Foo.java", "**/*.java"));
        }

        @Test
        void srcDoubleStarMatchesInsideSrc() {
            assertFalse(ToolUtils.doesNotMatchGlob("src/main/Foo.java", "src/**/*.java"));
            assertFalse(ToolUtils.doesNotMatchGlob("src/test/FooTest.java", "src/**/*.java"));
        }

        @Test
        void srcDoubleStarDoesNotMatchOutsideSrc() {
            assertTrue(ToolUtils.doesNotMatchGlob("test/FooTest.java", "src/**/*.java"));
        }

        @Test
        void simpleStarGlobMatchesFilenameOnly() {
            // *.java has no path separator → matches only the filename portion
            assertFalse(ToolUtils.doesNotMatchGlob("Foo.java", "*.java"));
            assertFalse(ToolUtils.doesNotMatchGlob("src/Foo.java", "*.java"));
        }

        @Test
        void doubleStarAtRootMatchesAtAnyDepth() {
            assertFalse(ToolUtils.doesNotMatchGlob("a/b/c/file.txt", "**/file.txt"));
            assertFalse(ToolUtils.doesNotMatchGlob("file.txt", "**/file.txt"));
        }
    }

    @Nested
    @DisplayName("fileType")
    class FileType {
        @ParameterizedTest
        @CsvSource({
            "Main.java, Java",
            "App.kt, Kotlin",
            "script.kts, Kotlin",
            "app.py, Python",
            "index.js, JavaScript",
            "App.jsx, JavaScript",
            "index.ts, TypeScript",
            "App.tsx, TypeScript",
            "main.go, Go",
            "pom.xml, XML",
            "data.json, JSON",
            "build.gradle, Gradle",
            "build.gradle.kts, Gradle",
            "config.yaml, YAML",
            "config.yml, YAML",
            "readme.md, Other",
        })
        void detectsType(String name, String expected) {
            assertEquals(expected, ToolUtils.fileType(name));
        }

        @Test
        void caseInsensitive() {
            assertEquals("Java", ToolUtils.fileType("MAIN.JAVA"));
            assertEquals("Python", ToolUtils.fileType("SCRIPT.PY"));
        }
    }

    @Nested
    @DisplayName("normalizeForMatch")
    class NormalizeForMatch {
        @Test
        void preservesAscii() {
            assertEquals("hello world", ToolUtils.normalizeForMatch("hello world"));
        }

        @Test
        void replacesNonAsciiWithQuestionMark() {
            // em-dash → ?
            assertEquals("a?b", ToolUtils.normalizeForMatch("a—b"));
        }

        @Test
        void normalizesCrLfToLf() {
            assertEquals("a\nb", ToolUtils.normalizeForMatch("a\r\nb"));
        }

        @Test
        void normalizesCrToLf() {
            assertEquals("a\nb", ToolUtils.normalizeForMatch("a\rb"));
        }

        @Test
        void handlesEmoji() {
            // Emoji (surrogate pair) → single ?
            assertEquals("hi ?", ToolUtils.normalizeForMatch("hi \uD83D\uDE00"));
        }

        @Test
        void emptyString() {
            assertEquals("", ToolUtils.normalizeForMatch(""));
        }
    }

    @Nested
    @DisplayName("findOriginalLength")
    class FindOriginalLength {
        @Test
        void asciiOnly() {
            assertEquals(5, ToolUtils.findOriginalLength("hello", 0, 5));
        }

        @Test
        void crlfCountsAsOne() {
            // "a\r\nb" → normalized "a\nb" (3 chars), original length for 3 normalized chars = 4
            assertEquals(4, ToolUtils.findOriginalLength("a\r\nb", 0, 3));
        }

        @Test
        void surrogatePairCountsAsOne() {
            // Emoji (2 chars) normalizes to 1 char
            String emoji = "A\uD83D\uDE00B";
            assertEquals(4, ToolUtils.findOriginalLength(emoji, 0, 3));
        }

        @Test
        void startFromMiddle() {
            assertEquals(3, ToolUtils.findOriginalLength("abcdef", 2, 3));
        }

        @Test
        void beyondEndClamps() {
            assertEquals(3, ToolUtils.findOriginalLength("abc", 0, 10));
        }
    }

    @Nested
    @DisplayName("truncateOutput")
    class TruncateOutput {
        @Test
        void shortOutputUnchanged() {
            assertEquals("hello", ToolUtils.truncateOutput("hello", 100, 0));
        }

        @Test
        void nullReturnsNull() {
            assertNull(ToolUtils.truncateOutput(null, 100, 0));
        }

        @Test
        void emptyReturnsEmpty() {
            assertEquals("", ToolUtils.truncateOutput("", 100, 0));
        }

        @Test
        void truncatesLongOutput() {
            String output = "a".repeat(200);
            String result = ToolUtils.truncateOutput(output, 50, 0);
            assertTrue(result.length() < 200, "Should be truncated");
            assertTrue(result.contains("offset=50"), "Should contain pagination hint");
        }

        @Test
        void paginatesFromOffset() {
            String output = "0123456789";
            String result = ToolUtils.truncateOutput(output, 5, 3);
            assertTrue(result.startsWith("34567"), "Should start from offset 3");
        }

        @Test
        void offsetBeyondEnd() {
            String result = ToolUtils.truncateOutput("short", 100, 1000);
            assertTrue(result.contains("offset beyond end"), "Should report out of bounds");
        }

        @Test
        void defaultOverload() {
            String output = "a".repeat(100);
            String result = ToolUtils.truncateOutput(output);
            assertEquals(output, result);
        }
    }

    @Nested
    class FormatFileSize {

        @Test
        void zeroBytes() {
            assertEquals("0 B", ToolUtils.formatFileSize(0));
        }

        @Test
        void smallByteCount() {
            assertEquals("512 B", ToolUtils.formatFileSize(512));
        }

        @Test
        void maxBytesBeforeKB() {
            assertEquals("1023 B", ToolUtils.formatFileSize(1023));
        }

        @Test
        void exactlyOneKB() {
            assertEquals("1.0 KB", ToolUtils.formatFileSize(1024));
        }

        @Test
        void kilobytes() {
            assertEquals("2.0 KB", ToolUtils.formatFileSize(2048));
        }

        @Test
        void exactlyOneMB() {
            assertEquals("1.0 MB", ToolUtils.formatFileSize(1024 * 1024));
        }

        @Test
        void megabytes() {
            assertEquals("2.0 MB", ToolUtils.formatFileSize(2 * 1024 * 1024));
        }
    }

    @Nested
    class FormatFileTimestamp {

        @Test
        void zeroReturnsUnknown() {
            assertEquals("unknown", ToolUtils.formatFileTimestamp(0));
        }

        @Test
        void nonZeroMatchesDatePattern() {
            // Any non-zero epoch should produce a yyyy-MM-dd string
            String result = ToolUtils.formatFileTimestamp(1_705_320_000_000L);
            assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}"),
                "Expected yyyy-MM-dd but got: " + result);
        }
    }

    @Nested
    class ParseDateParam {

        @Test
        void nullReturnsMinusOne() {
            assertEquals(-1L, ToolUtils.parseDateParam(null));
        }

        @Test
        void emptyStringReturnsMinusOne() {
            assertEquals(-1L, ToolUtils.parseDateParam(""));
        }

        @Test
        void blankStringReturnsMinusOne() {
            assertEquals(-1L, ToolUtils.parseDateParam("   "));
        }

        @Test
        void invalidDateReturnsMinusOne() {
            assertEquals(-1L, ToolUtils.parseDateParam("not-a-date"));
        }

        @Test
        void validDateReturnsUtcEpoch() {
            // 2024-01-15 00:00:00 UTC
            assertEquals(1_705_276_800_000L, ToolUtils.parseDateParam("2024-01-15"));
        }

        @Test
        void anotherValidDate() {
            // 2025-03-01 00:00:00 UTC
            assertEquals(1_740_787_200_000L, ToolUtils.parseDateParam("2025-03-01"));
        }
    }

    @Nested
    class GetCommandAbuseMessage {

        @Test
        void gitMessageStartsWithError() {
            assertTrue(ToolUtils.getCommandAbuseMessage("git").startsWith("Error:"));
        }

        @Test
        void catMessageStartsWithError() {
            assertTrue(ToolUtils.getCommandAbuseMessage("cat").startsWith("Error:"));
        }

        @Test
        void sedMessageStartsWithError() {
            assertTrue(ToolUtils.getCommandAbuseMessage("sed").startsWith("Error:"));
        }

        @Test
        void grepMessageStartsWithError() {
            assertTrue(ToolUtils.getCommandAbuseMessage("grep").startsWith("Error:"));
        }

        @Test
        void findMessageStartsWithError() {
            assertTrue(ToolUtils.getCommandAbuseMessage("find").startsWith("Error:"));
        }

        @Test
        void compileMessageStartsWithError() {
            assertTrue(ToolUtils.getCommandAbuseMessage("compile").startsWith("Error:"));
        }

        @Test
        void testMessageStartsWithError() {
            assertTrue(ToolUtils.getCommandAbuseMessage("test").startsWith("Error:"));
        }

        @Test
        void unknownTypeReturnsDefaultError() {
            assertTrue(ToolUtils.getCommandAbuseMessage("unknown").startsWith("Error:"));
        }

        @Test
        void gitMessageMentionsGitTools() {
            assertTrue(ToolUtils.getCommandAbuseMessage("git").contains("git_status"));
        }

        @Test
        void catMessageMentionsReadFile() {
            assertTrue(ToolUtils.getCommandAbuseMessage("cat").contains("read"));
        }

        @Test
        void sedMessageMentionsEditText() {
            assertTrue(ToolUtils.getCommandAbuseMessage("sed").contains("edit_text"));
        }

        @Test
        void grepMessageMentionsSearchText() {
            assertTrue(ToolUtils.getCommandAbuseMessage("grep").contains("search_text"));
        }
    }
}
