package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.Test;

import static com.github.catatafishen.agentbridge.psi.EdtUtil.ModalCheckAction.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EdtUtilTest {

    private static final long FAIL_AFTER = EdtUtil.MODAL_FAIL_AFTER_MS;

    // ── evaluateModalState ──────────────────────────────────────────────

    @Test
    void noModal_returnsReset() {
        assertEquals(RESET, EdtUtil.evaluateModalState(0, "", 1000, FAIL_AFTER));
    }

    @Test
    void noModal_evenWithPriorTracking_returnsReset() {
        assertEquals(RESET, EdtUtil.evaluateModalState(500, "", 2000, FAIL_AFTER));
    }

    @Test
    void modalJustAppeared_firstSeen0_returnsStartTimer() {
        assertEquals(START_TIMER, EdtUtil.evaluateModalState(0, "Dialog: Save?", 1000, FAIL_AFTER));
    }

    @Test
    void modalPresent_withinGracePeriod_returnsContinue() {
        long firstSeen = 1000;
        long now = firstSeen + FAIL_AFTER - 1;
        assertEquals(CONTINUE, EdtUtil.evaluateModalState(firstSeen, "Dialog: Confirm", now, FAIL_AFTER));
    }

    @Test
    void modalPresent_exactlyAtThreshold_returnsAbort() {
        long firstSeen = 1000;
        long now = firstSeen + FAIL_AFTER;
        assertEquals(ABORT, EdtUtil.evaluateModalState(firstSeen, "Dialog: Error", now, FAIL_AFTER));
    }

    @Test
    void modalPresent_pastThreshold_returnsAbort() {
        long firstSeen = 1000;
        long now = firstSeen + FAIL_AFTER + 500;
        assertEquals(ABORT, EdtUtil.evaluateModalState(firstSeen, "Dialog: Warning", now, FAIL_AFTER));
    }

    @Test
    void customFailAfter_shorterThreshold() {
        long customFailAfter = 100;
        assertEquals(ABORT, EdtUtil.evaluateModalState(1000, "Modal", 1100, customFailAfter));
        assertEquals(CONTINUE, EdtUtil.evaluateModalState(1000, "Modal", 1099, customFailAfter));
    }

    @Test
    void customFailAfter_longerThreshold() {
        long customFailAfter = 10_000;
        long firstSeen = 1000;
        assertEquals(CONTINUE, EdtUtil.evaluateModalState(firstSeen, "Modal", 5000, customFailAfter));
        assertEquals(ABORT, EdtUtil.evaluateModalState(firstSeen, "Modal", 11_000, customFailAfter));
    }

    // ── Constants ───────────────────────────────────────────────────────

    @Test
    void constants_haveExpectedValues() {
        assertEquals(500, EdtUtil.MODAL_POLL_INTERVAL_MS);
        assertEquals(1500, EdtUtil.MODAL_FAIL_AFTER_MS);
        assertEquals(30, EdtUtil.DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS);
    }
}
