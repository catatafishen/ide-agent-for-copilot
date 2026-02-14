# Linux Setup Instructions

## Restore from this archive

1. Extract this zip to your projects directory
2. Initialize git from the bundle:
   ```
   cd intellij-copilot-plugin
   git init
   git pull repo.bundle master
   rm repo.bundle
   ```

3. Update `gradle.properties` with your Linux paths:
   ```properties
   intellijPlatform.localPath=/path/to/your/intellij-idea
   org.gradle.java.home=/path/to/jdk-21
   ```

4. Build and test:
   ```bash
   ./gradlew :mcp-server:test
   ./gradlew :plugin-core:buildPlugin
   ```

5. Deploy - see QUICK-START.md and DEVELOPMENT.md
