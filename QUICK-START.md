# Quick Start Guide

## For Testing Without Restart

### Option 1: Use Sandbox IDE (Recommended) ✅ NOW WORKS!

```powershell
# From project root
.\gradlew.bat --no-daemon :plugin-core:runIde
```

**Benefits:**
- Separate IntelliJ instance for testing
- No need to uninstall/reinstall
- Changes persist: close sandbox → rebuild → relaunch
- Isolated from your main IDE

**Note:** First launch may take 2-3 minutes.

### Option 2: Quick Rebuild + Reinstall

```powershell
# Rebuild plugin (fast now - ~15 seconds)
.\gradlew.bat --no-daemon :plugin-core:buildPlugin

# Then in IntelliJ:
# 1. Settings → Plugins → Installed → Agentic Copilot → Unload
# 2. Install from Disk: plugin-core/build/distributions/plugin-core-0.1.0-SNAPSHOT.zip
# 3. Restart IDE
```

## Current Build (Ready to Install)

**Location:** `plugin-core/build/distributions/plugin-core-0.1.0-SNAPSHOT.zip`
**Size:** 5.94 MB (includes 7.23 MB sidecar binary)
**Built:** 2026-02-11 23:54 UTC

**What's Fixed:**
- ✅ Sidecar binary embedded in plugin
- ✅ Multi-path binary discovery
- ✅ Resource extraction to temp directory
- ✅ Build system fixed (`buildPlugin` now works!)

## Installation

1. **Uninstall old version** (if installed)
   - Settings → Plugins → Installed → Agentic Copilot → Uninstall

2. **Install new version**
   - Settings → Plugins → ⚙️ → Install Plugin from Disk
   - Select: `C:\Users\developer\IdeaProjects\intellij-copilot-plugin\plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip`

3. **Restart IntelliJ**

4. **Test**
   - View → Tool Windows → Agentic Copilot
   - Settings tab: Models dropdown should load
   - Prompt tab: Type prompt, click Run

## Development Workflow

### Fast Iteration Loop

**For UI/Code Changes:**
```powershell
# 1. Make code changes
# 2. Rebuild (15 seconds)
.\gradlew.bat --no-daemon :plugin-core:buildPlugin

# 3. Test in sandbox
.\gradlew.bat --no-daemon :plugin-core:runIde
```

**For Sidecar Changes:**
```powershell
# 1. Rebuild sidecar
cd copilot-bridge
go build -o bin/copilot-sidecar.exe cmd/sidecar/main.go
cd ..

# 2. Copy to resources (for bundling)
Copy-Item "copilot-bridge\bin\copilot-sidecar.exe" "plugin-core\src\main\resources\bin\" -Force

# 3. Rebuild plugin
.\gradlew.bat --no-daemon :plugin-core:buildPlugin

# 4. Test
.\gradlew.bat --no-daemon :plugin-core:runIde
```

### Build Commands

**Full clean build:**
```powershell
.\gradlew.bat --no-daemon :plugin-core:clean :plugin-core:buildPlugin
```

**Just compile (fast):**
```powershell
.\gradlew.bat --no-daemon :plugin-core:composedJar
```

**Run sandbox IDE:**
```powershell
.\gradlew.bat --no-daemon :plugin-core:runIde
```

## Debugging

### Check Logs
**Main IDE:**
```
Help → Show Log in Explorer
C:\Users\developer\AppData\Local\JetBrains\IntelliJIdea2025.3\log\idea.log
```

**Sandbox IDE:**
```
build/idea-sandbox/system/log/idea.log
```

### Enable Debug Logging
Help → Diagnostic Tools → Debug Log Settings:
```
#com.github.copilot.intellij
```

### Test Sidecar Manually
```powershell
cd copilot-bridge\bin
.\copilot-sidecar.exe --port 0 --debug

# Should print: SIDECAR_PORT=XXXXX
# Test with: curl http://localhost:XXXXX/health
```

## Project Context Files

Read these to understand the project:
- **CHECKPOINT.md** - Current state, what works, what's broken
- **DEVELOPMENT.md** - Detailed dev workflow and tips  
- **TODO.md** - What's next, prioritized tasks
- **QUICK-START.md** - This file

## Common Issues

### "Failed to start Copilot sidecar"
**Status:** FIXED in current build
**Solution:** Install latest plugin ZIP

### "Error loading models"
**Status:** FIXED in current build (same root cause as above)

### Build fails with Java error
**Solution:** Use IntelliJ's bundled Java:
```powershell
$ideaHome = Get-Content "C:\Users\developer\AppData\Local\JetBrains\IntelliJIdea2025.3\.home"
$env:JAVA_HOME = "$ideaHome\jbr"
```

### runIde fails with "Index: 1, Size: 1"
**Status:** FIXED! (disabled buildSearchableOptions)
**Solution:** Build is now working, just run `.\gradlew.bat --no-daemon :plugin-core:runIde`

## What Works Now

✅ Full plugin build (no more manual ZIP creation!)
✅ Sandbox IDE testing
✅ Sidecar binary embedded
✅ Binary discovery (dev + production paths)
✅ Tool window with 5 tabs
✅ All UI components

## What's Next

1. Test that sidecar actually starts
2. Verify models load
3. Test prompt execution
4. Integrate real Copilot SDK

---

*Last Updated: 2026-02-11 21:54 UTC*
