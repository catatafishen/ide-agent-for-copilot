package com.github.catatafishen.agentbridge.psi.tools.navigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the pure formatting methods in {@link ReferenceClassifier}.
 * Classification logic (classifyUsage) requires PSI elements and is covered by integration tests.
 */
class ReferenceClassifierTest {

    @Nested
    @DisplayName("formatContext")
    class FormatContext {

        @Test
        @DisplayName("usage type with container includes 'in container'")
        void withContainer() {
            assertEquals("[CALL in processRequest]",
                ReferenceClassifier.formatContext("CALL", "processRequest"));
        }

        @Test
        @DisplayName("usage type without container shows type only")
        void withoutContainer() {
            assertEquals("[IMPORT]",
                ReferenceClassifier.formatContext("IMPORT", null));
        }

        @Test
        @DisplayName("REF usage type with container")
        void refWithContainer() {
            assertEquals("[REF in MyClass]",
                ReferenceClassifier.formatContext("REF", "MyClass"));
        }

        @Test
        @DisplayName("all usage type constants are non-null")
        void usageTypeConstants() {
            assertEquals("CALL", ReferenceClassifier.USAGE_METHOD_CALL);
            assertEquals("FIELD_ACCESS", ReferenceClassifier.USAGE_FIELD_ACCESS);
            assertEquals("IMPORT", ReferenceClassifier.USAGE_IMPORT);
            assertEquals("TYPE_REF", ReferenceClassifier.USAGE_TYPE_REF);
            assertEquals("ANNOTATION", ReferenceClassifier.USAGE_ANNOTATION);
            assertEquals("EXTENDS", ReferenceClassifier.USAGE_EXTENDS);
            assertEquals("IMPLEMENTS", ReferenceClassifier.USAGE_IMPLEMENTS);
            assertEquals("NEW", ReferenceClassifier.USAGE_NEW);
            assertEquals("COMMENT", ReferenceClassifier.USAGE_COMMENT);
            assertEquals("REF", ReferenceClassifier.USAGE_REFERENCE);
        }
    }
}
