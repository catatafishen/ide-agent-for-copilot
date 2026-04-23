package com.github.catatafishen.agentbridge.acp.client.intercept;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure classification logic in {@link ShellRedirectPlanner}. These tests
 * never touch a {@code Project} or invoke MCP — they only verify that argv is mapped
 * to the correct tool name + arguments.
 */
class ShellRedirectPlannerTest {

    // ─── cat ──────────────────────────────────────────────────────────────

    @Test
    void catSimpleFile() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("cat", "README.md"));
        assertNotNull(plan);
        assertEquals("read_file", plan.toolName());
        assertEquals("README.md", plan.args().get("path").getAsString());
    }

    @Test
    void catWithFlagFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("cat", "-n", "file.txt")));
    }

    @Test
    void catMultipleFilesFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("cat", "a.txt", "b.txt")));
    }

    @Test
    void catWithAbsoluteBinaryPathStillWorks() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("/usr/bin/cat", "x.md"));
        assertNotNull(plan);
        assertEquals("read_file", plan.toolName());
    }

    // ─── head ─────────────────────────────────────────────────────────────

    @Test
    void headDefaults() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("head", "build.gradle.kts"));
        assertNotNull(plan);
        assertEquals("read_file", plan.toolName());
        assertEquals(1, plan.args().get("start_line").getAsInt());
        assertEquals(10, plan.args().get("end_line").getAsInt());
    }

    @Test
    void headDashNSpaceCount() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("head", "-n", "25", "x.txt"));
        assertNotNull(plan);
        assertEquals(25, plan.args().get("end_line").getAsInt());
    }

    @Test
    void headDashNAttachedCount() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("head", "-n50", "x.txt"));
        assertNotNull(plan);
        assertEquals(50, plan.args().get("end_line").getAsInt());
    }

    @Test
    void headLongFlagWithEquals() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("head", "--lines=15", "x.txt"));
        assertNotNull(plan);
        assertEquals(15, plan.args().get("end_line").getAsInt());
    }

    @Test
    void headWithoutFileFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("head", "-n", "5")));
    }

    @Test
    void headWithUnknownFlagFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("head", "-c", "100", "x.txt")));
    }

    @Test
    void headWithNegativeCountFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("head", "-n", "-5", "x.txt")));
    }

    // ─── grep / egrep / fgrep ────────────────────────────────────────────

    @Test
    void plainGrepFallsThrough() {
        // BRE semantics differ from Java regex — refuse so the user sees real grep behaviour.
        assertNull(ShellRedirectPlanner.plan(List.of("grep", "foo")));
    }

    @Test
    void grepFLiteral() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("grep", "-F", "needle"));
        assertNotNull(plan);
        assertEquals("search_text", plan.toolName());
        assertEquals("needle", plan.args().get("query").getAsString());
        assertEquals(false, plan.args().get("regex").getAsBoolean());
        assertEquals(true, plan.args().get("case_sensitive").getAsBoolean());
    }

    @Test
    void grepEExtended() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("grep", "-E", "fo+"));
        assertNotNull(plan);
        assertEquals(true, plan.args().get("regex").getAsBoolean());
    }

    @Test
    void grepCombinedFlagsRin() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("grep", "-rinE", "todo"));
        assertNotNull(plan);
        assertEquals(false, plan.args().get("case_sensitive").getAsBoolean());
        assertEquals(true, plan.args().get("regex").getAsBoolean());
    }

    @Test
    void grepWithGlobSecondArg() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("grep", "-F", "foo", "*.kt"));
        assertNotNull(plan);
        assertEquals("*.kt", plan.args().get("file_pattern").getAsString());
    }

    @Test
    void grepWithDirectorySecondArgFallsThrough() {
        // Directory paths don't translate to search_text's filename glob.
        assertNull(ShellRedirectPlanner.plan(List.of("grep", "-F", "foo", "src")));
    }

    @Test
    void grepWithThreePositionalsFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("grep", "-F", "foo", "*.kt", "*.java")));
    }

    @Test
    void grepUnknownFlagFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("grep", "-Fz", "foo")));
    }

    @Test
    void grepLeadingDashPatternFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("grep", "-F", "-something")));
    }

    @Test
    void grepDoubleDashAllowsLeadingDashPattern() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("grep", "-F", "--", "-something"));
        assertNotNull(plan);
        assertEquals("-something", plan.args().get("query").getAsString());
    }

    @Test
    void egrepIsImplicitlyExtendedRegex() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("egrep", "fo+"));
        assertNotNull(plan);
        assertEquals(true, plan.args().get("regex").getAsBoolean());
    }

    @Test
    void fgrepIsImplicitlyLiteral() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("fgrep", "literal.string"));
        assertNotNull(plan);
        assertEquals(false, plan.args().get("regex").getAsBoolean());
    }

    @Test
    void grepNoMatchesReportsExitOne() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("grep", "-F", "x"));
        assertNotNull(plan);
        assertEquals(1, plan.exitCodeFor().applyAsInt("No matches found for 'x'"));
        assertEquals(0, plan.exitCodeFor().applyAsInt("3 matches:\nfile:1: x\n"));
    }

    @Test
    void grepStripsMatchesHeader() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("grep", "-F", "x"));
        assertNotNull(plan);
        String stripped = plan.postProcess().apply("3 matches:\nfile.kt:1: x found\nfile.kt:2: x again\n");
        assertTrue(stripped.startsWith("file.kt:1: x found"), "Header should be stripped, got: " + stripped);
    }

    @Test
    void grepKeepsNoMatchesOutputAsIs() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("grep", "-F", "x"));
        assertNotNull(plan);
        String out = plan.postProcess().apply("No matches found for 'x'");
        assertEquals("No matches found for 'x'", out);
    }

    // ─── rg ───────────────────────────────────────────────────────────────

    @Test
    void rgDefaultsToRegex() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("rg", "fo+"));
        assertNotNull(plan);
        assertEquals(true, plan.args().get("regex").getAsBoolean());
    }

    @Test
    void rgFFlagDisablesRegex() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("rg", "-F", "literal.dot"));
        assertNotNull(plan);
        assertEquals(false, plan.args().get("regex").getAsBoolean());
    }

    @Test
    void rgGlobShortFormSpaceSeparated() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("rg", "-g", "*.kt", "TODO"));
        assertNotNull(plan);
        assertEquals("*.kt", plan.args().get("file_pattern").getAsString());
        assertEquals("TODO", plan.args().get("query").getAsString());
    }

    @Test
    void rgGlobLongFormWithEquals() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("rg", "--glob=*.java", "foo"));
        assertNotNull(plan);
        assertEquals("*.java", plan.args().get("file_pattern").getAsString());
    }

    @Test
    void rgWithPathPositionalFallsThrough() {
        // search_text takes no directory — refuse rather than ignore the path.
        assertNull(ShellRedirectPlanner.plan(List.of("rg", "TODO", "src")));
    }

    @Test
    void rgUnknownFlagFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("rg", "--multiline", "foo")));
    }

    // ─── git ──────────────────────────────────────────────────────────────

    @Test
    void gitStatusBare() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "status"));
        assertNotNull(plan);
        assertEquals("git_status", plan.toolName());
    }

    @Test
    void gitStatusWithFlagFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "status", "--short")));
    }

    @Test
    void gitDiffBare() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "diff"));
        assertNotNull(plan);
        assertEquals("git_diff", plan.toolName());
    }

    @Test
    void gitDiffStaged() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "diff", "--staged"));
        assertNotNull(plan);
        assertEquals(true, plan.args().get("staged").getAsBoolean());
    }

    @Test
    void gitDiffStat() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "diff", "--stat"));
        assertNotNull(plan);
        assertEquals(true, plan.args().get("stat_only").getAsBoolean());
    }

    @Test
    void gitDiffWithCommitRefFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "diff", "HEAD~1")));
    }

    @Test
    void gitLogBare() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "log"));
        assertNotNull(plan);
        assertEquals("git_log", plan.toolName());
    }

    @Test
    void gitLogOneline() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "log", "--oneline"));
        assertNotNull(plan);
        assertEquals("oneline", plan.args().get("format").getAsString());
    }

    @Test
    void gitLogMaxCount() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "log", "-n", "5"));
        assertNotNull(plan);
        assertEquals(5, plan.args().get("max_count").getAsInt());
    }

    @Test
    void gitLogWithBranchRefFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "log", "main")));
    }

    @Test
    void gitLogWithPathFilter() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "log", "--", "README.md"));
        assertNotNull(plan);
        assertEquals("git_log", plan.toolName());
        assertEquals("README.md", plan.args().get("path").getAsString());
    }

    @Test
    void gitLogOnelineWithPath() {
        RedirectPlan plan = ShellRedirectPlanner.plan(
            List.of("git", "log", "--oneline", "-n", "5", "--", "src/x.java"));
        assertNotNull(plan);
        assertEquals("oneline", plan.args().get("format").getAsString());
        assertEquals(5, plan.args().get("max_count").getAsInt());
        assertEquals("src/x.java", plan.args().get("path").getAsString());
    }

    @Test
    void gitLogMultiplePathsFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "log", "--", "a.txt", "b.txt")));
    }

    // ─── git show ─────────────────────────────────────────────────────────

    @Test
    void gitShowBare() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "show"));
        assertNotNull(plan);
        assertEquals("git_show", plan.toolName());
        assertNull(plan.args().get("ref"));
    }

    @Test
    void gitShowWithRef() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "show", "abc123"));
        assertNotNull(plan);
        assertEquals("abc123", plan.args().get("ref").getAsString());
    }

    @Test
    void gitShowStat() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "show", "--stat", "HEAD~1"));
        assertNotNull(plan);
        assertEquals(true, plan.args().get("stat_only").getAsBoolean());
        assertEquals("HEAD~1", plan.args().get("ref").getAsString());
    }

    @Test
    void gitShowUnknownFlagFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "show", "--name-only")));
    }

    @Test
    void gitShowMultipleRefsFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "show", "abc", "def")));
    }

    // ─── git blame ────────────────────────────────────────────────────────

    @Test
    void gitBlameSimple() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "blame", "src/Foo.java"));
        assertNotNull(plan);
        assertEquals("git_blame", plan.toolName());
        assertEquals("src/Foo.java", plan.args().get("path").getAsString());
        assertNull(plan.args().get("line_start"));
    }

    @Test
    void gitBlameWithLineRange() {
        RedirectPlan plan = ShellRedirectPlanner.plan(
            List.of("git", "blame", "-L", "10,50", "src/Foo.java"));
        assertNotNull(plan);
        assertEquals(10, plan.args().get("line_start").getAsInt());
        assertEquals(50, plan.args().get("line_end").getAsInt());
        assertEquals("src/Foo.java", plan.args().get("path").getAsString());
    }

    @Test
    void gitBlameWithLineRangeAttached() {
        RedirectPlan plan = ShellRedirectPlanner.plan(
            List.of("git", "blame", "-L1,5", "x"));
        assertNotNull(plan);
        assertEquals(1, plan.args().get("line_start").getAsInt());
        assertEquals(5, plan.args().get("line_end").getAsInt());
    }

    @Test
    void gitBlameRegexRangeFallsThrough() {
        // /regex/ form not supported.
        assertNull(ShellRedirectPlanner.plan(List.of("git", "blame", "-L", "/foo/", "x")));
    }

    @Test
    void gitBlameInvertedRangeFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "blame", "-L", "50,10", "x")));
    }

    @Test
    void gitBlameNoFileFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "blame")));
    }

    @Test
    void gitBlameUnknownFlagFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "blame", "-w", "x")));
    }

    @Test
    void gitBlameWithDoubleDash() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "blame", "--", "-tricky-name"));
        assertNotNull(plan);
        assertEquals("-tricky-name", plan.args().get("path").getAsString());
    }

    @Test
    void gitBranchBare() {
        RedirectPlan plan = ShellRedirectPlanner.plan(List.of("git", "branch"));
        assertNotNull(plan);
        assertEquals("git_branch", plan.toolName());
    }

    @Test
    void gitCommitFallsThrough() {
        // Mutating ops must remain visible — never intercept.
        assertNull(ShellRedirectPlanner.plan(List.of("git", "commit", "-m", "wip")));
    }

    @Test
    void gitPushFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("git", "push")));
    }

    // ─── unknown commands ─────────────────────────────────────────────────

    @Test
    void unknownBinaryFallsThrough() {
        assertNull(ShellRedirectPlanner.plan(List.of("ls")));
        assertNull(ShellRedirectPlanner.plan(List.of("find", ".", "-name", "*.kt")));
        assertNull(ShellRedirectPlanner.plan(List.of("tail", "x.log")));
        assertNull(ShellRedirectPlanner.plan(List.of("curl", "https://example.com")));
    }

    @Test
    void emptyArgvReturnsNull() {
        assertNull(ShellRedirectPlanner.plan(List.of()));
    }

    // ─── header-stripping helper ──────────────────────────────────────────

    @Test
    void stripSearchTextHeaderRemovesSingleMatchHeader() {
        assertEquals("file:1: hit\n",
            ShellRedirectPlanner.stripSearchTextHeader("1 match:\nfile:1: hit\n"));
    }

    @Test
    void stripSearchTextHeaderRemovesPluralHeader() {
        assertEquals("a:1: x\nb:2: x\n",
            ShellRedirectPlanner.stripSearchTextHeader("2 matches:\na:1: x\nb:2: x\n"));
    }

    @Test
    void stripSearchTextHeaderLeavesNonHeaderUnchanged() {
        assertEquals("No matches found for 'x'",
            ShellRedirectPlanner.stripSearchTextHeader("No matches found for 'x'"));
        assertEquals("file:1: line",
            ShellRedirectPlanner.stripSearchTextHeader("file:1: line"));
    }

    // ─── RedirectPlan defaults ────────────────────────────────────────────

    @Test
    void redirectPlanOfDefaults() {
        JsonObject args = new JsonObject();
        RedirectPlan plan = RedirectPlan.of("read_file", args);
        assertEquals("read_file", plan.toolName());
        assertEquals("hello", plan.postProcess().apply("hello"));
        assertEquals(0, plan.exitCodeFor().applyAsInt("anything"));
    }
}
