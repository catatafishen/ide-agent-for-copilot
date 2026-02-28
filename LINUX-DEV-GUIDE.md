# Linux Development Guide - Sandbox Mode

## Your Configuration

- **IntelliJ**: 2025.2.5 (Toolbox installation)
- **Java**: 21.0.2 (GraalVM via SDKMAN)
- **Copilot CLI**: 0.0.409

## Sandbox Development Workflow (RECOMMENDED)

The sandbox mode is **much faster** than manual installation because:

- ✅ **Auto-reload enabled** - plugin reloads without IDE restart on Linux
- ✅ Isolated environment - won't affect your main IDE
- ✅ Separate config/data - no conflicts with existing projects
- ✅ First launch ~90s, subsequent restarts ~20s

### Initial Launch

```bash
./gradlew :plugin-core:runIde
```

This will:

1. Download dependencies (first time only)
2. Build the plugin
3. Launch a sandboxed IntelliJ with plugin pre-installed
4. Sandbox data stored in `plugin-core/build/idea-sandbox/`

### Development Iteration (FAST!)

**Option A - Hot Reload (Keep Sandbox Running):**

```bash
# 1. Make code changes in your editor
# 2. In a separate terminal, run:
./gradlew :plugin-core:prepareSandbox

# Plugin auto-reloads in the running sandbox IDE!
# No restart needed on Linux thanks to autoReload=true
```

**Option B - Full Rebuild:**

```bash
# Close sandbox IDE, then:
./gradlew :plugin-core:buildPlugin
./gradlew :plugin-core:runIde
```

### Quick Test Cycle

```bash
# Run tests before launching sandbox:
./gradlew :mcp-server:test          # Fast - MCP server only (~5s)
./gradlew :plugin-core:test         # Slower - plugin unit tests
./gradlew test                      # All tests
```

## Manual Installation (If Needed)

Only use this if you need to test in your main IDE:

```bash
# 1. Build
./gradlew :plugin-core:buildPlugin

# 2. Find your IDE plugin directory (adjust version)
# Toolbox-managed: plugins are direct subfolders (no /plugins parent)
PLUGIN_DIR=~/.local/share/JetBrains/IntelliJIdea2025.3

# 3. Close your main IntelliJ, then install:
rm -rf "$PLUGIN_DIR/plugin-core"
unzip -q plugin-core/build/distributions/plugin-core-*.zip -d "$PLUGIN_DIR"

# 4. Restart IntelliJ
```

## Debugging

### Enable Debug Logging

In the sandbox IDE:

1. **Help → Diagnostic Tools → Debug Log Settings**
2. Add: `#com.github.catatafishen.ideagentforcopilot`

### Log Locations

- **Sandbox IDE**: `plugin-core/build/idea-sandbox/config/log/idea.log`
- **Main IDE**: `~/.local/share/JetBrains/IntelliJIdea2025.2/log/idea.log`
- **PSI Bridge**: `~/.copilot/psi-bridge.json`

### Tail Logs in Real-Time

```bash
tail -f plugin-core/build/idea-sandbox/config/log/idea.log | grep -i copilot
```

## Common Tasks

### Clean Build

```bash
./gradlew clean
./gradlew :plugin-core:buildPlugin
```

### Test Specific Class

```bash
./gradlew :mcp-server:test --tests McpServerTest
```

### Check Copilot CLI

```bash
copilot --version
copilot auth status
```

### Reset Sandbox

```bash
rm -rf plugin-core/build/idea-sandbox/
./gradlew :plugin-core:runIde
```

## Plugin Structure

```
plugin-core/
├── src/main/java/com/github/copilot/intellij/
│   ├── ui/              # Tool Window (Swing/Kotlin)
│   ├── services/        # CopilotService, Settings
│   ├── bridge/          # CopilotAcpClient (ACP protocol)
│   └── psi/             # PsiBridgeService (MCP tools)
└── src/test/java/

mcp-server/
└── src/main/java/com/github/copilot/mcp/
    └── McpServer.java   # MCP stdio server
```

## Tips

1. **Always use sandbox mode** for development - it's faster and safer
2. **Use prepareSandbox** for quick iterations - changes appear in ~10s
3. **Keep sandbox running** - no need to close between code changes
4. **Test in main IDE** only for final verification before release
5. **Open a different project** in sandbox than in your main IDE

## Next Steps

To verify everything works:

1. Launch sandbox: `./gradlew :plugin-core:runIde` (wait ~90s first launch)
2. In sandbox IDE: **View → Tool Windows → IDE Agent for Copilot**
3. Models dropdown should load
4. Make a code change, run `./gradlew :plugin-core:prepareSandbox`
5. Watch plugin reload automatically!
