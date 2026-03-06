package com.github.catatafishen.ideagentforcopilot.experimental;

import com.github.catatafishen.ideagentforcopilot.services.MacroToolRegistrar;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity that syncs macro tool registrations when the project opens.
 * This is the experimental variant's entry point for the macro tool feature.
 */
public final class MacroStartupActivity implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        MacroToolRegistrar.getInstance(project).syncRegistrations();
        return Unit.INSTANCE;
    }
}
