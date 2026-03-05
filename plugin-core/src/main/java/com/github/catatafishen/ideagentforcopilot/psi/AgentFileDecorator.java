package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleTextAttributes;

import java.awt.*;

/**
 * Decorates files in the Project View with read/write markers
 * based on agent activity during the current session.
 */
public final class AgentFileDecorator implements ProjectViewNodeDecorator {

    private static final SimpleTextAttributes READ_ATTRS = new SimpleTextAttributes(
        SimpleTextAttributes.STYLE_ITALIC,
        new Color(100, 140, 200));
    private static final SimpleTextAttributes WRITE_ATTRS = new SimpleTextAttributes(
        SimpleTextAttributes.STYLE_BOLD,
        new Color(100, 180, 100));
    private static final SimpleTextAttributes READ_WRITE_ATTRS = new SimpleTextAttributes(
        SimpleTextAttributes.STYLE_BOLD | SimpleTextAttributes.STYLE_ITALIC,
        new Color(180, 150, 80));

    @Override
    public void decorate(ProjectViewNode<?> node, PresentationData data) {
        VirtualFile vf = node.getVirtualFile();
        if (vf == null || vf.isDirectory()) return;

        FileAccessTracker.AccessType access = FileAccessTracker.getAccess(vf);
        if (access == null) return;

        switch (access) {
            case READ -> data.addText("  ⟵ read", READ_ATTRS);
            case WRITE -> data.addText("  ⟵ edited", WRITE_ATTRS);
            case READ_WRITE -> data.addText("  ⟵ read+edited", READ_WRITE_ATTRS);
        }
    }
}
