package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.Regex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests regex patterns in {@link GitOperationRenderer}.
 * Does not construct any Swing components.
 */
class GitOperationRendererTest {

    private final Regex successLine = GitOperationRenderer.INSTANCE.getSUCCESS_LINE();
    private final Regex errorLine = GitOperationRenderer.INSTANCE.getERROR_LINE();
    private final Regex conflictLine = GitOperationRenderer.INSTANCE.getCONFLICT_LINE();
    private final Regex alreadyUpToDate = GitOperationRenderer.INSTANCE.getALREADY_UP_TO_DATE();
    private final Regex nothingTo = GitOperationRenderer.INSTANCE.getNOTHING_TO();
    private final Regex fetchComplete = GitOperationRenderer.INSTANCE.getFETCH_COMPLETE();

    // ── SUCCESS_LINE ────────────────────────────────────────

    @Test
    void successLine_matchesPushedToOrigin() {
        assertTrue(successLine.containsMatchIn("✓ Pushed to origin/main"));
        assertEquals("Pushed to origin/main",
                successLine.find("✓ Pushed to origin/main", 0).getGroupValues().get(1));
    }

    @Test
    void successLine_doesNotMatchPlainText() {
        assertFalse(successLine.containsMatchIn("Just a normal line"));
    }

    @Test
    void successLine_doesNotMatchError() {
        assertFalse(successLine.containsMatchIn("Error: something failed"));
    }

    // ── ERROR_LINE ──────────────────────────────────────────

    @Test
    void errorLine_matchesFatalError() {
        assertTrue(errorLine.containsMatchIn("Error: fatal: remote origin not found"));
        assertEquals("fatal: remote origin not found",
                errorLine.find("Error: fatal: remote origin not found", 0).getGroupValues().get(1));
    }

    @Test
    void errorLine_doesNotMatchSuccessLine() {
        assertFalse(errorLine.containsMatchIn("✓ Pushed to origin/main"));
    }

    @Test
    void errorLine_doesNotMatchPlainText() {
        assertFalse(errorLine.containsMatchIn("Success: everything is fine"));
    }

    // ── CONFLICT_LINE ───────────────────────────────────────

    @Test
    void conflictLine_matchesMergeConflict() {
        assertTrue(conflictLine.containsMatchIn("CONFLICT (content): merge conflict in file.txt"));
    }

    @Test
    void conflictLine_matchesAutoMergingConflict() {
        assertTrue(conflictLine.containsMatchIn("Auto-merging file with conflict"));
    }

    @Test
    void conflictLine_doesNotMatchCleanMerge() {
        assertFalse(conflictLine.containsMatchIn("All merges successful"));
    }

    @Test
    void conflictLine_doesNotMatchUnrelatedText() {
        assertFalse(conflictLine.containsMatchIn("No issues found"));
    }

    // ── ALREADY_UP_TO_DATE ──────────────────────────────────

    @Test
    void alreadyUpToDate_matchesHyphenated() {
        assertTrue(alreadyUpToDate.containsMatchIn("Already up-to-date"));
    }

    @Test
    void alreadyUpToDate_matchesWithSpaces() {
        assertTrue(alreadyUpToDate.containsMatchIn("Already up to date."));
    }

    @Test
    void alreadyUpToDate_doesNotMatchUnrelated() {
        assertFalse(alreadyUpToDate.containsMatchIn("Changes pulled successfully"));
    }

    // ── NOTHING_TO ──────────────────────────────────────────

    @Test
    void nothingTo_matchesNothingToCommit() {
        assertTrue(nothingTo.containsMatchIn("nothing to commit, working tree clean"));
    }

    @Test
    void nothingTo_doesNotMatchStagedChanges() {
        assertFalse(nothingTo.containsMatchIn("Changes staged for commit"));
    }

    // ── FETCH_COMPLETE ──────────────────────────────────────

    @Test
    void fetchComplete_matchesFetchCompleted() {
        assertTrue(fetchComplete.containsMatchIn("Fetch completed successfully"));
    }

    @Test
    void fetchComplete_doesNotMatchFetchFailed() {
        assertFalse(fetchComplete.containsMatchIn("Fetch failed"));
    }

    @Test
    void fetchComplete_doesNotMatchLeadingWhitespace() {
        assertFalse(fetchComplete.containsMatchIn("  Fetch completed successfully"));
    }
}
