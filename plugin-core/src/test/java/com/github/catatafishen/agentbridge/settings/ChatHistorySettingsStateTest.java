package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatHistorySettings state")
class ChatHistorySettingsStateTest {

    private ChatHistorySettings settings;

    @BeforeEach
    void setUp() {
        settings = new ChatHistorySettings();
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    @DisplayName("default event log size is 600")
    void defaultEventLogSize() {
        assertEquals(ChatHistorySettings.DEFAULT_EVENT_LOG_SIZE, settings.getEventLogSize());
    }

    @Test
    @DisplayName("default DOM message limit is 80")
    void defaultDomMessageLimit() {
        assertEquals(ChatHistorySettings.DEFAULT_DOM_MESSAGE_LIMIT, settings.getDomMessageLimit());
    }

    @Test
    @DisplayName("default recent turns on restore is 5")
    void defaultRecentTurnsOnRestore() {
        assertEquals(ChatHistorySettings.DEFAULT_RECENT_TURNS_ON_RESTORE, settings.getRecentTurnsOnRestore());
    }

    @Test
    @DisplayName("default load-more batch size is 3")
    void defaultLoadMoreBatchSize() {
        assertEquals(ChatHistorySettings.DEFAULT_LOAD_MORE_BATCH_SIZE, settings.getLoadMoreBatchSize());
    }

    // ── Round-trips ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("eventLogSize round-trips correctly")
    void eventLogSizeRoundTrip() {
        settings.setEventLogSize(1000);
        assertEquals(1000, settings.getEventLogSize());
    }

    @Test
    @DisplayName("domMessageLimit round-trips correctly")
    void domMessageLimitRoundTrip() {
        settings.setDomMessageLimit(50);
        assertEquals(50, settings.getDomMessageLimit());
    }

    @Test
    @DisplayName("recentTurnsOnRestore round-trips correctly")
    void recentTurnsOnRestoreRoundTrip() {
        settings.setRecentTurnsOnRestore(10);
        assertEquals(10, settings.getRecentTurnsOnRestore());
    }

    @Test
    @DisplayName("loadMoreBatchSize round-trips correctly")
    void loadMoreBatchSizeRoundTrip() {
        settings.setLoadMoreBatchSize(5);
        assertEquals(5, settings.getLoadMoreBatchSize());
    }

    // ── loadState / getState ─────────────────────────────────────────────────

    @Test
    @DisplayName("loadState replaces internal state")
    void loadStateReplacesState() {
        ChatHistorySettings.State state = new ChatHistorySettings.State();
        state.setEventLogSize(999);
        state.setDomMessageLimit(25);
        settings.loadState(state);
        assertEquals(999, settings.getEventLogSize());
        assertEquals(25, settings.getDomMessageLimit());
    }

    @Test
    @DisplayName("getState reflects live mutations")
    void getStateReflectsMutations() {
        settings.setLoadMoreBatchSize(7);
        assertEquals(7, settings.getState().getLoadMoreBatchSize());
    }
}
