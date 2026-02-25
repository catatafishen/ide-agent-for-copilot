package com.github.copilot.intellij.psi;

import com.github.copilot.intellij.services.CopilotSettings;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles file read/write/create/delete tool calls for the PSI Bridge.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
class FileTools extends AbstractToolHandler {

    private static final Logger LOG = Logger.getInstance(FileTools.class);

    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_START_LINE = "start_line";
    private static final String PARAM_END_LINE = "end_line";
    private static final String PARAM_NEW_STR = "new_str";
    private static final String FORMAT_CHARS_SUFFIX = " chars)";

    /**
     * Extract context lines around an edit region for the response.
     * Returns ~5 lines before and after the edited region so the agent
     * can see what the file looks like post-edit/format.
     */
    private static String contextLines(Document doc, int editStartOffset, int editEndOffset) {
        int totalLines = doc.getLineCount();
        if (totalLines == 0) return "";
        int startLine = doc.getLineNumber(Math.min(editStartOffset, doc.getTextLength() - 1));
        int endLine = doc.getLineNumber(Math.min(editEndOffset, doc.getTextLength() - 1));
        int ctxStart = Math.max(0, startLine - 3);
        int ctxEnd = Math.min(totalLines - 1, endLine + 3);
        StringBuilder sb = new StringBuilder("\n\nContext after edit (lines ").append(ctxStart + 1).append("-").append(ctxEnd + 1).append("):\n");
        for (int i = ctxStart; i <= ctxEnd; i++) {
            int s = doc.getLineStartOffset(i);
            int e = doc.getLineEndOffset(i);
            sb.append(i + 1).append(": ").append(doc.getText(new com.intellij.openapi.util.TextRange(s, e))).append("\n");
        }
        return sb.toString();
    }

    FileTools(Project project) {
        super(project);
        register("read_file", this::readFile);
        register("intellij_read_file", this::readFile);
        register("write_file", this::writeFile);
        register("intellij_write_file", this::writeFile);
        register("create_file", this::createFile);
        register("delete_file", this::deleteFile);
        register("undo", this::undo);
    }

    private String readFile(JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int startLine = args.has(PARAM_START_LINE) ? args.get(PARAM_START_LINE).getAsInt() : -1;
        int endLine = args.has(PARAM_END_LINE) ? args.get(PARAM_END_LINE).getAsInt() : -1;

        String result = ReadAction.compute(() -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            String content = readFileContent(vf);
            if (content.startsWith("Error")) return content;

            if (startLine > 0 || endLine > 0) {
                return extractLineRange(content, startLine, endLine);
            }
            return content;
        });

        followFileIfEnabled(pathStr, startLine > 0 ? startLine : -1);
        return result;
    }

    private String readFileContent(VirtualFile vf) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            return doc.getText();
        }
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private String extractLineRange(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        int from = Math.max(0, (startLine > 0 ? startLine - 1 : 0));
        int to = Math.min(lines.length, (endLine > 0 ? endLine : lines.length));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * Opens the file in the editor if "Follow Agent Files" is enabled.
     */
    private void followFileIfEnabled(String pathStr, int line) {
        if (!CopilotSettings.getFollowAgentFiles()) return;

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) return;

                if (line > 0) {
                    new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line - 1, 0)
                        .navigate(false);
                } else {
                    FileEditorManager.getInstance(project).openFile(vf, false);
                }
            } catch (Exception e) {
                LOG.debug("Follow agent file failed: " + pathStr, e);
            }
        });
    }

    private String writeFile(JsonObject args) throws Exception {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        boolean autoFormat = !args.has("auto_format") || args.get("auto_format").getAsBoolean();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);

                if (args.has(PARAM_CONTENT)) {
                    writeFileFullContent(vf, pathStr, args.get(PARAM_CONTENT).getAsString(),
                        autoFormat, resultFuture);
                } else if (args.has("old_str") && args.has(PARAM_NEW_STR)) {
                    writeFilePartialEdit(vf, pathStr, args.get("old_str").getAsString(),
                        args.get(PARAM_NEW_STR).getAsString(), autoFormat, resultFuture);
                } else if (args.has(PARAM_START_LINE) && args.has(PARAM_NEW_STR)) {
                    writeFileLineRange(vf, pathStr, args, autoFormat, resultFuture);
                } else {
                    resultFuture.complete("write_file requires either 'content' (full write), " +
                        "'old_str'+'new_str' (partial edit), or 'start_line'+'new_str' (line-range replace)");
                }
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = resultFuture.get(15, TimeUnit.SECONDS);

        int line = args.has(PARAM_START_LINE) ? args.get(PARAM_START_LINE).getAsInt() : -1;
        followFileIfEnabled(pathStr, line);
        return result;
    }

    private void writeFileFullContent(VirtualFile vf, String pathStr, String newContent,
                                      boolean autoFormat, CompletableFuture<String> resultFuture) {
        if (vf == null) {
            createNewFile(pathStr, newContent, resultFuture);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            ApplicationManager.getApplication().runWriteAction(() ->
                com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                    project, () -> doc.setText(newContent), "Write File", null)
            );
            FileDocumentManager.getInstance().saveDocument(doc);
            String syntaxWarning = checkSyntaxErrors(pathStr);
            if (autoFormat && syntaxWarning.isEmpty()) autoFormatAfterWrite(pathStr);
            resultFuture.complete("Written: " + pathStr + " (" + newContent.length() + FORMAT_CHARS_SUFFIX + syntaxWarning);
        } else {
            ApplicationManager.getApplication().runWriteAction(() -> {
                try (var os = vf.getOutputStream(this)) {
                    os.write(newContent.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    resultFuture.complete("Error writing: " + e.getMessage());
                }
            });
            resultFuture.complete("Written: " + pathStr);
        }
    }

    private void createNewFile(String pathStr, String content, CompletableFuture<String> resultFuture) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                String normalized = pathStr.replace('\\', '/');
                String basePath = project.getBasePath();
                String fullPath;
                if (normalized.startsWith("/")) {
                    fullPath = normalized;
                } else if (basePath != null) {
                    fullPath = Path.of(basePath, normalized).toString();
                } else {
                    fullPath = normalized;
                }
                Path filePath = Path.of(fullPath);
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
                resultFuture.complete("Created: " + pathStr);
            } catch (IOException e) {
                resultFuture.complete("Error creating file: " + e.getMessage());
            }
        });
    }

    private void writeFilePartialEdit(VirtualFile vf, String pathStr, String oldStr, String newStr,
                                      boolean autoFormat, CompletableFuture<String> resultFuture) {
        if (vf == null) {
            resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            resultFuture.complete("Cannot open document: " + pathStr);
            return;
        }
        // Normalize line endings in old_str/new_str for consistent matching
        String normalizedOld = oldStr.replace("\r\n", "\n").replace("\r", "\n");
        String normalizedNew = newStr.replace("\r\n", "\n").replace("\r", "\n");

        int[] match = findMatchPosition(doc, vf, pathStr, normalizedOld, autoFormat);
        int idx = match[0];
        int matchLen = match[1];

        if (idx == -1) {
            String text = doc.getText();
            String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
            resultFuture.complete("old_str not found in " + pathStr +
                ". Ensure the text matches exactly (check special characters, whitespace, line endings)." +
                "\nFile starts with: " + preview.replace("\n", "\\n").substring(0, Math.min(preview.length(), 150)));
            return;
        }
        // Check for multiple matches using same strategy
        String text = doc.getText();
        String checkText = (matchLen == normalizedOld.length()) ? text : ToolUtils.normalizeForMatch(text);
        String checkOld = (matchLen == normalizedOld.length()) ? normalizedOld : ToolUtils.normalizeForMatch(normalizedOld);
        if (checkText.indexOf(checkOld, idx + 1) != -1) {
            resultFuture.complete("old_str matches multiple locations in " + pathStr + ". Make it more specific.");
            return;
        }
        final int finalIdx = idx;
        final int finalLen = matchLen;
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project, () -> doc.replaceString(finalIdx, finalIdx + finalLen, normalizedNew),
                "Edit File", null)
        );
        FileDocumentManager.getInstance().saveDocument(doc);
        String syntaxWarning = checkSyntaxErrors(pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) autoFormatAfterWrite(pathStr, false);
        int ctxEnd = Math.min(finalIdx + normalizedNew.length(), doc.getTextLength());
        resultFuture.complete("Edited: " + pathStr + " (replaced " + finalLen + " chars with " + normalizedNew.length() + FORMAT_CHARS_SUFFIX
            + contextLines(doc, finalIdx, ctxEnd) + syntaxWarning);
    }

    /**
     * Replaces a range of lines (start_line to end_line inclusive, 1-based) with new_str.
     * If end_line is omitted, only start_line is replaced.
     */
    private void writeFileLineRange(VirtualFile vf, String pathStr, JsonObject args,
                                    boolean autoFormat, CompletableFuture<String> resultFuture) {
        if (vf == null) {
            resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            resultFuture.complete("Cannot open document: " + pathStr);
            return;
        }
        int startLine = args.get(PARAM_START_LINE).getAsInt();
        int endLine = args.has(PARAM_END_LINE) ? args.get(PARAM_END_LINE).getAsInt() : startLine;
        String newStr = args.get(PARAM_NEW_STR).getAsString().replace("\r\n", "\n").replace("\r", "\n");

        int lineCount = doc.getLineCount();
        if (startLine < 1 || startLine > lineCount) {
            resultFuture.complete("start_line " + startLine + " out of range (file has " + lineCount + " lines)");
            return;
        }
        if (endLine < startLine || endLine > lineCount) {
            resultFuture.complete("end_line " + endLine + " out of range (file has " + lineCount + " lines, start_line=" + startLine + ")");
            return;
        }

        int startOffset = doc.getLineStartOffset(startLine - 1);
        int endOffset = doc.getLineEndOffset(endLine - 1);
        // Include the trailing newline if present so the replacement is clean
        if (endOffset < doc.getTextLength() && doc.getText().charAt(endOffset) == '\n') {
            endOffset++;
        }
        // Ensure new_str ends with newline for clean line replacement
        if (!newStr.isEmpty() && !newStr.endsWith("\n")) {
            newStr += "\n";
        }

        final int fStart = startOffset;
        final int fEnd = endOffset;
        final String fNew = newStr;
        int replacedLines = endLine - startLine + 1;
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project, () -> doc.replaceString(fStart, fEnd, fNew),
                "Edit File (line range)", null)
        );
        FileDocumentManager.getInstance().saveDocument(doc);
        String syntaxWarning = checkSyntaxErrors(pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) autoFormatAfterWrite(pathStr, false);
        int ctxEnd = Math.min(fStart + fNew.length(), doc.getTextLength());
        resultFuture.complete("Edited: " + pathStr + " (replaced lines " + startLine + "-" + endLine
            + " (" + replacedLines + " lines) with " + fNew.length() + FORMAT_CHARS_SUFFIX
            + contextLines(doc, fStart, ctxEnd) + syntaxWarning);
    }

    /**
     * Returns [index, matchLength] or [-1, 0] if not found.
     */
    private int[] findMatchPosition(Document doc, VirtualFile vf, String pathStr,
                                    String normalizedOld, boolean autoFormat) {
        String text = doc.getText();
        int idx = text.indexOf(normalizedOld);
        int matchLen = normalizedOld.length();

        // Fallback 1: auto-format the file and retry (normalizes whitespace/line endings)
        if (idx == -1 && autoFormat) {
            formatFileSync(vf);
            text = doc.getText();
            idx = text.indexOf(normalizedOld);
            if (idx != -1) {
                LOG.info("write_file: match succeeded after auto-format for " + pathStr);
                return new int[]{idx, matchLen};
            }
        }

        if (idx == -1) {
            // Fallback 2: normalize Unicode chars and retry
            String normText = ToolUtils.normalizeForMatch(text);
            String normOld = ToolUtils.normalizeForMatch(normalizedOld);
            idx = normText.indexOf(normOld);
            if (idx != -1) {
                LOG.info("write_file: normalized match succeeded for " + pathStr);
                matchLen = ToolUtils.findOriginalLength(text, idx, normOld.length());
            } else {
                LOG.warn("write_file: old_str not found in " + pathStr +
                    " (exact, formatted, and normalized all failed)");
            }
        }
        return new int[]{idx, matchLen};
    }

    /**
     * Synchronously format a file on the current EDT thread.
     * Used as a fallback when old_str matching fails — formatting normalizes
     * line endings, whitespace, and indentation for more reliable matching.
     */
    private void formatFileSync(VirtualFile vf) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
            }, "Pre-Format for Edit", null)
        );
    }

    /**
     * Auto-format and optimize imports on a file after a write operation.
     * Runs synchronously on the current EDT thread so the response reflects
     * the final formatted state and subsequent edits see consistent content.
     */
    private void autoFormatAfterWrite(String pathStr) {
        autoFormatAfterWrite(pathStr, true);
    }

    private void autoFormatAfterWrite(String pathStr, boolean optimizeImports) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return;

            ApplicationManager.getApplication().runWriteAction(() ->
                com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                    if (optimizeImports) {
                        new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).run();
                    }
                    new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                }, "Auto-Format After Write", null)
            );
            LOG.info("Auto-formatted after write: " + pathStr + (optimizeImports ? " (with import optimization)" : ""));
        } catch (Exception e) {
            LOG.warn("Auto-format failed for " + pathStr + ": " + e.getMessage());
        }
    }

    /**
     * Check for syntax errors in a file after writing. Returns a warning string
     * if errors are found, or empty string if the file is clean.
     * Runs on EDT (caller must be on EDT).
     */
    private String checkSyntaxErrors(String pathStr) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return "";
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "";
            Document doc = psiFile.getViewProvider().getDocument();

            java.util.List<String> errors = new java.util.ArrayList<>();
            collectPsiErrors(psiFile, doc, errors);

            if (errors.isEmpty()) return "";
            int count = Math.min(errors.size(), 5);
            String summary = "\n\n\u26A0\uFE0F WARNING: " + errors.size() + " syntax error(s) after write:\n"
                + String.join("\n", errors.subList(0, count));
            if (errors.size() > count) summary += "\n  ... and " + (errors.size() - count) + " more";
            return summary;
        } catch (Exception e) {
            return "";
        }
    }

    private void collectPsiErrors(com.intellij.psi.PsiElement element, Document doc,
                                  java.util.List<String> errors) {
        if (element instanceof com.intellij.psi.PsiErrorElement err) {
            int line = doc != null ? doc.getLineNumber(err.getTextOffset()) + 1 : -1;
            errors.add("  Line " + line + ": " + err.getErrorDescription());
        }
        for (com.intellij.psi.PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectPsiErrors(child, doc, errors);
        }
    }

    private String createFile(JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_CONTENT)) {
            return "Error: 'path' and 'content' parameters are required";
        }
        String pathStr = args.get("path").getAsString();
        String content = args.get(PARAM_CONTENT).getAsString();

        // Resolve path
        String basePath = project.getBasePath();
        Path pathObj = Path.of(pathStr);
        Path filePath;
        if (pathObj.isAbsolute()) {
            filePath = pathObj;
        } else if (basePath != null) {
            filePath = Path.of(basePath, pathStr);
        } else {
            return "Error: Cannot resolve relative path without project base path";
        }

        if (Files.exists(filePath)) {
            return "Error: File already exists: " + pathStr +
                ". Use intellij_write_file to modify existing files.";
        }

        // Create parent directories
        Path parentDir = filePath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        // Write content
        Files.writeString(filePath, content, StandardCharsets.UTF_8);

        // Refresh VFS so IntelliJ sees the file
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.toString());
                resultFuture.complete("✓ Created file: " + pathStr + " (" + content.length() + FORMAT_CHARS_SUFFIX);
            } catch (Exception e) {
                resultFuture.complete("File created but VFS refresh failed: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String deleteFile(JsonObject args) throws Exception {
        if (!args.has("path")) return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ReadAction.nonBlocking(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return null;
                }
                if (vf.isDirectory()) {
                    resultFuture.complete("Error: Cannot delete directories. Path is a directory: " + pathStr);
                    return null;
                }
                scheduleFileDeletion(vf, pathStr, resultFuture);
                return null;
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void scheduleFileDeletion(VirtualFile vf, String pathStr, CompletableFuture<String> resultFuture) {
        EdtUtil.invokeLater(() ->
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project,
                        () -> {
                            try {
                                vf.delete(FileTools.this);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        "Delete File: " + vf.getName(),
                        null
                    );
                    resultFuture.complete("✅ Deleted file: " + pathStr);
                } catch (Exception e) {
                    resultFuture.complete("Error deleting file: " + e.getMessage());
                }
            })
        );
    }

    /**
     * Undo the last editor action on a file. Uses IntelliJ's built-in UndoManager
     * which tracks all commands registered via CommandProcessor.
     */
    private void performUndo(String pathStr, int count, CompletableFuture<String> resultFuture) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            com.intellij.openapi.fileEditor.FileEditor fileEditor = findFileEditor(vf);
            UndoManager undoManager = UndoManager.getInstance(project);
            String result = executeUndoSteps(undoManager, fileEditor, count, pathStr);
            resultFuture.complete(result);
        } catch (Exception e) {
            resultFuture.complete("Undo failed: " + e.getMessage());
        }
    }

    private com.intellij.openapi.fileEditor.FileEditor findFileEditor(VirtualFile vf) {
        var editors = FileEditorManager.getInstance(project).getEditors(vf);
        for (var ed : editors) {
            if (ed instanceof TextEditor) return ed;
        }
        return editors.length > 0 ? editors[0] : null;
    }

    private String executeUndoSteps(UndoManager undoManager, com.intellij.openapi.fileEditor.FileEditor fileEditor, int count, String pathStr) {
        StringBuilder actions = new StringBuilder();
        int undone = 0;
        for (int i = 0; i < count; i++) {
            if (!undoManager.isUndoAvailable(fileEditor)) break;
            String actionName = undoManager.getUndoActionNameAndDescription(fileEditor).first;
            undoManager.undo(fileEditor);
            undone++;
            if (!actions.isEmpty()) actions.append(", ");
            actions.append(actionName != null && !actionName.isEmpty() ? actionName : "unknown");
        }
        if (undone == 0) {
            return "Nothing to undo for " + pathStr;
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        return "Undid " + undone + " action(s) on " + pathStr + ": " + actions;
    }

    private String undo(JsonObject args) throws Exception {
        if (!args.has("path")) return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int count = args.has("count") ? args.get("count").getAsInt() : 1;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> performUndo(pathStr, count, resultFuture));
        return resultFuture.get(10, TimeUnit.SECONDS);
    }
}
