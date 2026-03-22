# Integration Tests

## Overview

This module contains integration tests that validate the full plugin stack:
- IntelliJ IDE with plugin loaded
- PSI Bridge HTTP server
- MCP tools end-to-end functionality

**These tests are NOT run in CI** because they require:
1. Running IntelliJ sandbox IDE
2. External agent CLI service
3. Real project files and IDE state

## Running Tests

### Prerequisites

1. **Start the sandbox IDE:**
   ```bash
   cd /path/to/intellij-copilot-plugin
   ./gradlew :plugin-core:runIde
   ```

2. **Wait for IDE to fully load** (~30-60 seconds)

3. **Verify PSI Bridge is running:**
   ```bash
   cat ~/.copilot/psi-bridge.json
   # Should show: {"port":XXXXX,"projectPath":"..."}
   ```

### Run Tests

```bash
./gradlew :integration-tests:test -x :plugin-core:buildSearchableOptions
```

The `-x :plugin-core:buildSearchableOptions` flag skips IDE tasks that conflict with running sandbox.

### Run Specific Test

```bash
./gradlew :integration-tests:test --tests McpToolsIntegrationTest.testCreateScratchFile
```

## Test Coverage

Current tests validate:

1. **testPsiBridgeAlive** - Health check endpoint
2. **testListProjectFiles** - File listing functionality
3. **testReadFile** - File reading via IntelliJ Document API
4. **testCreateScratchFile** - Scratch file creation with syntax highlighting
5. **testSearchSymbols** - Symbol search across project
6. **testGetProjectInfo** - Project metadata retrieval

## Adding New Tests

Tests call tools via HTTP to the PSI Bridge:

```java
@Test
void testMyTool() throws Exception {
    String args = "{\"param\":\"value\"}";
    String result = callTool("my_tool_name", args);
    
    Assertions.assertTrue(result.contains("expected"), 
        "Tool should return expected result");
}
```

The `callTool()` helper:
- Sends `POST` to `http://localhost:{port}/tools/call`
- Request body: `{"name":"tool_name", "arguments":{...}}`
- Response: `{"result":"..."}`

## CI/CD Considerations

**Do NOT add these tests to CI pipelines** because:
- Require IntelliJ IDE running (heavyweight, slow)
- Depend on external agent CLI service
- Results may vary by environment/timing
- Not reproducible in isolated containers

For CI, use unit tests in `plugin-core/src/test/` instead.

## Debugging Failed Tests

1. **Check sandbox is running:**
   ```bash
   ps aux | grep "java.*idea.*sandbox"
   ```

2. **Check PSI Bridge logs:**
   ```bash
   tail -f plugin-core/build/idea-sandbox/IU-*/log/idea.log | grep PsiBridge
   ```

3. **Test tool manually:**
   ```bash
   PORT=$(cat ~/.copilot/psi-bridge.json | jq -r .port)
   curl -X POST http://localhost:$PORT/tools/call \
     -H "Content-Type: application/json" \
     -d '{"name":"get_project_info","arguments":{}}'
   ```

4. **View test reports:**
   ```bash
   open integration-tests/build/reports/tests/test/index.html
   ```

## Available Tools

The plugin exposes 92 MCP tools. See [FEATURES.md](../FEATURES.md) for the complete list.

## Notes

- Tests use `@Order` to run sequentially (some may depend on sandbox state)
- Each test prints `✓` marker on success for easy visual scanning
- Tests are lenient about response format (JSON vs plain text)
- PSI Bridge port is dynamic - read from `~/.copilot/psi-bridge.json`

---

*Last Updated: 2026-03-22*
