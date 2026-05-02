package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewTextFormatterTest {

    @Nested
    class FormatRanges {

        @Test
        void emptyList_returnsEmptyString() {
            assertEquals("", ReviewTextFormatter.formatRanges(Collections.emptyList()));
        }

        @Test
        void singleLineRange() {
            // startLine=4, endLine=5 → start=5, end=max(5,5)=5 → ":5"
            ChangeRange range = new ChangeRange(4, 5, ChangeType.ADDED, 0, 0);
            assertEquals(":5", ReviewTextFormatter.formatRanges(List.of(range)));
        }

        @Test
        void multiLineRange() {
            // startLine=0, endLine=5 → start=1, end=max(1,5)=5 → ":1-5"
            ChangeRange range = new ChangeRange(0, 5, ChangeType.MODIFIED, 0, 3);
            assertEquals(":1-5", ReviewTextFormatter.formatRanges(List.of(range)));
        }

        @Test
        void multipleRanges() {
            List<ChangeRange> ranges = List.of(
                new ChangeRange(0, 5, ChangeType.ADDED, 0, 0),
                new ChangeRange(9, 10, ChangeType.MODIFIED, 9, 1),
                new ChangeRange(11, 15, ChangeType.ADDED, 11, 0)
            );
            // range1: start=1, end=max(1,5)=5 → "1-5"
            // range2: start=10, end=max(10,10)=10 → "10"
            // range3: start=12, end=max(12,15)=15 → "12-15"
            assertEquals(":1-5,10,12-15", ReviewTextFormatter.formatRanges(ranges));
        }

        @Test
        void deletedPointRange() {
            // DELETED: startLine=5, endLine=5 → start=6, end=max(6,5)=6 → ":6"
            ChangeRange range = new ChangeRange(5, 5, ChangeType.DELETED, 5, 3);
            assertEquals(":6", ReviewTextFormatter.formatRanges(List.of(range)));
        }
    }

    @Nested
    class FormatReviewTimeoutError {

        @Test
        void singleFile_usesSingularForm() {
            String result = ReviewTextFormatter.formatReviewTimeoutError("git_commit", 1);
            assertTrue(result.contains("1 file has not been approved"));
            assertTrue(result.contains("'git_commit' cannot proceed"));
        }

        @Test
        void multipleFiles_usesPluralForm() {
            String result = ReviewTextFormatter.formatReviewTimeoutError("git_commit", 3);
            assertTrue(result.contains("3 files have not been approved"));
        }

        @Test
        void includesOperationName() {
            String result = ReviewTextFormatter.formatReviewTimeoutError("write_file", 2);
            assertTrue(result.contains("'write_file' cannot proceed"));
        }
    }

    @Nested
    class CountLines {

        @Test
        void nullInput_returnsZero() {
            assertEquals(0, ReviewTextFormatter.countLines(null));
        }

        @Test
        void emptyString_returnsZero() {
            assertEquals(0, ReviewTextFormatter.countLines(""));
        }

        @Test
        void singleLine() {
            assertEquals(1, ReviewTextFormatter.countLines("hello"));
        }

        @Test
        void multipleLines() {
            assertEquals(3, ReviewTextFormatter.countLines("a\nb\nc"));
        }

        @Test
        void trailingNewline_excludesTrailingEmpty() {
            // String.lines() does not produce an empty trailing element
            assertEquals(2, ReviewTextFormatter.countLines("a\nb\n"));
        }
    }
}
