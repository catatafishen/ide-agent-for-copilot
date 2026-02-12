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
- Should see: **"Agentic Copilot"** with ✓ Enabled

**Find Tool Window:**
- Look in **right sidebar** for "AgenticCopilot"
- Or: **View → Tool Windows → AgenticCopilot**

**Expected Result:**
- Tool window opens
- Shows 5 tabs: Prompt, Context, Plans, Timeline, Settings
- All tabs show placeholder text

---

### 3. Check Logs for Sidecar Startup

**View Logs:**
- **Help → Show Log in Explorer**
- Open `idea.log`
- Search for "sidecar" or "AgenticCopilot"

**Expected Log Messages:**
```
INFO - Starting sidecar process...
INFO - Sidecar started on port XXXXX
INFO - Health check passed
```

**If Errors:**
- Note the error message
- Check if sidecar binary exists: `copilot-bridge\bin\copilot-sidecar.exe`
- Try manual start (see below)

---

### 4. Manual Sidecar Test (Optional)

If you want to verify the sidecar works independently:

```powershell
cd copilot-bridge
.\bin\copilot-sidecar.exe --port 8765
```

**Expected output:**
```
SIDECAR_PORT=8765
Sidecar server starting on :8765
```

**Test health endpoint:**
```powershell
# In another terminal:
curl http://localhost:8765/health
```

Should return: `{"status":"ok"}`

Press Ctrl+C to stop.

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

### Sidecar Integration

- [ ] **Sidecar Auto-Starts**
  - Check logs after opening tool window
  - Should see "Sidecar started on port XXXX"
  - No error messages

- [ ] **Health Check Works**
  - Check logs for "Health check passed"
  - Port number is printed (e.g., port 54321)

- [ ] **Process Management**
  - Close IDE
  - Sidecar should terminate automatically
  - Verify no orphan processes: `tasklist | findstr sidecar`

---

## Troubleshooting

### Plugin Not Visible After Install

**Symptom:** No "AgenticCopilot" tool window

**Solutions:**
1. Check Settings → Plugins → ensure "Agentic Copilot" is enabled (has checkmark)
2. Restart IDE: File → Invalidate Caches and Restart → Just Restart
3. Check IDE logs for errors (Help → Show Log in Explorer)

---

### "Sidecar Failed to Start" Error

**Symptom:** Error in logs about starting sidecar

**Check Binary Exists:**
```powershell
Test-Path "copilot-bridge\bin\copilot-sidecar.exe"
```

If FALSE:
```powershell
cd copilot-bridge
make build
```

**Check Binary is Executable:**
- Right-click → Properties → Unblock (if present)
- Run manually to test: `.\bin\copilot-sidecar.exe --port 0`

**Check Antivirus:**
- Corporate AV might be blocking the binary
- Check AV logs/quarantine
- May need IT approval for custom executables

---

### Port Already in Use

**Symptom:** Sidecar fails with "address already in use"

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
- No actual Copilot SDK integration (sidecar uses mocks)
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
- Sidecar auto-starts and responds to health checks
- Clean shutdown and restart

---

## What's Next (Phase 2)

Once you've verified the plugin works:

**Phase 2 Development:**
1. Implement Prompt tab with working editor
2. Implement Context tab with "Add Selection" action
3. Wire up actual message sending to sidecar
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
3. **Sidecar Logs:** If running manually, copy console output
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
- All 5 tabs are visible
- Can switch between tabs
- IDE doesn't crash

✅ **Full Success:**
- Above +
- Sidecar auto-starts (check logs)
- Health check passes
- No errors in IDE logs
- IDE restarts cleanly

---

## Ready to Proceed?

Once you've:
1. ✅ Installed the plugin
2. ✅ Restarted IDE
3. ✅ Verified tool window appears
4. ✅ Checked logs for sidecar startup

Come back and report the results! We'll then:
- Debug any issues found
- Or proceed to Phase 2 implementation
- Or finalize the 2025.3-targeted build

**Happy testing!** 🚀
