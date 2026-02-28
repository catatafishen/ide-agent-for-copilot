# Quick Start Guide - Testing Your Plugin

## Installation Steps

### 1. Install Plugin in IntelliJ IDEA

**Current plugin location:**

```
plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip
Size: 1.82 MB
```

**Install:**

1. Open your IntelliJ IDEA (2025.3.1)
2. **File → Settings → Plugins**
3. Click **⚙️ (gear icon)** → **Install Plugin from Disk...**
4. Browse to and select: `plugin-core-0.1.0-SNAPSHOT.zip`
5. Click **OK**
6. Click **Restart IDE**

---

### 2. Verify Installation

After IDE restarts:

**Check Plugin is Loaded:**

- Settings → Plugins → Installed
- Should see: **"IDE Agent for Copilot"** with ✓ Enabled

**Find Tool Window:**

- Look in **right sidebar** for "AgenticCopilot"
- Or: **View → Tool Windows → AgenticCopilot**

**Expected Result:**

- Tool window opens
- Shows single-panel chat interface with toolbar and prompt input
- Chat console area is ready for conversation

---

### 3. Check Logs for ACP Startup

**View Logs:**

- **Help → Show Log in Explorer**
- Open `idea.log`
- Search for "Copilot" or "AgenticCopilot"

**Expected Log Messages:**

```
INFO - Starting Copilot CLI process...
INFO - ACP client initialized
INFO - MCP tools registered
```

**If Errors:**

- Note the error message
- Check if Copilot CLI is installed and authenticated (`copilot auth status`)

---

### 4. Manual ACP Test (Optional)

If you want to verify the Copilot CLI works independently:

```bash
copilot --version
copilot auth status
```

---

## What to Test

### Basic Functionality

- [ ] **Tool Window Opens**
    - Right sidebar shows "AgenticCopilot" icon
    - Clicking opens the window
    - No UI errors visible

- [ ] **Tabs Are Present**
    - Prompt tab (shows "Prompt tab - coming soon!")
    - Context tab (shows "Context tab - coming soon!")
    - Plans tab (shows "Plans tab - coming soon!")
    - Timeline tab (shows "Timeline tab - coming soon!")
    - Settings tab (shows "Settings tab - coming soon!")

- [ ] **Can Switch Tabs**
    - Click each tab
    - Content changes
    - No errors in IDE status bar

- [ ] **IDE Remains Stable**
    - No freezes
    - Can create/edit files normally
    - IDE doesn't crash

### ACP Integration

- [ ] **ACP Client Auto-Starts**
    - Check logs after opening tool window
    - Should see "ACP client initialized"
    - No error messages

- [ ] **Health Check Works**
    - Check logs for "MCP tools registered"

- [ ] **Process Management**
    - Close IDE
    - Copilot CLI process should terminate automatically
    - Verify no orphan processes

---

## Troubleshooting

### Plugin Not Visible After Install

**Symptom:** No "AgenticCopilot" tool window

**Solutions:**

1. Check Settings → Plugins → ensure "IDE Agent for Copilot" is enabled (has checkmark)
2. Restart IDE: File → Invalidate Caches and Restart → Just Restart
3. Check IDE logs for errors (Help → Show Log in Explorer)

---

### "ACP Client Failed to Start" Error

**Symptom:** Error in logs about starting Copilot CLI

**Check Copilot CLI is installed:**

```bash
copilot --version
```

If not found, install the Copilot CLI.

**Check Authentication:**

```bash
copilot auth status
```

If not authenticated, run `copilot auth` or `gh auth login`.

---

### Port Already in Use

**Find Process:**

```powershell
netstat -ano | findstr :8765
```

**Kill Process:**

```powershell
Stop-Process -Id <PID>
```

---

### IDE Crashes on Startup

**Symptom:** IDE won't start after plugin install

**Safe Mode Boot:**

1. Find your IDE's configuration directory:
   `C:\Users\developer\AppData\Roaming\JetBrains\IntelliJIdea2025.3`
2. Navigate to: `plugins\`
3. Delete or rename the `agentic-copilot` folder
4. Restart IDE normally

**Then:**

- Check IDE logs for the actual error
- Report issue with stack trace

---

## Known Limitations (v0.1.0-SNAPSHOT)

This is a **technical preview** with infrastructure only:

❌ **Not Implemented Yet:**

- No working UI controls (all placeholder text)
- No actual Copilot SDK integration (uses mocks in development mode)
- Can't send prompts or receive responses
- No context management
- No plan execution
- No timeline events
- No settings persistence
- No Git integration
- No code formatting hooks

✅ **What Works:**

- Plugin loads and displays UI
- Tool window with tabbed interface
- ACP client auto-starts and responds to health checks
- Clean shutdown and restart

---

## What's Next (Phase 2)

Once you've verified the plugin works:

**Phase 2 Development:**

1. Implement Prompt tab with working editor
2. Implement Context tab with "Add Selection" action
3. Wire up actual message sending via ACP
4. Display real responses in Timeline
5. Connect to real Copilot SDK (not mocks)

**Testing Checklist:**

- Open/close tool window multiple times
- Restart IDE and verify plugin reloads
- Check memory usage (Task Manager)
- Switch between tabs rapidly
- Run with IDE debugger attached

---

## Reporting Issues

If you encounter problems:

**Gather Information:**

1. **IDE Version:** Help → About → Copy full version info
2. **Logs:** Help → Show Log in Explorer → Copy relevant errors
3. **ACP Logs:** Check IDE logs for Copilot communication details
4. **Screenshots:** Capture any error dialogs or UI issues

**Common Info Needed:**

- Exact error message from logs
- Steps to reproduce
- When it happens (startup, clicking tab, etc.)
- System info (Windows version, RAM, CPU)

---

## Success Criteria for This Build

✅ **Minimal Success:**

- Plugin installs without errors
- Tool window appears and opens
- Chat console and toolbar are visible
- Prompt input is functional
- IDE doesn't crash

✅ **Full Success:**

- Above +
- ACP client connects (check logs)
- MCP tools respond to health check
- No errors in IDE logs
- IDE restarts cleanly

---

## Ready to Proceed?

Once you've:

1. ✅ Installed the plugin
2. ✅ Restarted IDE
3. ✅ Verified tool window appears
4. ✅ Checked logs for ACP startup

Come back and report the results! We'll then:

- Debug any issues found
- Or proceed to Phase 2 implementation
- Or finalize the 2025.3-targeted build

**Happy testing!** 🚀
