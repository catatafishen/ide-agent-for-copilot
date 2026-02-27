# Authentication Guide

The plugin authenticates via the **GitHub Copilot CLI** using the ACP (Agent Client Protocol).
No separate authentication setup is needed — the plugin uses the same credentials as the Copilot CLI.

## Setup

### 1. Install GitHub CLI

```bash
# Windows
winget install GitHub.cli

# macOS
brew install gh

# Linux
sudo apt install gh  # or equivalent for your distro
```

### 2. Authenticate

```bash
gh auth login
```

Follow the prompts to authenticate with GitHub.com via web browser.

### 3. Verify Copilot Access

```bash
gh auth status
# Should show: ✓ Logged in to github.com as <username>
```

Ensure GitHub Copilot is enabled for your account (individual subscription or organization license).

## How It Works

The plugin spawns the Copilot CLI as a child process and communicates via ACP (JSON-RPC 2.0 over stdin/stdout).
The CLI handles all authentication and token management automatically using your `gh` credentials.

## Troubleshooting

- **"Error loading models"**: Run `copilot auth` or `gh auth login` to re-authenticate.
- **No Copilot subscription**: Subscribe at https://github.com/copilot or contact your org admin.
- **CLI not found**: Ensure `copilot` or `gh` is in your PATH.

## Resources

- [GitHub CLI Documentation](https://cli.github.com/manual/)
- [GitHub Copilot Documentation](https://docs.github.com/copilot)
