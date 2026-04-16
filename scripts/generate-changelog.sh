#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# generate-changelog.sh — Generate release notes from git commits,
# optionally summarised by an LLM.
#
# Usage:
#   scripts/generate-changelog.sh [VERSION]
#
# Environment:
#   PLUGIN_VERSION      — fallback if VERSION positional arg is omitted
#   OPENAI_API_KEY      — if set, uses GPT-4o-mini to summarise commits
#   CHANGELOG_MODEL     — override the OpenAI model (default: gpt-4o-mini)
#   CHANGELOG_BASELINE  — override baseline tag (default: auto-detect)
#   CHANGELOG_FORMAT    — output format: "html" (default) or "md"
#
# Baseline detection (when CHANGELOG_BASELINE is not set):
#   1. 'marketplace-latest' tag — includes all commits since last marketplace
#      publish (used for plugin.xml change-notes that accumulate changes)
#   2. Falls back to latest v* tag if no marketplace tag exists
#
# Output: HTML (for plugin.xml <change-notes>) or Markdown (for GitHub
#         release notes), written to stdout.
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────
VERSION="${1:-${PLUGIN_VERSION:-}}"
if [[ -z "$VERSION" ]]; then
  echo "Error: version required (pass as arg or set PLUGIN_VERSION)" >&2
  exit 1
fi

FORMAT="${CHANGELOG_FORMAT:-html}"

# ── Determine baseline tag ─────────────────────────────────────────────────
if [[ -n "${CHANGELOG_BASELINE:-}" ]]; then
  BASELINE_TAG="$CHANGELOG_BASELINE"
else
  BASELINE_TAG=$(git tag --list 'marketplace-latest' | head -n1)
  if [[ -z "$BASELINE_TAG" ]]; then
    BASELINE_TAG=$(git tag --list 'v*' --sort=-v:refname | head -n1)
  fi
fi

# ── Collect commit subjects ────────────────────────────────────────────────
if [[ -z "$BASELINE_TAG" ]]; then
  COMMITS=$(git log --pretty=format:"%s" HEAD)
else
  COMMITS=$(git log --pretty=format:"%s" "${BASELINE_TAG}..HEAD")
fi

if [[ -z "$COMMITS" ]]; then
  echo "No commits since ${BASELINE_TAG:-initial}" >&2
  COMMITS="General improvements and bug fixes"
fi

# Strip conventional-commit prefixes for cleaner LLM input and plain output.
# "feat(scope): msg" → "msg", "fix!: msg" → "msg"
strip_prefix() {
  sed -E 's/^[a-z]+(\([^)]*\))?!?:[[:space:]]*//'
}

# Capitalise the first letter of each line.
capitalise() {
  while IFS= read -r line; do
    echo "$(echo "${line:0:1}" | tr '[:lower:]' '[:upper:]')${line:1}"
  done
}

CLEAN_COMMITS=$(echo "$COMMITS" | strip_prefix | capitalise)

# ── Static header (HTML only, for plugin.xml) ──────────────────────────────
STATIC_HEADER='<p><b>AgentBridge</b> connects AI coding agents to your IDE via 100+ MCP tools
for code intelligence, navigation, editing, debugging, and git.</p>
<p>
  <a href="https://github.com/catatafishen/agentbridge">GitHub</a> ·
  <a href="https://github.com/catatafishen/agentbridge/releases">All Releases</a>
</p>
<hr/>'

# ── LLM summarisation (optional) ──────────────────────────────────────────
# Returns JSON: { "title": "...", "bullets": ["...", ...] }
call_llm() {
  local model="${CHANGELOG_MODEL:-gpt-4o-mini}"
  local prompt
  prompt=$(cat <<'SYSTEM'
You are writing release notes for a JetBrains IDE plugin called AgentBridge.
Given git commit subjects since the last release, output ONLY a JSON object with:
- "title": a catchy 3-5 word release title (no version number)
- "bullets": array of 3-7 concise, user-facing bullet points

Rules:
- Merge related commits into single bullets
- Use plain English, no commit prefixes (feat/fix/refactor)
- Focus on what changed for the user, not implementation details
- Skip chore/ci/docs-only commits unless user-visible
SYSTEM
  )

  local user_msg="Commits:\n${CLEAN_COMMITS}"

  local payload
  payload=$(jq -n \
    --arg model "$model" \
    --arg system "$prompt" \
    --arg user "$user_msg" \
    '{
      model: $model,
      temperature: 0.3,
      response_format: { type: "json_object" },
      messages: [
        { role: "system", content: $system },
        { role: "user",   content: $user }
      ]
    }')

  local response
  response=$(curl -sS --fail-with-body \
    -H "Authorization: Bearer ${OPENAI_API_KEY}" \
    -H "Content-Type: application/json" \
    -d "$payload" \
    "https://api.openai.com/v1/chat/completions" 2>&1) || {
    echo "LLM API call failed: $response" >&2
    return 1
  }

  local content
  content=$(echo "$response" | jq -r '.choices[0].message.content // empty')
  if [[ -z "$content" ]]; then
    echo "LLM returned empty content" >&2
    return 1
  fi

  local title
  title=$(echo "$content" | jq -r '.title // empty')
  local bullets
  bullets=$(echo "$content" | jq -r '.bullets[]? // empty')

  if [[ -z "$title" || -z "$bullets" ]]; then
    echo "LLM response missing title or bullets" >&2
    return 1
  fi

  # Return structured output: first line = title, rest = bullets
  echo "$title"
  echo "$bullets"
}

# ── Format: HTML (for plugin.xml <change-notes>) ──────────────────────────
format_html() {
  local title="$1"
  shift
  local bullets=("$@")

  if [[ -n "$title" ]]; then
    echo "<h3>${VERSION} &mdash; ${title}</h3>"
  else
    echo "<h3>${VERSION}</h3>"
  fi
  echo "<ul>"
  for bullet in "${bullets[@]}"; do
    echo "    <li>${bullet}</li>"
  done
  echo "</ul>"
}

# ── Format: Markdown (for GitHub release notes) ───────────────────────────
format_md() {
  local title="$1"
  shift
  local bullets=("$@")

  if [[ -n "$title" ]]; then
    echo "## ${VERSION} — ${title}"
  else
    echo "## ${VERSION}"
  fi
  echo ""
  for bullet in "${bullets[@]}"; do
    echo "- ${bullet}"
  done
}

# ── Generate content ──────────────────────────────────────────────────────
LLM_TITLE=""
LLM_BULLETS=()

if [[ -n "${OPENAI_API_KEY:-}" ]] && command -v jq &>/dev/null; then
  llm_output=$(call_llm) || true
  if [[ -n "$llm_output" ]]; then
    LLM_TITLE=$(echo "$llm_output" | head -1)
    mapfile -t LLM_BULLETS < <(echo "$llm_output" | tail -n +2)
  fi
fi

# Fall back to cleaned commit subjects if LLM didn't produce output
if [[ ${#LLM_BULLETS[@]} -eq 0 ]]; then
  mapfile -t LLM_BULLETS < <(echo "$CLEAN_COMMITS")
fi

# ── Output ─────────────────────────────────────────────────────────────────
if [[ "$FORMAT" == "md" ]]; then
  format_md "$LLM_TITLE" "${LLM_BULLETS[@]}"
else
  echo "$STATIC_HEADER"
  echo ""
  format_html "$LLM_TITLE" "${LLM_BULLETS[@]}"
fi
