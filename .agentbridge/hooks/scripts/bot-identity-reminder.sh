#!/usr/bin/env bash
# Success hook for git_commit: reminds about bot identity for authorship.
#
# Receives JSON payload on stdin with: toolName, arguments, output, error, projectName, timestamp, durationMs
# Returns JSON with "append" to add text to tool output, or exits silently to leave output unchanged.

set -euo pipefail

payload=$(cat)

is_error=$(echo "$payload" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error', False))" 2>/dev/null || echo "false")
if [ "$is_error" = "True" ] || [ "$is_error" = "true" ]; then
    exit 0
fi

echo '{"append": "\nReminder: commits should be authored with the bot identity. If only user credentials are available, amend the commit and state explicitly that the change was authored by the bot on behalf of the user."}'
