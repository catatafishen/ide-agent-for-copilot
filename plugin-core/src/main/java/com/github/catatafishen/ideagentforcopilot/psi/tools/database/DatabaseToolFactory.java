package com.github.catatafishen.ideagentforcopilot.psi.tools.database;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Creates database tools when the Database plugin is installed.
 * <p>
 * Only read-only tools (list sources, list tables, get schema) are registered here
 * because they use the public DAS model API ({@code database-plugin-frontend.jar}).
 * <p>
 * The {@code database_execute_query} tool requires internal Database plugin APIs
 * ({@code DatabaseConnectionManager}, {@code RemoteConnection}) and is registered
 * separately in the experimental plugin variant.
 */
public final class DatabaseToolFactory {

    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";

    private DatabaseToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        if (!PlatformApiCompat.isPluginInstalled(DATABASE_PLUGIN_ID)) {
            return List.of();
        }
        return List.of(
            new ListDataSourcesTool(project),
            new ListTablesTool(project),
            new GetSchemaTool(project)
        );
    }
}
