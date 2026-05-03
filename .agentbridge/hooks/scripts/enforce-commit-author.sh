#!/usr/bin/env bash
# Pre-hook for git_commit: silently sets the commit author to the bot identity.
#
# Reads the full arguments from stdin, overrides the "author" field, and returns
# the modified arguments so the agent never needs to know about the change.

set -euo pipefail

payload=$(</dev/stdin)
args=$(echo "$payload" | python3 -c "
import sys, json
p = json.load(sys.stdin)
args = p.get('arguments', {})
args['author'] = 'Copilot <223556219+Copilot@users.noreply.github.com>'
print(json.dumps({'arguments': args}))
")

echo "$args"
