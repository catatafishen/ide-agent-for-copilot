# Authentication & SDK Setup Guide

**Last Updated**: 2026-02-12

---

## Overview

The sidecar uses the GitHub Copilot CLI SDK to provide agentic capabilities. To use the sidecar in production mode, you need:

1. GitHub Copilot CLI installed
2. GitHub authentication configured
3. Copilot enabled for your account

---

## Quick Start

### Development Mode (No Auth Required)

```bash
# Run with mock data for development/testing
copilot-sidecar.exe --mock

# With specific port
copilot-sidecar.exe --mock --port 9876
```

### Production Mode (Requires Auth)

```bash
# Run with real Copilot SDK
copilot-sidecar.exe

# Will fail with error if not authenticated
```

---

## Setup Instructions

### 1. Install GitHub CLI

**Windows** (using winget):
```powershell
winget install GitHub.cli
```

**Verify installation**:
```bash
gh --version
```

### 2. Authenticate with GitHub

```bash
gh auth login
```

Follow the prompts to:
- Select GitHub.com
- Choose HTTPS or SSH
- Authenticate via web browser
- Paste your authentication token

### 3. Enable GitHub Copilot

Ensure Copilot is enabled for your GitHub account:
- Individual subscription, or
- Organization/Enterprise license

### 4. Verify Setup

```bash
# Check auth status
gh auth status

# Should show:
# ✓ Logged in to github.com as <username>
```

---

## Running the Sidecar

### With Real SDK (Production)

```bash
# Start sidecar
cd copilot-bridge
.\bin\copilot-sidecar.exe

# Expected output:
# Starting Copilot Sidecar...
# 🚀 Initializing GitHub Copilot SDK...
# ✅ SDK initialized successfully
# Sidecar listening on http://localhost:<port>
```

### With Mock Data (Development)

```bash
# Start in mock mode
.\bin\copilot-sidecar.exe --mock

# Expected output:
# Starting Copilot Sidecar...
# ⚠️  Running in MOCK mode (for development only)
# Sidecar listening on http://localhost:<port>
```

---

## Troubleshooting

### Error: "Failed to initialize Copilot SDK"

**Symptoms**:
```
❌ Failed to initialize Copilot SDK:

failed to initialize Copilot SDK client: ...

Please ensure:
1. GitHub Copilot CLI is installed
2. You are authenticated (run: gh auth login)
3. Copilot is enabled for your account

💡 To use mock mode for development, run with --mock flag
```

**Solutions**:

1. **Check GitHub CLI is installed**:
   ```bash
   gh --version
   # If not found, install via winget install GitHub.cli
   ```

2. **Verify authentication**:
   ```bash
   gh auth status
   # Should show: ✓ Logged in to github.com
   ```

3. **Re-authenticate if needed**:
   ```bash
   gh auth login
   ```

4. **Check Copilot access**:
   - Go to https://github.com/settings/copilot
   - Verify Copilot is enabled

5. **Use mock mode for development**:
   ```bash
   copilot-sidecar.exe --mock
   ```

### Error: "Copilot CLI not found"

The SDK needs the Copilot CLI binary. It may be:
- Not installed
- Not in PATH
- Different version than expected

**Solution**: Install/update GitHub CLI and restart.

### Error: "Permission denied" or "Access denied"

Your GitHub account may not have Copilot enabled.

**Solution**:
- Individual users: Subscribe at https://github.com/copilot
- Organization users: Contact your admin

### Tests Timing Out

If SDK tests hang or timeout:

**Solution**: Run tests with mock client (already configured):
```bash
go test ./...  # Uses mock client automatically
```

---

## Command-Line Options

```
Usage of copilot-sidecar.exe:
  -callback string
        Plugin callback URL for tool execution
  -debug
        Enable debug logging
  -mock
        Run in mock mode (for development/testing)
  -port int
        Server port (0 for dynamic allocation)
```

### Examples

```bash
# Production with dynamic port
copilot-sidecar.exe

# Production with specific port
copilot-sidecar.exe --port 9876

# Development mode (no auth)
copilot-sidecar.exe --mock

# With debug logging
copilot-sidecar.exe --debug

# All options
copilot-sidecar.exe --port 9876 --mock --debug
```

---

## Environment Variables

### GITHUB_TOKEN (Optional)

If you have a GitHub personal access token, you can set:

```powershell
$env:GITHUB_TOKEN = "ghp_xxxxxxxxxxxxx"
```

However, `gh auth login` is the recommended method.

---

## Testing

### Run Unit Tests

```bash
cd copilot-bridge

# Go tests (use mock client automatically)
go test ./...

# PowerShell integration tests (use --mock flag)
.\test_sidecar.ps1
```

### Run with Real SDK

To test with real Copilot (after authentication):

```bash
# Remove --mock flag from test script temporarily
# Edit test_sidecar.ps1, line 17:
# Change: -ArgumentList "--port", "$FIXED_PORT", "--mock"
# To:     -ArgumentList "--port", "$FIXED_PORT"

.\test_sidecar.ps1
```

**Note**: This will make real API calls to GitHub Copilot.

---

## Architecture

```
Plugin (IntelliJ)
    ↓ JSON-RPC
Sidecar (Go)
    ↓
SDK Client ──┬──> Real Mode: copilot.NewClient()
             │        ↓
             │    Copilot CLI Process
             │        ↓
             │    GitHub Copilot API
             │
             └──> Mock Mode: MockClient()
                      ↓
                  Hardcoded responses
```

---

## FAQ

### Q: Why not use mock as fallback?

**A**: Mocks should only be in tests. In production, if the SDK is unavailable, we should fail clearly rather than silently fall back to fake data.

### Q: Can I use this without Copilot subscription?

**A**: Yes, use `--mock` flag for development. For production features, you need a Copilot subscription.

### Q: How do I switch between mock and real mode?

**A**: Use the `--mock` flag. No other changes needed.

### Q: Does this work offline?

**A**: No, the real SDK requires internet access. Use `--mock` for offline development.

### Q: What data is sent to GitHub?

**A**: When using real SDK:
- Your code context (files you select)
- Your prompts/questions
- Session metadata

When using `--mock`: Nothing is sent (all local).

---

## Next Steps

After authentication works:
1. Test with real Copilot queries
2. Add auth status checking endpoint
3. Implement auth refresh on expiry
4. Add UI for auth status

---

## Support

### Issues

If you encounter problems:
1. Check this troubleshooting guide
2. Run with `--debug` flag
3. Check sidecar logs
4. Verify `gh auth status`

### Resources

- [GitHub CLI Documentation](https://cli.github.com/manual/)
- [GitHub Copilot Documentation](https://docs.github.com/copilot)
- [Copilot SDK (Go)](https://github.com/github/copilot-sdk)

---

**Last Updated**: 2026-02-12  
**Version**: 1.0
