package com.github.catatafishen.agentbridge.psi.tools.file;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WriteFileTool#collectPsiErrors} — specifically the node-count limit
 * that caps PSI tree traversal at {@link WriteFileTool#MAX_PSI_NODES}.
 * <p>
 * Uses JDK dynamic proxies to create lightweight mock PSI elements without needing
 * the full IntelliJ platform.
 */
@DisplayName("WriteFileTool.collectPsiErrors node limit")
class WriteFileToolCollectPsiErrorsTest {

    private static PsiElement mockElement(PsiElement... children) {
        return (PsiElement) Proxy.newProxyInstance(
            PsiElement.class.getClassLoader(),
            new Class[]{PsiElement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getFirstChild" -> children.length > 0 ? children[0] : null;
                case "getNextSibling" -> null;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "MockPsiElement";
                default -> null;
            });
    }

    private static PsiErrorElement mockError(int offset, String description) {
        return (PsiErrorElement) Proxy.newProxyInstance(
            PsiErrorElement.class.getClassLoader(),
            new Class[]{PsiErrorElement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getTextOffset" -> offset;
                case "getErrorDescription" -> description;
                case "getFirstChild", "getNextSibling" -> null;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "MockPsiError(" + description + ")";
                default -> null;
            });
    }

    private static Document mockDocument() {
        return (Document) Proxy.newProxyInstance(
            Document.class.getClassLoader(),
            new Class[]{Document.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getLineNumber" -> 0;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> null;
            });
    }

    /**
     * Builds a flat tree: a root element with N leaf children linked via getNextSibling.
     */
    private static PsiElement flatTree(int size) {
        PsiElement[] children = new PsiElement[size];
        for (int i = size - 1; i >= 0; i--) {
            int idx = i;
            PsiElement next = (i < size - 1) ? children[i + 1] : null;
            children[i] = (PsiElement) Proxy.newProxyInstance(
                PsiElement.class.getClassLoader(),
                new Class[]{PsiElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getFirstChild" -> null;
                    case "getNextSibling" -> next;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Leaf-" + idx;
                    default -> null;
                });
        }
        PsiElement firstChild = children[0];
        return (PsiElement) Proxy.newProxyInstance(
            PsiElement.class.getClassLoader(),
            new Class[]{PsiElement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getFirstChild" -> firstChild;
                case "getNextSibling" -> null;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Root(" + size + " children)";
                default -> null;
            });
    }

    @Test
    @DisplayName("MAX_PSI_NODES is 10,000")
    void maxPsiNodesConstant() {
        assertEquals(10_000, WriteFileTool.MAX_PSI_NODES);
    }

    @Nested
    @DisplayName("collectPsiErrors")
    class CollectPsiErrors {

        @Test
        @DisplayName("collects error from a single PsiErrorElement")
        void singleError() {
            PsiErrorElement error = mockError(0, "unexpected token");
            Document doc = mockDocument();

            List<String> errors = new ArrayList<>();
            int[] nodeCount = {0};
            WriteFileTool.collectPsiErrors(error, doc, errors, nodeCount);

            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().contains("unexpected token"));
            assertEquals(1, nodeCount[0]);
        }

        @Test
        @DisplayName("collects no errors from a non-error element")
        void noErrors() {
            PsiElement element = mockElement();
            Document doc = mockDocument();

            List<String> errors = new ArrayList<>();
            int[] nodeCount = {0};
            WriteFileTool.collectPsiErrors(element, doc, errors, nodeCount);

            assertTrue(errors.isEmpty());
            assertEquals(1, nodeCount[0]);
        }

        @Test
        @DisplayName("handles null document gracefully (line = -1)")
        void nullDocument() {
            PsiErrorElement error = mockError(10, "syntax error");

            List<String> errors = new ArrayList<>();
            int[] nodeCount = {0};
            WriteFileTool.collectPsiErrors(error, null, errors, nodeCount);

            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().contains("Line -1"));
        }

        @Test
        @DisplayName("stops traversal at MAX_PSI_NODES")
        void stopsAtNodeLimit() {
            int overLimit = WriteFileTool.MAX_PSI_NODES + 100;
            PsiElement root = flatTree(overLimit);
            Document doc = mockDocument();

            List<String> errors = new ArrayList<>();
            int[] nodeCount = {0};
            WriteFileTool.collectPsiErrors(root, doc, errors, nodeCount);

            assertTrue(nodeCount[0] <= WriteFileTool.MAX_PSI_NODES + 1,
                "Node count should not exceed MAX_PSI_NODES + 1, was " + nodeCount[0]);
        }

        @Test
        @DisplayName("traverses all nodes when under the limit")
        void traversesAllUnderLimit() {
            PsiElement root = flatTree(100);
            Document doc = mockDocument();

            List<String> errors = new ArrayList<>();
            int[] nodeCount = {0};
            WriteFileTool.collectPsiErrors(root, doc, errors, nodeCount);

            // root (1) + 100 children = 101
            assertEquals(101, nodeCount[0]);
        }

        @Test
        @DisplayName("pre-incremented nodeCount at limit skips processing")
        void preIncrementedNodeCount() {
            PsiElement element = mockElement();
            Document doc = mockDocument();

            List<String> errors = new ArrayList<>();
            int[] nodeCount = {WriteFileTool.MAX_PSI_NODES};
            WriteFileTool.collectPsiErrors(element, doc, errors, nodeCount);

            assertTrue(errors.isEmpty());
            assertEquals(WriteFileTool.MAX_PSI_NODES + 1, nodeCount[0]);
        }
    }
}
