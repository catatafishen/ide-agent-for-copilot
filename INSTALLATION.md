# Manual Plugin Installation Guide

## Prerequisites

- IntelliJ IDEA 2025.1 or later
- Java 21 installed

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
    - Look for **"IDE Agent for Copilot"** in the list
    - Status should be **✓ Enabled**

2. Check for tool window:
    - Look for **"AgenticCopilot"** in the right sidebar
    - Or go to **View → Tool Windows → AgenticCopilot**

3. Check IDE logs for errors:
    - **Help → Show Log in Explorer**
    - Open `idea.log`
    - Search for "AgenticCopilot" or "Copilot"
    - Look for any ERROR or WARN messages

---

### 4. Test the Plugin

#### 4.1 Open Tool Window

1. Click **AgenticCopilot** in the right sidebar
2. Tool window should open with a single-panel chat interface:
    - **Chat console** (conversation area)
    - **Toolbar** (model selector, mode toggle, settings)
    - **Prompt input** with file attachment support

#### 4.2 Check ACP Connection

When the tool window opens and you send your first prompt, the plugin automatically starts the Copilot CLI.

**Check IDE logs** (`Help → Show Log in Explorer → idea.log`):

```
INFO - Starting Copilot CLI process...
INFO - ACP client initialized
INFO - MCP tools registered
```

If you see errors:

- Verify Copilot CLI is installed and authenticated
- Check error details in logs
- Verify port is not in use

#### 4.3 Test Health Check

Open **Find Action** (Ctrl+Shift+A or Cmd+Shift+A) and search for "copilot" to see if any actions are registered.

---

## Troubleshooting

### Plugin Not Appearing in Tool Windows

**Symptom:** No "AgenticCopilot" tool window visible

**Solutions:**

1. Check if plugin is enabled: **Settings → Plugins → IDE Agent for Copilot** (should have checkmark)
2. Restart IDE: **File → Invalidate Caches and Restart → Just Restart**
3. Check logs for errors: `Help → Show Log in Explorer`

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
2. Find **IDE Agent for Copilot**
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
# Settings → Plugins → IDE Agent for Copilot → Uninstall

# 3. Reinstall new version
# Settings → Plugins → ⚙️ → Install Plugin from Disk → select new ZIP

# 4. Restart IDE
```

### Faster Iteration

For faster development cycles:

1. Make plugin changes
2. Rebuild and reinstall
3. Restart IDE

---

## Next Steps

Once installation is successful:

### Phase 2 Tasks

1. **Implement Prompt Tab:**
    - Multi-line text editor with syntax highlighting
    - Token counter
    - Model selector dropdown
    - "Run" button to send prompt

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
    - Model dropdown (populated from ACP)
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
2. **Verify versions:**
    - IntelliJ IDEA: 2025.1+
    - Java: 21
3. **Rebuild from scratch:**
   ```powershell
   .\gradlew.bat clean
   .\gradlew.bat :plugin-core:buildPlugin --no-daemon -x buildSearchableOptions
   ```

---

## Known Limitations (v0.1.0-SNAPSHOT)

- **No real Copilot SDK integration yet:** Uses mock responses in development mode
- **No editor actions yet:** Right-click "Add to Context" not implemented
- **No settings persistence:** Settings don't save between sessions
- **No Git integration yet:** Commit/branch operations not implemented
- **No code formatting hooks yet:** Format-on-save not implemented
- **Sandbox IDE doesn't work:** `gradlew runIde` has known bug, use manual installation

These will be addressed in Phase 2 development.
