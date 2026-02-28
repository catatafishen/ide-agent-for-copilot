# Development Guide

## Prerequisites

### Required Software

- **JDK 21+** (Temurin recommended)
    - Set `JAVA_HOME` environment variable
- **Gradle 8.13+**
    - Or use the included Gradle wrapper (`./gradlew`)
- **IntelliJ IDEA 2025.1+** (Community or Ultimate)

### Optional

- **Git** (for version control)
- **GitHub Copilot CLI** (for ACP integration)
- **GitHub Copilot Subscription**

---

## Initial Setup

### 1. Clone and Open Project

```bash
git clone <repository-url>
cd intellij-copilot-plugin
```

Open the project in IntelliJ IDEA:

- File > Open > Select `intellij-copilot-plugin` directory
- Wait for Gradle sync to complete (first time will download IntelliJ SDK ~400MB)

### 2. Configure IntelliJ for Plugin Development

1. **Install Plugin DevKit** (if not already installed)
    - File > Settings > Plugins
    - Search for "Plugin DevKit"
    - Install and restart

2. **Enable Internal Mode** (optional, for debugging)
    - Help > Edit Custom Properties
    - Add: `idea.is.internal=true`
    - Restart IDE

---

## Development Workflow

### Building the Plugin

#### Using Gradle Wrapper (recommended)

```bash
# Full build
./gradlew build

# Quick compile (no tests)
./gradlew assemble

# Run tests
./gradlew test

# Build plugin ZIP
./gradlew buildPlugin
```

#### Using IntelliJ Gradle Tool Window

1. View > Tool Windows > Gradle
2. Expand `intellij-copilot-plugin > Tasks`
3. Double-click tasks like `build`, `test`, or `runIde`

### Running in Sandbox IDE

```bash
./gradlew runIde
```

This:

1. Compiles the plugin
2. Downloads IntelliJ IDEA (if needed)
3. Launches a sandboxed IDE with plugin installed
4. Sandbox data: `build/idea-sandbox/`

To reset sandbox:

```bash
rm -rf build/idea-sandbox
```

### Hot Reload (Fast Iteration)

While sandbox IDE is running:

1. Make code changes
2. In main IDE: Build > Build Project (Ctrl+F9)
3. In sandbox IDE: Help > Actions > Reload All from Disk
4. Plugin changes applied without restart!

**Note**: Major changes (plugin.xml, services) require full restart.

---

## Project Structure

```
intellij-copilot-plugin/
├── build.gradle.kts          # Root build configuration
├── settings.gradle.kts       # Module configuration
│
├── plugin-core/              # Main plugin module (Java 21)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── java/
│       │   │   └── com/github/copilot/intellij/
│       │   │       ├── ui/           # Swing UI components
│       │   │       ├── services/     # Application/project services
│       │   │       ├── bridge/       # ACP protocol communication
│       │   │       ├── psi/          # PSI bridge (MCP tools)
│       │   │       ├── git/          # Git VCS integration
│       │   │       ├── format/       # Code formatting
│       │   │       └── settings/     # Configuration
│       │   └── resources/
│       │       └── META-INF/
│       │           └── plugin.xml    # Plugin manifest
│       └── test/
│           └── java/                 # Unit tests
│
├── mcp-server/               # MCP stdio server (standalone JAR)
│   └── src/main/java/
│
├── integration-tests/        # End-to-end tests
│   └── src/test/java/
│
└── docs/                     # Documentation
    ├── ARCHITECTURE.md
    └── CONTRIBUTING.md
```

---

## Adding New Features

### 1. Create a New UI Component

**Example**: Add a new tab to the Tool Window

```java
// plugin-core/src/main/java/com/github/copilot/intellij/ui/NewTab.java
package com.github.copilot.intellij.ui;

import com.intellij.ui.components.JBPanel;

import javax.swing.*;

public class NewTab extends JBPanel {
    public NewTab() {
        setLayout(new BorderLayout());
        add(new JLabel("New Tab Content"), BorderLayout.CENTER);
    }
}
```

Add to Tool Window:

```java
// In AgenticCopilotToolWindow.java
tabbedPane.addTab("New Tab",new NewTab());
```

### 2. Create a New Service

**Example**: Add a service for managing user preferences

```java
// plugin-core/src/main/java/com/github/copilot/intellij/services/PreferenceService.java
package com.github.copilot.intellij.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

@Service(Service.Level.PROJECT)
public final class PreferenceService {
    private final Project project;

    public PreferenceService(Project project) {
        this.project = project;
    }

    public static PreferenceService getInstance(Project project) {
        return project.getService(PreferenceService.class);
    }

    // Service methods here
}
```

Register in `plugin.xml`:

```xml
<extensions defaultExtensionNs="com.intellij">
  <projectService 
      serviceImplementation="com.github.copilot.intellij.services.PreferenceService"/>
</extensions>
```

### 3. Add a New Action

**Example**: Add menu item to invoke agent

```java
// plugin-core/src/main/java/com/github/copilot/intellij/actions/InvokeAgentAction.java
package com.github.copilot.intellij.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class InvokeAgentAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Action logic here
    }
}
```

Register in `plugin.xml`:

```xml

<actions>
    <action id="AgenticCopilot.Invoke"
            class="com.github.copilot.intellij.actions.InvokeAgentAction"
            text="Invoke Copilot Agent"
            description="Open Copilot Agent with current selection">
        <add-to-group group-id="EditorPopupMenu" anchor="first"/>
        <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt C"/>
    </action>
</actions>
```

---

## Testing

### Running Unit Tests

```bash
# All tests
./gradlew test

# Specific module
./gradlew :plugin-core:test

# Specific test class
./gradlew test --tests "com.github.copilot.intellij.SomeTest"

# With coverage
./gradlew test jacocoTestReport
# Open: build/reports/jacoco/test/html/index.html
```

### Writing Tests

**Unit Test Example**:

```java
package com.github.copilot.intellij.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CopilotAcpClientTest {
    @Test
    void testSessionCreate() throws Exception {
        // Test ACP protocol communication
        // ... assertions
    }
}
```

**Integration Test Example**:

```java
package com.github.copilot.intellij;

import org.junit.jupiter.api.*;

class AcpIntegrationTest {

    @Test
    void testHealthCheck() {
        // Test ACP client lifecycle
    }
}
```

---

## Debugging

### Debug Plugin in Sandbox IDE

1. Add breakpoints in your code
2. Run with debugger:
   ```bash
   ./gradlew runIde --debug-jvm
   ```
3. In main IDE: Run > Attach to Process
4. Select the process with port 5005
5. Breakpoints will be hit in sandbox IDE

### View Logs

**Plugin Logs**:

- Help > Show Log in Explorer
- Or: `build/idea-sandbox/system/log/idea.log`

**Add Logging in Code**:

```java
import com.intellij.openapi.diagnostic.Logger;

private static final Logger LOG = Logger.getInstance(MyClass.class);

LOG.

info("Info message");
LOG.

warn("Warning message");
LOG.

error("Error message",exception);
```

---

## Common Issues

### Issue: Gradle sync fails with "Unresolved reference: intellijPlatform"

**Solution**: Make sure IntelliJ Platform Gradle Plugin is applied correctly in `build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.intellij.platform") version "2.1.0" apply false
}
```

### Issue: Sandbox IDE won't start

**Solution**:

1. Delete sandbox: `rm -rf build/idea-sandbox`
2. Re-run: `./gradlew runIde`

### Issue: Changes not reflected in sandbox

**Solution**:

1. Stop sandbox IDE
2. Clean build: `./gradlew clean build`
3. Re-run: `./gradlew runIde`

### Issue: "JAVA_HOME is not set"

**Solution**:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk21"
$env:Path += ";$env:JAVA_HOME\bin"
```

---

## Code Style

### Java

- Follow IntelliJ IDEA's default Java code style
- Use `@NotNull` and `@Nullable` annotations
- Prefer immutability where possible
- Document public APIs with Javadoc

---

## Useful Commands

```bash
# Build plugin ZIP
./gradlew buildPlugin
# Output: build/distributions/agentic-copilot-intellij-0.1.0-SNAPSHOT.zip

# Verify plugin structure
./gradlew verifyPlugin

# Run plugin verifier (compatibility check)
./gradlew runPluginVerifier

# List all Gradle tasks
./gradlew tasks --all

# Clean all build artifacts
./gradlew clean
```

---

## Next Steps

1. **Complete Phase 1**: See `plan.md` for detailed tasks
2. **Read Architecture**: See `docs/ARCHITECTURE.md`
3. **Write Tests**: Aim for ≥85% coverage
4. **Submit PR**: Follow contribution guidelines

---

## Getting Help

- **IntelliJ Platform SDK Docs**: https://plugins.jetbrains.com/docs/intellij/
- **GitHub Copilot**: https://docs.github.com/copilot
- **Project Issues**: https://github.com/yourusername/intellij-copilot-plugin/issues
