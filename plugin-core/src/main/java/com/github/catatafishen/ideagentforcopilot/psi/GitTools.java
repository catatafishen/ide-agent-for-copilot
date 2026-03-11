package com.github.catatafishen.ideagentforcopilot.psi;

import com.github.catatafishen.ideagentforcopilot.services.ToolBuilder;
import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.Category;
import com.github.catatafishen.ideagentforcopilot.services.ToolSchemas;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * Git tool handlers — thin wrappers delegating to {@link GitCommands}.
 * Each tool is defined as a {@link ToolDefinition} with full metadata.
 */
class GitTools extends AbstractToolHandler {

    private final List<ToolDefinition> definitions;

    GitTools(Project project, GitToolHandler gitToolHandler) {
        super(project);
        var c = new GitCommands(gitToolHandler);

        definitions = List.of(
            // Read-only git tools
            git("git_status", "Git Status", "Show working tree status", c::gitStatus)
                .readOnly().build(),
            git("git_diff", "Git Diff", "Show changes as a diff", c::gitDiff)
                .readOnly().build(),
            git("git_log", "Git Log", "Show commit history", c::gitLog)
                .readOnly().build(),
            git("git_blame", "Git Blame", "Show per-line authorship for a file", c::gitBlame)
                .readOnly().build(),
            git("git_show", "Git Show", "Show details and diff for a specific commit", c::gitShow)
                .readOnly().build(),
            git("get_file_history", "Get File History", "Get git commit history for a file, including renames", c::getFileHistory)
                .readOnly().build(),
            git("git_remote", "Git Remote", "List, add, remove, or update remote repositories", c::gitRemote)
                .readOnly().build(),

            // Write git tools
            git("git_commit", "Git Commit", "Commit staged changes with a message", c::gitCommit)
                .permissionTemplate("Commit: \"{message}\"").build(),
            git("git_stage", "Git Stage", "Stage one or more files for the next commit", c::gitStage)
                .permissionTemplate("Stage {path}").build(),
            git("git_unstage", "Git Unstage", "Unstage files that were previously staged", c::gitUnstage)
                .permissionTemplate("Unstage {path}").build(),
            git("git_branch", "Git Branch", "List, create, switch, or delete branches", c::gitBranch)
                .permissionTemplate("{action} branch {name}").build(),
            git("git_stash", "Git Stash", "Push, pop, apply, list, or drop stashed changes", c::gitStash)
                .permissionTemplate("{action} stash").build(),
            git("git_revert", "Git Revert", "Revert a commit by creating a new commit", c::gitRevert)
                .build(),

            // Destructive git tools
            git("git_push", "Git Push", "Push commits to a remote repository", c::gitPush)
                .destructive().openWorld()
                .permissionTemplate("Push to {remote} ({branch})").build(),
            git("git_reset", "Git Reset", "Reset HEAD to a specific commit", c::gitReset)
                .destructive()
                .permissionTemplate("{mode} reset to {commit}").build(),
            git("git_rebase", "Git Rebase", "Rebase current branch onto another", c::gitRebase)
                .destructive()
                .permissionTemplate("Rebase onto {branch}").build(),

            // Open-world git tools
            git("git_fetch", "Git Fetch", "Download objects and refs from a remote", c::gitFetch)
                .openWorld()
                .permissionTemplate("Fetch {remote}").build(),
            git("git_pull", "Git Pull", "Fetch and integrate changes into the current branch", c::gitPull)
                .openWorld()
                .permissionTemplate("Pull {remote}/{branch}").build(),
            git("git_merge", "Git Merge", "Merge a branch into the current branch", c::gitMerge)
                .permissionTemplate("Merge {branch}").build(),
            git("git_cherry_pick", "Git Cherry Pick", "Apply specific commits from another branch", c::gitCherryPick)
                .permissionTemplate("Cherry-pick {commits}").build(),
            git("git_tag", "Git Tag", "List, create, or delete tags", c::gitTag)
                .permissionTemplate("{action} tag {name}").build()
        );

        // Still register in legacy map for backward compatibility
        for (ToolDefinition def : definitions) {
            register(def.id(), def::execute);
        }
    }

    @Override
    java.util.List<ToolDefinition> getDefinitions() {
        return definitions;
    }

    private static ToolBuilder git(String id, String displayName, String description,
                                   ToolHandler handler) {
        return ToolBuilder.create(id, displayName, description, Category.GIT)
            .schema(ToolSchemas.getInputSchema(id))
            .handler(handler);
    }
}
