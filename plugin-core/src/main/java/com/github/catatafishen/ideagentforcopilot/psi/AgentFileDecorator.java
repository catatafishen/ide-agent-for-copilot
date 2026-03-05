package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.vfs.VirtualFile;

import java.awt.*;

/**
 * Decorates files in the Project View with read/write markers
 * based on agent activity during the current session.
 * Uses a short location suffix and subtle background tint.
 */
public final class AgentFileDecorator implements ProjectViewNodeDecorator {

    private static final Color BG_READ = new Color(100, 140, 200, 25);
    private static final Color BG_WRITE = new Color(100, 180, 100, 30);
    private static final Color BG_READ_WRITE = new Color(180, 150, 80, 30);

    @Override
    public void decorate(@org.jetbrains.annotations.NotNull ProjectViewNode<?> node,
                         @org.jetbrains.annotations.NotNull PresentationData data) {
        VirtualFile vf = node.getVirtualFile();
        if (vf == null || vf.isDirectory()) return;

        FileAccessTracker.AccessType access = FileAccessTracker.getAccess(vf);
        if (access == null) return;

        switch (access) {
            case READ -> {
                data.setLocationString("R");
                data.setBackground(BG_READ);
                data.setTooltip("Read by agent");
            }
            case WRITE -> {
                data.setLocationString("W");
                data.setBackground(BG_WRITE);
                data.setTooltip("Edited by agent");
            }
            case READ_WRITE -> {
                data.setLocationString("RW");
                data.setBackground(BG_READ_WRITE);
                data.setTooltip("Read and edited by agent");
            }
        }
    }
}
