package com.github.catatafishen.ideagentforcopilot.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that use the real Copilot CLI but select the free model (0x usage)
 * to avoid incurring API costs.
 * <p>
 * Requires: Copilot CLI installed and authenticated (copilot --acp).
 * These tests are tagged as "integration" and skipped when CLI is unavailable.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CopilotFreeModelIntegrationTest {

    private CopilotAcpClient client;
    private String sessionId;
    private String freeModelId;

    private static boolean copilotAvailable() {
        try {
            // Try Linux/Mac first
            Process p = new ProcessBuilder("which", "copilot").start();
            if (p.waitFor() == 0) return true;
        } catch (Exception ignored) {
            // copilot CLI detection is best-effort
        }
        try {
            // Try Windows
            Process p = new ProcessBuilder("where", "copilot").start();
            return p.waitFor() == 0;
        } catch (
            Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(copilotAvailable(), "Copilot CLI not available");
        client = new CopilotAcpClient(null);
        client.start();
        sessionId = client.createSession();

        List<CopilotAcpClient.Model> models = client.listModels();
        // Select free model (0x) or cheapest available
        freeModelId = models.stream()
            .filter(m -> "0x".equals(m.getUsage()))
            .findFirst()
            .or(() -> models.stream().filter(m -> "0.33x".equals(m.getUsage())).findFirst())
            .map(CopilotAcpClient.Model::getId)
            .orElse(null);

        Assumptions.assumeTrue(freeModelId != null,
            "No free model available, skipping to avoid costs");
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    @Test
    @Order(1)
    void testFreeModelExists() throws Exception {
        List<CopilotAcpClient.Model> models = client.listModels();
        CopilotAcpClient.Model freeModel = models.stream()
            .filter(m -> m.getId().equals(freeModelId))
            .findFirst().orElse(null);

        assertNotNull(freeModel, "Free model should exist");
        assertEquals("0x", freeModel.getUsage(), "Free model usage should be 0x");
        assertNotNull(freeModel.getName(), "Free model should have a name");
    }

    @Test
    @Order(2)
    void testSimplePromptWithFreeModel() throws Exception {
        StringBuilder response = new StringBuilder();
        String stopReason = client.sendPrompt(sessionId,
            "Reply with exactly one word: yes",
            freeModelId, response::append);

        assertEquals("end_turn", stopReason, "Should end normally");
        assertFalse(response.toString().isBlank(), "Should have response content");
    }

    @Test
    @Order(3)
    void testStreamingChunksArriveInOrder() throws Exception {
        StringBuilder accumulated = new StringBuilder();
        AtomicInteger chunkCount = new AtomicInteger(0);

        String stopReason = client.sendPrompt(sessionId,
            "Count from 1 to 5, each number on a new line",
            freeModelId, chunk -> {
                chunkCount.incrementAndGet();
                accumulated.append(chunk);
            });

        assertEquals("end_turn", stopReason);
        assertTrue(chunkCount.get() > 0, "Should have received streaming chunks");
        String text = accumulated.toString();
        assertTrue(text.contains("1"), "Response should contain '1'");
        assertTrue(text.contains("5"), "Response should contain '5'");
    }

    @Test
    @Order(4)
    void testMultiTurnConversation() throws Exception {
        StringBuilder r1 = new StringBuilder();
        client.sendPrompt(sessionId,
            "Remember this number: 42. Reply with just 'ok'",
            freeModelId, r1::append);
        assertFalse(r1.toString().isBlank());

        StringBuilder r2 = new StringBuilder();
        client.sendPrompt(sessionId,
            "What number did I ask you to remember? Reply with just the number.",
            freeModelId, r2::append);
        assertTrue(r2.toString().contains("42"),
            "Model should remember context from previous turn");
    }

    @Test
    @Order(5)
    void testNewSessionAfterClose() throws Exception {
        client.close();
        assertFalse(client.isHealthy());

        // Create new client
        client = new CopilotAcpClient(null);
        client.start();
        assertTrue(client.isHealthy());

        sessionId = client.createSession();
        assertNotNull(sessionId);

        // Re-resolve free model
        List<CopilotAcpClient.Model> models = client.listModels();
        freeModelId = models.stream()
            .filter(m -> "0x".equals(m.getUsage()))
            .findFirst()
            .map(CopilotAcpClient.Model::getId)
            .orElse(null);
        Assumptions.assumeTrue(freeModelId != null);

        StringBuilder response = new StringBuilder();
        client.sendPrompt(sessionId, "Say hello", freeModelId, response::append);
        assertFalse(response.toString().isBlank());
    }

    @Test
    @Order(6)
    void testMultipleModelsAvailable() throws Exception {
        List<CopilotAcpClient.Model> models = client.listModels();

        assertTrue(models.size() >= 2, "Should have multiple models available");

        // All models should have required fields
        for (CopilotAcpClient.Model model : models) {
            assertNotNull(model.getId(), "Model id required");
            assertFalse(model.getId().isEmpty(), "Model id should not be empty");
            assertNotNull(model.getName(), "Model name required");
        }
    }

    @Test
    @Order(7)
    void testCodeGenerationPrompt() throws Exception {
        StringBuilder response = new StringBuilder();
        client.sendPrompt(sessionId,
            "Write a one-line Java hello world program. Reply with only the code, no explanation.",
            freeModelId, response::append);

        String code = response.toString();
        assertFalse(code.isBlank());
        // Should contain some Java keywords
        assertTrue(code.contains("System") || code.contains("print") || code.contains("class"),
            "Response should contain Java code");
    }

    @Test
    @Order(8)
    void testEmptyResponseHandling() throws Exception {
        // Ask for minimal response
        String stopReason = client.sendPrompt(sessionId,
            "Reply with an empty response - literally nothing",
            freeModelId, text -> {
            });

        // Should complete without error regardless of content
        assertNotNull(stopReason);
    }
}
