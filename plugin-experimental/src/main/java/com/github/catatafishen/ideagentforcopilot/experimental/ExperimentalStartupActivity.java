package com.github.catatafishen.ideagentforcopilot.experimental;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService;
import com.github.catatafishen.ideagentforcopilot.psi.tools.database.ExecuteQueryTool;
import com.github.catatafishen.ideagentforcopilot.psi.tools.quality.RunInspectionsTool;
import com.github.catatafishen.ideagentforcopilot.services.MacroToolRegistrar;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity for the experimental plugin variant.
 * Registers experimental tools (RunInspectionsTool, ExecuteQueryTool) and syncs macro tool registrations.
 */
public final class ExperimentalStartupActivity implements ProjectActivity {

    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        MacroToolRegistrar.getInstance(project).syncRegistrations();
        PsiBridgeService.getInstance(project).registerTool(new RunInspectionsTool(project));
        if (PlatformApiCompat.isPluginInstalled(DATABASE_PLUGIN_ID)) {
            PsiBridgeService.getInstance(project).registerTool(new ExecuteQueryTool(project));
        }
        return Unit.INSTANCE;
    }
}
