package com.github.catatafishen.agentbridge.psi.tools.project;

import com.intellij.openapi.roots.DependencyScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JUnit 5 tests for {@link EditProjectStructureTool}'s private static
 * {@code parseDependencyScope(String)} method, exercised via reflection.
 *
 * <p>Extracted from {@link EditProjectStructureToolTest} (which extends
 * {@link com.intellij.testFramework.fixtures.BasePlatformTestCase} / JUnit 3)
 * so these tests are reliably discovered by the JUnit 5 engine in CI.
 */
@DisplayName("EditProjectStructureTool.parseDependencyScope")
class ParseDependencyScopeTest {

    private static final Method PARSE_DEPENDENCY_SCOPE;

    static {
        try {
            PARSE_DEPENDENCY_SCOPE = EditProjectStructureTool.class
                .getDeclaredMethod("parseDependencyScope", String.class);
            PARSE_DEPENDENCY_SCOPE.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found — signature changed?", e);
        }
    }

    /**
     * Invoke the private static method, unwrapping {@link InvocationTargetException}
     * so tests see the real exception (e.g. {@link NullPointerException}).
     */
    private static DependencyScope invoke(String scopeStr) throws Exception {
        try {
            return (DependencyScope) PARSE_DEPENDENCY_SCOPE.invoke(null, scopeStr);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    // ── Standard uppercase values ────────────────────────────────────────

    @Test
    void compile_uppercase() throws Exception {
        assertEquals(DependencyScope.COMPILE, invoke("COMPILE"));
    }

    @Test
    void test_uppercase() throws Exception {
        assertEquals(DependencyScope.TEST, invoke("TEST"));
    }

    @Test
    void runtime_uppercase() throws Exception {
        assertEquals(DependencyScope.RUNTIME, invoke("RUNTIME"));
    }

    @Test
    void provided_uppercase() throws Exception {
        assertEquals(DependencyScope.PROVIDED, invoke("PROVIDED"));
    }

    // ── Case-insensitivity (toUpperCase) ─────────────────────────────────

    @Test
    void compile_lowercase() throws Exception {
        assertEquals(DependencyScope.COMPILE, invoke("compile"));
    }

    @Test
    void test_lowercase() throws Exception {
        assertEquals(DependencyScope.TEST, invoke("test"));
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    void emptyString_returnsNull() throws Exception {
        assertNull(invoke(""));
    }

    @Test
    void nullInput_throwsNpe() {
        assertThrows(NullPointerException.class, () -> invoke(null));
    }

    @Test
    void invalidScope_returnsNull() throws Exception {
        assertNull(invoke("INVALID_SCOPE"));
    }
}
