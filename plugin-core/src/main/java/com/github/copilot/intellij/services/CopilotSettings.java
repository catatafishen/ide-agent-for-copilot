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

    private CopilotSettings() {
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
