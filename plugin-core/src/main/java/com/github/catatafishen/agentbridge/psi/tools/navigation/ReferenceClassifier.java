package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Classifies reference usage context by walking the PSI parent chain.
 * Language-agnostic: uses PSI class name heuristics rather than depending on
 * specific language plugin APIs (java-psi, kotlin-psi, etc.).
 * <p>
 * No IDE dependencies beyond the PSI API — fully unit-testable with mock elements.
 */
final class ReferenceClassifier {

    static final String USAGE_METHOD_CALL = "CALL";
    static final String USAGE_FIELD_ACCESS = "FIELD_ACCESS";
    static final String USAGE_IMPORT = "IMPORT";
    static final String USAGE_TYPE_REF = "TYPE_REF";
    static final String USAGE_ANNOTATION = "ANNOTATION";
    static final String USAGE_EXTENDS = "EXTENDS";
    static final String USAGE_IMPLEMENTS = "IMPLEMENTS";
    static final String USAGE_NEW = "NEW";
    static final String USAGE_COMMENT = "COMMENT";
    static final String USAGE_REFERENCE = "REF";

    private ReferenceClassifier() {
    }

    /**
     * Classifies the usage type of a reference by examining the PSI parent chain.
     *
     * @param ref the reference whose usage context to classify
     * @return a short label describing the usage type (e.g. "CALL", "IMPORT", "TYPE_REF")
     */
    static @NotNull String classifyUsage(@NotNull PsiReference ref) {
        return classifyUsage(ref.getElement());
    }

    /**
     * Classifies the usage type of an element by examining the PSI parent chain.
     */
    static @NotNull String classifyUsage(@NotNull PsiElement element) {
        PsiElement current = element;
        for (int i = 0; i < 5 && current != null; i++) {
            String classified = classifyByClassName(current.getClass().getSimpleName());
            if (classified != null) return classified;
            current = current.getParent();
        }
        return USAGE_REFERENCE;
    }

    /**
     * Maps a PSI class simple name to a usage type label, or returns null if unrecognized.
     */
    @SuppressWarnings("java:S1168") // null return is intentional — signals "no match, continue walking"
    private static @Nullable String classifyByClassName(@NotNull String cls) {
        if (containsAny(cls, "ImportStatement", "ImportDirective", "ImportDeclaration", "Import")) {
            return USAGE_IMPORT;
        }
        if (containsAny(cls, "MethodCall", "CallExpression", "FunctionCall")) {
            return USAGE_METHOD_CALL;
        }
        if (containsAny(cls, "NewExpression", "ConstructorCall", "ObjectCreation")) {
            return USAGE_NEW;
        }
        if (containsAny(cls, "Annotation", "AnnotationEntry")) {
            return USAGE_ANNOTATION;
        }
        if (containsAny(cls, "ExtendsListImpl", "ExtendsClause", "SuperTypeList")) {
            return USAGE_EXTENDS;
        }
        if (containsAny(cls, "ImplementsList", "ImplementsClause")) {
            return USAGE_IMPLEMENTS;
        }
        if (containsAny(cls, "Comment", "DocComment", "KDoc")) {
            return USAGE_COMMENT;
        }
        if (containsAny(cls, "TypeElement", "TypeReference", "UserType", "TypeAnnotation")) {
            return USAGE_TYPE_REF;
        }
        if (containsAny(cls, "FieldAccess", "QualifiedAccess")) {
            return USAGE_FIELD_ACCESS;
        }
        return null;
    }

    /**
     * Finds the name of the nearest containing named element (method, function, class).
     * Returns the simple name of the container, or {@code null} if at file level.
     */
    static @Nullable String findContainerName(@NotNull PsiElement element) {
        PsiElement current = element.getParent();
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof PsiNamedElement named) {
                String name = named.getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Formats the usage context as a bracket-enclosed annotation for reference output.
     * Example: {@code "[CALL in processRequest]"} or {@code "[IMPORT]"} (no container).
     */
    static @NotNull String formatContext(@NotNull String usageType, @Nullable String containerName) {
        if (containerName != null) {
            return "[" + usageType + " in " + containerName + "]";
        }
        return "[" + usageType + "]";
    }

    private static boolean containsAny(String className, String... patterns) {
        for (String pattern : patterns) {
            if (className.contains(pattern)) return true;
        }
        return false;
    }
}
