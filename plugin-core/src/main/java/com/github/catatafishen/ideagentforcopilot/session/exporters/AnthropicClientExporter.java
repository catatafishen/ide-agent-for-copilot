package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class AnthropicClientExporter {

    private static final Logger LOG = Logger.getInstance(AnthropicClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_MAX_TOKEN_ESTIMATE = 20_000;

    private AnthropicClientExporter() {
    }

    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath) throws IOException {
        exportToFile(messages, targetPath, DEFAULT_MAX_TOKEN_ESTIMATE);
    }

    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath,
        int maxTokenEstimate) throws IOException {

        List<SessionMessage> budgeted = applyTokenBudget(messages, maxTokenEstimate);
        List<AnthropicMessage> anthropicMessages = toAnthropicMessages(budgeted);

        StringBuilder sb = new StringBuilder();
        for (AnthropicMessage msg : anthropicMessages) {
            sb.append(msg.toJsonLine()).append('\n');
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            parent.toFile().mkdirs();
        }
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @NotNull
    private static List<SessionMessage> applyTokenBudget(
        @NotNull List<SessionMessage> messages,
        int maxTokenEstimate) {

        if (messages.isEmpty()) return messages;

        int budget = maxTokenEstimate;
        List<Boolean> keep = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            keep.add(false);
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            SessionMessage msg = messages.get(i);
            int cost = estimateTokens(msg);
            if (budget <= 0) break;
            keep.set(i, true);
            budget -= cost;
        }

        List<SessionMessage> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keep.get(i)) result.add(messages.get(i));
        }
        return result;
    }

    private static int estimateTokens(@NotNull SessionMessage msg) {
        int total = 0;
        for (JsonObject part : msg.parts) {
            String type = part.has("type") ? part.get("type").getAsString() : "";
            if ("text".equals(type) || "reasoning".equals(type)) {
                String text = part.has("text") ? part.get("text").getAsString() : "";
                total += text.length() / 4;
            } else if ("tool-invocation".equals(type) && part.has("toolInvocation")) {
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                if (inv.has("result")) {
                    total += inv.get("result").getAsString().length() / 4;
                }
                if (inv.has("args")) {
                    total += inv.get("args").getAsString().length() / 4;
                }
            }
        }
        return Math.max(total, 1);
    }

    @NotNull
    private static List<AnthropicMessage> toAnthropicMessages(@NotNull List<SessionMessage> messages) {
        List<AnthropicMessage> result = new ArrayList<>();

        for (SessionMessage msg : messages) {
            if ("separator".equals(msg.role)) continue;

            if ("user".equals(msg.role)) {
                List<JsonObject> blocks = new ArrayList<>();
                for (JsonObject part : msg.parts) {
                    String type = part.has("type") ? part.get("type").getAsString() : "";
                    if ("text".equals(type)) {
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "text");
                        block.addProperty("text", part.has("text") ? part.get("text").getAsString() : "");
                        blocks.add(block);
                    }
                }
                if (!blocks.isEmpty()) {
                    result.add(new AnthropicMessage("user", blocks));
                }

            } else if ("assistant".equals(msg.role)) {
                List<JsonObject> assistantBlocks = new ArrayList<>();
                List<AnthropicMessage> toolResultMessages = new ArrayList<>();

                for (JsonObject part : msg.parts) {
                    String type = part.has("type") ? part.get("type").getAsString() : "";

                    if ("text".equals(type)) {
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "text");
                        block.addProperty("text", part.has("text") ? part.get("text").getAsString() : "");
                        assistantBlocks.add(block);

                    } else if ("reasoning".equals(type)) {
                        continue;

                    } else if ("tool-invocation".equals(type) && part.has("toolInvocation")) {
                        JsonObject inv = part.getAsJsonObject("toolInvocation");
                        String state = inv.has("state") ? inv.get("state").getAsString() : "call";

                        if (!"result".equals(state)) continue;

                        String toolCallId = inv.has("toolCallId") ? inv.get("toolCallId").getAsString() : "";
                        String toolName = inv.has("toolName") ? inv.get("toolName").getAsString() : "unknown";
                        String argsStr = inv.has("args") ? inv.get("args").getAsString() : "{}";
                        String resultStr = inv.has("result") ? inv.get("result").getAsString() : "";

                        JsonObject inputObj;
                        try {
                            inputObj = JsonParser.parseString(argsStr).getAsJsonObject();
                        } catch (Exception e) {
                            LOG.warn("Could not parse tool args as JSON object, wrapping as string: " + argsStr);
                            inputObj = new JsonObject();
                            inputObj.addProperty("_raw", argsStr);
                        }

                        JsonObject toolUseBlock = new JsonObject();
                        toolUseBlock.addProperty("type", "tool_use");
                        toolUseBlock.addProperty("id", toolCallId);
                        toolUseBlock.addProperty("name", toolName);
                        toolUseBlock.add("input", inputObj);
                        assistantBlocks.add(toolUseBlock);

                        JsonObject toolResultBlock = new JsonObject();
                        toolResultBlock.addProperty("type", "tool_result");
                        toolResultBlock.addProperty("tool_use_id", toolCallId);
                        toolResultBlock.addProperty("content", resultStr);
                        toolResultMessages.add(new AnthropicMessage("user", List.of(toolResultBlock)));

                    } else if ("subagent".equals(type) || "status".equals(type) || "file".equals(type)) {
                        continue;
                    }
                }

                if (!assistantBlocks.isEmpty()) {
                    result.add(new AnthropicMessage("assistant", assistantBlocks));
                    result.addAll(toolResultMessages);
                }
            }
        }

        return result;
    }

    private static final class AnthropicMessage {
        private final String role;
        private final List<JsonObject> contentBlocks;

        AnthropicMessage(@NotNull String role, @NotNull List<JsonObject> contentBlocks) {
            this.role = role;
            this.contentBlocks = List.copyOf(contentBlocks);
        }

        @NotNull
        String toJsonLine() {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", role);
            var contentArray = new com.google.gson.JsonArray();
            contentBlocks.forEach(contentArray::add);
            obj.add("content", contentArray);
            return GSON.toJson(obj);
        }
    }
}
