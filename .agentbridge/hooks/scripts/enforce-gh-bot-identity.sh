#!/usr/bin/env bash
# Pre-hook for run_command: intercepts GitHub CLI commands that create content
# (PRs, comments, issues, discussions) and enforces bot identity.
#
# Silent fix: if AGENTBRIDGE_BOT_TOKEN is set, prepends GH_TOKEN=... to the command.
# Error: if no bot token is available, blocks the call with an actionable error message.
#
# Detected patterns:
#   gh pr create|comment|review|edit|merge
#   gh issue create|comment|edit
#   gh discussion create|comment
#   gh api ... -X POST/PATCH/PUT (to github.com repos)

set -euo pipefail

payload=$(</dev/stdin)

command=$(echo "$payload" | python3 -c "
import sys, json
p = json.load(sys.stdin)
print(p.get('arguments', {}).get('command', ''))
")

# Check if this is a GitHub CLI command that creates or modifies content
needs_bot_identity=$(echo "$command" | python3 -c "
import sys, re

cmd = sys.stdin.read().strip()

# Direct gh subcommands that create/modify GitHub content
content_patterns = [
    r'^gh\s+pr\s+(create|comment|review|edit|merge)\b',
    r'^gh\s+issue\s+(create|comment|edit)\b',
    r'^gh\s+discussion\s+(create|comment)\b',
    r'^gh\s+release\s+create\b',
    # gh api with write methods
    r'^gh\s+api\s+.*\s+-(X|method)\s+(POST|PATCH|PUT)\b',
    r'^gh\s+api\s+-(X|method)\s+(POST|PATCH|PUT)\b',
]

for pattern in content_patterns:
    if re.search(pattern, cmd, re.IGNORECASE):
        print('true')
        sys.exit(0)

print('false')
")

if [ "$needs_bot_identity" != "true" ]; then
    exit 0  # Not a content-creating command, pass through unchanged
fi

# Check for bot token
bot_token="${AGENTBRIDGE_BOT_TOKEN:-}"

if [ -z "$bot_token" ] && [ -f "${HOME}/.agentbridge/bot-token" ]; then
    bot_token=$(tr -d '[:space:]' < "${HOME}/.agentbridge/bot-token")
fi

if [ -n "$bot_token" ]; then
    # Silent fix: inject bot token into the command
    echo "$payload" | python3 -c "
import sys, json
p = json.load(sys.stdin)
args = p.get('arguments', {})
cmd = args.get('command', '')
args['command'] = 'GH_TOKEN=${AGENTBRIDGE_BOT_TOKEN} ' + cmd
print(json.dumps({'arguments': args}))
"
else
    # No bot token available — block with actionable error
    echo '{"error": "Identity policy: this command would post GitHub content as the repository owner. Set AGENTBRIDGE_BOT_TOKEN or create ~/.agentbridge/bot-token with a bot PAT to allow silent identity switching. Alternatively, use gh api with a bot-authenticated token."}'
fi
