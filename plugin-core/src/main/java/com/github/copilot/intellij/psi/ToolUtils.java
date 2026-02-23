package com.github.copilot.intellij.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;

/**
 * Shared utility methods and constants extracted from PsiBridgeService
 * for use by individual tool handler classes.
 */
final class ToolUtils {

    // Error message constants
    static final String ERROR_PREFIX = "Error: ";
    static final String ERROR_FILE_NOT_FOUND = "File not found: ";
    static final String ERROR_CANNOT_PARSE = "Cannot parse file: ";
    static final String ERROR_PATH_REQUIRED = "Error: 'path' parameter is required";
    static final String JAVA_EXTENSION = ".java";
    static final String BUILD_DIR = "build";

    // Element type constants
    static final String ELEMENT_TYPE_CLASS = "class";
    static final String ELEMENT_TYPE_INTERFACE = "interface";
    static final String ELEMENT_TYPE_ENUM = "enum";
    static final String ELEMENT_TYPE_FIELD = "field";
    static final String ELEMENT_TYPE_FUNCTION = "function";
    static final String ELEMENT_TYPE_METHOD = "method";

    private ToolUtils() {
    }

    static String classifyElement(PsiElement element) {
        String cls = element.getClass().getSimpleName();

        // Java PSI
        if (cls.contains("PsiClass") && !cls.contains("Initializer")) {
            return classifyJavaClass(element);
        }
        if (cls.contains("PsiMethod")) return ELEMENT_TYPE_METHOD;
        if (cls.contains("PsiField")) return ELEMENT_TYPE_FIELD;
        if (cls.contains("PsiEnumConstant")) return ELEMENT_TYPE_FIELD;

        // Kotlin PSI
        String kotlinType = classifyKotlinElement(cls, element);
        if (kotlinType != null) return kotlinType;

        // Generic patterns
        if (cls.contains("Interface") && !cls.contains("Reference")) return ELEMENT_TYPE_INTERFACE;
        if (cls.contains("Enum") && cls.contains("Class")) return ELEMENT_TYPE_CLASS;

        return null;
    }

    static String classifyJavaClass(PsiElement element) {
        try {
            if ((boolean) element.getClass().getMethod("isInterface").invoke(element))
                return ELEMENT_TYPE_INTERFACE;
            if ((boolean) element.getClass().getMethod("isEnum").invoke(element)) return ELEMENT_TYPE_ENUM;
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                 | IllegalAccessException ignored) {
            // Reflection unavailable for this PsiClass variant
        }
        return ELEMENT_TYPE_CLASS;
    }

    static String classifyKotlinElement(String cls, PsiElement element) {
        return switch (cls) {
            case "KtClass", "KtObjectDeclaration" -> classifyKotlinClass(element);
            case "KtNamedFunction" -> ELEMENT_TYPE_FUNCTION;
            case "KtProperty" -> ELEMENT_TYPE_FIELD;
            case "KtTypeAlias" -> ELEMENT_TYPE_CLASS;
            default -> null;
        };
    }

    static String classifyKotlinClass(PsiElement element) {
        try {
            var isInterface = element.getClass().getMethod("isInterface");
            if ((boolean) isInterface.invoke(element)) return ELEMENT_TYPE_INTERFACE;
            var isEnum = element.getClass().getMethod("isEnum");
            if ((boolean) isEnum.invoke(element)) return ELEMENT_TYPE_ENUM;
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                 | IllegalAccessException ignored) {
            // Reflection unavailable for this Kotlin class variant
        }
        return ELEMENT_TYPE_CLASS;
    }

    static VirtualFile resolveVirtualFile(Project project, String path) {
        String normalized = path.replace('\\', '/');
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(normalized);
        if (vf != null) return vf;

        String basePath = project.getBasePath();
        if (basePath != null) {
            vf = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + normalized);
        }
        return vf;
    }

    static String relativize(String basePath, String filePath) {
        String base = basePath.replace('\\', '/');
        String file = filePath.replace('\\', '/');
        return file.startsWith(base + "/") ? file.substring(base.length() + 1) : file;
    }

    static String getLineText(Document doc, int lineIndex) {
        if (lineIndex < 0 || lineIndex >= doc.getLineCount()) return "";
        int start = doc.getLineStartOffset(lineIndex);
        int end = doc.getLineEndOffset(lineIndex);
        return doc.getText().substring(start, end).trim();
    }

    static boolean doesNotMatchGlob(String fileName, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return !fileName.matches(regex);
    }

    static String fileType(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(JAVA_EXTENSION)) return "Java";
        if (l.endsWith(".kt") || l.endsWith(".kts")) return "Kotlin";
        if (l.endsWith(".py")) return "Python";
        if (l.endsWith(".js") || l.endsWith(".jsx")) return "JavaScript";
        if (l.endsWith(".ts") || l.endsWith(".tsx")) return "TypeScript";
        if (l.endsWith(".go")) return "Go";
        if (l.endsWith(".xml")) return "XML";
        if (l.endsWith(".json")) return "JSON";
        if (l.endsWith(".gradle") || l.endsWith(".gradle.kts")) return "Gradle";
        if (l.endsWith(".yaml") || l.endsWith(".yml")) return "YAML";
        return "Other";
    }

    /**
     * Normalize text for fuzzy matching: replace common Unicode variants with ASCII equivalents.
     * This handles em-dashes, smart quotes, non-breaking spaces, etc. that LLMs often can't reproduce exactly.
     */
    static String normalizeForMatch(String s) {
        // First normalize line endings.
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        // Replace ALL non-ASCII chars with '?' - this matches what LLMs naturally do
        // when they can't reproduce em-dashes, smart quotes, etc.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c > 127 ? '?' : c);
        }
        return sb.toString();
    }

    /**
     * Finds the length in the original text that corresponds to a given length in the normalized text,
     * starting from the given position. This accounts for multibyte chars that normalize to single chars.
     */
    static int findOriginalLength(String original, int startIdx, int normalizedLen) {
        int origPos = startIdx;
        int normCount = 0;
        while (normCount < normalizedLen && origPos < original.length()) {
            char c = original.charAt(origPos);
            // CRLF counts as 1 normalized char
            if (c == '\r' && origPos + 1 < original.length() && original.charAt(origPos + 1) == '\n') {
                origPos += 2;
            } else {
                origPos++;
            }
            normCount++;
        }
        return origPos - startIdx;
    }

    static String truncateOutput(String output) {
        if (output.length() <= 8000) return output;
        return "...(truncated)\n" + output.substring(output.length() - 8000);
    }
}
