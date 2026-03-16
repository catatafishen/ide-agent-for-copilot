[2026-03-16 11:44] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Runtime context mismatch",
    "EXPECTATION": "They run Junie from CLI via a custom plugin and need command-line arguments to exclude built-in tools, not IDE UI instructions.",
    "NEW INSTRUCTION": "WHEN user indicates CLI-based Junie startup THEN propose exact CLI flags to exclude built-in tools"
}

[2026-03-16 12:12] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Parity with Copilot workaround",
    "EXPECTATION": "Handle Junie’s ignored excludedTools the same way Copilot is handled and add documentation about this limitation for tracking future changes.",
    "NEW INSTRUCTION": "WHEN excluding built-in tools for Junie THEN apply Copilot-style fallback and document limitation"
}

[2026-03-16 12:15] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool name mapping failure",
    "EXPECTATION": "Junie should display mapped, human-friendly tool names instead of raw MCP IDs like 'intellij-code-tools/search_text'.",
    "NEW INSTRUCTION": "WHEN tool chip contains 'intellij-code-tools/' THEN display mapped friendly tool name without namespace"
}

[2026-03-16 12:30] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool name mapping location",
    "EXPECTATION": "Tool name mappings should live in the respective AgentClient classes per agent, not in the UI.",
    "NEW INSTRUCTION": "WHEN rendering tool names anywhere THEN use AgentClient-provided friendly name mapping"
}

[2026-03-16 13:59] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Unmapped tool popup details",
    "EXPECTATION": "The unmapped tool popup should show any input/output instead of only 'complete'.",
    "NEW INSTRUCTION": "WHEN showing unmapped tool popup THEN display tool input and output payloads if available"
}

[2026-03-16 14:01] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Unmapped tool popup missing I/O",
    "EXPECTATION": "Tool calls like search_symbols should display their input parameters and output results, not just a generic 'complete' message.",
    "NEW INSTRUCTION": "WHEN tool status is 'complete' and payload present THEN render input and output sections in popup"
}

[2026-03-16 14:08] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie tool name normalization",
    "EXPECTATION": "JunieAcpClient should map MCP plugin tool IDs (e.g., 'intellij-code-tools/edit_text') to the same friendly tool names used by other agents so UI renderers resolve correctly and labels look consistent.",
    "NEW INSTRUCTION": "WHEN normalizing Junie MCP plugin tool IDs THEN map to shared friendly names used by other agents"
}

[2026-03-16 15:00] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "HTTP error display",
    "EXPECTATION": "When 403 occurs, show the server's response message alongside the status to hint at model availability issues.",
    "NEW INSTRUCTION": "WHEN HTTP 4xx/5xx includes response body THEN display status and server error message"
}

[2026-03-16 15:19] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Infinite scroll history",
    "EXPECTATION": "Scrolling to the very top should repeatedly load older messages, not just the first page.",
    "NEW INSTRUCTION": "WHEN chat viewport reaches top AND hasMore=true THEN request next page and prepend messages while preserving scroll"
}

[2026-03-16 15:21] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Infinite scroll still broken",
    "EXPECTATION": "Scrolling upward should continue loading older messages beyond 14:48 until no more history is available.",
    "NEW INSTRUCTION": "WHEN chat scroll reaches top repeatedly AND hasMore=true THEN fetch next page and prepend while preserving scroll"
}

[2026-03-16 15:28] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Client-specific tool mapping",
    "EXPECTATION": "Junie tool names should be normalized in JunieAcpClient and shown as friendly names (without MCP namespace), not via a generic mapping in AcpAgentClient.",
    "NEW INSTRUCTION": "WHEN normalizing Junie MCP tool names THEN implement mapping in JunieAcpClient, not AcpAgentClient"
}

[2026-03-16 16:05] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie naming schema",
    "EXPECTATION": "Only normalize Junie’s actual tool IDs (e.g., intellij-code-tools/intellij_read_file); double-underscore patterns are not used.",
    "NEW INSTRUCTION": "WHEN normalizing tool names in JunieAcpClient THEN map only Junie slash-prefixed IDs from tools list"
}

[2026-03-16 16:12] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Client normalization design",
    "EXPECTATION": "Junie should not map GitHub tools, subclasses must own normalization (no super), and UI should not prefix tool chips with 'Tool: '.",
    "NEW INSTRUCTION": "WHEN defining normalizeToolName in AcpClient THEN make it abstract; do not provide fallback"
}

[2026-03-16 16:23] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Abstract class instantiation",
    "EXPECTATION": "After making AcpClient abstract, it should never be directly instantiated; ActiveAgentManager must construct a concrete client or fail explicitly.",
    "NEW INSTRUCTION": "WHEN ActiveAgentManager default case constructs client THEN do not use AcpClient; select concrete or throw"
}

[2026-03-16 16:39] - Updated by Junie
{
    "TYPE": "positive",
    "CATEGORY": "Output correctness confirmation",
    "EXPECTATION": "The startup context and categorized tool counts matched expectations, with friendly tool names applied.",
    "NEW INSTRUCTION": "WHEN listing tools or environment context THEN include category counts and friendly tool names"
}

[2026-03-16 16:42] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Missing startup instructions",
    "EXPECTATION": "Assistant should not claim access to default-startup-instructions.md if it was not injected, and should explicitly report its absence.",
    "NEW INSTRUCTION": "WHEN asked to confirm startup instructions THEN explicitly state if not injected and request injection"
}

