package com.github.copilot.intellij.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
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
    private static final String FORMAT_CHARS_SUFFIX = " chars)";

    FileTools(Project project) {
        super(project);
        register("read_file", this::readFile);
        register("intellij_read_file", this::readFile);
        register("write_file", this::writeFile);
        register("intellij_write_file", this::writeFile);
        register("create_file", this::createFile);
        register("delete_file", this::deleteFile);
    }

    private String readFile(JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int startLine = args.has("start_line") ? args.get("start_line").getAsInt() : -1;
        int endLine = args.has("end_line") ? args.get("end_line").getAsInt() : -1;

        return ReadAction.compute(() -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            String content = readFileContent(vf);
            if (content.startsWith("Error")) return content;

            if (startLine > 0 || endLine > 0) {
                return extractLineRange(content, startLine, endLine);
            }
            return content;
        });
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
                } else if (args.has("old_str") && args.has("new_str")) {
                    writeFilePartialEdit(vf, pathStr, args.get("old_str").getAsString(),
                        args.get("new_str").getAsString(), autoFormat, resultFuture);
                } else if (args.has("start_line") && args.has("new_str")) {
                    writeFileLineRange(vf, pathStr, args, autoFormat, resultFuture);
                } else {
                    resultFuture.complete("write_file requires either 'content' (full write), " +
                        "'old_str'+'new_str' (partial edit), or 'start_line'+'new_str' (line-range replace)");
                }
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        return resultFuture.get(15, TimeUnit.SECONDS);
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
            if (autoFormat) autoFormatAfterWrite(pathStr);
            resultFuture.complete("Written: " + pathStr + " (" + newContent.length() + FORMAT_CHARS_SUFFIX);
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
        if (autoFormat) autoFormatAfterWrite(pathStr);
        resultFuture.complete("Edited: " + pathStr + " (replaced " + finalLen + " chars with " + normalizedNew.length() + FORMAT_CHARS_SUFFIX);
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
        int startLine = args.get("start_line").getAsInt();
        int endLine = args.has("end_line") ? args.get("end_line").getAsInt() : startLine;
        String newStr = args.get("new_str").getAsString().replace("\r\n", "\n").replace("\r", "\n");

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
        if (autoFormat) autoFormatAfterWrite(pathStr);
        resultFuture.complete("Edited: " + pathStr + " (replaced lines " + startLine + "-" + endLine
            + " (" + replacedLines + " lines) with " + fNew.length() + FORMAT_CHARS_SUFFIX);
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
     * Runs asynchronously on EDT — does not block the caller.
     */
    private void autoFormatAfterWrite(String pathStr) {
        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) return;
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) return;

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).run();
                        new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                    }, "Auto-Format After Write", null)
                );
                LOG.info("Auto-formatted after write: " + pathStr);
            } catch (Exception e) {
                LOG.warn("Auto-format failed for " + pathStr + ": " + e.getMessage());
            }
        });
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
                    resultFuture.complete("✓ Deleted file: " + pathStr);
                } catch (Exception e) {
                    resultFuture.complete("Error deleting file: " + e.getMessage());
                }
            })
        );
    }
}
