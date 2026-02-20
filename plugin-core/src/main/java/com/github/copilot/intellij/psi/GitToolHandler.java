package com.github.copilot.intellij.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles all git-related tool calls for the PSI Bridge.
 */
final class GitToolHandler {
    private static final String ERROR_PATH_REQUIRED = "Error: 'path' parameter is required";

    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_STAT_ONLY = "stat_only";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_METHOD = "method";
    private static final String GIT_FLAG_ALL = "--all";

    private static final String JSON_PATHS = "paths";
    private static final String JSON_ACTION = "action";
    private static final String JSON_STASH = "stash";
    private static final String JSON_INDEX = "index";
    private static final String JSON_STASH_PREFIX = "stash@{";
    private static final String JSON_APPLY = "apply";

    private static final String STATUS_PARAM = "status";

    private final Project project;

    GitToolHandler(Project project) {
        this.project = project;
    }

    private String runGit(String... args) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: no project base path";

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("--no-pager");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(basePath));
        pb.redirectErrorStream(false);
        Process p = pb.start();

        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return "Error: git command timed out";
        }

        if (p.exitValue() != 0) {
            return "Error (exit " + p.exitValue() + "): " + stderr.trim();
        }
        return stdout;
    }

    String gitStatus(JsonObject args) throws Exception {
        boolean verbose = args.has("verbose") && args.get("verbose").getAsBoolean();
        if (verbose) {
            return runGit(STATUS_PARAM);
        }
        return runGit(STATUS_PARAM, "--short", "--branch");
    }

    String gitDiff(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("diff");

        if (args.has("staged") && args.get("staged").getAsBoolean()) {
            gitArgs.add("--cached");
        }
        if (args.has(PARAM_COMMIT)) {
            gitArgs.add(args.get(PARAM_COMMIT).getAsString());
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        if (args.has(PARAM_STAT_ONLY) && args.get(PARAM_STAT_ONLY).getAsBoolean()) {
            gitArgs.add(1, "--stat");
        }
        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitLog(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("log");

        int maxCount = args.has("max_count") ? args.get("max_count").getAsInt() : 20;
        gitArgs.add("-" + maxCount);

        String format = args.has("format") ? args.get("format").getAsString() : "medium";
        switch (format) {
            case "oneline" -> gitArgs.add("--oneline");
            case "short" -> gitArgs.add("--format=%h %s (%an, %ar)");
            case "full" -> gitArgs.add("--format=commit %H%nAuthor: %an <%ae>%nDate:   %ad%n%n    %s%n%n%b");
            default -> {
                // "medium" is git default - no flag needed
            }
        }

        if (args.has("author")) {
            gitArgs.add("--author=" + args.get("author").getAsString());
        }
        if (args.has("since")) {
            gitArgs.add("--since=" + args.get("since").getAsString());
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        if (args.has(PARAM_BRANCH)) {
            gitArgs.add(2, args.get(PARAM_BRANCH).getAsString());
        }
        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitBlame(JsonObject args) throws Exception {
        if (!args.has("path")) return ERROR_PATH_REQUIRED;

        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("blame");

        if (args.has("line_start") && args.has("line_end")) {
            gitArgs.add("-L");
            gitArgs.add(args.get("line_start").getAsInt() + "," + args.get("line_end").getAsInt());
        }

        gitArgs.add(args.get("path").getAsString());
        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitCommit(JsonObject args) throws Exception {
        if (!args.has(PARAM_MESSAGE)) return "Error: 'message' parameter is required";

        // Save all documents before committing to ensure disk matches editor state
        EdtUtil.invokeAndWait(() ->
            ApplicationManager.getApplication().runWriteAction(() ->
                FileDocumentManager.getInstance().saveAllDocuments()));

        List<String> gitArgs = new ArrayList<>();
        gitArgs.add(PARAM_COMMIT);

        if (args.has("amend") && args.get("amend").getAsBoolean()) {
            gitArgs.add("--amend");
        }
        if (args.has("all") && args.get("all").getAsBoolean()) {
            gitArgs.add(GIT_FLAG_ALL);
        }

        gitArgs.add("-m");
        gitArgs.add(args.get(PARAM_MESSAGE).getAsString());

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitStage(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("add");

        if (args.has("all") && args.get("all").getAsBoolean()) {
            gitArgs.add(GIT_FLAG_ALL);
        } else if (args.has(JSON_PATHS)) {
            for (var elem : args.getAsJsonArray(JSON_PATHS)) {
                gitArgs.add(elem.getAsString());
            }
        } else if (args.has("path")) {
            gitArgs.add(args.get("path").getAsString());
        } else {
            return "Error: 'path', 'paths', or 'all' parameter is required";
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitUnstage(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("restore");
        gitArgs.add("--staged");

        if (args.has(JSON_PATHS)) {
            for (var elem : args.getAsJsonArray(JSON_PATHS)) {
                gitArgs.add(elem.getAsString());
            }
        } else if (args.has("path")) {
            gitArgs.add(args.get("path").getAsString());
        } else {
            return "Error: 'path' or 'paths' parameter is required";
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitBranch(JsonObject args) throws Exception {
        String action = args.has(JSON_ACTION) ? args.get(JSON_ACTION).getAsString() : "list";

        return switch (action) {
            case "list" -> {
                boolean all = args.has("all") && args.get("all").getAsBoolean();
                yield runGit(PARAM_BRANCH, all ? GIT_FLAG_ALL : "--list", "-v");
            }
            case "create" -> {
                if (!args.has("name")) yield "Error: 'name' required for create";
                String base = args.has("base") ? args.get("base").getAsString() : "HEAD";
                yield runGit(PARAM_BRANCH, args.get("name").getAsString(), base);
            }
            case "switch", "checkout" -> {
                if (!args.has("name")) yield "Error: 'name' required for switch";
                yield runGit("switch", args.get("name").getAsString());
            }
            case "delete" -> {
                if (!args.has("name")) yield "Error: 'name' required for delete";
                boolean force = args.has("force") && args.get("force").getAsBoolean();
                yield runGit(PARAM_BRANCH, force ? "-D" : "-d", args.get("name").getAsString());
            }
            default -> "Error: unknown action '" + action + "'. Use: list, create, switch, delete";
        };
    }

    String gitStash(JsonObject args) throws Exception {
        String action = args.has(JSON_ACTION) ? args.get(JSON_ACTION).getAsString() : "list";

        return switch (action) {
            case "list" -> runGit(JSON_STASH, "list");
            case "push", "save" -> {
                List<String> gitArgs = new ArrayList<>(List.of(JSON_STASH, "push"));
                if (args.has(PARAM_MESSAGE)) {
                    gitArgs.add("-m");
                    gitArgs.add(args.get(PARAM_MESSAGE).getAsString());
                }
                if (args.has("include_untracked") && args.get("include_untracked").getAsBoolean()) {
                    gitArgs.add("--include-untracked");
                }
                yield runGit(gitArgs.toArray(new String[0]));
            }
            case "pop" -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, "pop") : runGit(JSON_STASH, "pop", JSON_STASH_PREFIX + index + "}");
            }
            case JSON_APPLY -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, JSON_APPLY) : runGit(JSON_STASH, JSON_APPLY, JSON_STASH_PREFIX + index + "}");
            }
            case "drop" -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, "drop") : runGit(JSON_STASH, "drop", JSON_STASH_PREFIX + index + "}");
            }
            default -> "Error: unknown stash action '" + action + "'. Use: list, push, pop, apply, drop";
        };
    }

    String gitShow(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("show");

        String ref = args.has("ref") ? args.get("ref").getAsString() : "HEAD";
        gitArgs.add(ref);

        if (args.has(PARAM_STAT_ONLY) && args.get(PARAM_STAT_ONLY).getAsBoolean()) {
            gitArgs.add("--stat");
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        return runGit(gitArgs.toArray(new String[0]));
    }
}
