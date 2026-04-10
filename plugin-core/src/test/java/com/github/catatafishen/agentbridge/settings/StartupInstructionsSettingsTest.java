package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link StartupInstructionsSettings} business logic.
 * Constructs the object directly — no IntelliJ application context required.
 */
@DisplayName("StartupInstructionsSettings")
class StartupInstructionsSettingsTest {

    private StartupInstructionsSettings settings;

    @BeforeEach
    void setUp() {
        settings = new StartupInstructionsSettings();
    }

    // ── Default state ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("no custom instructions by default")
    void noCustomByDefault() {
        assertNull(settings.getCustomInstructions());
        assertFalse(settings.isUsingCustomInstructions());
    }

    @Test
    @DisplayName("getInstructions returns non-empty string by default (template or fallback)")
    void defaultInstructionsNonEmpty() {
        String instructions = settings.getInstructions();
        assertNotNull(instructions);
        assertFalse(instructions.isBlank());
    }

    // ── Custom instructions ───────────────────────────────────────────────────

    @Test
    @DisplayName("setCustomInstructions activates custom mode")
    void setCustomInstructions() {
        settings.setCustomInstructions("Be concise.");
        assertTrue(settings.isUsingCustomInstructions());
        assertEquals("Be concise.", settings.getCustomInstructions());
    }

    @Test
    @DisplayName("getInstructions returns custom instructions when set")
    void getInstructionsReturnsCustom() {
        settings.setCustomInstructions("Custom prompt.");
        assertEquals("Custom prompt.", settings.getInstructions());
    }

    @Test
    @DisplayName("setCustomInstructions(null) reverts to default")
    void setNullRevertsToDefault() {
        settings.setCustomInstructions("Something");
        settings.setCustomInstructions(null);
        assertFalse(settings.isUsingCustomInstructions());
        assertNull(settings.getCustomInstructions());
    }

    @Test
    @DisplayName("setCustomInstructions with blank string reverts to default")
    void setBlankRevertsToDefault() {
        settings.setCustomInstructions("Something");
        settings.setCustomInstructions("   ");
        assertFalse(settings.isUsingCustomInstructions());
    }

    @Test
    @DisplayName("setCustomInstructions with empty string reverts to default")
    void setEmptyRevertsToDefault() {
        settings.setCustomInstructions("Something");
        settings.setCustomInstructions("");
        assertFalse(settings.isUsingCustomInstructions());
    }

    // ── loadState / getState ──────────────────────────────────────────────────

    @Test
    @DisplayName("loadState with custom instructions activates custom mode")
    void loadStateActivatesCustomMode() {
        StartupInstructionsSettings.State state = new StartupInstructionsSettings.State();
        state.setCustomInstructions("Loaded custom");
        settings.loadState(state);
        assertTrue(settings.isUsingCustomInstructions());
        assertEquals("Loaded custom", settings.getInstructions());
    }

    @Test
    @DisplayName("loadState with null custom instructions uses default")
    void loadStateWithNullUsesDefault() {
        // First set a custom value
        settings.setCustomInstructions("Something");
        // Then load a state with null
        StartupInstructionsSettings.State state = new StartupInstructionsSettings.State();
        state.setCustomInstructions(null);
        settings.loadState(state);
        assertFalse(settings.isUsingCustomInstructions());
    }

    @Test
    @DisplayName("getState returns state with current custom instructions")
    void getStateReturnsCurrentState() {
        settings.setCustomInstructions("My instructions");
        assertNotNull(settings.getState());
        assertEquals("My instructions", settings.getState().getCustomInstructions());
    }
}
