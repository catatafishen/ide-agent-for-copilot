# Billing & Usage Tracking

## Overview

The plugin displays real-time Copilot premium request usage in the toolbar,
including a sparkline graph, usage counter, cost estimates, and overage warnings.

## Data Source

Usage data comes from GitHub's **undocumented** `copilot_internal/user` API endpoint:

```
GET https://api.github.com/copilot_internal/user
Authorization: Bearer <gh-cli-token>
```

This is the same endpoint used by VS Code's Copilot status bar and the
[Copilot Insights](https://github.com/kasuken/vscode-copilot-insights) extension.

### Response Structure (relevant fields)

```json
{
  "copilot_plan": "individual_pro",
  "quota_reset_date": "2026-04-01",
  "quota_reset_date_utc": "2026-04-01T00:00:00.000Z",
  "quota_snapshots": {
    "chat": { "unlimited": true, ... },
    "completions": { "unlimited": true, ... },
    "premium_interactions": {
      "entitlement": 1500,
      "remaining": 861,
      "unlimited": false,
      "overage_permitted": false,
      "overage_count": 0,
      "percent_remaining": 57.4
    }
  }
}
```

The `premium_interactions` quota is the one that matters — it tracks premium
model requests (Claude Opus, GPT-5, etc.) against the monthly entitlement.

### Prerequisites

- **GitHub CLI (`gh`)** must be installed and authenticated (`gh auth login`)
- No special OAuth scopes required — the default `gh` token works

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   BillingManager                     │
│  (orchestrator: session counting, UI updates)        │
│                                                      │
│  • loadBillingData() — fetches on pooled thread      │
│  • recordTurnCompleted() — local counter increment   │
│  • refreshUsageDisplay() — updates labels            │
│  • showUsagePopup() — detail popup on click          │
└──────────────┬──────────────────────┬────────────────┘
               │                      │
   ┌───────────▼───────────┐  ┌──────▼──────────────┐
   │ CopilotBillingClient  │  │   UsageGraphPanel    │
   │  (data layer)         │  │  (Swing rendering)   │
   │                       │  │                      │
   │  • findGhCli()        │  │  • Sparkline graph   │
   │  • isGhAuthenticated()│  │  • Projection line   │
   │  • fetchBillingData() │  │  • Over-quota shading│
   │    → BillingSnapshot  │  │  • UsageGraphData    │
   └───────────────────────┘  │  • UsageGraphAction  │
                              └──────────────────────┘
```

### File Breakdown

| File | Responsibility | Lines |
|------|---------------|-------|
| `CopilotBillingClient.kt` | gh CLI discovery, API calls, JSON parsing → `BillingSnapshot` | ~140 |
| `UsageGraphPanel.kt` | Swing sparkline rendering, `UsageGraphData`, `UsageGraphAction` | ~210 |
| `BillingManager.kt` | Session counting, UI label management, popup, animation | ~300 |

## How It Works

### Initial Load

1. `AgenticCopilotToolWindowContent` creates a `BillingManager` at startup
2. Calls `billing.loadBillingData()` which runs on a pooled thread
3. `CopilotBillingClient.fetchBillingData()` executes `gh api /copilot_internal/user`
4. Response is parsed into a `BillingSnapshot` (entitlement, remaining, unlimited, etc.)
5. `BillingManager` stores the snapshot and updates UI labels + graph on EDT

### Real-Time Session Tracking

Between API polls, the plugin tracks usage locally:

1. Each agent turn completion calls `billing.recordTurnCompleted(multiplier)`
2. The multiplier (e.g., "1x", "3x", "0.33x") comes from the model metadata
3. `estimatedUsed = lastApiUsed + localSessionPremiumRequests`
4. Labels update immediately — no API call needed
5. Usage change triggers a brief green→normal color pulse animation

### Display Modes

Click the usage label to toggle between:

- **Monthly**: `639 / 1500` — total used vs. entitlement
- **Session**: `5 session (≈15 premium)` — current session's local count

### Overage Tracking

- When `remaining < 0`: shows estimated overage cost at $0.04/request
- When projected usage > entitlement: graph tooltip shows projected overage
- Over-quota graph segments render in red

### Usage Graph

The toolbar shows a mini sparkline (120×28px) with:

- **Green triangle**: cumulative usage from cycle start to today
- **Red triangle** (if over quota): usage above the entitlement line
- **Dashed gray line**: linear projection to end of billing cycle
- **Dashed horizontal line**: entitlement threshold
- **Dot**: current day position

Click the graph for a larger popup with detailed statistics.

## Limitations

- The `copilot_internal/user` API is **undocumented** and may change without notice
- Data is fetched once at startup — it does not auto-refresh during a session
- Local session counting is an estimate (the API count may drift if other editors are in use)
- Organization/enterprise billing requires different API endpoints (not supported)
