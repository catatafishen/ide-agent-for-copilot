package com.github.catatafishen.agentbridge.shim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the form-encoded argv parser used by {@link ShimController}.
 *
 * <p>The end-to-end HTTP path and MCP redirect path are exercised in
 * integration tests under {@code integration-tests}. Here we just guard the
 * pure parsing logic — broken argv decoding would silently corrupt every
 * command the agent runs.
 */
class ShimControllerTest {

    @Test
    void emptyBodyReturnsEmptyList() {
        assertTrue(ShimController.parseArgv("").isEmpty());
    }

    @Test
    void singleArgvParses() {
        assertEquals(List.of("cat"), ShimController.parseArgv("argv=cat"));
    }

    @Test
    void multipleArgvPreservesOrder() {
        assertEquals(
            List.of("grep", "-F", "needle", "haystack.txt"),
            ShimController.parseArgv("argv=grep&argv=-F&argv=needle&argv=haystack.txt")
        );
    }

    @Test
    void urlEncodedSpaceDecodes() {
        assertEquals(
            List.of("cat", "file with space.txt"),
            ShimController.parseArgv("argv=cat&argv=file+with+space.txt")
        );
    }

    @Test
    void percentEncodedBytesDecode() {
        // %26 = '&', %3D = '=' — must not split the parser
        assertEquals(
            List.of("echo", "a&b=c"),
            ShimController.parseArgv("argv=echo&argv=a%26b%3Dc")
        );
    }

    @Test
    void unrelatedFieldsAreIgnored() {
        assertEquals(
            List.of("cat", "foo"),
            ShimController.parseArgv("token=abc&argv=cat&meta=x&argv=foo")
        );
    }

    @Test
    void malformedPairsAreSkipped() {
        // No '=' at all → skip silently rather than crash
        assertEquals(
            List.of("cat"),
            ShimController.parseArgv("brokenPair&argv=cat")
        );
    }

    // ===== parseField =====

    @Test
    void parseFieldReturnsNullWhenAbsent() {
        assertNull(ShimController.parseField("argv=cat&argv=foo", "cwd"));
    }

    @Test
    void parseFieldReturnsNullForEmptyBody() {
        assertNull(ShimController.parseField("", "cwd"));
    }

    @Test
    void parseFieldDecodesValue() {
        assertEquals(
            "/home/user/my project",
            ShimController.parseField("argv=ls&cwd=%2Fhome%2Fuser%2Fmy+project", "cwd")
        );
    }

    @Test
    void parseFieldReturnsFirstWhenDuplicated() {
        assertEquals(
            "/a",
            ShimController.parseField("cwd=%2Fa&cwd=%2Fb", "cwd")
        );
    }

    @Test
    void parseFieldIgnoresMalformedPairs() {
        assertEquals(
            "/x",
            ShimController.parseField("brokenPair&cwd=%2Fx", "cwd")
        );
    }
}
