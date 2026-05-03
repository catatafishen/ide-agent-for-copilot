#!/usr/bin/env bash
# Success hook for run_in_terminal: appends a soft nudge when the command has
# a better dedicated MCP tool equivalent. Does not block — the command runs
# normally, but the output is annotated to guide the agent toward the better tool.
#
# Receives JSON payload on stdin: { toolName, arguments: {command, ...}, output, error, ... }
# Returns JSON with "append" to add nudge text, or exits silently if no nudge needed.

set -euo pipefail

result=$(cat | python3 -c "
import sys, json

payload = json.load(sys.stdin)
if payload.get('error'):
    sys.exit(0)

cmd = (payload.get('arguments') or {}).get('command', '').lower().strip()

def is_grep(c):
    return (c.startswith(('grep ', 'rg ', 'ag ')) or
            '| grep ' in c or '| rg ' in c or '| ag ' in c)

def is_cat(c):
    return (c.startswith(('cat ', 'head ', 'tail ', 'less ', 'more ')) or
            '| cat ' in c)

def is_find(c):
    return c.startswith('find ') or c.startswith('find.')

def is_ls(c):
    return (c.startswith('ls ') or c == 'ls' or
            c.startswith('dir ') or c == 'dir' or
            c.startswith('tree ') or c == 'tree')

def is_test(c):
    test_starters = [
        'npm test', 'npm run test', 'yarn test', 'pnpm test',
        'pytest', 'python -m pytest',
        'jest', 'vitest', 'mocha', 'ava', 'jasmine',
        './gradlew test', 'gradle test', './gradlew check', './gradlew build',
        'mvn test', 'mvn verify', 'mvn package',
        'go test',
    ]
    return any(c.startswith(s) or c == s.rstrip() for s in test_starters)

def is_build(c):
    build_starters = ['./gradlew compile', './gradlew classes', 'gradle compile', 'mvn compile']
    return any(c.startswith(s) for s in build_starters)

if is_grep(cmd):
    msg = '\u26a0\ufe0f Prefer search_text or search_symbols over shell grep \u2014 they search live editor buffers and support semantic lookup.'
elif is_cat(cmd):
    msg = '\u26a0\ufe0f Prefer read_file over shell cat/head/tail \u2014 it reads live editor buffers, not stale disk content.'
elif is_find(cmd):
    msg = '\u26a0\ufe0f Prefer list_project_files or list_directory_tree over shell find \u2014 they respect project structure and exclusions.'
elif is_ls(cmd):
    msg = '\u26a0\ufe0f Prefer list_project_files or list_directory_tree over shell ls/tree \u2014 they respect project structure and exclusions.'
elif is_test(cmd):
    msg = '\u26a0\ufe0f Prefer run_tests over shell test commands \u2014 it provides structured pass/fail results with IntelliJ test runner integration.'
elif is_build(cmd):
    msg = '\u26a0\ufe0f Prefer build_project over shell compile/build commands \u2014 it uses IntelliJ incremental compiler with structured error reporting.'
else:
    sys.exit(0)

print(json.dumps({'append': '\n\n' + msg}))
" 2>/dev/null) || exit 0

echo "$result"
