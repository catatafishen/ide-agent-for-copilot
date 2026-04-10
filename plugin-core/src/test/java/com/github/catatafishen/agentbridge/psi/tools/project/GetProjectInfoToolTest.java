package com.github.catatafishen.agentbridge.psi.tools.project;

import com.google.gson.JsonObject;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform tests for {@link GetProjectInfoTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be {@code public void testXxx()}.
 * Run via Gradle only: {@code ./gradlew :plugin-core:test}.
 */
public class GetProjectInfoToolTest extends BasePlatformTestCase {

    private GetProjectInfoTool tool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tool = new GetProjectInfoTool(getProject());
    }

    public void testExecuteReturnsNonEmptyString() {
        String result = tool.execute(new JsonObject());
        assertNotNull(result);
        assertFalse("Result should not be empty", result.isBlank());
    }

    public void testOutputContainsProjectName() {
        String result = tool.execute(new JsonObject());
        assertTrue("Expected 'Project: ' prefix, got: " + result,
            result.contains("Project: "));
    }

    public void testOutputContainsPath() {
        String result = tool.execute(new JsonObject());
        assertTrue("Expected 'Path: ' prefix, got: " + result,
            result.contains("Path: "));
    }

    public void testOutputContainsAgentWorkspace() {
        String result = tool.execute(new JsonObject());
        assertTrue("Expected Agent Workspace line, got: " + result,
            result.contains("Agent Workspace: "));
    }

    public void testOutputContainsModulesSection() {
        String result = tool.execute(new JsonObject());
        assertTrue("Expected Modules section, got: " + result,
            result.contains("Modules ("));
    }

    public void testOutputContainsOsInfo() {
        String result = tool.execute(new JsonObject());
        assertTrue("Expected OS line, got: " + result,
            result.contains("OS: "));
    }

    public void testOutputContainsJavaInfo() {
        String result = tool.execute(new JsonObject());
        assertTrue("Expected Java line, got: " + result,
            result.contains("Java: "));
    }

    public void testProjectNameMatchesTestProject() {
        String result = tool.execute(new JsonObject());
        String projectName = getProject().getName();
        assertTrue("Expected project name '" + projectName + "' in output: " + result,
            result.contains("Project: " + projectName));
    }

    public void testProjectPathMatchesTestProject() {
        String result = tool.execute(new JsonObject());
        String basePath = getProject().getBasePath();
        assertNotNull(basePath);
        assertTrue("Expected base path '" + basePath + "' in output: " + result,
            result.contains("Path: " + basePath));
    }
}
