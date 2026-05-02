package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests regex patterns in {@link SearchResultRenderer}.
 */
class SearchResultRendererTest {

    private static final SearchResultRenderer R = SearchResultRenderer.INSTANCE;

    @Nested
    class LineRefPattern {

        @Test
        void matchesStandardLineRef() {
            MatchResult match = R.getLINE_REF_PATTERN().matchEntire(
                "src/Foo.java:42: some matching text");

            assertNotNull(match);
            assertEquals("src/Foo.java", match.getGroupValues().get(1));
            assertEquals("42", match.getGroupValues().get(2));
            assertEquals("some matching text", match.getGroupValues().get(3));
        }

        @Test
        void matchesDeepPath() {
            MatchResult match = R.getLINE_REF_PATTERN().matchEntire(
                "com/example/service/UserService.java:100: private void save()");

            assertNotNull(match);
            assertEquals("com/example/service/UserService.java", match.getGroupValues().get(1));
            assertEquals("100", match.getGroupValues().get(2));
            assertEquals("private void save()", match.getGroupValues().get(3));
        }

        @Test
        void doesNotMatchWithoutLineNumber() {
            assertNull(R.getLINE_REF_PATTERN().matchEntire("src/Foo.java: no line number"));
        }

        @Test
        void doesNotMatchEmptyContent() {
            // Pattern requires at least one char in content group (.+)
            assertNull(R.getLINE_REF_PATTERN().matchEntire("src/Foo.java:42: "));
        }
    }

    @Nested
    class LocationPattern {

        @Test
        void matchesWithBadge() {
            MatchResult match = R.getLOCATION_PATTERN().matchEntire(
                "src/Foo.java:42 [method] doSomething");

            assertNotNull(match);
            assertEquals("src/Foo.java", match.getGroupValues().get(1));
            assertEquals("42", match.getGroupValues().get(2));
            assertEquals("method", match.getGroupValues().get(3));
            assertEquals("doSomething", match.getGroupValues().get(4));
        }

        @Test
        void matchesClassBadge() {
            MatchResult match = R.getLOCATION_PATTERN().matchEntire(
                "src/Bar.kt:1 [class] Bar");

            assertNotNull(match);
            assertEquals("class", match.getGroupValues().get(3));
            assertEquals("Bar", match.getGroupValues().get(4));
        }

        @Test
        void doesNotMatchWithoutBadge() {
            assertNull(R.getLOCATION_PATTERN().matchEntire(
                "src/Foo.java:42 doSomething"));
        }
    }

    @Nested
    class CountHeader {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "15 matches found in 3 files|15|matches",
            "7 results in project|7|results",
            "3 references found|3|references",
            "1 symbol found|1|symbol"
        })
        void matchesCountVariants(String input, String expectedCount, String expectedType) {
            MatchResult match = R.getCOUNT_HEADER().find(input, 0);

            assertNotNull(match);
            assertEquals(expectedCount, match.getGroupValues().get(1));
            assertEquals(expectedType, match.getGroupValues().get(2));
        }

        @Test
        void doesNotMatchWithoutCount() {
            assertNull(R.getCOUNT_HEADER().find("some matches found", 0));
        }
    }

    @Nested
    class NoMatches {

        @ParameterizedTest
        @ValueSource(strings = {
            "No matches found",
            "No results found",
            "No references found",
            "No symbols found"
        })
        void matchesNoResultsVariants(String line) {
            assertTrue(R.getNO_MATCHES().containsMatchIn(line));
        }

        @Test
        void doesNotMatchPresence() {
            assertFalse(R.getNO_MATCHES().containsMatchIn("15 matches found"));
        }

        @Test
        void doesNotMatchEmpty() {
            assertFalse(R.getNO_MATCHES().containsMatchIn(""));
        }
    }
}
