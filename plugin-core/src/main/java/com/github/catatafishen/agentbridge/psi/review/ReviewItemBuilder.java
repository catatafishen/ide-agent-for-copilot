package com.github.catatafishen.agentbridge.psi.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link ReviewItem} lists from raw review session state.
 * Pure logic — no IntelliJ Platform dependencies.
 */
public final class ReviewItemBuilder {

    /**
     * Per-path edit metrics passed together to keep method signatures manageable.
     */
    public record EditMetrics(
        @NotNull Map<String, ApprovalState> approvals,
        @NotNull Map<String, Long> lastEditedAt,
        @NotNull Map<String, Integer> linesAdded,
        @NotNull Map<String, Integer> linesRemoved
    ) {
    }

    private ReviewItemBuilder() {
    }

    /**
     * Assembles a sorted list of {@link ReviewItem}s from the session's tracked state maps.
     * Files that appear in both {@code newFiles} and {@code deletedFiles} are skipped
     * (created then immediately deleted within the same session).
     */
    public static @NotNull List<ReviewItem> buildReviewItems(
        @NotNull Map<String, String> snapshots,
        @NotNull Set<String> newFiles,
        @NotNull Map<String, String> deletedFiles,
        @NotNull EditMetrics metrics,
        @Nullable String basePath
    ) {
        List<ReviewItem> items = new ArrayList<>();

        for (Map.Entry<String, String> entry : snapshots.entrySet()) {
            String path = entry.getKey();
            if (newFiles.contains(path) || deletedFiles.containsKey(path)) continue;
            items.add(buildItem(path, basePath, ReviewItem.Status.MODIFIED, entry.getValue(), metrics));
        }
        for (String path : newFiles) {
            if (deletedFiles.containsKey(path)) continue;
            items.add(buildItem(path, basePath, ReviewItem.Status.ADDED, null, metrics));
        }
        for (Map.Entry<String, String> entry : deletedFiles.entrySet()) {
            String path = entry.getKey();
            if (newFiles.contains(path)) continue;
            String beforeContent = snapshots.getOrDefault(path, entry.getValue());
            items.add(buildItem(path, basePath, ReviewItem.Status.DELETED, beforeContent, metrics));
        }

        items.sort((a, b) -> a.relativePath().compareToIgnoreCase(b.relativePath()));
        return items;
    }

    /**
     * Builds a single {@link ReviewItem} from per-path state.
     */
    static @NotNull ReviewItem buildItem(
        @NotNull String path,
        @Nullable String basePath,
        @NotNull ReviewItem.Status status,
        @Nullable String beforeContent,
        @NotNull EditMetrics metrics
    ) {
        ApprovalState state = metrics.approvals().getOrDefault(path, ApprovalState.PENDING);
        long ts = metrics.lastEditedAt().getOrDefault(path, 0L);
        int added = metrics.linesAdded().getOrDefault(path, 0);
        int removed = metrics.linesRemoved().getOrDefault(path, 0);
        return new ReviewItem(path, relativize(path, basePath), status, beforeContent,
            state, ts, added, removed);
    }

    /**
     * Converts an absolute path to a project-relative display path.
     * Falls back to the file name if the path is outside the base directory.
     */
    public static @NotNull String relativize(@NotNull String path, @Nullable String basePath) {
        if (basePath != null && path.startsWith(basePath + "/")) {
            return path.substring(basePath.length() + 1);
        }
        return new File(path).getName();
    }
}
