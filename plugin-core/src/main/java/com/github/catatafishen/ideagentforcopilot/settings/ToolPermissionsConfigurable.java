package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.ui.PermissionsPanel;
import com.intellij.openapi.options.Configurable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Settings page wrapping the Tool Permissions panel.
 * Appears under Settings > Tools > IDE Agent for Copilot > Tool Permissions.
 */
public final class ToolPermissionsConfigurable implements Configurable {

    private PermissionsPanel permissionsPanel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Tool Permissions";
    }

    @Override
    public @Nullable JComponent createComponent() {
        permissionsPanel = new PermissionsPanel();
        JComponent comp = permissionsPanel.getComponent();
        comp.setPreferredSize(JBUI.size(740, 560));
        comp.setMinimumSize(JBUI.size(500, 340));
        return comp;
    }

    @Override
    public boolean isModified() {
        return permissionsPanel != null && permissionsPanel.isModified();
    }

    @Override
    public void apply() {
        if (permissionsPanel != null) {
            permissionsPanel.save();
        }
    }

    @Override
    public void reset() {
        if (permissionsPanel != null) {
            permissionsPanel.reload();
        }
    }

    @Override
    public void disposeUIResources() {
        permissionsPanel = null;
    }
}
