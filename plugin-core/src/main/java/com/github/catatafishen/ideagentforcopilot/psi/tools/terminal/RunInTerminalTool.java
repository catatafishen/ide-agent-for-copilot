package com.github.catatafishen.ideagentforcopilot.psi.tools.terminal;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command in IntelliJ's integrated terminal.
 */
public final class RunInTerminalTool extends TerminalTool {

    public RunInTerminalTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "run_in_terminal";
    }

    @Override
    public @NotNull String displayName() {
        return "Run in Terminal";
    }

    @Override
    public @NotNull String description() {
        return "Run a command in IntelliJ's integrated terminal";
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run in terminal: {command}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"command", TYPE_STRING, "The command to run in the terminal"},
            {"tab_name", TYPE_STRING, "Name for the terminal tab. If omitted, reuses the most recent agent-created tab or creates a new one"},
            {"new_tab", TYPE_BOOLEAN, "If true, always create a new terminal tab instead of reusing an existing one"},
            {"shell", TYPE_STRING, "Shell to use (e.g., 'bash', 'zsh'). If omitted, uses the default shell"}
        }, "command");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        String command = args.get("command").getAsString();
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;
        boolean newTab = args.has("new_tab") && args.get("new_tab").getAsBoolean();
        String shell = args.has("shell") ? args.get("shell").getAsString() : null;

        // Flush all editor buffers to disk so terminal commands see current content
        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() ->
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()));

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var managerClass = Class.forName(TERMINAL_MANAGER_CLASS);
                var manager = managerClass.getMethod(GET_INSTANCE_METHOD, Project.class).invoke(null, project);

                var result = getOrCreateTerminalWidget(managerClass, manager, tabName, newTab, shell, command);
                sendTerminalCommand(result.widget(), command);

                resultFuture.complete("Command sent to terminal '" + result.tabName() + "': " + command +
                    "\n\nNote: Use read_terminal_output to read terminal content, or run_command if you need output returned directly.");

            } catch (ClassNotFoundException e) {
                resultFuture.complete("Terminal plugin not available. Use run_command tool instead.");
            } catch (Exception e) {
                LOG.warn("Failed to open terminal", e);
                resultFuture.complete("Failed to open terminal: " + e.getMessage() + ". Use run_command tool instead.");
            }
        });

        try {
            return resultFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Terminal opened (response timed out, but command was likely sent).";
        } catch (Exception e) {
            return "Terminal opened (response timed out, but command was likely sent).";
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }
}
