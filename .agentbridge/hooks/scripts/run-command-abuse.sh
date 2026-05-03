#!/usr/bin/env bash
# Permission hook for run_command: blocks shell commands that should use dedicated MCP tools.
# Prevents IntelliJ buffer desync and guides agents toward IDE-integrated equivalents.
#
# Receives JSON payload on stdin: { toolName, arguments: {command, ...}, projectName, timestamp }
# Returns: {"decision":"deny","reason":"..."} to block, or nothing to allow.
#
# Note: grep and test/build commands are intentionally NOT blocked here —
#   grep may legitimately target non-source paths (checked by the tool itself),
#   and test/build commands are redirected to the dedicated RunTestsTool.

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

def is_cat(c):
    return (c.startswith(('cat ', 'head ', 'tail ', 'less ', 'more ')) or
            '| cat ' in c or '&& cat ' in c or '; cat ' in c)

def is_sed(c):
    return c.startswith('sed ') or '| sed' in c or '&& sed' in c or '; sed' in c

def is_find(c):
    return c.startswith('find ') or c.startswith('find.')

def is_gradle_compile_only(c):
    if 'gradlew' not in c: return False
    compile_tasks = ['compilejava', 'compilekotlin', 'compiletestjava', 'compiletestkotlin', 'classes', 'testclasses']
    return any(t in c for t in compile_tasks) and not any(t in c for t in ['test', 'check', 'build', 'assemble'])

if is_git(cmd):
    print(json.dumps({'decision': 'deny', 'reason':
        'git commands are not allowed via run_command (causes IntelliJ buffer desync). '
        'Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, '
        'git_stage, git_unstage, git_branch, git_stash, git_show, git_blame, git_push, '
        'git_remote, git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, '
        'git_tag, git_reset.'}))
elif is_cat(cmd):
    print(json.dumps({'decision': 'deny', 'reason':
        'cat/head/tail/less/more are not allowed via run_command (reads stale disk files). '
        'Use read_file to read live editor buffers instead.'}))
elif is_sed(cmd):
    print(json.dumps({'decision': 'deny', 'reason':
        'sed is not allowed via run_command (bypasses IntelliJ editor buffers). '
        'Use edit_text with old_str/new_str for file editing instead.'}))
elif is_find(cmd):
    print(json.dumps({'decision': 'deny', 'reason':
        'find commands are not allowed via run_command. '
        'Use list_project_files or list_directory_tree to find files instead.'}))
elif is_gradle_compile_only(cmd):
    print(json.dumps({'decision': 'deny', 'reason':
        'Gradle compile tasks are not allowed via run_command. '
        'Use build_project to compile via IntelliJ incremental compiler instead.'}))
" 2>/dev/null) || exit 0

echo "$result"
