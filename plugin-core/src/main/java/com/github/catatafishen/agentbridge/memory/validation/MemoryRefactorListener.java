package com.github.catatafishen.agentbridge.memory.validation;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.kg.KnowledgeGraph;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import com.intellij.refactoring.listeners.RefactoringEventData;
import com.intellij.refactoring.listeners.RefactoringEventListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Listens for refactoring events (rename, move) and updates memory evidence references
 * in both the Lucene index and the Knowledge Graph to keep them current.
 *
 * <p>On rename/move, captures the old FQN from {@code refactoringStarted} and resolves
 * the new FQN from {@code refactoringDone}, then updates all evidence strings that
 * reference the old name.
 */
public final class MemoryRefactorListener implements RefactoringEventListener, Disposable {

    private static final Logger LOG = Logger.getInstance(MemoryRefactorListener.class);

    private static final String RENAME_ID = "refactoring.rename";
    private static final String MOVE_ID = "refactoring.move";

    private final Project project;

    /**
     * Captured from {@code refactoringStarted} — the old FQN before rename/move.
     */
    private volatile @Nullable String pendingOldFqn;

    public MemoryRefactorListener(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Register this listener on the project message bus.
     *
     * @param parentDisposable controls the subscription lifetime
     */
    public void register(@NotNull Disposable parentDisposable) {
        project.getMessageBus()
            .connect(parentDisposable)
            .subscribe(RefactoringEventListener.REFACTORING_EVENT_TOPIC, this);
    }

    @Override
    public void refactoringStarted(@NotNull String refactoringId, @Nullable RefactoringEventData beforeData) {
        if (isIrrelevantRefactoring(refactoringId)) return;
        if (beforeData == null) return;

        PsiElement element = beforeData.getUserData(RefactoringEventData.PSI_ELEMENT_KEY);
        if (element == null) return;

        pendingOldFqn = extractFqn(element);
    }

    @Override
    public void refactoringDone(@NotNull String refactoringId, @Nullable RefactoringEventData afterData) {
        String oldFqn = pendingOldFqn;
        pendingOldFqn = null;

        if (oldFqn == null) return;
        if (isIrrelevantRefactoring(refactoringId)) return;
        if (afterData == null) return;

        PsiElement element = afterData.getUserData(RefactoringEventData.PSI_ELEMENT_KEY);
        if (element == null) return;

        String newFqn = extractFqn(element);
        if (newFqn == null || newFqn.equals(oldFqn)) return;

        updateEvidenceReferences(oldFqn, newFqn);
    }

    @Override
    public void undoRefactoring(@NotNull String refactoringId) {
        pendingOldFqn = null;
    }

    @Override
    public void dispose() {
        pendingOldFqn = null;
    }

    private static boolean isIrrelevantRefactoring(@NotNull String refactoringId) {
        return !RENAME_ID.equals(refactoringId) && !MOVE_ID.equals(refactoringId);
    }

    /**
     * Extract a fully-qualified name from a PSI element.
     * Returns null for elements that don't have a meaningful FQN.
     */
    static @Nullable String extractFqn(@NotNull PsiElement element) {
        if (element instanceof PsiClass psiClass) {
            return psiClass.getQualifiedName();
        }
        if (element instanceof PsiMethod psiMethod) {
            PsiClass containingClass = psiMethod.getContainingClass();
            if (containingClass != null && containingClass.getQualifiedName() != null) {
                return containingClass.getQualifiedName() + "." + psiMethod.getName();
            }
        }
        if (element instanceof PsiFile psiFile) {
            return psiFile.getVirtualFile() != null ? psiFile.getVirtualFile().getPath() : null;
        }
        if (element instanceof PsiNamedElement named) {
            return named.getName();
        }
        return null;
    }

    private void updateEvidenceReferences(@NotNull String oldFqn, @NotNull String newFqn) {
        MemoryService memoryService = project.getService(MemoryService.class);
        if (memoryService == null) return;

        MemoryStore store = memoryService.getStore();
        KnowledgeGraph kg = memoryService.getKnowledgeGraph();

        int storeUpdated = 0;
        int kgUpdated = 0;

        if (store != null) {
            try {
                storeUpdated = store.updateEvidenceRef(oldFqn, newFqn);
            } catch (IOException e) {
                LOG.warn("Failed to update memory store evidence: " + oldFqn + " → " + newFqn, e);
            }
        }

        if (kg != null) {
            try {
                kgUpdated = kg.updateEvidence(oldFqn, newFqn);
            } catch (IOException e) {
                LOG.warn("Failed to update KG evidence: " + oldFqn + " → " + newFqn, e);
            }
        }

        if (storeUpdated > 0 || kgUpdated > 0) {
            LOG.info("Refactor evidence update: " + oldFqn + " → " + newFqn
                + " (store: " + storeUpdated + ", kg: " + kgUpdated + ")");
        }
    }
}
