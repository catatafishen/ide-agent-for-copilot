package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ToolRenderers#parseDiffStats(String)}.
 * Does not construct any Swing components.
 */
class ToolRenderersParsingTest {

    @Test
    void parseDiffStats_fullStats() {
        ToolRenderers.DiffStats stats = ToolRenderers.INSTANCE.parseDiffStats(
                "3 files changed, 10 insertions(+), 5 deletions(-)");
        assertEquals("3 files changed", stats.getFiles());
        assertEquals("10", stats.getInsertions());
        assertEquals("5", stats.getDeletions());
    }

    @Test
    void parseDiffStats_insertionsOnly() {
        ToolRenderers.DiffStats stats = ToolRenderers.INSTANCE.parseDiffStats(
                "1 file changed, 1 insertion(+)");
        assertEquals("1 file changed", stats.getFiles());
        assertEquals("1", stats.getInsertions());
        assertEquals("", stats.getDeletions());
    }

    @Test
    void parseDiffStats_deletionsOnly() {
        ToolRenderers.DiffStats stats = ToolRenderers.INSTANCE.parseDiffStats(
                "2 files changed, 7 deletions(-)");
        assertEquals("2 files changed", stats.getFiles());
        assertEquals("", stats.getInsertions());
        assertEquals("7", stats.getDeletions());
    }

    @Test
    void parseDiffStats_fileChangedOnly() {
        ToolRenderers.DiffStats stats = ToolRenderers.INSTANCE.parseDiffStats(
                "1 file changed");
        assertEquals("1 file changed", stats.getFiles());
        assertEquals("", stats.getInsertions());
        assertEquals("", stats.getDeletions());
    }

    @Test
    void parseDiffStats_noStatsLine() {
        ToolRenderers.DiffStats stats = ToolRenderers.INSTANCE.parseDiffStats(
                "no stats here");
        // Falls back to full line as the files field
        assertEquals("no stats here", stats.getFiles());
        assertEquals("", stats.getInsertions());
        assertEquals("", stats.getDeletions());
    }
}
