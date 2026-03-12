package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.psi.tools.file.FileTool;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Invokes a named IDE quick-fix or intention action at a specific file and line.
 * Action names come from {@code get_highlights} or {@code get_available_actions} output.
 *
 * <p>For highlight-based quick-fixes, the action is looked up in the cached daemon highlight data.
 * For intention actions (refactoring, conversions, etc.), an optional {@code column} positions the
 * caret precisely so that only actions relevant to that symbol are considered.</p>
 *
 * <p>Unlike {@code apply_quickfix} (which requires an inspection ID), this tool invokes the action
 * directly via IntelliJ's {@link IntentionAction} API — the same path as clicking the light-bulb.</p>
 */
public final class ApplyActionTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(ApplyActionTool.class);
    private static final String PARAM_COLUMN = "column";
    private static final String PARAM_ACTION_NAME = "action_name";
    private static final String LINE_LABEL = " line ";

    public ApplyActionTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "apply_action";
    }

    @Override
    public @NotNull String displayName() {
        return "Apply Action";
    }

    @Override
    public @NotNull String description() {
        return "Invoke a named IDE quick-fix or intention action at a specific file and line. "
            + "Action names come from get_highlights or get_available_actions output. "
            + "Provide 'column' when applying an intention action to ensure the caret is positioned "
            + "at the correct symbol (required for refactoring intentions). "
            + "Tip: use optimize_imports to fix all missing imports at once.";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Path to the file"},
            {"line", TYPE_INTEGER, "Line number (1-based)"},
            {PARAM_ACTION_NAME, TYPE_STRING, "Exact action name from get_highlights / get_available_actions output"},
            {PARAM_COLUMN, TYPE_INTEGER, "Column number (1-based, optional). Required when applying "
                + "intention actions to position the caret at the correct symbol."}
        }, "file", "line", PARAM_ACTION_NAME);
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file") || !args.has("line") || !args.has(PARAM_ACTION_NAME)) {
            return "Error: 'file', 'line', and 'action_name' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        String actionName = args.get(PARAM_ACTION_NAME).getAsString();
        Integer targetCol = args.has(PARAM_COLUMN) ? args.get(PARAM_COLUMN).getAsInt() : null;

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                future.complete(invokeAction(pathStr, targetLine, actionName, targetCol));
            } catch (Exception e) {
                LOG.warn("Error invoking action '" + actionName + "' at " + pathStr + ":" + targetLine, e);
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = future.get(30, TimeUnit.SECONDS);
        if (result != null && !result.startsWith("Error") && !result.startsWith("No ")) {
            FileTool.followFileIfEnabled(project, pathStr, targetLine, targetLine,
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " applied action");
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    // ── Private helpers ──────────────────────────────────────

    private String invokeAction(String pathStr, int targetLine, String actionName, @Nullable Integer targetCol) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + FORMAT_LINES_SUFFIX;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr;

        Editor editor = getOrOpenEditor(vf);
        if (editor == null) {
            return "Error: Could not open editor for " + pathStr + ". Ensure the file is open in the IDE.";
        }

        // Position caret — use column if provided, otherwise start of line
        int caretCol = (targetCol != null) ? Math.max(0, targetCol - 1) : 0;
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(targetLine - 1, caretCol));

        IntentionAction action = findActionToApply(doc, targetLine, actionName, editor, psiFile);
        if (action == null) {
            List<String> available = collectAvailableActionNames(doc, targetLine, editor, psiFile);
            String hint = available.isEmpty() ? "none" : String.join(", ", available);
            return "Action '" + actionName + "' not found at " + pathStr + LINE_LABEL + targetLine
                + ". Available: [" + hint + "]";
        }

        if (!action.isAvailable(project, editor, psiFile)) {
            return "Action '" + actionName + "' is not currently applicable at " + pathStr
                + LINE_LABEL + targetLine + ".";
        }

        WriteCommandAction.runWriteCommandAction(project, actionName, null,
            () -> action.invoke(project, editor, psiFile));

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        return "Applied action: " + actionName + "\n  File: " + pathStr + LINE_LABEL + targetLine;
    }

    /**
     * Finds the named action by first searching highlight quick-fixes, then falling back to
     * {@code IntentionManager} intention actions at the current caret position.
     */
    @Nullable
    private IntentionAction findActionToApply(Document doc, int targetLine, String name,
                                              Editor editor, PsiFile psiFile) {
        // 1. Search highlight quick-fixes
        for (var h : highlightsOnLine(doc, targetLine)) {
            IntentionAction found = h.findRegisteredQuickFix((descriptor, range) -> {
                IntentionAction a = descriptor.getAction();
                if (name.equals(a.getText())) return a;
                return null;
            });
            if (found != null) return found;
        }

        // 2. Fall back to IntentionManager (handles refactoring / conversion intentions)
        return findIntentionByName(name, editor, psiFile);
    }

    /**
     * Collects names from both highlight quick-fixes and available intentions at the current
     * caret position, for use in "not found" error messages.
     */
    private List<String> collectAvailableActionNames(Document doc, int targetLine,
                                                     Editor editor, PsiFile psiFile) {
        List<String> names = new ArrayList<>();
        highlightsOnLine(doc, targetLine).forEach(h -> names.addAll(collectQuickFixNames(h)));
        names.addAll(collectIntentionNames(editor, psiFile));
        return names;
    }
}
