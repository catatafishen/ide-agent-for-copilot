package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Moves a file to a different directory.
 */
@SuppressWarnings("java:S112")
public final class MoveFileTool extends FileTool {

    public MoveFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "move_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Move File";
    }

    @Override
    public @NotNull String description() {
        return "Move a file to a different directory";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Move {path} → {destination}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to move (absolute or project-relative)"},
            {"destination", TYPE_STRING, "Destination directory path (absolute or project-relative)"}
        }, "path", "destination");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || !args.has("destination"))
            return ToolUtils.ERROR_PREFIX + "'path' and 'destination' parameters are required";
        String pathStr = args.get("path").getAsString();
        String destStr = args.get("destination").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        final MoveFileTool requestor = this;

        ReadAction.nonBlocking(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return null;
                }
                VirtualFile destDir = resolveVirtualFile(destStr);
                if (destDir == null || !destDir.isDirectory()) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + "Destination directory not found: " + destStr);
                    return null;
                }
                String oldPath = vf.getPath();
                EdtUtil.invokeLater(() ->
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        try {
                            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                                project,
                                () -> {
                                    try {
                                        vf.move(requestor, destDir);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                "Move File: " + vf.getName(),
                                null
                            );
                            resultFuture.complete("Moved " + oldPath + " to " + destDir.getPath() + "/" + vf.getName());
                        } catch (Exception e) {
                            resultFuture.complete("Error moving file: " + e.getMessage());
                        }
                    })
                );
                return null;
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }
}
