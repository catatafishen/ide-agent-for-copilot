package com.github.catatafishen.agentbridge.ui.side;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionDiffAnimatorTest {

    @Test
    void initialStateReturnsZeros() {
        SessionDiffAnimator animator = new SessionDiffAnimator();
        SessionDiffAnimator.DiffCounts counts = animator.displayCounts(0L);
        assertEquals(0, counts.added());
        assertEquals(0, counts.removed());
        assertFalse(animator.isAnimating(0L));
    }

    @Test
    void updateTriggersAnimation() {
        SessionDiffAnimator animator = new SessionDiffAnimator();
        animator.update(20, 8, 100L);

        // At start of animation, should be near 0 (interpolating from 0)
        SessionDiffAnimator.DiffCounts atStart = animator.displayCounts(100L);
        assertEquals(0, atStart.added());
        assertEquals(0, atStart.removed());
        assertTrue(animator.isAnimating(100L));
    }

    @Test
    void midpointShowsIntermediateValues() {
        SessionDiffAnimator animator = new SessionDiffAnimator();
        animator.update(20, 10, 0L);

        long midpoint = SessionDiffAnimator.ANIMATION_DURATION_MS / 2;
        SessionDiffAnimator.DiffCounts mid = animator.displayCounts(midpoint);
        assertTrue(mid.added() > 0 && mid.added() < 20,
            "midpoint added should be between 0 and 20, was " + mid.added());
        assertTrue(mid.removed() > 0 && mid.removed() < 10,
            "midpoint removed should be between 0 and 10, was " + mid.removed());
    }

    @Test
    void afterDurationReachesTarget() {
        SessionDiffAnimator animator = new SessionDiffAnimator();
        animator.update(42, 17, 0L);

        long finished = SessionDiffAnimator.ANIMATION_DURATION_MS + 1L;
        SessionDiffAnimator.DiffCounts end = animator.displayCounts(finished);
        assertEquals(42, end.added());
        assertEquals(17, end.removed());
        assertFalse(animator.isAnimating(finished));
    }

    @Test
    void retargetPreservesCurrentPosition() {
        SessionDiffAnimator animator = new SessionDiffAnimator();
        animator.update(20, 10, 0L);

        // At midpoint, retarget to higher values
        long midpoint = SessionDiffAnimator.ANIMATION_DURATION_MS / 2;
        SessionDiffAnimator.DiffCounts midValues = animator.displayCounts(midpoint);
        animator.update(40, 20, midpoint);

        // The new animation should start from the midpoint values
        SessionDiffAnimator.DiffCounts afterRetarget = animator.displayCounts(midpoint);
        assertEquals(midValues.added(), afterRetarget.added());
        assertEquals(midValues.removed(), afterRetarget.removed());
        assertTrue(animator.isAnimating(midpoint));

        // And eventually reach the new target
        long finished = midpoint + SessionDiffAnimator.ANIMATION_DURATION_MS + 1L;
        SessionDiffAnimator.DiffCounts finalCounts = animator.displayCounts(finished);
        assertEquals(40, finalCounts.added());
        assertEquals(20, finalCounts.removed());
    }

    @Test
    void sameValuesDoNotRestartAnimation() {
        SessionDiffAnimator animator = new SessionDiffAnimator();
        animator.update(10, 5, 0L);

        // Let it finish
        long finished = SessionDiffAnimator.ANIMATION_DURATION_MS + 1L;
        assertFalse(animator.isAnimating(finished));

        // Update with same values — should not start animating
        animator.update(10, 5, finished + 100L);
        assertFalse(animator.isAnimating(finished + 100L));
    }

    @Test
    void onlyAddedAnimates() {
        SessionDiffAnimator animator = new SessionDiffAnimator();
        animator.update(30, 0, 0L);

        long midpoint = SessionDiffAnimator.ANIMATION_DURATION_MS / 2;
        SessionDiffAnimator.DiffCounts mid = animator.displayCounts(midpoint);
        assertTrue(mid.added() > 0 && mid.added() < 30);
        assertEquals(0, mid.removed());

        long finished = SessionDiffAnimator.ANIMATION_DURATION_MS + 1L;
        assertEquals(30, animator.displayCounts(finished).added());
    }

    @Test
    void onlyRemovedAnimates() {
        SessionDiffAnimator animator = new SessionDiffAnimator();
        animator.update(0, 15, 0L);

        long midpoint = SessionDiffAnimator.ANIMATION_DURATION_MS / 2;
        SessionDiffAnimator.DiffCounts mid = animator.displayCounts(midpoint);
        assertEquals(0, mid.added());
        assertTrue(mid.removed() > 0 && mid.removed() < 15);

        long finished = SessionDiffAnimator.ANIMATION_DURATION_MS + 1L;
        assertEquals(15, animator.displayCounts(finished).removed());
    }
}
