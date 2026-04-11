package com.github.catatafishen.agentbridge.agent.claude;

import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AbstractClaudeAgentClientTest {

    /**
     * Minimal concrete subclass for testing.
     * normalizeToolName is a public instance method that doesn't use the registry field.
     */
    private static class TestableClient extends AbstractClaudeAgentClient {
        TestableClient() {
            super(null);
        }

        @Override
        public String agentId() {
            return "";
        }

        @Override
        public String displayName() {
            return "";
        }

        @Override
        public void start() throws Exception {

        }

        @Override
        public void stop() {

        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public String createSession(String cwd) throws Exception {
            return "";
        }

        @Override
        public void cancelSession(String sessionId) {

        }

        @Override
        public PromptResponse sendPrompt(PromptRequest request, Consumer<SessionUpdate> onUpdate) throws Exception {
            return null;
        }
    }

    private final TestableClient client = new TestableClient();

    // ── normalizeToolName ───────────────────────────────────────────────

    @Test
    void normalizeToolName_stripsMcpPrefix() {
        assertEquals("read_file", client.normalizeToolName("mcp__agentbridge__read_file"));
    }

    @Test
    void normalizeToolName_noPrefix() {
        assertEquals("read_file", client.normalizeToolName("read_file"));
    }

    @Test
    void normalizeToolName_partialPrefix() {
        assertEquals("mcp__other__tool", client.normalizeToolName("mcp__other__tool"));
    }

    @Test
    void normalizeToolName_onlyPrefix() {
        assertEquals("", client.normalizeToolName("mcp__agentbridge__"));
    }

    // ── isRateLimitError (protected static) ─────────────────────────────

    @Test
    void isRateLimitError_hitLimit() throws Exception {
        assertTrue(invokeIsRateLimitError("You hit your premium request limit"));
    }

    @Test
    void isRateLimitError_rateLimit() throws Exception {
        assertTrue(invokeIsRateLimitError("rate limit exceeded"));
    }

    @Test
    void isRateLimitError_rateLimitWithHyphen() throws Exception {
        assertTrue(invokeIsRateLimitError("rate-limit exceeded"));
    }

    @Test
    void isRateLimitError_usageLimit() throws Exception {
        assertTrue(invokeIsRateLimitError("Your usage limit has been reached"));
    }

    @Test
    void isRateLimitError_caseInsensitive() throws Exception {
        assertTrue(invokeIsRateLimitError("Rate Limit reached for this model"));
    }

    @Test
    void isRateLimitError_noMatch() throws Exception {
        assertFalse(invokeIsRateLimitError("connection refused"));
    }

    @Test
    void isRateLimitError_emptyString() throws Exception {
        assertFalse(invokeIsRateLimitError(""));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static boolean invokeIsRateLimitError(String errorText) throws Exception {
        Method m = AbstractClaudeAgentClient.class.getDeclaredMethod("isRateLimitError", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, errorText);
    }
}
