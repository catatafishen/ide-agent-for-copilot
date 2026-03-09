package com.github.catatafishen.ideagentforcopilot.services;

/**
 * How MCP server configuration is injected into the agent process.
 */
public enum McpInjectionMethod {

    /**
     * Write a JSON config file and pass it via {@code --additional-mcp-config @path}.
     * The config template is resolved with placeholders and written to a temp file.
     */
    CONFIG_FLAG,

    /**
     * Set an environment variable whose value is the resolved JSON config template.
     * Used by agents like OpenCode that read config from env vars.
     */
    ENV_VAR,

    /**
     * No MCP injection — the agent either doesn't support MCP or handles it externally.
     */
    NONE
}
