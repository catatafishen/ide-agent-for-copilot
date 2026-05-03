package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Executes hook scripts with the stdin/stdout JSON protocol.
 * Handles timeout enforcement, failOpen behavior, and result parsing.
 */
public final class HookExecutor {

    private static final Logger LOG = Logger.getInstance(HookExecutor.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_HOOK_OUTPUT_CHARS = 16_000;

    private HookExecutor() {
    }

    /**
     * Runs a hook script and returns the parsed result.
     *
     * @param definition the hook definition
     * @param trigger    the trigger type being executed
     * @param payload    the JSON payload to send on stdin
     * @return the parsed hook result, or {@link HookResult.NoOp} if the hook produces no output
     * @throws HookExecutionException if the hook fails and is not failOpen
     */
    public static @NotNull HookResult execute(@NotNull HookDefinition definition,
                                              @NotNull HookTrigger trigger,
                                              @NotNull HookPayload payload) throws HookExecutionException {
        HookTriggerConfig config = definition.triggerConfig(trigger);
        if (config == null) return new HookResult.NoOp();

        Path scriptPath = definition.resolveScript(trigger);
        if (scriptPath == null) {
            LOG.warn("No script for OS in hook '" + definition.id() + "' trigger " + trigger.jsonKey());
            return new HookResult.NoOp();
        }

        try {
            String stdout = runScript(scriptPath, config, payload, definition.sourceDir());
            return parseResult(trigger, stdout);
        } catch (IOException e) {
            if (config.failOpen()) {
                LOG.warn("Hook '" + definition.id() + "' (" + trigger.jsonKey() + ") failed (failOpen=true): " + e.getMessage());
                return new HookResult.NoOp();
            }
            throw new HookExecutionException(definition.id(), trigger, e.getMessage(), e);
        }
    }

    private static @NotNull String runScript(@NotNull Path scriptPath,
                                             @NotNull HookTriggerConfig config,
                                             @NotNull HookPayload payload,
                                             @NotNull Path hookSourceDir) throws IOException {
        List<String> command = buildCommand(scriptPath);
        ProcessBuilder builder = new ProcessBuilder(command);

        Path workingDir = config.cwd() != null ? hookSourceDir.resolve(config.cwd()) : hookSourceDir;
        builder.directory(workingDir.toFile());

        if (!config.env().isEmpty()) {
            builder.environment().putAll(config.env());
        }

        Process process = builder.start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());

        try (var stdin = process.getOutputStream()) {
            stdin.write(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
        }

        boolean finished;
        try {
            finished = process.waitFor(config.timeoutSec(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Hook script interrupted", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Hook timed out after " + config.timeoutSec() + "s: " + scriptPath);
        }

        String out = await(stdout, "stdout");
        String err = await(stderr, "stderr");
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            String detail = formatOutput(out, err);
            throw new IOException("Hook exited with code " + exitCode + detail);
        }

        if (!err.isBlank()) {
            LOG.info("Hook stderr: " + truncate(err));
        }
        return out;
    }

    static @NotNull HookResult parseResult(@NotNull HookTrigger trigger, @Nullable String stdout) {
        if (stdout == null || stdout.isBlank()) return new HookResult.NoOp();

        String trimmed = stdout.trim();
        JsonElement element = parseJsonOrNull(trimmed);

        if (element == null || !element.isJsonObject()) {
            if (trigger == HookTrigger.POST || trigger == HookTrigger.ON_FAILURE) {
                return new HookResult.OutputModification(stripTrailingLineBreaks(stdout), null);
            }
            return new HookResult.NoOp();
        }

        JsonObject obj = element.getAsJsonObject();

        return switch (trigger) {
            case PERMISSION -> parsePermissionResult(obj);
            case PRE -> parsePreResult(obj);
            case POST, ON_FAILURE -> parseOutputResult(obj, stdout);
        };
    }

    private static @NotNull HookResult parsePermissionResult(@NotNull JsonObject obj) {
        if (!obj.has("decision")) return new HookResult.NoOp();
        String decision = obj.get("decision").getAsString();
        boolean allowed = "allow".equalsIgnoreCase(decision);
        String reason = obj.has("reason") ? obj.get("reason").getAsString() : null;
        return new HookResult.PermissionDecision(allowed, reason);
    }

    private static @NotNull HookResult parsePreResult(@NotNull JsonObject obj) {
        if (obj.has("error")) {
            JsonElement error = obj.get("error");
            String message = error.isJsonNull() ? "" : error.getAsString();
            if (message.isBlank()) {
                message = "Pre-hook stopped tool execution";
            }
            return new HookResult.PreHookFailure(message);
        }
        if (!obj.has("arguments")) return new HookResult.NoOp();
        return new HookResult.ModifiedArguments(obj.getAsJsonObject("arguments"));
    }

    private static @NotNull HookResult parseOutputResult(@NotNull JsonObject obj, @NotNull String rawStdout) {
        if (obj.has("output")) {
            JsonElement output = obj.get("output");
            String text = output.isJsonNull() ? "" : output.getAsString();
            return new HookResult.OutputModification(text, null);
        }
        if (obj.has("append")) {
            JsonElement append = obj.get("append");
            String text = append.isJsonNull() ? null : append.getAsString();
            return new HookResult.OutputModification(null, text);
        }
        return new HookResult.OutputModification(stripTrailingLineBreaks(rawStdout), null);
    }

    private static @NotNull List<String> buildCommand(@NotNull Path scriptPath) {
        List<String> cmd = new ArrayList<>();
        String fileName = scriptPath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".ps1")) {
            cmd.add("powershell");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-File");
        } else {
            cmd.add("/bin/sh");
        }
        cmd.add(scriptPath.toAbsolutePath().toString());
        return cmd;
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                byte[] bytes = stream.readAllBytes();
                String text = new String(bytes, StandardCharsets.UTF_8);
                return text.length() > MAX_HOOK_OUTPUT_CHARS
                    ? text.substring(0, MAX_HOOK_OUTPUT_CHARS)
                    : text;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static String await(CompletableFuture<String> future, String streamName) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted reading hook " + streamName, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException unchecked) throw unchecked.getCause();
            throw new IOException("Failed reading hook " + streamName, cause);
        }
    }

    private static @Nullable JsonElement parseJsonOrNull(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String stripTrailingLineBreaks(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String formatOutput(String stdout, String stderr) {
        String combined = (stdout + stderr).trim();
        if (combined.isEmpty()) return "";
        return ": " + truncate(combined);
    }

    private static String truncate(String text) {
        return text.length() > MAX_HOOK_OUTPUT_CHARS
            ? text.substring(0, MAX_HOOK_OUTPUT_CHARS) + "..."
            : text;
    }

    /**
     * Thrown when a hook script fails and is configured with {@code failOpen: false}.
     */
    public static final class HookExecutionException extends Exception {
        private final String hookId;
        private final HookTrigger trigger;

        public HookExecutionException(String hookId, HookTrigger trigger, String message, Throwable cause) {
            super("Hook '" + hookId + "' (" + trigger.jsonKey() + ") failed: " + message, cause);
            this.hookId = hookId;
            this.trigger = trigger;
        }

        public String hookId() {
            return hookId;
        }

        public HookTrigger trigger() {
            return trigger;
        }
    }
}
