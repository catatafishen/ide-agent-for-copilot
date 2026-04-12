package com.github.catatafishen.agentbridge.permissions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbuseDetectorTest {

    private final AbuseDetector detector = new AbuseDetector();

    @Test
    void nonShellToolReturnsNull() {
        assertNull(detector.check("read_file", "{\"command\": \"git status\"}"));
    }

    @Test
    void nullArgumentsReturnsNull() {
        assertNull(detector.check("run_command", null));
    }

    @Test
    void blankArgumentsReturnsNull() {
        assertNull(detector.check("run_command", "   "));
    }

    @Test
    void detectsGitCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"git status\"}");
        assertNotNull(result);
        assertEquals("git", result.category());
    }

    @Test
    void detectsGitWithFullPath() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"/usr/bin/git log\"}");
        assertNotNull(result);
        assertEquals("git", result.category());
    }

    @Test
    void detectsCatCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"cat file.txt\"}");
        assertNotNull(result);
        assertEquals("cat", result.category());
    }

    @Test
    void detectsHeadTailLessMore() {
        for (String cmd : new String[]{"head -20 file.txt", "tail -f log.txt", "less README.md", "more file.txt"}) {
            AbuseResult result = detector.check("run_command", "{\"command\": \"" + cmd + "\"}");
            assertNotNull(result, "Expected abuse for: " + cmd);
            assertEquals("cat", result.category(), "Wrong category for: " + cmd);
        }
    }

    @Test
    void detectsGrepCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"grep -r pattern .\"}");
        assertNotNull(result);
        assertEquals("grep", result.category());
    }

    @Test
    void detectsRipgrepAndAg() {
        for (String cmd : new String[]{"rg pattern file.txt", "ag search_term .", "ack pattern src/"}) {
            AbuseResult result = detector.check("run_command", "{\"command\": \"" + cmd + "\"}");
            assertNotNull(result, "Expected abuse for: " + cmd);
            assertEquals("grep", result.category(), "Wrong category for: " + cmd);
        }
    }

    @Test
    void detectsSedCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"sed -i s/old/new/ file\"}");
        assertNotNull(result);
        assertEquals("sed", result.category());
    }

    @Test
    void detectsAwkCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"awk '{print $1}' file\"}");
        assertNotNull(result);
        assertEquals("sed", result.category());
    }

    @Test
    void detectsFindCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"find . -name '*.java'\"}");
        assertNotNull(result);
        assertEquals("find", result.category());
    }

    @Test
    void detectsFdAndLocate() {
        for (String cmd : new String[]{"fd pattern .", "locate myfile"}) {
            AbuseResult result = detector.check("run_command", "{\"command\": \"" + cmd + "\"}");
            assertNotNull(result, "Expected abuse for: " + cmd);
            assertEquals("find", result.category(), "Wrong category for: " + cmd);
        }
    }

    @Test
    void detectsLsCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"ls -la\"}");
        assertNotNull(result);
        assertEquals("ls", result.category());
    }

    @Test
    void detectsDirCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"dir /w\"}");
        assertNotNull(result);
        assertEquals("ls", result.category());
    }

    @Test
    void allowsNonAbusingCommand() {
        assertNull(detector.check("run_command", "{\"command\": \"npm run build\"}"));
    }

    @Test
    void rawStringFallback() {
        AbuseResult result = detector.check("run_command", "git status");
        assertNotNull(result);
        assertEquals("git", result.category());
    }

    @Test
    void runInTerminalAlsoChecked() {
        AbuseResult result = detector.check("run_in_terminal", "{\"command\": \"git status\"}");
        assertNotNull(result);
        assertEquals("git", result.category());
    }

    @Test
    void escapedQuotesInCommand() {
        // JSON: {"command": "echo \"hello\""}  — escaped quotes inside the value
        // extractCommand should parse without error; "echo" is not an abuse pattern
        AbuseResult result = detector.check("run_command", "{\"command\": \"echo \\\"hello\\\"\"}");
        assertNull(result);
    }

    @Test
    void reasonContainsSuggestion() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"git status\"}");
        assertNotNull(result);
        assertTrue(result.reason().contains("git_status"),
            "Reason should mention the MCP tool alternative, got: " + result.reason());
    }

    @Test
    void leadingWhitespaceInCommand() {
        AbuseResult result = detector.check("run_command", "{\"command\": \"  git status\"}");
        assertNotNull(result);
        assertEquals("git", result.category());
    }
}
