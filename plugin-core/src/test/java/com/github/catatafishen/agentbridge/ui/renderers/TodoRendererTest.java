package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import kotlin.text.Regex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests regex patterns in {@link TodoRenderer}.
 * Does not construct any Swing components.
 */
class TodoRendererTest {

    private final Regex checkboxLine = TodoRenderer.INSTANCE.getCHECKBOX_LINE();
    private final Regex headerLine = TodoRenderer.INSTANCE.getHEADER_LINE();

    // ── CHECKBOX_LINE ───────────────────────────────────────

    @Test
    void checkboxLine_matchesCheckedLowercase() {
        MatchResult m = checkboxLine.find("- [x] Buy groceries", 0);
        assertNotNull(m);
        assertEquals("x", m.getGroupValues().get(1));
        assertEquals("Buy groceries", m.getGroupValues().get(2));
    }

    @Test
    void checkboxLine_matchesCheckedUppercase() {
        MatchResult m = checkboxLine.find("- [X] Done item", 0);
        assertNotNull(m);
        assertEquals("X", m.getGroupValues().get(1));
        assertEquals("Done item", m.getGroupValues().get(2));
    }

    @Test
    void checkboxLine_matchesUnchecked() {
        MatchResult m = checkboxLine.find("- [ ] Pending item", 0);
        assertNotNull(m);
        assertEquals(" ", m.getGroupValues().get(1));
        assertEquals("Pending item", m.getGroupValues().get(2));
    }

    @Test
    void checkboxLine_doesNotMatchNoSpaceInBrackets() {
        assertNull(checkboxLine.find("- [] No space", 0));
    }

    @Test
    void checkboxLine_doesNotMatchNoDashPrefix() {
        assertNull(checkboxLine.find("[x] no dash prefix", 0));
    }

    // ── HEADER_LINE ─────────────────────────────────────────

    @Test
    void headerLine_matchesOneHash() {
        MatchResult m = headerLine.find("# Top heading", 0);
        assertNotNull(m);
        assertEquals("Top heading", m.getGroupValues().get(1));
    }

    @Test
    void headerLine_matchesTwoHashes() {
        MatchResult m = headerLine.find("## Section", 0);
        assertNotNull(m);
        assertEquals("Section", m.getGroupValues().get(1));
    }

    @Test
    void headerLine_matchesThreeHashes() {
        MatchResult m = headerLine.find("### Sub-section", 0);
        assertNotNull(m);
        assertEquals("Sub-section", m.getGroupValues().get(1));
    }

    @Test
    void headerLine_matchesFourHashes() {
        MatchResult m = headerLine.find("#### Deep", 0);
        assertNotNull(m);
        assertEquals("Deep", m.getGroupValues().get(1));
    }

    @Test
    void headerLine_doesNotMatchFiveHashes() {
        assertNull(headerLine.find("##### 5 levels", 0));
    }

    @Test
    void headerLine_doesNotMatchNoHash() {
        assertNull(headerLine.find("No hash heading", 0));
    }
}
