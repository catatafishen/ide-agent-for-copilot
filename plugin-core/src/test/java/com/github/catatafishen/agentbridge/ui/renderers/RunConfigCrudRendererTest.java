package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import kotlin.text.Regex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests all 6 regex patterns in {@link RunConfigCrudRenderer}.
 * Does not construct any Swing components.
 */
class RunConfigCrudRendererTest {

    private final Regex created = RunConfigCrudRenderer.INSTANCE.getCREATED();
    private final Regex updated = RunConfigCrudRenderer.INSTANCE.getUPDATED();
    private final Regex deleted = RunConfigCrudRenderer.INSTANCE.getDELETED();
    private final Regex started = RunConfigCrudRenderer.INSTANCE.getSTARTED();
    private final Regex runTab = RunConfigCrudRenderer.INSTANCE.getRUN_TAB();
    private final Regex error = RunConfigCrudRenderer.INSTANCE.getERROR();

    // ── CREATED ─────────────────────────────────────────────

    @Test
    void created_matchesWithType() {
        MatchResult m = created.find("Created run configuration 'My Test' [junit]", 0);
        assertNotNull(m);
        assertEquals("My Test", m.getGroupValues().get(1));
        assertEquals("junit", m.getGroupValues().get(2));
    }

    @Test
    void created_matchesWithoutType() {
        MatchResult m = created.find("Created run configuration 'Simple'", 0);
        assertNotNull(m);
        assertEquals("Simple", m.getGroupValues().get(1));
        // Group 2 is empty when no type bracket is present
        assertEquals("", m.getGroupValues().get(2));
    }

    @Test
    void created_doesNotMatchUnrelated() {
        assertNull(created.find("Deleted run configuration 'X'", 0));
    }

    // ── UPDATED ─────────────────────────────────────────────

    @Test
    void updated_matchesAndCapturesName() {
        MatchResult m = updated.find("Updated run configuration 'My Test'", 0);
        assertNotNull(m);
        assertEquals("My Test", m.getGroupValues().get(1));
    }

    @Test
    void updated_doesNotMatchCreated() {
        assertNull(updated.find("Created run configuration 'My Test'", 0));
    }

    // ── DELETED ─────────────────────────────────────────────

    @Test
    void deleted_matchesAndCapturesName() {
        MatchResult m = deleted.find("Deleted run configuration 'My Test'", 0);
        assertNotNull(m);
        assertEquals("My Test", m.getGroupValues().get(1));
    }

    @Test
    void deleted_doesNotMatchUpdated() {
        assertNull(deleted.find("Updated run configuration 'My Test'", 0));
    }

    // ── STARTED ─────────────────────────────────────────────

    @Test
    void started_matchesStarted() {
        MatchResult m = started.find("Started 'My Config'", 0);
        assertNotNull(m);
        assertEquals("My Config", m.getGroupValues().get(1));
    }

    @Test
    void started_matchesExecuted() {
        MatchResult m = started.find("Executed 'Build'", 0);
        assertNotNull(m);
        assertEquals("Build", m.getGroupValues().get(1));
    }

    @Test
    void started_matchesRunning() {
        MatchResult m = started.find("Running 'Server'", 0);
        assertNotNull(m);
        assertEquals("Server", m.getGroupValues().get(1));
    }

    @Test
    void started_doesNotMatchPlainText() {
        assertNull(started.find("My Config is running", 0));
    }

    // ── RUN_TAB ─────────────────────────────────────────────

    @Test
    void runTab_matchesAndCapturesTabName() {
        MatchResult m = runTab.find("Run tab: My Build Output", 0);
        assertNotNull(m);
        assertEquals("My Build Output", m.getGroupValues().get(1));
    }

    @Test
    void runTab_doesNotMatchWithoutPrefix() {
        assertNull(runTab.find("My Build Output", 0));
    }

    // ── ERROR ───────────────────────────────────────────────

    @Test
    void error_matchesAndCapturesMessage() {
        MatchResult m = error.find("Error: Configuration 'X' not found", 0);
        assertNotNull(m);
        assertEquals("Configuration 'X' not found", m.getGroupValues().get(1));
    }

    @Test
    void error_doesNotMatchSuccessLine() {
        assertNull(error.find("✓ Pushed to origin/main", 0));
    }
}
