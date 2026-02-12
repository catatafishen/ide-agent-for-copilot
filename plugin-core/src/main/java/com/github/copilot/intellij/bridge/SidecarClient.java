package com.github.copilot.intellij.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * HTTP client for communicating with the Go sidecar via JSON-RPC 2.0.
 */
public class SidecarClient {
    private static final Logger LOG = Logger.getInstance(SidecarClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson;
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    public SidecarClient(@NotNull String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
    }

    /**
     * Check if sidecar is healthy.
     */
    public boolean healthCheck() throws SidecarException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;

        } catch (IOException | InterruptedException e) {
            throw new SidecarException("Health check failed", e);
        }
    }

    /**
     * Create a new Copilot session.
     */
    @NotNull
    public SessionResponse createSession() throws SidecarException {
        JsonObject params = new JsonObject();
        JsonObject response = sendRpc("session.create", params);
        return gson.fromJson(response, SessionResponse.class);
    }

    /**
     * Close an existing session.
     */
    public void closeSession(@NotNull String sessionId) throws SidecarException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        sendRpc("session.close", params);
    }

    /**
     * Send a message to the agent (convenience method).
     */
    @NotNull
    public MessageResponse sendMessage(@NotNull String sessionId, @NotNull String prompt, @NotNull String model) 
            throws SidecarException {
        SendMessageRequest request = new SendMessageRequest(sessionId, prompt, null, model);
        return sendMessage(request);
    }

    /**
     * Send a message to the agent.
     */
    @NotNull
    public MessageResponse sendMessage(@NotNull SendMessageRequest request) throws SidecarException {
        JsonObject params = gson.toJsonTree(request).getAsJsonObject();
        JsonObject response = sendRpc("session.send", params);
        return gson.fromJson(response, MessageResponse.class);
    }

    /**
     * List available models.
     */
    @NotNull
    public List<Model> listModels() throws SidecarException {
        JsonObject params = new JsonObject();
        JsonObject response = sendRpc("models.list", params);
        ModelsResponse modelsResponse = gson.fromJson(response, ModelsResponse.class);
        return modelsResponse.models;
    }

    /**
     * Send a JSON-RPC request to the sidecar.
     */
    @NotNull
    private JsonObject sendRpc(@NotNull String method, @NotNull JsonObject params) throws SidecarException {
        try {
            // Build JSON-RPC request
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", requestIdCounter.getAndIncrement());
            request.addProperty("method", method);
            request.add("params", params);

            String requestBody = gson.toJson(request);
            LOG.debug("RPC request: " + method + " - " + requestBody);

            // Send HTTP POST
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rpc"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {
                throw new SidecarException("HTTP error: " + httpResponse.statusCode());
            }

            // Parse JSON-RPC response
            JsonObject response = gson.fromJson(httpResponse.body(), JsonObject.class);
            LOG.debug("RPC response: " + response);

            if (response.has("error")) {
                JsonObject error = response.getAsJsonObject("error");
                String errorMessage = error.has("message") 
                    ? error.get("message").getAsString() 
                    : "Unknown error";
                throw new SidecarException("RPC error: " + errorMessage, null, false);
            }

            return response.getAsJsonObject("result");

        } catch (IOException | InterruptedException e) {
            throw new SidecarException("RPC call failed: " + method, e);
        }
    }

    /**
     * Stream events from a session using Server-Sent Events (SSE).
     * Blocks until stream completes or is interrupted.
     * 
     * @param sessionId The session ID to stream from
     * @param onChunk Callback for each chunk received
     * @throws SidecarException if streaming fails
     */
    public void streamResponse(@NotNull String sessionId, @NotNull Consumer<String> onChunk) 
            throws SidecarException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/stream/" + sessionId))
                    .timeout(Duration.ofMinutes(5)) // Longer timeout for streaming
                    .GET()
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                request, 
                HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() != 200) {
                throw new SidecarException("Stream error: HTTP " + response.statusCode());
            }

            // Read SSE stream line by line
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // SSE format: "data: <json>\n\n"
                    if (line.startsWith("data: ")) {
                        String jsonData = line.substring(6); // Remove "data: " prefix
                        onChunk.accept(jsonData);
                    } else if (line.startsWith("event: ")) {
                        String eventType = line.substring(7);
                        LOG.debug("SSE event: " + eventType);
                        if ("done".equals(eventType)) {
                            break; // Stream complete
                        }
                    }
                }
            }

        } catch (IOException | InterruptedException e) {
            throw new SidecarException("Stream failed", e);
        }
    }

    // Response DTOs
    public static class SessionResponse {
        public String sessionId;
        public String createdAt;
    }

    public static class MessageResponse {
        public String messageId;
        public String streamUrl;
    }

    public static class SendMessageRequest {
        public String sessionId;
        public String prompt;
        public List<ContextItem> context;
        public String model;

        public SendMessageRequest(String sessionId, String prompt, List<ContextItem> context, String model) {
            this.sessionId = sessionId;
            this.prompt = prompt;
            this.context = context;
            this.model = model;
        }
    }

    public static class ContextItem {
        public String file;
        public int startLine;
        public int endLine;
        public String content;
        public String symbol;
    }

    public static class Model {
        public String id;
        public String name;
        public List<String> capabilities;
        public int contextWindow;
    }

    private static class ModelsResponse {
        public List<Model> models;
    }
}
