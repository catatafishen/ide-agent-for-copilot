#!/usr/bin/env bash
# Success hook for git_push: appends PR creation tip after pushing feature branches.
#
# Receives JSON payload on stdin with: toolName, arguments, output, error, projectName, timestamp, durationMs
# Returns JSON with "append" to add text to tool output, or exits silently to leave output unchanged.

set -euo pipefail

payload=$(cat)

is_error=$(echo "$payload" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error', False))" 2>/dev/null || echo "false")
if [ "$is_error" = "True" ] || [ "$is_error" = "true" ]; then
    exit 0
fi

output=$(echo "$payload" | python3 -c "import sys,json; print(json.load(sys.stdin).get('output',''))" 2>/dev/null || echo "")
branch=$(echo "$output" | grep -oP 'Pushed \K[^ ]+' | head -1 || true)

if [ -z "$branch" ]; then
    exit 0
fi

# Only append the PR reminder for feature branches.
if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
    exit 0
fi

echo '{"append": "\nTip: create a PR with: gh pr create\nReminder: PRs, issues, and discussions should use the bot identity. If only user credentials are available, say explicitly that the action was authored by the bot on behalf of the user."}'
