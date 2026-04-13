package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import kotlin.text.Regex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests all 4 regex patterns in {@link TypeHierarchyRenderer}.
 * Does not construct any Swing components.
 */
class TypeHierarchyRendererTest {

    private final Regex header = TypeHierarchyRenderer.INSTANCE.getHEADER();
    private final Regex sectionHeader = TypeHierarchyRenderer.INSTANCE.getSECTION_HEADER();
    private final Regex typeEntry = TypeHierarchyRenderer.INSTANCE.getTYPE_ENTRY();
    private final Regex noneFound = TypeHierarchyRenderer.INSTANCE.getNONE_FOUND();

    // ── HEADER ──────────────────────────────────────────────

    @Test
    void header_matchesClassHierarchy() {
        MatchResult m = header.find("Type hierarchy for: com.example.MyClass (class)", 0);
        assertNotNull(m);
        assertEquals("com.example.MyClass", m.getGroupValues().get(1));
        assertEquals("class", m.getGroupValues().get(2));
    }

    @Test
    void header_matchesInterfaceHierarchy() {
        MatchResult m = header.find("Type hierarchy for: com.example.MyApi (interface)", 0);
        assertNotNull(m);
        assertEquals("com.example.MyApi", m.getGroupValues().get(1));
        assertEquals("interface", m.getGroupValues().get(2));
    }

    @Test
    void header_doesNotMatchMissingParentheses() {
        assertNull(header.find("Type hierarchy for: com.example.MyClass", 0));
    }

    // ── SECTION_HEADER ──────────────────────────────────────

    @Test
    void sectionHeader_matchesSupertypes() {
        MatchResult m = sectionHeader.find("Supertypes:", 0);
        assertNotNull(m);
        assertEquals("Supertypes", m.getGroupValues().get(1));
    }

    @Test
    void sectionHeader_matchesSubtypes() {
        MatchResult m = sectionHeader.find("Subtypes:", 0);
        assertNotNull(m);
        assertEquals("Subtypes", m.getGroupValues().get(1));
    }

    @Test
    void sectionHeader_matchesImplementations() {
        MatchResult m = sectionHeader.find("Implementations:", 0);
        assertNotNull(m);
        assertEquals("Implementations", m.getGroupValues().get(1));
    }

    @Test
    void sectionHeader_doesNotMatchArbitrary() {
        assertNull(sectionHeader.find("Members:", 0));
    }

    // ── TYPE_ENTRY ──────────────────────────────────────────

    @Test
    void typeEntry_matchesClassWithLocation() {
        MatchResult m = typeEntry.find("  class com.example.Base [src/Base.java]", 0);
        assertNotNull(m);
        assertEquals("class", m.getGroupValues().get(1));
        assertEquals("com.example.Base", m.getGroupValues().get(2));
        assertEquals("src/Base.java", m.getGroupValues().get(3));
    }

    @Test
    void typeEntry_matchesInterfaceWithoutLocation() {
        MatchResult m = typeEntry.find("  interface com.example.Api", 0);
        assertNotNull(m);
        assertEquals("interface", m.getGroupValues().get(1));
        assertEquals("com.example.Api", m.getGroupValues().get(2));
        // Group 3 is empty when no location bracket is present
        assertEquals("", m.getGroupValues().get(3));
    }

    @Test
    void typeEntry_doesNotMatchPlainText() {
        assertNull(typeEntry.find("com.example.MyClass", 0));
    }

    // ── NONE_FOUND ──────────────────────────────────────────

    @Test
    void noneFound_matchesSimple() {
        assertTrue(noneFound.containsMatchIn("  (none found)"));
    }

    @Test
    void noneFound_matchesExtended() {
        assertTrue(noneFound.containsMatchIn("  (none found for this query)"));
    }

    @Test
    void noneFound_doesNotMatchWithoutLeadingWhitespace() {
        assertFalse(noneFound.containsMatchIn("(none found)"));
    }
}
