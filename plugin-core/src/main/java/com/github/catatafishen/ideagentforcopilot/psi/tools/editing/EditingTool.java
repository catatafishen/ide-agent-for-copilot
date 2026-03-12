package com.github.catatafishen.ideagentforcopilot.psi.tools.editing;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base for symbol editing tools. Contains shared utilities for
 * PSI-aware structural edits (symbol resolution, formatting, validation).
 */
public abstract class EditingTool extends Tool {

    protected static final String PARAM_FILE = "file";
    protected static final String PARAM_SYMBOL = "symbol";
    protected static final String PARAM_LINE = "line";
    protected static final String ERROR_CANNOT_OPEN_DOC = "Cannot open document: ";
    protected static final String FORMATTED_SUFFIX = " (formatted & imports optimized)";
    protected static final String SYMBOL_PREFIX = "Symbol '";

    protected record SymbolLocation(int startLine, int endLine, String type, String name) {
    }

    protected EditingTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.REFACTOR;
    }

    /**
     * Format and optimize imports immediately on the EDT.
     * Unlike edit_text (which defers formatting because old_str matching is
     * position-sensitive), symbol tools resolve by name so formatting the file
     * between edits is safe and prevents stale formatting changes leaking
     * across commits.
     */
    protected void formatInline(VirtualFile vf) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        ApplicationManager.getApplication().runWriteAction(() ->
            CommandProcessor.getInstance().executeCommand(project, () -> {
                PsiDocumentManager.getInstance(project).commitAllDocuments();
                new OptimizeImportsProcessor(project, psiFile).run();
                new ReformatCodeProcessor(psiFile, false).run();
                PsiDocumentManager.getInstance(project).commitAllDocuments();
            }, "Auto-Format (Symbol Edit)", null)
        );
    }

    protected @Nullable SymbolLocation resolveSymbol(String pathStr, String symbolName, @Nullable Integer lineHint) {
        return ApplicationManager.getApplication().runReadAction((Computable<SymbolLocation>) () -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return null;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return null;

            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;

            List<SymbolLocation> matches = new ArrayList<>();
            psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    if (element instanceof PsiNamedElement named) {
                        String name = named.getName();
                        if (symbolName.equals(name)) {
                            String type = ToolUtils.classifyElement(element);
                            if (type != null) {
                                TextRange range = element.getTextRange();
                                int startLine = doc.getLineNumber(range.getStartOffset()) + 1;
                                int endLine = doc.getLineNumber(range.getEndOffset()) + 1;
                                matches.add(new SymbolLocation(startLine, endLine, type, name));
                            }
                        }
                    }
                    super.visitElement(element);
                }
            });

            if (matches.isEmpty()) return null;
            if (matches.size() == 1) return matches.getFirst();

            if (lineHint != null) {
                for (SymbolLocation loc : matches) {
                    if (loc.startLine == lineHint) return loc;
                }
                SymbolLocation closest = matches.getFirst();
                int minDist = Math.abs(closest.startLine - lineHint);
                for (SymbolLocation loc : matches) {
                    int dist = Math.abs(loc.startLine - lineHint);
                    if (dist < minDist) {
                        closest = loc;
                        minDist = dist;
                    }
                }
                return closest;
            }
            return matches.getFirst();
        });
    }

    protected static @Nullable String validateArgs(JsonObject args, String contentParam) {
        if (!args.has(PARAM_FILE) || args.get(PARAM_FILE).isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        if (!args.has(PARAM_SYMBOL) || args.get(PARAM_SYMBOL).isJsonNull())
            return "Missing required parameter: symbol";
        if (!args.has(contentParam) || args.get(contentParam).isJsonNull())
            return "Missing required parameter: " + contentParam;
        return null;
    }

    protected String symbolNotFoundMessage(String pathStr, String symbolName, @Nullable Integer lineHint) {
        String available = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "";
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return "";

            List<String> symbols = new ArrayList<>();
            psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    if (element instanceof PsiNamedElement named && named.getName() != null) {
                        String type = ToolUtils.classifyElement(element);
                        if (type != null) {
                            int line = doc.getLineNumber(element.getTextOffset()) + 1;
                            symbols.add(type + " " + named.getName() + " (line " + line + ")");
                        }
                    }
                    super.visitElement(element);
                }
            });
            if (symbols.isEmpty()) return "";
            return "\nAvailable symbols: " + String.join(", ", symbols);
        });

        String msg = SYMBOL_PREFIX + symbolName + "' not found in " + pathStr;
        if (lineHint != null) msg += " (near line " + lineHint + ")";
        return msg + available;
    }
}
