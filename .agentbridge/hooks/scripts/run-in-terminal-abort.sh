#!/usr/bin/env bash
# Permission hook for run_in_terminal: blocks commands that cause IDE state desync.
# Only hard-blocks git and sed — commands with better MCP alternatives are handled
# by the success hook (run-in-terminal-reprimand.sh) as soft warnings.
#
# Receives JSON payload on stdin: { toolName, arguments: {command, ...}, projectName, timestamp }
# Returns: {"decision":"deny","reason":"..."} to block, or nothing to allow.

set -euo pipefail

result=$(cat | python3 -c "
import sys, json

payload = json.load(sys.stdin)
cmd = (payload.get('arguments') or {}).get('command', '').lower().strip()

def is_git(c):
    if c.startswith('git ') or c == 'git': return True
    if '&& git ' in c or '; git ' in c or '| git ' in c: return True
    if c.startswith(('sudo git', 'env git', 'command git', 'nohup git')): return True
    idx = c.find(' git')
    return idx > 0 and (idx + 4 >= len(c) or c[idx + 4] == ' ')

if is_git(cmd):
    print(json.dumps({'decision': 'deny', 'reason':
        'git commands are not allowed via run_in_terminal (causes IntelliJ buffer desync). '
        'Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, etc.'}))
elif cmd.startswith('sed ') or '| sed' in cmd:
    print(json.dumps({'decision': 'deny', 'reason':
        'sed is not allowed via run_in_terminal (bypasses IntelliJ editor buffers). '
        'Use edit_text with old_str/new_str for file editing instead.'}))
" 2>/dev/null) || exit 0

echo "$result"
