# TODO - Next Steps

## Immediate (This Session or Next)
- [ ] **Test Fixed Sidecar Integration**
  - Install new plugin: `plugin-core/build/distributions/plugin-core-0.1.0-SNAPSHOT.zip`
  - Verify models dropdown loads
  - Verify prompt execution works
  - Check logs: `C:\Users\developer\AppData\Local\JetBrains\IntelliJIdea2025.3\log\idea.log`

## High Priority
- [ ] **Fix Build System**
  - Disable `buildSearchableOptions` in `build.gradle.kts`
  - Fix `runIde` task (investigate "Index: 1, Size: 1" error)
  - Automate binary bundling properly in Gradle

- [ ] **Improve Development Workflow**
  - Enable sandbox IDE for testing (fix runIde)
  - Add dynamic plugin reloading support
  - Document fast iteration workflow

## Medium Priority
- [ ] **Real Copilot SDK Integration**
  - Replace mock responses in sidecar
  - Integrate with GitHub Copilot authentication
  - Test with real agent conversations
  - Implement proper conversation state

- [ ] **Polish UI**
  - Remove demo/placeholder data
  - Add proper error handling
  - Improve loading states
  - Add status indicators

## Low Priority (Future Phases)
- [ ] **Phase 3: Git Integration**
  - Git status display in tool window
  - Commit with Conventional Commits format
  - Branch management UI
  - Approval dialogs for dangerous operations

- [ ] **Phase 4: Code Quality**
  - Format on save integration
  - Format after agent edits
  - Import optimization
  - Pre-commit hooks

- [ ] **Phase 5: Advanced Features**
  - Multi-session support
  - Session history/persistence
  - Export/import conversations
  - Custom model configuration

## Technical Debt
- [ ] Make plugin dynamic-reload capable
- [ ] Add comprehensive error handling
- [ ] Add unit tests for core services
- [ ] Add integration tests
- [ ] Performance optimization (binary extraction caching)
- [ ] Security review (temp file handling)

## Documentation
- [ ] Update README with installation instructions
- [ ] Add screenshots to docs
- [ ] Create user guide
- [ ] Document troubleshooting steps
- [ ] Add contributing guidelines

---

## Blocked / Waiting
- ⏸️ Copilot SDK availability (need GitHub SDK documentation)
- ⏸️ User testing results (current session fixes)

## Done This Session ✅
- ✅ Diagnosed sidecar binary loading failure
- ✅ Implemented multi-path binary search
- ✅ Added resource extraction from JAR
- ✅ Fixed compilation errors (imports)
- ✅ Built new plugin with embedded binary
- ✅ Created CHECKPOINT.md for continuity
- ✅ Created DEVELOPMENT.md for workflow
- ✅ Updated plan.md with current status

---

*Last Updated: 2026-02-11 21:52 UTC*
