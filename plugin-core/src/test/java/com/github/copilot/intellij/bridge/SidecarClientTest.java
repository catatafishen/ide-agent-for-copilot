package com.github.copilot.intellij.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SidecarClientTest {
    
    private SidecarClient client;
    private Process mockServerProcess;
    private int testPort;
    
    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // Find available port
        try (ServerSocket socket = new ServerSocket(0)) {
            testPort = socket.getLocalPort();
        }
        
        // Start mock sidecar server for testing
        // Navigate up from plugin-core/build/classes/... to project root
        String projectDir = new File(System.getProperty("user.dir")).getParentFile().getAbsolutePath();
        String sidecarPath = projectDir + File.separator + "copilot-bridge" + File.separator + "bin" + File.separator + "copilot-sidecar.exe";
        
        if (!new File(sidecarPath).exists()) {
            throw new FileNotFoundException("Sidecar binary not found at: " + sidecarPath + ". Run 'go build' first.");
        }
        
        ProcessBuilder pb = new ProcessBuilder(sidecarPath, "--port", String.valueOf(testPort));
        mockServerProcess = pb.start();
        
        // Wait for server to start
        Thread.sleep(2000);
        
        client = new SidecarClient("http://localhost:" + testPort);
    }
    
    @AfterEach
    void tearDown() {
        if (mockServerProcess != null && mockServerProcess.isAlive()) {
            mockServerProcess.destroy();
        }
    }
    
    @Test
    void testHealthCheck() throws SidecarException {
        boolean healthy = client.healthCheck();
        assertTrue(healthy, "Sidecar should be healthy");
    }
    
    @Test
    void testListModels() throws SidecarException {
        List<SidecarClient.Model> models = client.listModels();
        
        assertNotNull(models, "Models list should not be null");
        assertEquals(5, models.size(), "Should return 5 models");
        
        SidecarClient.Model firstModel = models.get(0);
        assertNotNull(firstModel.id, "Model should have id");
        assertNotNull(firstModel.name, "Model should have name");
        assertTrue(firstModel.name.contains("Mock"), 
                  "Model name should contain 'Mock'");
    }
    
    @Test
    void testCreateSession() throws SidecarException {
        SidecarClient.SessionResponse session = client.createSession();
        
        assertNotNull(session, "Session should not be null");
        assertNotNull(session.sessionId, "Session should have sessionId");
        assertNotNull(session.createdAt, "Session should have createdAt");
        
        assertFalse(session.sessionId.isEmpty(), "Session ID should not be empty");
    }
    
    @Test
    void testSendMessage() throws SidecarException {
        // First create a session
        SidecarClient.SessionResponse session = client.createSession();
        String sessionId = session.sessionId;
        
        // Send a message
        SidecarClient.MessageResponse response = client.sendMessage(sessionId, "Test prompt", "gpt-4o");
        
        assertNotNull(response, "Response should not be null");
        assertNotNull(response.messageId, "Response should have messageId");
        assertNotNull(response.streamUrl, "Response should have streamUrl");
    }
    
    @Test
    void testCloseSession() throws SidecarException {
        // Create a session
        SidecarClient.SessionResponse session = client.createSession();
        String sessionId = session.sessionId;
        
        // Close it (returns void, so just check no exception)
        assertDoesNotThrow(() -> client.closeSession(sessionId), 
                          "Should close session without exception");
    }
    
    @Test
    void testSendMessageToNonExistentSession() {
        assertThrows(SidecarException.class, () -> {
            client.sendMessage("non-existent-session", "Test", "gpt-4o");
        }, "Should throw exception for non-existent session");
    }
}
