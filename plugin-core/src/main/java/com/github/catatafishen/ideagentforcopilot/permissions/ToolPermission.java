package com.github.catatafishen.ideagentforcopilot.permissions;

/**
 * Permission levels for tool execution.
 */
public enum ToolPermission {
    /** Tool executes without user confirmation. */
    ALLOW,
    /** User is prompted before execution. */
    ASK,
    /** Tool execution is denied. Agent receives a denial reason. */
    DENY
}
