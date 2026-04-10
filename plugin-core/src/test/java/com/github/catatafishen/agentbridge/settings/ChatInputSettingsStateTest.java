package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChatInputSettings.State} round-trips and service-level helpers.
 * Uses the settings object directly — no IntelliJ application context needed.
 */
@DisplayName("ChatInputSettings state")
class ChatInputSettingsStateTest {

    private ChatInputSettings settings;

    @BeforeEach
    void setUp() {
        settings = new ChatInputSettings();
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    @DisplayName("showShortcutHints defaults to true")
    void defaultShowShortcutHints() {
        assertTrue(settings.isShowShortcutHints());
    }

    @Test
    @DisplayName("smartPasteEnabled defaults to true")
    void defaultSmartPasteEnabled() {
        assertTrue(settings.isSmartPasteEnabled());
    }

    @Test
    @DisplayName("softWrapsEnabled defaults to true")
    void defaultSoftWrapsEnabled() {
        assertTrue(settings.isSoftWrapsEnabled());
    }

    @Test
    @DisplayName("smartPasteMinLines defaults to 3")
    void defaultSmartPasteMinLines() {
        assertEquals(ChatInputSettings.DEFAULT_SMART_PASTE_MIN_LINES, settings.getSmartPasteMinLines());
    }

    @Test
    @DisplayName("smartPasteMinChars defaults to 500")
    void defaultSmartPasteMinChars() {
        assertEquals(ChatInputSettings.DEFAULT_SMART_PASTE_MIN_CHARS, settings.getSmartPasteMinChars());
    }

    @Test
    @DisplayName("fileSearchTrigger defaults to '#'")
    void defaultFileSearchTrigger() {
        assertEquals("#", settings.getFileSearchTrigger());
    }

    // ── Round-trips ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("showShortcutHints round-trips correctly")
    void showShortcutHintsRoundTrip() {
        settings.setShowShortcutHints(false);
        assertFalse(settings.isShowShortcutHints());
        settings.setShowShortcutHints(true);
        assertTrue(settings.isShowShortcutHints());
    }

    @Test
    @DisplayName("smartPasteEnabled round-trips correctly")
    void smartPasteEnabledRoundTrip() {
        settings.setSmartPasteEnabled(false);
        assertFalse(settings.isSmartPasteEnabled());
    }

    @Test
    @DisplayName("softWrapsEnabled round-trips correctly")
    void softWrapsEnabledRoundTrip() {
        settings.setSoftWrapsEnabled(false);
        assertFalse(settings.isSoftWrapsEnabled());
    }

    @Test
    @DisplayName("smartPasteMinLines round-trips correctly")
    void smartPasteMinLinesRoundTrip() {
        settings.setSmartPasteMinLines(10);
        assertEquals(10, settings.getSmartPasteMinLines());
    }

    @Test
    @DisplayName("smartPasteMinChars round-trips correctly")
    void smartPasteMinCharsRoundTrip() {
        settings.setSmartPasteMinChars(200);
        assertEquals(200, settings.getSmartPasteMinChars());
    }

    @Test
    @DisplayName("fileSearchTrigger round-trips correctly")
    void fileSearchTriggerRoundTrip() {
        settings.setFileSearchTrigger("@");
        assertEquals("@", settings.getFileSearchTrigger());
    }

    // ── loadState / getState ─────────────────────────────────────────────────

    @Test
    @DisplayName("loadState replaces internal state")
    void loadStateReplacesState() {
        ChatInputSettings.State state = new ChatInputSettings.State();
        state.showShortcutHints = false;
        state.fileSearchTrigger = "@";
        settings.loadState(state);
        assertFalse(settings.isShowShortcutHints());
        assertEquals("@", settings.getFileSearchTrigger());
    }

    @Test
    @DisplayName("getState reflects live mutations")
    void getStateReflectsMutations() {
        settings.setSmartPasteMinChars(300);
        assertEquals(300, settings.getState().smartPasteMinChars);
    }
}
