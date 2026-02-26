# Manual Plugin Installation Guide

## Prerequisites

- IntelliJ IDEA 2025.1 or later
- Java 21 installed
- Go 1.22+ installed (for sidecar binary)

## Installation Steps

### 1. Locate the Plugin ZIP

The plugin has been built and is located at:

```
plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip
```

**Size:** ~1.9 MB  
**Contents:** Plugin JAR, metadata, icon

---

### 2. Install Plugin in IntelliJ IDEA

#### Option A: Via Settings Dialog

1. Open IntelliJ IDEA
2. Go to **File → Settings** (or **IntelliJ IDEA → Settings** on macOS)
3. Navigate to **Plugins**
4. Click the **⚙️ (gear icon)** → **Install Plugin from Disk...**
5. Browse to `plugin-core\build\distributions\`
6. Select `plugin-core-0.1.0-SNAPSHOT.zip`
7. Click **OK**
8. Click **Restart IDE** when prompted

#### Option B: Via Drag & Drop

1. Open IntelliJ IDEA
2. Drag the ZIP file from File Explorer directly onto the IDE window
3. Confirm installation
4. Restart when prompted

---

### 3. Verify Installation

After restart:

1. Check if plugin is loaded:
    - **Settings → Plugins → Installed**
    - Look for **"Agentic Copilot"** in the list
    - Status should be **✓ Enabled**

2. Check for tool window:
    - Look for **"AgenticCopilot"** in the right sidebar
    - Or go to **View → Tool Windows → AgenticCopilot**

3. Check IDE logs for errors:
    - **Help → Show Log in Explorer**
    - Open `idea.log`
    - Search for "AgenticCopilot" or "Sidecar"
    - Look for any ERROR or WARN messages

---

### 4. Build Sidecar Binary (If Not Already Done)

The plugin needs the Go sidecar binary to function:

```powershell
cd copilot-bridge
make build
```

**Expected output:**

```
Built: bin\copilot-sidecar.exe (7.2 MB)
```

**Verify it works:**

```powershell
.\bin\copilot-sidecar.exe --port 8765
```

You should see:

```
SIDECAR_PORT=8765
Sidecar server starting on :8765
```

Press Ctrl+C to stop.

---

### 5. Test the Plugin

#### 5.1 Open Tool Window

1. Click **AgenticCopilot** in the right sidebar
2. Tool window should open with a single-panel chat interface:
    - **Chat console** (conversation area)
    - **Toolbar** (model selector, mode toggle, settings)
    - **Prompt input** with file attachment support

#### 5.2 Check ACP Connection

When the tool window opens and you send your first prompt, the plugin automatically starts the Copilot CLI.

**Check IDE logs** (`Help → Show Log in Explorer → idea.log`):

```
INFO - Starting Copilot CLI process...
INFO - ACP client initialized
INFO - MCP tools registered
```

If you see errors:

- Verify sidecar binary exists: `copilot-bridge\bin\copilot-sidecar.exe`
- Check binary path in logs
- Verify port is not in use

#### 5.3 Test Health Check

Open **Find Action** (Ctrl+Shift+A or Cmd+Shift+A) and search for "copilot" to see if any actions are registered.

---

## Troubleshooting

### Plugin Not Appearing in Tool Windows

**Symptom:** No "AgenticCopilot" tool window visible

**Solutions:**

1. Check if plugin is enabled: **Settings → Plugins → Agentic Copilot** (should have checkmark)
2. Restart IDE: **File → Invalidate Caches and Restart → Just Restart**
3. Check logs for errors: `Help → Show Log in Explorer`

### "Sidecar Failed to Start" Error

**Symptom:** Error in IDE logs about sidecar process

**Solutions:**

1. **Binary not found:**
    - Build the binary: `cd copilot-bridge && make build`
    - Check path: `copilot-bridge\bin\copilot-sidecar.exe` exists

2. **Port already in use:**
    - Check for existing processes: `netstat -ano | findstr :8765`
    - Kill the process or restart

3. **Permission denied:**
    - Run IntelliJ as Administrator (temporary test)
    - Check antivirus isn't blocking the binary

### Plugin Install Fails

**Symptom:** Error during installation: "Plugin is invalid"

**Solutions:**

1. **Rebuild the plugin:**
   ```powershell
   $env:JAVA_HOME = "C:\Users\developer\.jdks\temurin-21.0.6"
   .\gradlew.bat :plugin-core:buildPlugin --no-daemon -x buildSearchableOptions
   ```

2. **Check ZIP is not corrupted:**
    - File size should be ~1.9 MB
    - Can extract with 7-Zip to verify contents

3. **Check IDE version:**
    - Plugin requires IntelliJ 2025.1 or later
    - Check: **Help → About** → Build number should be 251.x or higher

### Tool Window Opens But Shows Errors

**Symptom:** Tool window visible but UI is broken

**Solutions:**

1. Check for Java exceptions in logs
2. Verify Kotlin runtime is available
3. Check for classpath conflicts (look for "NoClassDefFoundError")

---

## Uninstallation

To remove the plugin:

1. **Settings → Plugins**
2. Find **Agentic Copilot**
3. Click **⚙️ → Uninstall**
4. Restart IDE

---

## Development Mode

If you're actively developing and want to test changes:

### Quick Rebuild & Reinstall

```powershell
# 1. Rebuild plugin
$env:JAVA_HOME = "C:\Users\developer\.jdks\temurin-21.0.6"
.\gradlew.bat :plugin-core:buildPlugin --no-daemon -x buildSearchableOptions

# 2. Uninstall old version in IDE
# Settings → Plugins → Agentic Copilot → Uninstall

# 3. Reinstall new version
# Settings → Plugins → ⚙️ → Install Plugin from Disk → select new ZIP

# 4. Restart IDE
```

### Faster Iteration

For faster development cycles:

1. Keep sidecar running separately: `.\copilot-bridge\bin\copilot-sidecar.exe --port 8765`
2. Make plugin changes
3. Rebuild and reinstall
4. Restart IDE

The sidecar will stay running between IDE restarts.

---

## Next Steps

Once installation is successful:

### Phase 2 Tasks

1. **Implement Prompt Tab:**
    - Multi-line text editor with syntax highlighting
    - Token counter
    - Model selector dropdown
    - "Run" button to send to sidecar

2. **Implement Context Tab:**
    - List view of context items
    - "Add Selection" action in editor right-click menu
    - Display file path + line range

3. **Implement Plans Tab:**
    - Tree view for hierarchical plans
    - Status indicators (pending/running/complete/failed)

4. **Implement Timeline Tab:**
    - Chronological event list
    - Expandable event details
    - Auto-scroll to bottom

5. **Implement Settings Tab:**
    - Model dropdown (populated from sidecar)
    - Tool permission matrix
    - Formatting options

### Testing Checklist

- [ ] Tool window opens without errors
- [ ] ACP client connects on first prompt
- [ ] Chat console and toolbar are visible
- [ ] No errors in IDE logs
- [ ] Prompt input works
- [ ] IDE remains responsive

---

## Support

If you encounter issues not covered here:

1. **Check logs:** `Help → Show Log in Explorer → idea.log`
2. **Check sidecar logs:** If sidecar runs separately, check console output
3. **Verify versions:**
    - IntelliJ IDEA: 2025.1+
    - Java: 21
    - Go: 1.22+
4. **Rebuild from scratch:**
   ```powershell
   .\gradlew.bat clean
   .\gradlew.bat :plugin-core:buildPlugin --no-daemon -x buildSearchableOptions
   ```

---

## Known Limitations (v0.1.0-SNAPSHOT)

- **No real Copilot SDK integration yet:** Sidecar uses mock responses
- **No editor actions yet:** Right-click "Add to Context" not implemented
- **No settings persistence:** Settings don't save between sessions
- **No Git integration yet:** Commit/branch operations not implemented
- **No code formatting hooks yet:** Format-on-save not implemented
- **Sandbox IDE doesn't work:** `gradlew runIde` has known bug, use manual installation

These will be addressed in Phase 2 development.
