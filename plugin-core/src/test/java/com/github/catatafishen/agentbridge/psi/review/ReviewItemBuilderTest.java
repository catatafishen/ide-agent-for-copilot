package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReviewItemBuilder} — pure logic for building review item lists.
 */
@DisplayName("ReviewItemBuilder")
class ReviewItemBuilderTest {

    private static final String BASE = "/project";

    private static ReviewItemBuilder.EditMetrics emptyMetrics() {
        return new ReviewItemBuilder.EditMetrics(Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static ReviewItemBuilder.EditMetrics metricsWithApproval(String path, ApprovalState state) {
        return new ReviewItemBuilder.EditMetrics(
            Map.of(path, state), Map.of(), Map.of(), Map.of());
    }

    @Nested
    @DisplayName("relativize")
    class Relativize {

        @Test
        @DisplayName("strips base path prefix")
        void stripsPrefix() {
            assertEquals("src/Main.java",
                ReviewItemBuilder.relativize("/project/src/Main.java", "/project"));
        }

        @Test
        @DisplayName("falls back to file name when outside base")
        void fallsBackToFileName() {
            assertEquals("Other.java",
                ReviewItemBuilder.relativize("/other/dir/Other.java", "/project"));
        }

        @Test
        @DisplayName("handles null base path")
        void nullBasePath() {
            assertEquals("File.java",
                ReviewItemBuilder.relativize("/any/path/File.java", null));
        }

        @Test
        @DisplayName("exact base path match does not strip (no trailing slash)")
        void exactMatch() {
            assertEquals("project",
                ReviewItemBuilder.relativize("/project", "/project"));
        }
    }

    @Nested
    @DisplayName("buildReviewItems")
    class BuildReviewItems {

        @Test
        @DisplayName("empty state produces empty list")
        void emptyState() {
            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                Map.of(), Set.of(), Map.of(), emptyMetrics(), BASE);
            assertTrue(items.isEmpty());
        }

        @Test
        @DisplayName("modified file appears with MODIFIED status")
        void modifiedFile() {
            Map<String, String> snapshots = new HashMap<>();
            snapshots.put("/project/src/A.java", "original");

            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                snapshots, Set.of(), Map.of(), emptyMetrics(), BASE);

            assertEquals(1, items.size());
            assertEquals(ReviewItem.Status.MODIFIED, items.getFirst().status());
            assertEquals("src/A.java", items.getFirst().relativePath());
            assertEquals("original", items.getFirst().beforeContent());
        }

        @Test
        @DisplayName("added file appears with ADDED status and null beforeContent")
        void addedFile() {
            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                Map.of(), Set.of("/project/src/New.java"), Map.of(), emptyMetrics(), BASE);

            assertEquals(1, items.size());
            assertEquals(ReviewItem.Status.ADDED, items.getFirst().status());
            assertNull(items.getFirst().beforeContent());
        }

        @Test
        @DisplayName("deleted file appears with DELETED status")
        void deletedFile() {
            Map<String, String> deleted = new HashMap<>();
            deleted.put("/project/src/Gone.java", "was here");

            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                Map.of(), Set.of(), deleted, emptyMetrics(), BASE);

            assertEquals(1, items.size());
            assertEquals(ReviewItem.Status.DELETED, items.getFirst().status());
            assertEquals("was here", items.getFirst().beforeContent());
        }

        @Test
        @DisplayName("deleted file uses snapshot content when available")
        void deletedUsesSnapshot() {
            Map<String, String> snapshots = new HashMap<>();
            snapshots.put("/project/src/X.java", "snapshot content");
            Map<String, String> deleted = new HashMap<>();
            deleted.put("/project/src/X.java", "delete fallback");

            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                snapshots, Set.of(), deleted, emptyMetrics(), BASE);

            assertEquals(1, items.size());
            assertEquals("snapshot content", items.getFirst().beforeContent());
        }

        @Test
        @DisplayName("file in both newFiles and deletedFiles is excluded")
        void createdThenDeleted() {
            Set<String> newFiles = new HashSet<>(Set.of("/project/src/Temp.java"));
            Map<String, String> deleted = new HashMap<>();
            deleted.put("/project/src/Temp.java", "content");

            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                Map.of(), newFiles, deleted, emptyMetrics(), BASE);

            assertTrue(items.isEmpty());
        }

        @Test
        @DisplayName("snapshot file in deletedFiles is excluded from MODIFIED")
        void snapshotDeletedExcluded() {
            Map<String, String> snapshots = new HashMap<>();
            snapshots.put("/project/src/Moved.java", "old content");
            Map<String, String> deleted = new HashMap<>();
            deleted.put("/project/src/Moved.java", "old content");

            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                snapshots, Set.of(), deleted, emptyMetrics(), BASE);

            assertEquals(1, items.size());
            assertEquals(ReviewItem.Status.DELETED, items.getFirst().status());
        }

        @Test
        @DisplayName("results are sorted by relative path case-insensitively")
        void sortedByRelativePath() {
            Map<String, String> snapshots = new HashMap<>();
            snapshots.put("/project/z.txt", "z");
            snapshots.put("/project/a.txt", "a");
            snapshots.put("/project/M.txt", "m");

            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                snapshots, Set.of(), Map.of(), emptyMetrics(), BASE);

            assertEquals(3, items.size());
            assertEquals("a.txt", items.get(0).relativePath());
            assertEquals("M.txt", items.get(1).relativePath());
            assertEquals("z.txt", items.get(2).relativePath());
        }

        @Test
        @DisplayName("approval state is read from metrics")
        void approvalFromMetrics() {
            Map<String, String> snapshots = new HashMap<>();
            snapshots.put("/project/src/A.java", "original");

            var metrics = metricsWithApproval("/project/src/A.java", ApprovalState.APPROVED);
            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                snapshots, Set.of(), Map.of(), metrics, BASE);

            assertEquals(ApprovalState.APPROVED, items.getFirst().approvalState());
        }

        @Test
        @DisplayName("missing approval defaults to PENDING")
        void missingApprovalDefaultsPending() {
            Map<String, String> snapshots = new HashMap<>();
            snapshots.put("/project/src/A.java", "original");

            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                snapshots, Set.of(), Map.of(), emptyMetrics(), BASE);

            assertEquals(ApprovalState.PENDING, items.getFirst().approvalState());
        }

        @Test
        @DisplayName("edit metrics (lines added/removed, timestamp) are populated")
        void editMetrics() {
            String path = "/project/src/A.java";
            Map<String, String> snapshots = new HashMap<>();
            snapshots.put(path, "original");

            var metrics = new ReviewItemBuilder.EditMetrics(
                Map.of(path, ApprovalState.PENDING),
                Map.of(path, 1234567890L),
                Map.of(path, 10),
                Map.of(path, 3)
            );
            List<ReviewItem> items = ReviewItemBuilder.buildReviewItems(
                snapshots, Set.of(), Map.of(), metrics, BASE);

            ReviewItem item = items.getFirst();
            assertEquals(1234567890L, item.lastEditedMillis());
            assertEquals(10, item.linesAdded());
            assertEquals(3, item.linesRemoved());
        }
    }
}
