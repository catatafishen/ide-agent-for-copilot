package com.github.catatafishen.agentbridge.psi.tools.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for pure static methods in {@link EditProjectStructureTool}.
 * Uses reflection to access private methods that perform string-to-enum mapping.
 */
class EditProjectStructureToolStaticMethodsTest {

    // ── parseDependencyScope ────────────────────────────────────────────

    @Nested
    @DisplayName("parseDependencyScope")
    class ParseDependencyScopeTest {

        @Test
        @DisplayName("COMPILE returns COMPILE scope")
        void compile() throws Exception {
            Object result = invokeParseDependencyScope("COMPILE");
            assertNotNull(result, "COMPILE should return a non-null scope");
            assertEquals("COMPILE", ((Enum<?>) result).name());
        }

        @Test
        @DisplayName("TEST returns TEST scope")
        void test() throws Exception {
            Object result = invokeParseDependencyScope("TEST");
            assertNotNull(result, "TEST should return a non-null scope");
            assertEquals("TEST", ((Enum<?>) result).name());
        }

        @Test
        @DisplayName("RUNTIME returns RUNTIME scope")
        void runtime() throws Exception {
            Object result = invokeParseDependencyScope("RUNTIME");
            assertNotNull(result, "RUNTIME should return a non-null scope");
            assertEquals("RUNTIME", ((Enum<?>) result).name());
        }

        @Test
        @DisplayName("PROVIDED returns PROVIDED scope")
        void provided() throws Exception {
            Object result = invokeParseDependencyScope("PROVIDED");
            assertNotNull(result, "PROVIDED should return a non-null scope");
            assertEquals("PROVIDED", ((Enum<?>) result).name());
        }

        @Test
        @DisplayName("lowercase 'compile' is normalized to COMPILE")
        void lowercaseCompile() throws Exception {
            Object result = invokeParseDependencyScope("compile");
            assertNotNull(result, "lowercase 'compile' should resolve");
            assertEquals("COMPILE", ((Enum<?>) result).name());
        }

        @Test
        @DisplayName("mixed case 'tEsT' is normalized to TEST")
        void mixedCaseTest() throws Exception {
            Object result = invokeParseDependencyScope("tEsT");
            assertNotNull(result, "mixed case 'tEsT' should resolve");
            assertEquals("TEST", ((Enum<?>) result).name());
        }

        @Test
        @DisplayName("lowercase 'runtime' is normalized to RUNTIME")
        void lowercaseRuntime() throws Exception {
            Object result = invokeParseDependencyScope("runtime");
            assertNotNull(result, "lowercase 'runtime' should resolve");
            assertEquals("RUNTIME", ((Enum<?>) result).name());
        }

        @Test
        @DisplayName("lowercase 'provided' is normalized to PROVIDED")
        void lowercaseProvided() throws Exception {
            Object result = invokeParseDependencyScope("provided");
            assertNotNull(result, "lowercase 'provided' should resolve");
            assertEquals("PROVIDED", ((Enum<?>) result).name());
        }

        @Test
        @DisplayName("unknown value returns null")
        void unknownReturnsNull() throws Exception {
            assertNull(invokeParseDependencyScope("UNKNOWN"));
        }

        @Test
        @DisplayName("empty string returns null")
        void emptyReturnsNull() throws Exception {
            assertNull(invokeParseDependencyScope(""));
        }

        @Test
        @DisplayName("random string returns null")
        void randomReturnsNull() throws Exception {
            assertNull(invokeParseDependencyScope("foobar"));
        }

        @Test
        @DisplayName("string with whitespace is not trimmed (returns null)")
        void whitespaceNotTrimmed() throws Exception {
            assertNull(invokeParseDependencyScope(" COMPILE "));
        }

        private Object invokeParseDependencyScope(String scopeStr) throws Exception {
            Method m = EditProjectStructureTool.class.getDeclaredMethod(
                "parseDependencyScope", String.class);
            m.setAccessible(true);
            return m.invoke(null, scopeStr);
        }
    }
}
