package com.github.copilot.intellij.services;

import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persists plugin settings using IntelliJ's PropertiesComponent.
 */
public final class CopilotSettings {
    private static final String KEY_SELECTED_MODEL = "copilot.selectedModel";
    private static final String KEY_SESSION_MODE = "copilot.sessionMode";
    private static final String KEY_MONTHLY_REQUESTS = "copilot.monthlyRequests";
    private static final String KEY_MONTHLY_COST = "copilot.monthlyCost";
    private static final String KEY_USAGE_RESET_MONTH = "copilot.usageResetMonth";
    private static final String KEY_PROMPT_TIMEOUT = "copilot.promptTimeout";
    private static final String KEY_MAX_TOOL_CALLS = "copilot.maxToolCallsPerTurn";
    private static final int DEFAULT_PROMPT_TIMEOUT = 300;
    private static final int DEFAULT_MAX_TOOL_CALLS = 0;

    private CopilotSettings() {
    }

    /** Inactivity timeout in seconds (no activity = stop agent). */
    public static int getPromptTimeout() {
        return PropertiesComponent.getInstance().getInt(KEY_PROMPT_TIMEOUT, DEFAULT_PROMPT_TIMEOUT);
    }

    public static void setPromptTimeout(int seconds) {
        PropertiesComponent.getInstance().setValue(KEY_PROMPT_TIMEOUT, seconds, DEFAULT_PROMPT_TIMEOUT);
    }

    /** Max tool calls per turn (0 = unlimited). */
    public static int getMaxToolCallsPerTurn() {
        return PropertiesComponent.getInstance().getInt(KEY_MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS);
    }

    public static void setMaxToolCallsPerTurn(int count) {
        PropertiesComponent.getInstance().setValue(KEY_MAX_TOOL_CALLS, count, DEFAULT_MAX_TOOL_CALLS);
    }

    @Nullable
    public static String getSelectedModel() {
        return PropertiesComponent.getInstance().getValue(KEY_SELECTED_MODEL);
    }

    public static void setSelectedModel(@NotNull String modelId) {
        PropertiesComponent.getInstance().setValue(KEY_SELECTED_MODEL, modelId);
    }

    @NotNull
    public static String getSessionMode() {
        return PropertiesComponent.getInstance().getValue(KEY_SESSION_MODE, "agent");
    }

    public static void setSessionMode(@NotNull String mode) {
        PropertiesComponent.getInstance().setValue(KEY_SESSION_MODE, mode);
    }

    public static int getMonthlyRequests() {
        return PropertiesComponent.getInstance().getInt(KEY_MONTHLY_REQUESTS, 0);
    }

    public static void setMonthlyRequests(int count) {
        PropertiesComponent.getInstance().setValue(KEY_MONTHLY_REQUESTS, count, 0);
    }

    public static double getMonthlyCost() {
        String val = PropertiesComponent.getInstance().getValue(KEY_MONTHLY_COST, "0.0");
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static void setMonthlyCost(double cost) {
        PropertiesComponent.getInstance().setValue(KEY_MONTHLY_COST, String.valueOf(cost));
    }

    /**
     * Returns the month string (YYYY-MM) for the last usage reset.
     */
    @NotNull
    public static String getUsageResetMonth() {
        return PropertiesComponent.getInstance().getValue(KEY_USAGE_RESET_MONTH, "");
    }

    public static void setUsageResetMonth(@NotNull String month) {
        PropertiesComponent.getInstance().setValue(KEY_USAGE_RESET_MONTH, month);
    }
}
