package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.openapi.project.Project;

/**
 * Git tool handlers — thin wrappers delegating to {@link GitToolHandler}.
 */
class GitTools extends AbstractToolHandler {

    GitTools(Project project, GitToolHandler gitToolHandler) {
        super(project);
        register("git_status", gitToolHandler::gitStatus);
        register("git_diff", gitToolHandler::gitDiff);
        register("git_log", gitToolHandler::gitLog);
        register("git_blame", gitToolHandler::gitBlame);
        register("git_commit", gitToolHandler::gitCommit);
        register("git_stage", gitToolHandler::gitStage);
        register("git_unstage", gitToolHandler::gitUnstage);
        register("git_branch", gitToolHandler::gitBranch);
        register("git_stash", gitToolHandler::gitStash);
        register("git_show", gitToolHandler::gitShow);
        register("git_push", gitToolHandler::gitPush);
        register("git_remote", gitToolHandler::gitRemote);
    }
}
