package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentBinaryResolver} — uses a test subclass to verify the
 * resolution order (custom path → primary → alternates) and the
 * {@link AgentBinaryResolver#withCustomPath(String)} override factory.
 */
class AgentBinaryResolverTest {

    // ── Custom path takes priority ─────────────────────────

    @Test
    void resolve_returnsCustomPathWhenSet() {
        var resolver = new TestResolver("/custom/bin/agent", "agent", "agent-alt");
        assertEquals("/custom/bin/agent", resolver.resolve());
    }

    @Test
    void resolve_returnsCustomPathEvenWhenNotExecutable() {
        // The custom path is trusted without filesystem validation
        var resolver = new TestResolver("/nonexistent/path/agent", "agent");
        assertEquals("/nonexistent/path/agent", resolver.resolve());
    }

    @Test
    void resolve_skipsEmptyCustomPath() {
        // Empty string should be treated as "not set"
        var resolver = new TestResolver("", "agent");
        // Without mocking BinaryDetector.findBinaryPath, this will fall through to null
        assertNull(resolver.resolve());
    }

    @Test
    void resolve_skipsNullCustomPath() {
        var resolver = new TestResolver(null, "agent");
        assertNull(resolver.resolve());
    }

    // ── withCustomPath override ────────────────────────────

    @Test
    void withCustomPath_overridesCustomBinaryPath() {
        var original = new TestResolver(null, "agent", "agent-alt");
        var overridden = original.withCustomPath("/override/path");
        assertEquals("/override/path", overridden.resolve());
    }

    @Test
    void withCustomPath_preservesPrimaryName() {
        var original = new TestResolver(null, "agent");
        var overridden = original.withCustomPath("/override");
        // The overridden resolver should still have the same primary name
        assertEquals("agent", invokeProtected(overridden, "primaryBinaryName"));
    }

    @Test
    void withCustomPath_preservesAlternateNames() {
        var original = new TestResolver(null, "agent", "alt1", "alt2");
        var overridden = original.withCustomPath("/override");
        String[] alts = invokeProtectedArray(overridden, "alternateNames");
        assertArrayEquals(new String[]{"alt1", "alt2"}, alts);
    }

    // ── alternateNames default ─────────────────────────────

    @Test
    void alternateNames_defaultsToEmptyArray() {
        var resolver = new AgentBinaryResolver() {
            @Override
            protected String customBinaryPath() { return null; }
            @Override
            protected String primaryBinaryName() { return "agent"; }
        };
        assertArrayEquals(new String[0], resolver.alternateNames());
    }

    // ── Helpers ────────────────────────────────────────────

    private static String invokeProtected(AgentBinaryResolver resolver, String methodName) {
        try {
            var m = AgentBinaryResolver.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            return (String) m.invoke(resolver);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String[] invokeProtectedArray(AgentBinaryResolver resolver, String methodName) {
        try {
            var m = AgentBinaryResolver.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            return (String[]) m.invoke(resolver);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Test subclass with controlled field values. Does NOT call
     * {@link BinaryDetector} for auto-detection — that static dependency
     * makes the detection branches untestable without a process mock.
     */
    private static final class TestResolver extends AgentBinaryResolver {
        private final String customPath;
        private final String primary;
        private final String[] alternates;

        TestResolver(String customPath, String primary, String... alternates) {
            this.customPath = customPath;
            this.primary = primary;
            this.alternates = alternates;
        }

        @Override
        protected String customBinaryPath() { return customPath; }

        @Override
        protected String primaryBinaryName() { return primary; }

        @Override
        protected String[] alternateNames() { return alternates; }
    }
}
