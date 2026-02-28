# Quick Setup: Run Plugin in IntelliJ

Since you have the project open in IntelliJ IDEA:

## Option 1: Use Gradle Tool Window (Easiest)

1. **Open Gradle Tool Window**: View → Tool Windows → Gradle (or click Gradle icon on right side)

2. **Navigate to runIde task**:
   ```
   intellij-copilot-plugin
     └─ plugin-core
        └─ Tasks
           └─ intellij
              └─ runIde
   ```

3. **Right-click on `runIde`** → Select "Run 'intellij-copilot-plugin:plugin-core [runIde]'"

   **Note**: This will likely still fail with the same "Index: 1, Size: 1" error

---

## Option 2: Manual Install (Recommended - Working Now)

Since manual installation is working perfectly, here's a quick script:

### Create `install-plugin.ps1` in project root:

```powershell
#!/usr/bin/env pwsh
# Quick plugin build and install helper

Write-Host "`n🔨 Building plugin..." -ForegroundColor Cyan

$ideaHome = Get-Content "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2025.3\.home"
$env:JAVA_HOME = "$ideaHome\jbr"

.\gradlew.bat --no-daemon :plugin-core:buildPlugin

if ($LASTEXITCODE -eq 0) {
    $zipPath = Resolve-Path "plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip"
    
    Write-Host "`n✅ Build successful!" -ForegroundColor Green
    Write-Host "`n📦 Plugin ZIP created:" -ForegroundColor Yellow
    Write-Host "   $zipPath" -ForegroundColor White
    
    Write-Host "`n📋 To install:" -ForegroundColor Cyan
    Write-Host "   1. Open IntelliJ Settings (Ctrl+Alt+S)" -ForegroundColor White
    Write-Host "   2. Go to: Plugins" -ForegroundColor White
    Write-Host "   3. Click gear icon (⚙️) → Install Plugin from Disk..." -ForegroundColor White
    Write-Host "   4. Select: $zipPath" -ForegroundColor White
    Write-Host "   5. Click OK and restart IntelliJ`n" -ForegroundColor White
    
    # Option to copy path to clipboard
    $copy = Read-Host "Copy path to clipboard? (y/n)"
    if ($copy -eq 'y') {
        Set-Clipboard -Value $zipPath
        Write-Host "✓ Path copied to clipboard!" -ForegroundColor Green
    }
} else {
    Write-Host "`n❌ Build failed - check errors above" -ForegroundColor Red
}
```

### Usage:
```powershell
.\install-plugin.ps1
```

---

## Option 3: IntelliJ Plugin DevKit (Alternative)

If the project is recognized as a plugin project:

1. **Check if Plugin DevKit is configured**:
   - Run → Edit Configurations...
   - Look for a "Plugin" configuration type

2. **If not present, create one**:
   - Click "+" → Plugin
   - Configure:
     - Name: `Run Copilot Plugin`
     - Module: `intellij-copilot-plugin.plugin-core.main`
   - Click OK

3. **Run**: Click green play button

**Note**: This might also hit the same Gradle bug depending on how it's configured.

---

## Conclusion

**Current Best Practice**:
1. Run `.\install-plugin.ps1` (or the build command)
2. Install ZIP via Settings → Plugins → Install from Disk
3. Restart IDE
4. Test changes

This is reliable and works every time. The runIde bug is a known issue with the IntelliJ Platform Gradle Plugin 2.1.0 that we can't easily work around.

**Development Cycle**:
- Make changes
- Run build script (15-30 seconds)
- Install in IDE (30 seconds)
- Restart IDE (30 seconds)
- Total: ~2 minutes per iteration

This is acceptable for plugin development.
