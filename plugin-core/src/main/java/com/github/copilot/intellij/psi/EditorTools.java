package com.github.copilot.intellij.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles editor-related tool calls: open_in_editor, show_diff,
 * create_scratch_file, and list_scratch_files.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
class EditorTools extends AbstractToolHandler {

    private static final Logger LOG = Logger.getInstance(EditorTools.class);

    private static final String PARAM_CONTENT = "content";
    private static final String FORMAT_CHARS_SUFFIX = " chars)";
    private static final String DIFF_LABEL_CURRENT = "Current";
    private static final String JSON_TITLE = "title";

    EditorTools(Project project) {
        super(project);
        register("open_in_editor", this::openInEditor);
        register("show_diff", this::showDiff);
        register("create_scratch_file", this::createScratchFile);
        register("list_scratch_files", this::listScratchFiles);
        register("get_chat_html", this::getChatHtml);
    }

    private String openInEditor(JsonObject args) throws Exception {
        if (!args.has("file")) {
            return "Error: 'file' parameter is required";
        }
        String pathStr = args.get("file").getAsString();
        int line = args.has("line") ? args.get("line").getAsInt() : -1;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                if (line > 0) {
                    new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line - 1, 0)
                        .navigate(true);
                } else {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .openFile(vf, true);
                }

                // Force DaemonCodeAnalyzer to run on this file
                PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
                if (psiFile != null) {
                    // Using deprecated restart(PsiFile) method - no alternative available
                    //noinspection deprecation
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile); //NOSONAR S1874 - no non-deprecated alternative for per-file restart
                }

                resultFuture.complete("Opened " + pathStr + (line > 0 ? " at line " + line : "") +
                    " (daemon analysis triggered - use get_highlights after a moment)");
            } catch (Exception e) {
                resultFuture.complete("Error opening file: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    /**
     * Show a diff between two files, or between the current file content and a provided string,
     * in IntelliJ's diff viewer.
     */
    private String showDiff(JsonObject args) throws Exception {
        if (!args.has("file")) {
            return "Error: 'file' parameter is required";
        }
        String pathStr = args.get("file").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                String result = showDiffForFile(args, vf, pathStr);
                resultFuture.complete(result);
            } catch (Exception e) {
                resultFuture.complete("Error showing diff: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String showDiffForFile(JsonObject args, VirtualFile vf, String pathStr) {
        if (args.has("file2")) {
            return showTwoFileDiff(args, vf, pathStr);
        } else if (args.has(PARAM_CONTENT)) {
            return showContentDiff(args, vf, pathStr);
        } else {
            return showVcsDiff(vf, pathStr);
        }
    }

    private String showTwoFileDiff(JsonObject args, VirtualFile vf, String pathStr) {
        String pathStr2 = args.get("file2").getAsString();
        VirtualFile vf2 = resolveVirtualFile(pathStr2);
        if (vf2 == null) {
            return "Error: Second file not found: " + pathStr2;
        }
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        var content2 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf2);
        var request = new com.intellij.diff.requests.SimpleDiffRequest(
            "Diff: " + vf.getName() + " vs " + vf2.getName(),
            content1, content2, vf.getName(), vf2.getName());
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        return "Showing diff: " + pathStr + " vs " + pathStr2;
    }

    private String showContentDiff(JsonObject args, VirtualFile vf, String pathStr) {
        String newContent = args.get(PARAM_CONTENT).getAsString();
        String title = args.has(JSON_TITLE) ? args.get(JSON_TITLE).getAsString() : "Proposed Changes";
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        var content2 = com.intellij.diff.DiffContentFactory.getInstance()
            .create(project, newContent, vf.getFileType());
        var request = new com.intellij.diff.requests.SimpleDiffRequest(
            title, content1, content2, DIFF_LABEL_CURRENT, "Proposed");
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        return "Showing diff for " + pathStr + ": current vs proposed changes";
    }

    private String showVcsDiff(VirtualFile vf, String pathStr) {
        var content1 = com.intellij.diff.DiffContentFactory.getInstance().create(project, vf);
        com.intellij.diff.DiffManager.getInstance().showDiff(project,
            new com.intellij.diff.requests.SimpleDiffRequest(
                "File: " + vf.getName(), content1, content1, DIFF_LABEL_CURRENT, DIFF_LABEL_CURRENT));
        return "Opened " + pathStr + " in diff viewer. " +
            "Tip: pass 'file2' for two-file diff, or 'content' to diff against proposed changes.";
    }

    private String createScratchFile(JsonObject args) {
        String name = args.has("name") ? args.get("name").getAsString() : "scratch.txt";
        String content = args.has(PARAM_CONTENT) ? args.get(PARAM_CONTENT).getAsString() : "";

        try {
            final VirtualFile[] resultFile = new VirtualFile[1];
            final String[] errorMsg = new String[1];

            EdtUtil.invokeAndWait(() ->
                createAndOpenScratchFile(name, content, resultFile, errorMsg));

            if (resultFile[0] == null) {
                return "Error: Failed to create scratch file" +
                    (errorMsg[0] != null ? ": " + errorMsg[0] : "");
            }

            return "Created scratch file: " + resultFile[0].getPath() + " (" + content.length() + FORMAT_CHARS_SUFFIX;
        } catch (Exception e) {
            LOG.warn("Failed to create scratch file", e);
            return "Error creating scratch file: " + e.getMessage();
        }
    }

    private void createAndOpenScratchFile(String name, String content,
                                          VirtualFile[] resultFile, String[] errorMsg) {
        try {
            com.intellij.ide.scratch.ScratchFileService scratchService =
                com.intellij.ide.scratch.ScratchFileService.getInstance();
            com.intellij.ide.scratch.ScratchRootType scratchRoot =
                com.intellij.ide.scratch.ScratchRootType.getInstance();

            // Cast needed: runWriteAction is overloaded (Computable vs. ThrowableComputable)
            //noinspection RedundantCast
            resultFile[0] = ApplicationManager.getApplication().runWriteAction(
                (com.intellij.openapi.util.Computable<VirtualFile>) () -> {
                    try {
                        VirtualFile file = scratchService.findFile(
                            scratchRoot, name,
                            com.intellij.ide.scratch.ScratchFileService.Option.create_if_missing
                        );
                        if (file != null) {
                            OutputStream out = file.getOutputStream(null);
                            out.write(content.getBytes(StandardCharsets.UTF_8));
                            out.close();
                        }
                        return file;
                    } catch (IOException e) {
                        LOG.warn("Failed to create/write scratch file", e);
                        errorMsg[0] = e.getMessage();
                        return null;
                    }
                }
            );

            if (resultFile[0] != null) {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openFile(resultFile[0], true);
            }
        } catch (Exception e) {
            LOG.warn("Failed in EDT execution", e);
            errorMsg[0] = e.getMessage();
        }
    }

    /**
     * List all scratch files visible to the IDE.
     * Returns paths that can be used with intellij_read_file.
     */
    @SuppressWarnings("unused")
    private String listScratchFiles(JsonObject args) {
        try {
            StringBuilder result = new StringBuilder();
            final int[] count = {0};
            final Set<String> seenPaths = new HashSet<>();

            EdtUtil.invokeAndWait(() -> {
                try {
                    result.append("Scratch files:\n");

                    // First, check currently open files in editors (catches files open but not in VFS yet)
                    com.intellij.openapi.fileEditor.FileEditorManager editorManager =
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                    VirtualFile[] openFiles = editorManager.getOpenFiles();

                    for (VirtualFile file : openFiles) {
                        // Check if this is a scratch file (path contains "scratches")
                        String path = file.getPath();
                        if (path.contains("scratches") && !file.isDirectory()) {
                            seenPaths.add(path);
                            long sizeKB = file.getLength() / 1024;
                            result.append("- ").append(path)
                                .append(" (").append(sizeKB).append(" KB) [OPEN]\n");
                            count[0]++;
                        }
                    }

                    // Then, list files from scratch root directory (catches files on disk)
                    com.intellij.ide.scratch.ScratchRootType scratchRoot =
                        com.intellij.ide.scratch.ScratchRootType.getInstance();

                    // Get scratch root directory
                    VirtualFile scratchesDir = scratchRoot.findFile(null, "",
                        com.intellij.ide.scratch.ScratchFileService.Option.existing_only);

                    if (scratchesDir != null && scratchesDir.exists()) {
                        listScratchFilesRecursive(scratchesDir, result, count, 0, seenPaths);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to list scratch files", e);
                    result.append("Error listing scratch files: ").append(e.getMessage());
                }
            });

            if (count[0] == 0 && !result.toString().contains("Error")) {
                result.append("\nTotal: 0 scratch files\n");
                result.append("Use create_scratch_file to create one.");
            } else {
                result.append("\nTotal: ").append(count[0]).append(" scratch file(s)\n");
                result.append("Use intellij_read_file with these paths to read content.");
            }

            return result.toString();
        } catch (Exception e) {
            LOG.warn("Failed to list scratch files", e);
            return "Error listing scratch files: " + e.getMessage();
        }
    }

    private void listScratchFilesRecursive(VirtualFile dir, StringBuilder result, int[] count, int depth, Set<
        String> seenPaths) {
        if (depth > 3) return; // Prevent excessive recursion

        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                listScratchFilesRecursive(child, result, count, depth + 1, seenPaths);
            } else {
                String path = child.getPath();
                if (!seenPaths.contains(path)) {  // Skip if already listed from open files
                    seenPaths.add(path);
                    String indent = "  ".repeat(depth);
                    long sizeKB = child.getLength() / 1024;
                    result.append(indent).append("- ").append(path)
                        .append(" (").append(sizeKB).append(" KB)\n");
                    count[0]++;
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private String getChatHtml(JsonObject args) throws Exception {
        var panel = com.github.copilot.intellij.ui.ChatConsolePanel.Companion.getInstance(project);
        if (panel == null) {
            return "Error: Chat panel not found. Is the Copilot tool window open?";
        }
        String html = panel.getPageHtml();
        if (html == null) {
            return "Error: Could not retrieve page HTML. Browser may not be ready.";
        }
        return html;
    }
}
