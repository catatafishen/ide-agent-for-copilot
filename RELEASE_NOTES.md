# Release Notes - v0.2.0-BETA

**Release Date:** February 26, 2026  
**Status:** Ready for User Testing

## 🎉 Major Improvements

### ✨ New Features

1. **Plan Mode with [[PLAN]] Prefix**
   - Toggle "Plan" mode in toolbar to enable structured planning
   - User prompts automatically prefixed with `[[PLAN]]` for agent awareness
   - Applies to both direct prompts and quick-reply selections

2. **Improved Model Switching**
   - Fixed: Model selection now uses `session/set_model` RPC instead of CLI restart
   - Instant model switching without process interruption
   - Saved model preference applied to new sessions automatically

3. **Code Block Copy Button Fix**
   - Fixed: "Copy" text no longer appears during response streaming
   - Copy button positioned as sibling element (not child of code block)
   - Appears on hover in top-right corner of code blocks

### 🐛 Bug Fixes

- Fixed model switching - now uses proper ACP RPC instead of CLI restart
- Fixed "Copy" text appearing at start of responses with code blocks
- Fixed empty agent bubble appearing briefly during thinking
- Fixed quick-reply tag detection (requires own line, not inline)

### 🎨 UI Improvements

- Added smooth animations for section collapse/expand (250ms fade + scale)
- Added sub-agent status badges with animated pulse (Working/Done/Failed)
- Improved auth error messaging with actionable suggestions
- Enhanced keyboard accessibility (tabindex, ARIA labels, focus indicators)
- Added code block copy button with theme-aware styling
- Replaced JOptionPane with IntelliJ Messages API (6 locations)

### ♿ Accessibility Improvements

- All interactive elements now keyboard accessible
- Added ARIA attributes (aria-expanded, role attributes)
- Added :focus-visible outlines to all buttons and interactive elements
- Added prefers-reduced-motion media query support
- Proper tab ordering in tool window

### 📱 Theme Support

- Proper dark/light theme colors via JBColor and CSS custom properties
- Theme-adaptive shadows and contrast ratios
- Color injection from Kotlin to CSS variables at runtime

## 📊 Code Quality

### Reduced Warnings

- Extracted 20+ duplicated string literals to constants
- Removed unused function parameters
- Replaced deprecated API calls

**Remaining Issues (non-critical, tracked):**
- 5 high-complexity methods flagged (S3776) - refactoring candidates
- 2 regex complexity warnings - can be simplified
- 3-4 other code quality suggestions (style-related)

## 🧪 Testing

### Test Coverage

- 48 unit tests across 4 test files
- All tests passing (verified Feb 26)
- Coverage includes:
  - ACP client communication
  - Permission handling  
  - MCP tool execution
  - Session lifecycle

### Running Tests

```bash
./gradlew test
# Output: 48 tests passed
```

## 📚 Documentation Updates

### Updated Documentation
- **README.md** - Current feature status and quick overview
- **QUICK-START.md** - Step-by-step build/install guide with troubleshooting
- **DEVELOPMENT.md** - Development setup and architecture
- **TESTING.md** - How to run tests and test coverage info

### Archived Documentation
The following checkpoint/phase documents are now archived and not needed for user testing:
- CHECKPOINT.md (from Feb 12, obsolete)
- docs/PHASE1-COMPLETE.md, PHASE2-COMPLETE.md, UPGRADE-COMPLETE.md
- docs/DEVELOPMENT.md (superseded by root DEVELOPMENT.md)
- SANDBOX-TESTING.md (sandbox no longer primary testing method)
- SETUP-LINUX.md (consolidated into DEVELOPMENT.md)
- LINUX-DEV-GUIDE.md (consolidated into DEVELOPMENT.md)

### Active Documentation
- **README.md** - Main overview and architecture
- **QUICK-START.md** - User-focused getting started guide
- **DEVELOPMENT.md** - Developer guide
- **TESTING.md** - Test running and coverage
- **PROJECT-SPEC.md** - Original specification
- **ROADMAP.md** - Project phases and future work
- **docs/** - Technical deep-dives (ACP, MCP, Authentication, etc.)

## 🚀 Deployment

### Building for User Testing

```bash
# Windows (PowerShell)
.\gradlew.bat :plugin-core:clean :plugin-core:buildPlugin

# Linux/macOS
./gradlew :plugin-core:clean :plugin-core:buildPlugin

# Output: plugin-core/build/distributions/plugin-core-0.1.0-SNAPSHOT.zip
```

### Installation Instructions

See **QUICK-START.md** for detailed platform-specific instructions.

**Quick Summary:**
1. Close IntelliJ IDEA
2. Extract ZIP to `{IDEA_CONFIG}/plugins/` directory  
3. Restart IntelliJ
4. Open View → Tool Windows → Copilot Bridge

## ✅ User Testing Checklist

Before sharing with test users, verify:

- [ ] Plugin builds without errors: `./gradlew clean build`
- [ ] All tests pass: `./gradlew test`
- [ ] No SonarQube HIGH/MEDIUM issues (code quality)
- [ ] Plugin installs and loads in IntelliJ
- [ ] Tool window appears and all tabs accessible
- [ ] Models load and can be selected
- [ ] Can send prompts and receive streaming responses
- [ ] Plan mode prefix works correctly
- [ ] Model switching is instant (no CLI restart)
- [ ] Code blocks have copy button
- [ ] No "Copy" text appears in code content
- [ ] Auth errors show helpful messages
- [ ] IDE doesn't crash on repeated use

## 🔗 Known Issues & Workarounds

### ACP/CLI Issues

**Issue:** Model filtering doesn't work in --acp mode (Copilot CLI #556)  
**Status:** Worked around with permission denial + MCP tool retry  
**Details:** See docs/CLI-BUG-556-WORKAROUND.md

### Windows File Cleanup

**Issue:** Clean build may fail on Windows with locked sandbox files  
**Workaround:** Omit `clean` task or run from fresh sandbox

## 💡 For Test Users

### Tips for Testing

1. **Start Simple:**
   - "Explain what's in the root directory"
   - "Create a test file with sample content"
   - "What build system does this project use?"

2. **Test Advanced Features:**
   - Attach files/selections as context
   - Switch between models and observe instant change
   - Toggle Plan mode to see structured planning
   - Try quick-reply buttons

3. **Observe Quality:**
   - Smooth streaming responses (no delays)
   - Proper error handling (no crashes)
   - Keyboard navigation works
   - Dark/light theme support works

### Feedback Template

Please report:
- What worked well
- What felt confusing
- Any bugs or crashes
- Missing features you'd expect
- Performance observations

## 🔮 What's Next

After user testing phase:
- Address high-priority feedback
- Fix any remaining warnings (S3776 complex methods)
- Performance optimization if needed
- Documentation improvements based on user questions
- Possible features: markdown rendering, terminal support, advanced git operations

## 📦 Version Info

- **Plugin Version:** 0.2.0-BETA
- **IntelliJ Platform:** 2024.3+
- **Java:** 21
- **Gradle:** 8.x
- **GitHub Copilot CLI:** Latest (0.0.418+)

---

**Questions?** See the docs/ folder or check the troubleshooting section in QUICK-START.md
