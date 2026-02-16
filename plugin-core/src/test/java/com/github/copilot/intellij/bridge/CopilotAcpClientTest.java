package com.github.copilot.intellij.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for CopilotAcpClient.
 * <p>
 * Tests are split into:
 * - Unit tests using a mock ACP process (always run)
 * - Integration tests using real copilot CLI (require copilot installed + authenticated)
 */
class CopilotAcpClientTest {

    // ========================
    // Unit tests with mock ACP server
    // ========================

    /**
     * Creates a mock ACP process that responds to JSON-RPC requests.
     * Simulates the copilot --acp protocol for testing.
     */
    private static Process startMockAcpProcess(String... responses) throws IOException {
        // Build a script that echoes predefined JSON-RPC responses for each line of input
        String script = "import sys, json\n" +
            "responses = " + toPythonList(responses) + "\n" +
            "idx = 0\n" +
            "for line in sys.stdin:\n" +
            "    line = line.strip()\n" +
            "    if not line: continue\n" +
            "    msg = json.loads(line)\n" +
            "    if 'id' in msg and idx < len(responses):\n" +
            "        print(responses[idx], flush=True)\n" +
            "        idx += 1\n" +
            "    elif msg.get('method') == 'initialized':\n" +
            "        pass\n";

        ProcessBuilder pb = new ProcessBuilder("python", "-c", script);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    private static String toPythonList(String[] items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(", ");
            // Escape for Python string
            sb.append("'").append(items[i].replace("'", "\\'").replace("\\", "\\\\")).append("'");
        }
        sb.append("]");
        return sb.toString();
    }

    @Test
    void testCopilotExceptionRecoverable() {
        CopilotException recoverable = new CopilotException("timeout", null, true);
        assertTrue(recoverable.isRecoverable());
        assertEquals("timeout", recoverable.getMessage());

        CopilotException nonRecoverable = new CopilotException("auth error", null, false);
        assertFalse(nonRecoverable.isRecoverable());
    }

    @Test
    void testCopilotExceptionDefaultRecoverable() {
        CopilotException ex = new CopilotException("test");
        assertTrue(ex.isRecoverable(), "Default should be recoverable");
    }

    @Test
    void testModelDto() {
        CopilotAcpClient.Model model = new CopilotAcpClient.Model();
        model.setId("gpt-4.1");
        model.setName("GPT-4.1");
        model.setDescription("Fast model");
        model.setUsage("0x");

        assertEquals("gpt-4.1", model.getId());
        assertEquals("GPT-4.1", model.getName());
        assertEquals("Fast model", model.getDescription());
        assertEquals("0x", model.getUsage());
    }

    @Test
    void testAuthMethodDto() {
        CopilotAcpClient.AuthMethod auth = new CopilotAcpClient.AuthMethod();
        auth.setId("copilot-login");
        auth.setName("Log in with Copilot CLI");
        auth.setCommand("copilot.exe");
        auth.setArgs(List.of("login"));

        assertEquals("copilot-login", auth.getId());
        assertEquals("copilot.exe", auth.getCommand());
        assertEquals(1, auth.getArgs().size());
        assertEquals("login", auth.getArgs().getFirst());
    }

    @Test
    void testClientIsNotHealthyBeforeStart() {
        try (CopilotAcpClient client = new CopilotAcpClient()) {
            assertFalse(client.isHealthy(), "Client should not be healthy before start");
        }
    }

    @Test
    void testCloseIdempotent() {
        try (CopilotAcpClient client = new CopilotAcpClient()) {
            // Should not throw even if never started
            assertDoesNotThrow(client::close);
            assertDoesNotThrow(client::close);
        }
    }

    // ========================
    // Integration tests (require copilot CLI installed + authenticated)
    // ========================

    private static boolean copilotAvailable() {
        try {
            Process p = new ProcessBuilder("where", "copilot").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Nested
    @Tag("integration")
    class IntegrationTests {

        private CopilotAcpClient client;

        @BeforeEach
        void setUp() throws Exception {
            Assumptions.assumeTrue(copilotAvailable(), "Copilot CLI not available, skipping integration tests");
            client = new CopilotAcpClient();
            client.start();
        }

        @AfterEach
        void tearDown() {
            if (client != null) {
                client.close();
            }
        }

        @Test
        void testInitializeAndHealthy() {
            assertTrue(client.isHealthy(), "Client should be healthy after start");
        }

        @Test
        void testCreateSession() throws CopilotException {
            String sessionId = client.createSession();
            assertNotNull(sessionId, "Session ID should not be null");
            assertFalse(sessionId.isEmpty(), "Session ID should not be empty");
        }

        @Test
        void testListModels() throws CopilotException {
            List<CopilotAcpClient.Model> models = client.listModels();
            assertNotNull(models);
            assertFalse(models.isEmpty(), "Should return at least one model");

            // Verify model structure
            CopilotAcpClient.Model first = models.getFirst();
            assertNotNull(first.getId(), "Model should have id");
            assertNotNull(first.getName(), "Model should have name");
            assertFalse(first.getId().isEmpty());
        }

        @Test
        void testListModelsContainsKnownModels() throws CopilotException {
            List<CopilotAcpClient.Model> models = client.listModels();
            List<String> modelIds = models.stream().map(CopilotAcpClient.Model::getId).toList();

            // At least some of these should be present
            boolean hasGpt = modelIds.stream().anyMatch(id -> id.startsWith("gpt-"));
            boolean hasClaude = modelIds.stream().anyMatch(id -> id.startsWith("claude-"));
            assertTrue(hasGpt || hasClaude, "Should have at least GPT or Claude models");
        }

        @Test
        void testGetAuthMethod() {
            CopilotAcpClient.AuthMethod auth = client.getAuthMethod();
            // Auth method should always be present from initialize
            assertNotNull(auth, "Auth method should not be null");
            assertNotNull(auth.getId());
            assertNotNull(auth.getName());
        }

        @Test
        void testSendPromptWithStreaming() throws CopilotException {
            String sessionId = client.createSession();
            StringBuilder accumulated = new StringBuilder();

            String stopReason = client.sendPrompt(sessionId,
                "Reply with exactly one word: hello", null, accumulated::append);

            assertNotNull(stopReason);
            assertEquals("end_turn", stopReason, "Should end normally");
            assertFalse(accumulated.toString().isEmpty(), "Should have received streaming content");
        }

        @Test
        void testSendPromptWithModelSelection() throws CopilotException {
            String sessionId = client.createSession();
            List<CopilotAcpClient.Model> models = client.listModels();
            // Pick the cheapest model
            String cheapModel = models.stream()
                .filter(m -> "0x".equals(m.getUsage()) || "0.33x".equals(m.getUsage()))
                .findFirst()
                .map(CopilotAcpClient.Model::getId)
                .orElse(models.getLast().getId());

            StringBuilder response = new StringBuilder();
            String stopReason = client.sendPrompt(sessionId,
                "Reply with exactly: ok", cheapModel, response::append);

            assertEquals("end_turn", stopReason);
            assertFalse(response.toString().isEmpty());
        }

        @Test
        void testMultiplePromptsInSameSession() throws CopilotException {
            String sessionId = client.createSession();

            StringBuilder r1 = new StringBuilder();
            client.sendPrompt(sessionId, "Say 'one'", null, r1::append);
            assertFalse(r1.toString().isEmpty(), "First response should not be empty");

            StringBuilder r2 = new StringBuilder();
            client.sendPrompt(sessionId, "Say 'two'", null, r2::append);
            assertFalse(r2.toString().isEmpty(), "Second response should not be empty");
        }

        @Test
        void testCloseAndRestart() throws CopilotException {
            assertTrue(client.isHealthy());
            client.close();
            assertFalse(client.isHealthy());

            // Create a new client
            client = new CopilotAcpClient();
            client.start();
            assertTrue(client.isHealthy());
        }
    }
}
