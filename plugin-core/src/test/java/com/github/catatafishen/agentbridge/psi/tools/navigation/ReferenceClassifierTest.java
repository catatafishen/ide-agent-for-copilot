package com.github.catatafishen.agentbridge.psi.tools.navigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ReferenceClassifier}.
 */
class ReferenceClassifierTest {

    @Nested
    @DisplayName("formatContext")
    class FormatContext {

        @Test
        @DisplayName("usage type with container includes 'in container'")
        void withContainer() {
            assertEquals("[CALL in processRequest]",
                ReferenceClassifier.formatContext("CALL", "processRequest"));
        }

        @Test
        @DisplayName("usage type without container shows type only")
        void withoutContainer() {
            assertEquals("[IMPORT]",
                ReferenceClassifier.formatContext("IMPORT", null));
        }

        @Test
        @DisplayName("REF usage type with container")
        void refWithContainer() {
            assertEquals("[REF in MyClass]",
                ReferenceClassifier.formatContext("REF", "MyClass"));
        }

        @Test
        @DisplayName("all usage type constants are non-null")
        void usageTypeConstants() {
            assertEquals("CALL", ReferenceClassifier.USAGE_METHOD_CALL);
            assertEquals("FIELD_ACCESS", ReferenceClassifier.USAGE_FIELD_ACCESS);
            assertEquals("IMPORT", ReferenceClassifier.USAGE_IMPORT);
            assertEquals("TYPE_REF", ReferenceClassifier.USAGE_TYPE_REF);
            assertEquals("ANNOTATION", ReferenceClassifier.USAGE_ANNOTATION);
            assertEquals("EXTENDS", ReferenceClassifier.USAGE_EXTENDS);
            assertEquals("IMPLEMENTS", ReferenceClassifier.USAGE_IMPLEMENTS);
            assertEquals("NEW", ReferenceClassifier.USAGE_NEW);
            assertEquals("COMMENT", ReferenceClassifier.USAGE_COMMENT);
            assertEquals("REF", ReferenceClassifier.USAGE_REFERENCE);
        }
    }

    @Nested
    @DisplayName("classifyByClassName")
    class ClassifyByClassName {

        @ParameterizedTest
        @ValueSource(strings = {"PsiImportStatement", "KtImportDirective", "ES6ImportDeclaration", "PsiImport"})
        @DisplayName("import-related class names → IMPORT")
        void importsClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_IMPORT,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiMethodCallExpression", "KtCallExpression", "JSFunctionCall"})
        @DisplayName("call-related class names → CALL")
        void callsClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_METHOD_CALL,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiNewExpression", "KtConstructorCall", "PsiObjectCreation"})
        @DisplayName("new/constructor class names → NEW")
        void newExpressionClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_NEW,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiAnnotation", "KtAnnotationEntry"})
        @DisplayName("annotation class names → ANNOTATION")
        void annotationClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_ANNOTATION,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiReferenceExtendsListImpl", "ExtendsClause", "KtSuperTypeList"})
        @DisplayName("extends class names → EXTENDS")
        void extendsClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_EXTENDS,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiReferenceImplementsList", "ImplementsClause"})
        @DisplayName("implements class names → IMPLEMENTS")
        void implementsClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_IMPLEMENTS,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiComment", "PsiDocComment", "KDoc"})
        @DisplayName("comment class names → COMMENT")
        void commentClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_COMMENT,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiTypeElement", "KtTypeReference", "KtUserType", "JSTypeReference"})
        @DisplayName("type reference class names → TYPE_REF")
        void typeRefClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_TYPE_REF,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiFieldAccess", "KtQualifiedAccess"})
        @DisplayName("field access class names → FIELD_ACCESS")
        void fieldAccessClassified(String className) {
            assertEquals(ReferenceClassifier.USAGE_FIELD_ACCESS,
                ReferenceClassifier.classifyByClassName(className));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PsiReferenceExpression", "PsiWhiteSpace", "SomeRandomClass"})
        @DisplayName("unrecognized class names → null")
        void unrecognizedReturnsNull(String className) {
            assertNull(ReferenceClassifier.classifyByClassName(className));
        }
    }
}
