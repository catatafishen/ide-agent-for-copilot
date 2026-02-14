package com.github.copilot.intellij.services;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Tests for CopilotSettings — persistent storage via PropertiesComponent.
 * Extends BasePlatformTestCase to get a real IntelliJ application context.
 */
public class CopilotSettingsTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Clear any stale values from previous runs
        PropertiesComponent props = PropertiesComponent.getInstance();
        props.unsetValue("copilot.selectedModel");
        props.unsetValue("copilot.sessionMode");
        props.unsetValue("copilot.monthlyRequests");
        props.unsetValue("copilot.monthlyCost");
        props.unsetValue("copilot.usageResetMonth");
    }

    public void testSelectedModelDefaultNull() {
        assertNull(CopilotSettings.getSelectedModel());
    }

    public void testSetAndGetSelectedModel() {
        CopilotSettings.setSelectedModel("gpt-4.1");
        assertEquals("gpt-4.1", CopilotSettings.getSelectedModel());
    }

    public void testSelectedModelOverwrite() {
        CopilotSettings.setSelectedModel("gpt-4.1");
        CopilotSettings.setSelectedModel("claude-sonnet-4.5");
        assertEquals("claude-sonnet-4.5", CopilotSettings.getSelectedModel());
    }

    public void testSessionModeDefaultAgent() {
        assertEquals("agent", CopilotSettings.getSessionMode());
    }

    public void testSetAndGetSessionMode() {
        CopilotSettings.setSessionMode("plan");
        assertEquals("plan", CopilotSettings.getSessionMode());
    }

    public void testMonthlyRequestsDefault() {
        assertEquals(0, CopilotSettings.getMonthlyRequests());
    }

    public void testSetAndGetMonthlyRequests() {
        CopilotSettings.setMonthlyRequests(42);
        assertEquals(42, CopilotSettings.getMonthlyRequests());
    }

    public void testMonthlyCostDefault() {
        assertEquals(0.0, CopilotSettings.getMonthlyCost(), 0.001);
    }

    public void testSetAndGetMonthlyCost() {
        CopilotSettings.setMonthlyCost(12.50);
        assertEquals(12.50, CopilotSettings.getMonthlyCost(), 0.001);
    }

    public void testUsageResetMonthDefault() {
        assertEquals("", CopilotSettings.getUsageResetMonth());
    }

    public void testSetAndGetUsageResetMonth() {
        CopilotSettings.setUsageResetMonth("2026-02");
        assertEquals("2026-02", CopilotSettings.getUsageResetMonth());
    }
}
