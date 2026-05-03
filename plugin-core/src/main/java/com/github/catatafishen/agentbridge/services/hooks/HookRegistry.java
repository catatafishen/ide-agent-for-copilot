package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and caches {@link HookDefinition}s from the project's hooks directory.
 * Scans for {@code hook.json} files in immediate subdirectories of the hooks root.
 *
 * <p>The hooks directory defaults to {@code hooks/} in the project root but can be
 * overridden in project settings.
 */
public final class HookRegistry {

    private static final Logger LOG = Logger.getInstance(HookRegistry.class);
    private static final String HOOK_DEFINITION_FILE = "hook.json";
    private static final String HOOKS_JSON_KEY = "hooks";
    static final String DEFAULT_HOOKS_DIR = "hooks";

    private final Project project;
    private final ConcurrentHashMap<String, List<HookDefinition>> hooksByTool = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public HookRegistry(@NotNull Project project) {
        this.project = project;
    }

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static HookRegistry getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(HookRegistry.class);
    }

    /**
     * Returns hook definitions that apply to the given tool and trigger, or an empty list.
     */
    public @NotNull List<HookDefinition> findHooks(@NotNull String toolId, @NotNull HookTrigger trigger) {
        ensureLoaded();
        List<HookDefinition> forTool = hooksByTool.getOrDefault(toolId, List.of());
        return forTool.stream()
            .filter(def -> def.triggerConfig(trigger) != null)
            .toList();
    }

    /**
     * Returns the first hook definition that applies to the given tool and trigger, or null.
     * Per the design, there should be at most one hook per tool per trigger.
     */
    public @Nullable HookDefinition findHook(@NotNull String toolId, @NotNull HookTrigger trigger) {
        List<HookDefinition> hooks = findHooks(toolId, trigger);
        return hooks.isEmpty() ? null : hooks.getFirst();
    }

    /**
     * Returns all loaded hook definitions.
     */
    public @NotNull List<HookDefinition> getAllHooks() {
        ensureLoaded();
        List<HookDefinition> all = new ArrayList<>();
        hooksByTool.values().forEach(all::addAll);
        return all.stream().distinct().toList();
    }

    public void reload() {
        synchronized (this) {
            hooksByTool.clear();
            loaded = false;
            doLoad();
            loaded = true;
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            doLoad();
            loaded = true;
        }
    }

    private void doLoad() {
        Path hooksDir = resolveHooksDirectory();
        if (hooksDir == null || !Files.isDirectory(hooksDir)) return;

        try (DirectoryStream<Path> subdirs = Files.newDirectoryStream(hooksDir, Files::isDirectory)) {
            for (Path subdir : subdirs) {
                Path hookFile = subdir.resolve(HOOK_DEFINITION_FILE);
                if (Files.isRegularFile(hookFile)) {
                    loadHookFile(hookFile);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan hooks directory: " + hooksDir, e);
        }
    }

    private void loadHookFile(@NotNull Path hookFile) {
        try (Reader reader = Files.newBufferedReader(hookFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            HookDefinition definition = parseDefinition(root, hookFile.getParent());

            for (String toolId : definition.tools()) {
                hooksByTool.computeIfAbsent(toolId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(definition);
            }
            LOG.info("Loaded hook '" + definition.id() + "' for tools: " + definition.tools());
        } catch (Exception e) {
            LOG.warn("Failed to load hook definition: " + hookFile, e);
        }
    }

    static @NotNull HookDefinition parseDefinition(@NotNull JsonObject root, @NotNull Path sourceDir) {
        int version = root.has("version") ? root.get("version").getAsInt() : 1;
        String id = root.get("id").getAsString();
        String name = root.get("name").getAsString();
        String description = root.has("description") ? root.get("description").getAsString() : null;

        List<String> tools = new ArrayList<>();
        JsonArray toolsArray = root.getAsJsonArray("tools");
        for (JsonElement elem : toolsArray) {
            tools.add(elem.getAsString());
        }

        Map<HookTrigger, HookTriggerConfig> hooksMap = new HashMap<>();
        JsonObject hooksObj = root.getAsJsonObject(HOOKS_JSON_KEY);
        for (String triggerKey : hooksObj.keySet()) {
            HookTrigger trigger = HookTrigger.fromJsonKey(triggerKey);
            JsonObject triggerObj = hooksObj.getAsJsonObject(triggerKey);
            hooksMap.put(trigger, parseTriggerConfig(triggerObj));
        }

        return new HookDefinition(version, id, name, description, List.copyOf(tools), Map.copyOf(hooksMap), sourceDir);
    }

    private static @NotNull HookTriggerConfig parseTriggerConfig(@NotNull JsonObject obj) {
        String bash = obj.has("bash") ? obj.get("bash").getAsString() : null;
        String powershell = obj.has("powershell") ? obj.get("powershell").getAsString() : null;
        int timeoutSec = obj.has("timeoutSec") ? obj.get("timeoutSec").getAsInt() : 10;
        boolean failOpen = !obj.has("failOpen") || obj.get("failOpen").getAsBoolean();

        Map<String, String> env = new HashMap<>();
        if (obj.has("env")) {
            JsonObject envObj = obj.getAsJsonObject("env");
            for (String key : envObj.keySet()) {
                env.put(key, envObj.get(key).getAsString());
            }
        }

        String cwd = obj.has("cwd") ? obj.get("cwd").getAsString() : null;
        return new HookTriggerConfig(bash, powershell, timeoutSec, failOpen, Map.copyOf(env), cwd);
    }

    @Nullable Path resolveHooksDirectory() {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return Path.of(basePath, DEFAULT_HOOKS_DIR);
    }
}
