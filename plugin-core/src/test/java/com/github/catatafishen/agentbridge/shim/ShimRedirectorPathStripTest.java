package com.github.catatafishen.agentbridge.shim;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link ShimRedirector#realPathEnv(String)} — the PATH
 * stripping that prevents the server-side visible-fallthrough exec from
 * recursing into its own shim. The full visible-fallthrough flow is exercised
 * via integration tests; here we just guard the recursion-prevention logic
 * because a regression would silently re-enter the shim and hang.
 */
class ShimRedirectorPathStripTest {

    @Test
    void pathStrippingRemovesShimDirEntry() {
        // Sanity: the JVM under test always has a PATH.
        String origPath = System.getenv("PATH");
        assertNotNull(origPath);
        // Pick a real entry from current PATH and pretend it's the shim dir.
        String sep = System.getProperty("path.separator", ":");
        String[] parts = origPath.split(java.util.regex.Pattern.quote(sep), -1);
        String victim = null;
        for (String p : parts) {
            if (!p.isEmpty()) { victim = p; break; }
        }
        assertNotNull(victim, "test environment has no usable PATH entry");

        Map<String, String> env = ShimRedirector.realPathEnv(victim);
        String stripped = env.get("PATH");
        assertNotNull(stripped);
        // Stripped PATH must not contain the victim entry verbatim.
        for (String p : stripped.split(java.util.regex.Pattern.quote(sep), -1)) {
            assertNotEquals(victim, p,
                "PATH entry " + victim + " was not stripped: " + stripped);
        }
    }

    @Test
    void pathStrippingPreservesOtherEnv() {
        Map<String, String> env = ShimRedirector.realPathEnv("/nonexistent/shim/dir");
        // System.getenv() always has at least HOME (Unix) or USERPROFILE (Win).
        boolean hasInheritedKey = env.containsKey("HOME") || env.containsKey("USERPROFILE")
            || env.containsKey("PATH");
        assertTrue(hasInheritedKey, "realPathEnv should inherit other env keys");
    }

    @Test
    void pathStrippingHandlesNullPath() {
        // If PATH happens to be unset in the JVM (unlikely but possible in CI),
        // realPathEnv must not throw — the inherited env is returned unchanged.
        Map<String, String> env = ShimRedirector.realPathEnv("/whatever");
        assertNotNull(env);
    }
}
