package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Returns quick-fix and intention action names available at a specific file and line.
 *
 * <p>Without {@code column}: returns only highlight-based quick-fixes (errors/warnings with
 * attached fixes). Uses cached daemon data — no extra analysis is triggered.</p>
 *
 * <p>With {@code column}: additionally returns context-aware intention actions available at that
 * exact symbol position (the full Alt+Enter menu). Requires opening an editor to position the caret.</p>
 */
public final class GetAvailableActionsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(GetAvailableActionsTool.class);
    private static final String PARAM_COLUMN = "column";
    private static final String LINE_LABEL = " line ";

    public GetAvailableActionsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_available_actions";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Available Actions";
    }

    @Override
    public @NotNull String description() {
        return "Get quick-fix and intention action names available at a specific file and line. "
            + "Without 'column': returns highlight-based quick-fixes (errors/warnings with fixes). "
            + "With 'column': also returns context-aware intention actions at that symbol position "
            + "(refactoring, conversions, etc. — the full Alt+Enter menu). "
            + "Use apply_action to invoke one, or optimize_imports to fix all missing imports at once.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Path to the file"},
            {"line", TYPE_INTEGER, "Line number (1-based)"},
            {PARAM_COLUMN, TYPE_INTEGER, "Column number (1-based, optional). When provided, also returns "
                + "intention actions available at that exact symbol position (refactoring, conversions, etc.)"}
        }, "file", "line");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file") || !args.has("line")) {
            return "Error: 'file' and 'line' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        Integer targetCol = args.has(PARAM_COLUMN) ? args.get(PARAM_COLUMN).getAsInt() : null;

        if (targetCol != null) {
            // Must run on EDT to position the caret and evaluate intention availability
            CompletableFuture<String> future = new CompletableFuture<>();
            int col = targetCol;
            EdtUtil.invokeLater(() -> {
                try {
                    future.complete(collectActionsWithIntentions(pathStr, targetLine, col));
                } catch (Exception e) {
                    LOG.warn("Error collecting actions at " + pathStr + ":" + targetLine + ":" + col, e);
                    future.complete("Error: " + e.getMessage());
                }
            });
            return future.get(15, TimeUnit.SECONDS);
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() ->
            com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(() -> {
                try {
                    future.complete(collectQuickFixesOnly(pathStr, targetLine));
                } catch (Exception e) {
                    LOG.warn("Error collecting quick-fixes at " + pathStr + ":" + targetLine, e);
                    future.complete("Error: " + e.getMessage());
                }
            })
        );
        return future.get(15, TimeUnit.SECONDS);
    }

    // ── Quick-fixes only (no column) ─────────────────────────

    private String collectQuickFixesOnly(String pathStr, int targetLine) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return "Error: File not found: " + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + " lines)";
        }

        List<HighlightInfo> lineHighlights = highlightsOnLine(doc, targetLine);
        List<String> entries = new ArrayList<>();
        Set<String> allFixNames = new LinkedHashSet<>();

        for (var h : lineHighlights) {
            String desc = h.getDescription();
            if (desc == null) continue;

            int actualLine = doc.getLineNumber(h.getStartOffset()) + 1;
            String severity = h.getSeverity().getName();
            List<String> fixes = collectQuickFixNames(h);

            String entry = pathStr + ":" + actualLine + " [" + severity + "] " + desc;
            if (!fixes.isEmpty()) {
                entry += "  →  Quick fixes: [" + String.join(", ", fixes) + "]";
                allFixNames.addAll(fixes);
            }
            entries.add(entry);
        }

        if (entries.isEmpty()) {
            return "No highlights found at " + pathStr + LINE_LABEL + targetLine + ". "
                + "The daemon may not have analyzed this file yet — open it in the editor or call get_highlights first. "
                + "Tip: provide a 'column' to also query intention actions (refactoring, conversions, etc.).";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[QUICK FIX] at ").append(pathStr).append(LINE_LABEL).append(targetLine).append(":\n\n");
        sb.append(String.join("\n", entries));
        if (!allFixNames.isEmpty()) {
            sb.append("\n\nTip: provide 'column' to also see intention actions at a specific symbol. "
                + "Use apply_action(file, line, action_name) to invoke a fix.");
        }
        return sb.toString();
    }

    // ── Quick-fixes + intentions (with column) ───────────────

    private String collectActionsWithIntentions(String pathStr, int targetLine, int targetCol) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return "Error: File not found: " + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + " lines)";
        }

        Editor editor = getOrOpenEditor(vf);
        if (editor == null) return "Error: Could not open editor for " + pathStr;

        // Position caret at the requested symbol
        int clampedCol = Math.max(0, targetCol - 1);
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(targetLine - 1, clampedCol));

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return "Error: Cannot parse file: " + pathStr;

        // Quick-fix names from daemon highlights on the line
        List<String> quickFixes = highlightsOnLine(doc, targetLine).stream()
            .flatMap(h -> collectQuickFixNames(h).stream())
            .distinct()
            .toList();

        // Intention actions available at the caret (EDT has implicit read access)
        List<String> intentions = collectIntentionNames(editor, psiFile);

        if (quickFixes.isEmpty() && intentions.isEmpty()) {
            return "No actions available at " + pathStr + LINE_LABEL + targetLine + " col " + targetCol + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Actions at ").append(pathStr)
            .append(LINE_LABEL).append(targetLine)
            .append(" col ").append(targetCol).append(":\n");

        if (!quickFixes.isEmpty()) {
            sb.append("\n[QUICK FIX]\n");
            quickFixes.forEach(f -> sb.append("  • ").append(f).append("\n"));
        }
        if (!intentions.isEmpty()) {
            sb.append("\n[INTENTION]\n");
            intentions.forEach(i -> sb.append("  • ").append(i).append("\n"));
        }

        sb.append("\nUse apply_action(file, line, action_name) to invoke one.");
        return sb.toString();
    }

}
