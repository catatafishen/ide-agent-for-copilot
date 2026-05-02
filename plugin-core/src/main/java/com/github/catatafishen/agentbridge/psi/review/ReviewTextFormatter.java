package com.github.catatafishen.agentbridge.psi.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pure text formatting utilities for the review system.
 * All methods are stateless and have no IntelliJ Platform dependencies.
 */
public final class ReviewTextFormatter {

    private static final String ERR_PREFIX = "Error: ";

    private ReviewTextFormatter() {}

    /**
     * Formats a list of change ranges as a colon-prefixed comma-separated string.
     * Single-line ranges render as {@code :5}, multi-line as {@code :5-10}.
     * Returns empty string if ranges is empty.
     *
     * @return e.g. {@code ":1-5,10,12-15"} or {@code ""}
     */
    public static @NotNull String formatRanges(@NotNull List<ChangeRange> ranges) {
        if (ranges.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(":");
        boolean first = true;
        for (ChangeRange r : ranges) {
            if (!first) sb.append(',');
            first = false;
            int start = r.startLine() + 1;
            int end = Math.max(start, r.endLine());
            if (start == end) sb.append(start);
            else sb.append(start).append('-').append(end);
        }
        return sb.toString();
    }

    /**
     * Builds the error message shown when pending reviews block a git/tool operation.
     * Handles pluralization of "file has" vs "files have".
     */
    public static @NotNull String formatReviewTimeoutError(@NotNull String operation, int fileCount) {
        return ERR_PREFIX + fileCount + (fileCount == 1 ? " file has" : " files have")
            + " not been approved or rejected by the user. '"
            + operation + "' cannot proceed until all pending agent edits are reviewed."
            + " The user must accept or revert the pending edits in the"
            + " Review panel (left of chat), then retry.";
    }

    /**
     * Counts the number of lines in a string.
     * Returns 0 for null or empty input.
     */
    public static int countLines(@Nullable String content) {
        if (content == null || content.isEmpty()) return 0;
        return (int) content.lines().count();
    }
}
