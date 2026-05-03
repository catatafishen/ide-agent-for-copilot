#!/usr/bin/env bash
# Hook for git_commit and git_push: appends workflow reminders about bot authorship
# and PR creation after pushing a feature branch.
#
# Receives a JSON payload on stdin with fields:
#   toolName, arguments, argumentsJson, output, error, projectName, timestamp
#
# Returns JSON with "append" to add text to the tool output, or exits silently
# (empty stdout) to leave the output unchanged.

set -euo pipefail

# Read the hook payload from stdin.
payload=$(cat)

# Don't modify error responses.
is_error=$(echo "$payload" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error', False))" 2>/dev/null || echo "false")
if [ "$is_error" = "True" ] || [ "$is_error" = "true" ]; then
    exit 0
fi

tool_name=$(echo "$payload" | python3 -c "import sys,json; print(json.load(sys.stdin).get('toolName',''))" 2>/dev/null || echo "")

if [ "$tool_name" = "git_commit" ]; then
    echo '{"append": "\nReminder: commits should be authored with the bot identity. If only user credentials are available, amend the commit and state explicitly that the change was authored by the bot on behalf of the user."}'
    exit 0
fi

if [ "$tool_name" != "git_push" ]; then
    exit 0
fi

# Extract the branch from the git_push output.
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
