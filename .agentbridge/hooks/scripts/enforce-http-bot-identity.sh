#!/usr/bin/env bash
# Pre-hook for http_request: intercepts GitHub API calls that create/modify
# content and enforces bot identity via Authorization header.
#
# Silent fix: if AGENTBRIDGE_BOT_TOKEN is set, injects/replaces the auth header.
# Error: if no bot token is available, blocks the call with an actionable error.
#
# Detected patterns:
#   POST/PATCH/PUT to api.github.com (or github.com/api)

set -euo pipefail

payload=$(</dev/stdin)

result=$(echo "$payload" | python3 -c "
import sys, json, os, re

p = json.load(sys.stdin)
args = p.get('arguments', {})
url = args.get('url', '')
method = args.get('method', 'GET').upper()

# Only intercept write methods to GitHub API
is_github_write = (
    method in ('POST', 'PATCH', 'PUT')
    and re.search(r'(api\.github\.com|github\.com/api)', url)
)

if not is_github_write:
    print('PASS')
    sys.exit(0)

bot_token = os.environ.get('AGENTBRIDGE_BOT_TOKEN', '')

if not bot_token:
    token_file = os.path.expanduser('~/.agentbridge/bot-token')
    try:
        with open(token_file) as f:
            bot_token = f.read().strip()
    except FileNotFoundError:
        pass

if bot_token:
    # Silent fix: inject bot auth header
    headers = args.get('headers', {})
    if isinstance(headers, str):
        headers = json.loads(headers)
    headers['Authorization'] = f'Bearer {bot_token}'
    args['headers'] = headers
    # Also set auth shorthand if present
    if 'auth' in args:
        args['auth'] = f'bearer {bot_token}'
    print(json.dumps({'arguments': args}))
else:
    print(json.dumps({
        'error': 'Identity policy: this HTTP request would call the GitHub API as the repository owner. '
                 'Set AGENTBRIDGE_BOT_TOKEN or create ~/.agentbridge/bot-token with a bot PAT.'
    }))
")

if [ "$result" = "PASS" ]; then
    exit 0  # Not a GitHub write operation, pass through
fi

echo "$result"
