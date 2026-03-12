package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReadIdeLogTool extends InfrastructureTool {

    private static final String IDEA_LOG_FILENAME = "idea.log";
    private static final String PARAM_LINES = "lines";
    private static final String PARAM_FILTER = "filter";
    private static final String PARAM_LEVEL = "level";

    public ReadIdeLogTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "read_ide_log";
    }

    @Override
    public @NotNull String displayName() {
        return "Read IDE Log";
    }

    @Override
    public @NotNull String description() {
        return "Read recent IntelliJ IDE log entries, optionally filtered by level or text";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_LINES, TYPE_INTEGER, "Number of recent lines to return (default: 50)"},
            {PARAM_FILTER, TYPE_STRING, "Only return lines containing this text"},
            {PARAM_LEVEL, TYPE_STRING, "Filter by log level: INFO, WARN, ERROR"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws IOException {
        int lines = args.has(PARAM_LINES) ? args.get(PARAM_LINES).getAsInt() : 50;
        String filter = args.has(PARAM_FILTER) ? args.get(PARAM_FILTER).getAsString() : null;
        String level = args.has(PARAM_LEVEL) ? args.get(PARAM_LEVEL).getAsString().toUpperCase() : null;

        Path logFile = findIdeLogFile();
        if (logFile == null) {
            return "Could not locate idea.log";
        }

        List<String> filtered = Files.readAllLines(logFile);

        if (level != null) {
            final String lvl = level;
            filtered = filtered.stream()
                .filter(l -> l.contains(lvl))
                .toList();
        }
        if (filter != null) {
            final String f = filter;
            filtered = filtered.stream()
                .filter(l -> l.contains(f))
                .toList();
        }

        int start = Math.max(0, filtered.size() - lines);
        List<String> result = filtered.subList(start, filtered.size());
        return String.join("\n", result);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    private static @Nullable Path findIdeLogFile() {
        Path logFile = Path.of(System.getProperty("idea.log.path", ""), IDEA_LOG_FILENAME);
        if (Files.exists(logFile)) return logFile;

        String logDir = System.getProperty("idea.system.path");
        if (logDir != null) {
            logFile = Path.of(logDir, "..", "log", IDEA_LOG_FILENAME);
            if (Files.exists(logFile)) return logFile;
        }

        try {
            Class<?> pm = Class.forName("com.intellij.openapi.application.PathManager");
            String logPath = (String) pm.getMethod("getLogPath").invoke(null);
            logFile = Path.of(logPath, IDEA_LOG_FILENAME);
            if (Files.exists(logFile)) return logFile;
        } catch (Exception ignored) {
            // PathManager not available or reflection failed
        }

        return null;
    }
}
