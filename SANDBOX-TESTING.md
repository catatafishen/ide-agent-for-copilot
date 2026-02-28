# Testing in IntelliJ Sandbox

## Option 1: Using IntelliJ Run Configuration (Recommended for Development)

### Setup Steps:

1. **Open the project in IntelliJ IDEA**
    - File → Open → Select `intellij-copilot-plugin` directory

2. **Create a Gradle Run Configuration**
    - Run → Edit Configurations...
    - Click "+" → Gradle
    - Configure:
        - Name: `Run Plugin in Sandbox`
        - Gradle project: `intellij-copilot-plugin:plugin-core`
        - Tasks: `runIde`
        - VM options: `-Xmx2048m`
    - Click OK

3. **Alternative: Use Gradle Tool Window**
    - Open Gradle tool window (View → Tool Windows → Gradle)
    - Navigate to: `intellij-copilot-plugin → plugin-core → Tasks → intellij → runIde`
    - Right-click → Run

4. **If runIde still fails, use prepareSandbox + manual launch**:
   ```powershell
   # Build and prepare sandbox
   .\gradlew.bat --no-daemon :plugin-core:prepareSandbox
   
   # Find sandbox location
   ls build/idea-sandbox/
   
   # Launch IDE with plugin
   # (See workaround below)
   ```

---

## Option 2: Manual Install (Currently Working)

This is what you've been using successfully:

1. **Build the plugin**:
   ```powershell
   .\gradlew.bat --no-daemon :plugin-core:buildPlugin
   ```

2. **Install the ZIP**:
    - Settings → Plugins → Gear icon → Install Plugin from Disk
    - Select: `plugin-core/build/distributions/plugin-core-0.1.0-SNAPSHOT.zip`
    - Restart IntelliJ

3. **Test the plugin**:
    - View → Tool Windows → IDE Agent for Copilot
    - Settings tab: Check models dropdown
    - Prompt tab: Test a prompt

---

## Option 3: Workaround for runIde Bug

The runIde task fails due to a bug in the IntelliJ Platform Gradle Plugin 2.1.0. Here's a potential workaround:

### Try disabling IDE home resolution:

Add to `plugin-core/build.gradle.kts`:

```kotlin
tasks {
    runIde {
        maxHeapSize = "2g"

        // Try to work around the ProductInfo bug
        jvmArgs("-Didea.plugins.path=${layout.buildDirectory.dir("idea-sandbox/plugins").get()}")
        jvmArgs("-Didea.system.path=${layout.buildDirectory.dir("idea-sandbox/system").get()}")
        jvmArgs("-Didea.config.path=${layout.buildDirectory.dir("idea-sandbox/config").get()}")
        jvmArgs("-Didea.log.path=${layout.buildDirectory.dir("idea-sandbox/system/log").get()}")
    }
}
```

Then try:

```powershell
.\gradlew.bat --no-daemon :plugin-core:runIde --info
```

---

## Quick Test Script

Create `test-plugin.ps1`:

```powershell
# Build plugin
Write-Host "Building plugin..." -ForegroundColor Cyan
$ideaHome = Get-Content "C:\Users\developer\AppData\Local\JetBrains\IntelliJIdea2025.3\.home"
$env:JAVA_HOME = "$ideaHome\jbr"
.\gradlew.bat --no-daemon :plugin-core:buildPlugin

if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ Build successful!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Plugin location:" -ForegroundColor Yellow
    Write-Host "  plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip"
    Write-Host ""
    Write-Host "To install:" -ForegroundColor Yellow
    Write-Host "  1. Settings → Plugins → Gear → Install Plugin from Disk"
    Write-Host "  2. Select the ZIP file above"
    Write-Host "  3. Restart IntelliJ"
} else {
    Write-Host "✗ Build failed" -ForegroundColor Red
}
```

---

## Clean Build (Important!)

After making code changes, the sandbox may use **stale cached class files** from incremental builds. If the agent or
plugin doesn't reflect your latest changes, do a clean rebuild:

```bash
# Option 1: Use the restart script (handles config persistence automatically)
./restart-sandbox.sh --clean

# Option 2: Manual clean rebuild
JAVA_HOME=/path/to/jdk21 ./gradlew :mcp-server:clean :plugin-core:clean \
  :plugin-core:prepareSandbox --no-daemon --rerun-tasks
JAVA_HOME=/path/to/jdk21 nohup ./gradlew :plugin-core:runIde --no-daemon > ide_launch.log 2>&1 &
```

**Why this matters**: `prepareSandbox` copies compiled jars into the sandbox. If Gradle's incremental compilation caches
a stale `.class` file, the sandbox IDE runs old code even though your source files are updated. The `clean` +
`--rerun-tasks` flags force a full recompilation.

**Config persistence**: The `prepareSandbox` task automatically restores settings from `.sandbox-config/` (disabled
plugins, UI preferences, etc.), so your IDE settings survive clean rebuilds.

---

## Current Status

**Working**: `runIde` task, sandbox mode, config persistence, clean rebuilds
**Recommended**: Use `./restart-sandbox.sh` for restarts, add `--clean` after code changes
