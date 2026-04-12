package com.github.catatafishen.agentbridge.psi.tools.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseToolStaticMethodsTest {

    // ── DatabaseTool.formatQualifiedName ─────────────────────────────────

    @Nested
    @DisplayName("formatQualifiedName")
    class FormatQualifiedNameTest {

        @Test
        @DisplayName("non-empty schema returns schema.name")
        void nonEmptySchema() {
            assertEquals("public.users", DatabaseTool.formatQualifiedName("public", "users"));
        }

        @Test
        @DisplayName("empty schema returns name only")
        void emptySchema() {
            assertEquals("orders", DatabaseTool.formatQualifiedName("", "orders"));
        }

        @Test
        @DisplayName("null schema returns name only")
        void nullSchema() {
            assertEquals("products", DatabaseTool.formatQualifiedName(null, "products"));
        }

        @Test
        @DisplayName("both schema and name non-null")
        void bothNonNull() {
            assertEquals("myschema.mytable", DatabaseTool.formatQualifiedName("myschema", "mytable"));
        }
    }

    // ── ListTablesTool.matchesSchema (private → reflection) ─────────────

    @Nested
    @DisplayName("matchesSchema")
    class MatchesSchemaTest {

        private boolean invokeMatchesSchema(String tableSchema, String schemaFilter) throws Exception {
            Method method = ListTablesTool.class.getDeclaredMethod(
                    "matchesSchema", String.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, tableSchema, schemaFilter);
        }

        @Test
        @DisplayName("null filter matches everything")
        void nullFilterMatchesAll() throws Exception {
            assertTrue(invokeMatchesSchema("public", null));
        }

        @Test
        @DisplayName("matching filter same case returns true")
        void matchingSameCase() throws Exception {
            assertTrue(invokeMatchesSchema("public", "public"));
        }

        @Test
        @DisplayName("matching filter different case returns true")
        void matchingDifferentCase() throws Exception {
            assertTrue(invokeMatchesSchema("PUBLIC", "public"));
        }

        @Test
        @DisplayName("non-matching filter returns false")
        void nonMatching() throws Exception {
            assertFalse(invokeMatchesSchema("sales", "public"));
        }

        @Test
        @DisplayName("empty filter vs empty schema returns true")
        void emptyFilterEmptySchema() throws Exception {
            assertTrue(invokeMatchesSchema("", ""));
        }

        @Test
        @DisplayName("null tableSchema with non-null filter returns false")
        void nullTableSchemaNonNullFilter() throws Exception {
            assertFalse(invokeMatchesSchema(null, "public"));
        }
    }
}
