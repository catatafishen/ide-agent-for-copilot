package com.github.catatafishen.agentbridge.memory.validation;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import com.intellij.openapi.vfs.VirtualFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MemoryRefactorListener#extractFqn(PsiElement)}.
 */
class MemoryRefactorListenerTest {

    @Nested
    @DisplayName("extractFqn")
    class ExtractFqnTests {

        @Test
        @DisplayName("extracts FQN from PsiClass")
        void extractsFqnFromPsiClass() {
            PsiClass psiClass = mock(PsiClass.class);
            when(psiClass.getQualifiedName()).thenReturn("com.example.UserService");

            assertEquals("com.example.UserService", MemoryRefactorListener.extractFqn(psiClass));
        }

        @Test
        @DisplayName("returns null for PsiClass without qualified name")
        void returnsNullForClassWithoutQualifiedName() {
            PsiClass psiClass = mock(PsiClass.class);
            when(psiClass.getQualifiedName()).thenReturn(null);

            assertNull(MemoryRefactorListener.extractFqn(psiClass));
        }

        @Test
        @DisplayName("extracts FQN from PsiMethod with containing class")
        void extractsFqnFromPsiMethod() {
            PsiClass containingClass = mock(PsiClass.class);
            when(containingClass.getQualifiedName()).thenReturn("com.example.UserService");

            PsiMethod psiMethod = mock(PsiMethod.class);
            when(psiMethod.getName()).thenReturn("authenticate");
            when(psiMethod.getContainingClass()).thenReturn(containingClass);

            assertEquals("com.example.UserService.authenticate",
                MemoryRefactorListener.extractFqn(psiMethod));
        }

        @Test
        @DisplayName("returns null for PsiMethod without containing class")
        void returnsNullForMethodWithoutClass() {
            PsiMethod psiMethod = mock(PsiMethod.class);
            when(psiMethod.getContainingClass()).thenReturn(null);

            assertNull(MemoryRefactorListener.extractFqn(psiMethod));
        }

        @Test
        @DisplayName("returns null for PsiMethod whose class has no FQN")
        void returnsNullForMethodWhoseClassHasNoFqn() {
            PsiClass containingClass = mock(PsiClass.class);
            when(containingClass.getQualifiedName()).thenReturn(null);

            PsiMethod psiMethod = mock(PsiMethod.class);
            when(psiMethod.getContainingClass()).thenReturn(containingClass);

            assertNull(MemoryRefactorListener.extractFqn(psiMethod));
        }

        @Test
        @DisplayName("extracts path from PsiFile")
        void extractsPathFromPsiFile() {
            VirtualFile vFile = mock(VirtualFile.class);
            when(vFile.getPath()).thenReturn("/src/main/java/UserService.java");

            PsiFile psiFile = mock(PsiFile.class);
            when(psiFile.getVirtualFile()).thenReturn(vFile);

            assertEquals("/src/main/java/UserService.java",
                MemoryRefactorListener.extractFqn(psiFile));
        }

        @Test
        @DisplayName("returns null for PsiFile without virtual file")
        void returnsNullForPsiFileWithoutVirtualFile() {
            PsiFile psiFile = mock(PsiFile.class);
            when(psiFile.getVirtualFile()).thenReturn(null);

            assertNull(MemoryRefactorListener.extractFqn(psiFile));
        }

        @Test
        @DisplayName("extracts name from PsiNamedElement")
        void extractsNameFromPsiNamedElement() {
            PsiNamedElement named = mock(PsiNamedElement.class);
            when(named.getName()).thenReturn("myVariable");

            assertEquals("myVariable", MemoryRefactorListener.extractFqn(named));
        }

        @Test
        @DisplayName("returns null for unknown PsiElement type")
        void returnsNullForUnknownElement() {
            PsiElement element = mock(PsiElement.class);
            assertNull(MemoryRefactorListener.extractFqn(element));
        }
    }
}
