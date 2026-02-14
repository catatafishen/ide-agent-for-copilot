package com.github.copilot.intellij.bridge;

import org.junit.jupiter.api.*;

import java.io.*;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CopilotAcpClient.
 * 
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
        StringBuilder script = new StringBuilder();
        script.append("import sys, json\n");
        script.append("responses = ").append(toPythonList(responses)).append("\n");
        script.append("idx = 0\n");
        script.append("for line in sys.stdin:\n");
        script.append("    line = line.strip()\n");
        script.append("    if not line: continue\n");
        script.append("    msg = json.loads(line)\n");
        script.append("    if 'id' in msg and idx < len(responses):\n");
        script.append("        print(responses[idx], flush=True)\n");
        script.append("        idx += 1\n");
        script.append("    elif msg.get('method') == 'initialized':\n");
        script.append("        pass\n");

        ProcessBuilder pb = new ProcessBuilder("python", "-c", script.toString());
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
        model.id = "gpt-4.1";
        model.name = "GPT-4.1";
        model.description = "Fast model";
        model.usage = "0x";

        assertEquals("gpt-4.1", model.id);
        assertEquals("GPT-4.1", model.name);
        assertEquals("Fast model", model.description);
        assertEquals("0x", model.usage);
    }

    @Test
    void testAuthMethodDto() {
        CopilotAcpClient.AuthMethod auth = new CopilotAcpClient.AuthMethod();
        auth.id = "copilot-login";
        auth.name = "Log in with Copilot CLI";
        auth.command = "copilot.exe";
        auth.args = List.of("login");

        assertEquals("copilot-login", auth.id);
        assertEquals("copilot.exe", auth.command);
        assertEquals(1, auth.args.size());
        assertEquals("login", auth.args.get(0));
    }

    @Test
    void testClientIsNotHealthyBeforeStart() {
        CopilotAcpClient client = new CopilotAcpClient();
        assertFalse(client.isHealthy(), "Client should not be healthy before start");
    }

    @Test
    void testCloseIdempotent() {
        CopilotAcpClient client = new CopilotAcpClient();
        // Should not throw even if never started
        assertDoesNotThrow(client::close);
        assertDoesNotThrow(client::close);
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
            CopilotAcpClient.Model first = models.get(0);
            assertNotNull(first.id, "Model should have id");
            assertNotNull(first.name, "Model should have name");
            assertFalse(first.id.isEmpty());
        }

        @Test
        void testListModelsContainsKnownModels() throws CopilotException {
            List<CopilotAcpClient.Model> models = client.listModels();
            List<String> modelIds = models.stream().map(m -> m.id).toList();

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
            assertNotNull(auth.id);
            assertNotNull(auth.name);
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
                    .filter(m -> "0x".equals(m.usage) || "0.33x".equals(m.usage))
                    .findFirst()
                    .map(m -> m.id)
                    .orElse(models.get(models.size() - 1).id);

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
