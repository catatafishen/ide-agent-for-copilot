# Development Workflow

## Testing Without Restart

### Method 1: Use Sandbox IDE (Recommended)
Instead of installing in your main IntelliJ, use the sandbox IDE:

```powershell
# From project root
.\gradlew.bat --no-daemon :plugin-core:runIde
```

**Benefits:**
- Launches a separate IntelliJ instance with the plugin pre-loaded
- Isolated from your main IDE settings
- No need to uninstall/reinstall
- Just close sandbox and run again to test changes

**Note:** The buildSearchableOptions task currently fails, but you can work around it:

```powershell
# Build just the code, skip searchable options
.\gradlew.bat --no-daemon :plugin-core:composedJar

# Then manually start sandbox (if runIde fails)
# Or install the generated ZIP in your IDE
```

### Method 2: Dynamic Plugin Reloading
For hot-reload without restart (requires setup):

1. Add to `plugin.xml`:
```xml
<idea-plugin>
  <!-- Enable dynamic plugin loading -->
  <id>com.github.copilot.intellij</id>
  <!-- ... rest of config ... -->
</idea-plugin>
```

2. Make plugin services implement `DynamicPluginListener`
3. Use "Unload" action in Plugins settings (for dynamic plugins)
4. Reload without restart

**Current Status:** Our plugin is NOT yet dynamic-enabled. Services need refactoring.

### Method 3: Fast Rebuild + Quick Restart
If you must use main IDE:

```powershell
# Quick build (skips tests, checks)
.\gradlew.bat --no-daemon :plugin-core:composedJar

# Create distribution
.\gradlew.bat --no-daemon :plugin-core:jar
```

Then in IntelliJ:
- Settings → Plugins → Installed → Agentic Copilot → Unload
- Install new version from disk
- Click "Restart IDE" (faster than full close/open)

## Current Build Issues

### Problem: `buildSearchableOptions` fails with "Index: 1, Size: 1"
This is a known issue with the IntelliJ Platform Gradle Plugin when combined with our configuration.

### Workaround:
Build individual tasks instead of full `buildPlugin`:

```powershell
# Set Java home (if needed)
$ideaHome = Get-Content "C:\Users\developer\AppData\Local\JetBrains\IntelliJIdea2025.3\.home"
$env:JAVA_HOME = "$ideaHome\jbr"

# Build JAR only
.\gradlew.bat --no-daemon :plugin-core:composedJar

# Manual ZIP creation (see build.gradle.kts for automation)
```

### Solution (TODO):
Disable searchable options in `build.gradle.kts`:

```kotlin
tasks {
    buildSearchableOptions {
        enabled = false
    }
}
```

## Quick Development Loop

**Recommended workflow:**

1. Make code changes
2. Run: `.\gradlew.bat --no-daemon :plugin-core:composedJar`
3. Use sandbox: `.\gradlew.bat --no-daemon :plugin-core:runIde` (when it works)
4. OR install ZIP in main IDE and restart

**When sandbox works properly:**
- Change code → Close sandbox → Rebuild → Launch sandbox → Test
- Much faster than reinstalling in main IDE

## Debugging

### Enable Debug Logging
Add to `Help > Diagnostic Tools > Debug Log Settings`:
```
#com.github.copilot.intellij
```

### View Logs
- Sandbox IDE logs: `build/idea-sandbox/system/log/idea.log`
- Main IDE logs: `C:\Users\developer\AppData\Local\JetBrains\IntelliJIdea2025.3\log\idea.log`

### Debug with Breakpoints
```powershell
.\gradlew.bat --no-daemon :plugin-core:runIde --debug-jvm
```
Then attach debugger to port 5005.

## Current Known Issues

1. ❌ `runIde` fails with "Index: 1, Size: 1" error
2. ❌ `buildPlugin` fails on buildSearchableOptions task
3. ✅ Manual JAR build + ZIP creation works
4. ✅ Plugin installs and runs in main IDE
5. ✅ Sidecar binary embedded in JAR resources

## Next Steps for Better Dev Experience

- [ ] Disable buildSearchableOptions to fix build
- [ ] Fix runIde task for sandbox testing
- [ ] Make plugin dynamic-reload capable
- [ ] Add hot-reload support for faster iteration
