# Message Storage Format

Internal reference for how chat messages are stored, serialized, and restored.

---

## Entry Types (`EntryData` sealed class)

The in-memory model uses `EntryData` (defined in `ChatDataModel.kt`) with 8 subtypes:

### `Prompt` — user message
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `text` | `String` | *(required)* | No | ✅ | ✅ |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (part `ts`) |
| `contextFiles` | `List<Triple<name,path,line>>?` | `null` | No | ✅ (`ctxFiles`) | ✅ (`file` parts) |
| `id` | `String` | `""` | No | ✅ (`id`) | ❌ |

### `Text` — assistant text response
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `raw` | `StringBuilder` | `StringBuilder()` | Content appended during streaming | ✅ (`raw`) | ✅ (`text`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (part `ts`) |
| `agent` | `String` | `""` | No | ✅ (`agent`) | ✅ (message `agent`) |

### `Thinking` — reasoning/thinking block
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `raw` | `StringBuilder` | `StringBuilder()` | Content appended during streaming | ✅ (`raw`) | ✅ (`text`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (part `ts`) |
| `agent` | `String` | `""` | No | ✅ (`agent`) | ✅ (message `agent`) |

### `ToolCall` — tool invocation and result
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `title` | `String` | *(required)* | No | ✅ (`title`) | ✅ (`toolName`) |
| `arguments` | `String?` | `null` | No | ✅ (`args`) | ✅ (`args`) |
| `kind` | `String` | `"other"` | Yes | ✅ (`kind`) | ✅ (`kind`) |
| `result` | `String?` | `null` | Yes | ✅ (`result`) | ✅ (`result`) |
| `status` | `String?` | `null` | Yes | ✅ (`status`) | ✅ (`status`) |
| `description` | `String?` | `null` | Yes | ✅ (`description`) | ✅ (`description`) |
| `filePath` | `String?` | `null` | Yes | ✅ (`filePath`) | ✅ (`filePath`) |
| `autoDenied` | `Boolean` | `false` | Yes | ✅ (`autoDenied`) | ✅ (via `denialReason`) |
| `denialReason` | `String?` | `null` | Yes | ✅ (`denialReason`) | ✅ (`denialReason`) |
| `mcpHandled` | `Boolean` | `false` | Yes | ✅ (`mcpHandled`) | ✅ (`mcpHandled`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (part `ts`) |
| `agent` | `String` | `""` | No | ✅ (`agent`) | ✅ (message `agent`) |

### `SubAgent` — sub-agent invocation
| Field | Type | Default | Mutable | Persisted V1 | Persisted V2 |
|-------|------|---------|---------|:------------:|:------------:|
| `agentType` | `String` | *(required)* | No | ✅ | ✅ |
| `description` | `String` | *(required)* | No | ✅ | ✅ |
| `prompt` | `String?` | `null` | No | ✅ | ✅ |
| `result` | `String?` | `null` | Yes | ✅ | ✅ |
| `status` | `String?` | `null` | Yes | ✅ | ✅ |
| `colorIndex` | `Int` | `0` | Yes | ✅ | ✅ |
| `callId` | `String?` | `null` | No | ✅ (`callId`) | ✅ (`callId`) |
| `autoDenied` | `Boolean` | `false` | Yes | ✅ | ✅ (`autoDenied`) |
| `denialReason` | `String?` | `null` | Yes | ✅ | ✅ (`denialReason`) |
| `timestamp` | `String` | `""` | No | ✅ (`ts`) | ✅ (part `ts`) |
| `agent` | `String` | `""` | No | ✅ (`agent`) | ✅ (message `agent`) |

### `ContextFiles` — attached file references (transient)
| Field | Type | Persisted V1 | Persisted V2 |
|-------|------|:------------:|:------------:|
| `files` | `List<Pair<name, path>>` | ✅ (`context`) | ✅ (`file` parts on user message) |

### `Status` — status indicator (transient)
| Field | Type | Persisted V1 | Persisted V2 |
|-------|------|:------------:|:------------:|
| `icon` | `String` | ✅ | ✅ |
| `message` | `String` | ✅ | ✅ |

### `SessionSeparator` — session boundary marker
| Field | Type | Persisted V1 | Persisted V2 |
|-------|------|:------------:|:------------:|
| `timestamp` | `String` | ✅ | ✅ (message `createdAt`) |
| `agent` | `String` | ✅ | ✅ (message `agent`) |

---

## Persistence Formats

### V1 — JSON Array (`ConversationSerializer`)

**File:** `<projectBase>/.agent-work/conversation.json`

A flat JSON array where each element is one `EntryData`. Type discriminator: `"type"` field.

```json
[
  {"type":"prompt","text":"Hello","ts":"2026-04-01T10:00:00Z","id":"p1"},
  {"type":"text","raw":"Response","ts":"2026-04-01T10:00:01Z","agent":"copilot"},
  {"type":"tool","title":"read_file","args":"{}","kind":"read","result":"content",
   "status":"completed","ts":"2026-04-01T10:00:02Z","agent":"copilot"},
  {"type":"subagent","agentType":"explore","description":"Find code",
   "prompt":"search","result":"found","status":"completed","colorIndex":0,
   "callId":"call-1","ts":"2026-04-01T10:00:03Z","agent":"copilot"},
  {"type":"separator","timestamp":"2026-04-01T11:00:00Z","agent":"copilot"}
]
```

**Type values:** `prompt`, `text`, `thinking`, `tool`, `subagent`, `context`, `status`, `separator`

### V2 — JSONL (`SessionStoreV2` + `EntryDataConverter`)

**Directory:** `<projectBase>/.agent-work/sessions/`
- `sessions-index.json` — array of session metadata
- `<uuid>.jsonl` — one `SessionMessage` per line

Each `SessionMessage` groups consecutive same-agent entries:

```json
{"id":"m1","role":"user","parts":[{"type":"text","text":"Hello","ts":"2026-04-01T10:00:00Z"}],"createdAt":1743505200000,"agent":null,"model":null}
{"id":"m2","role":"assistant","parts":[{"type":"reasoning","text":"thinking...","ts":"2026-04-01T10:00:01Z"},{"type":"text","text":"Response","ts":"2026-04-01T10:00:02Z"}],"createdAt":1743505201000,"agent":"copilot","model":null}
```

**Message roles:** `user`, `assistant`, `separator`

**Part types:**
| Part type | Maps to | Key fields |
|-----------|---------|------------|
| `text` | `Prompt` (if user) / `Text` (if assistant) | `text`, `ts` |
| `reasoning` | `Thinking` | `text`, `ts` |
| `tool-invocation` | `ToolCall` | `toolInvocation.{toolName,args,result,kind,status,description,filePath,denialReason,mcpHandled}`, `ts` |
| `subagent` | `SubAgent` | `agentType`, `description`, `prompt`, `result`, `status`, `colorIndex`, `callId`, `autoDenied`, `denialReason`, `ts` |
| `status` | `Status` | `icon`, `message` |
| `file` | `ContextFiles` | `filename`, `path` |

**Per-entry timestamps:** Each part carries its own `"ts"` (ISO 8601) preserving the original entry timestamp. The message-level `createdAt` (epoch millis) is a fallback for parts written before this enrichment.

---

## JS Event Types (SSE / Event Log)

Events pushed to JCEF and SSE clients via `ChatWebServer.pushJsEvent()`:

| Event | Description |
|-------|-------------|
| `addUserMessage(text, ts, bubbleHtml, turnId)` | New user message |
| `showWorkingIndicator()` | Show typing indicator |
| `appendAgentText(turnId, agentId, text, ts)` | Streaming text chunk |
| `finalizeAgentText(turnId, agentId, html)` | Finalize streamed text → rendered HTML |
| `addThinkingText(turnId, agentId, text)` | Streaming thinking chunk |
| `collapseThinking(turnId, agentId, html)` | Finalize thinking → collapsed chip |
| `addToolCall(turnId, agentId, domId, label, params, kind, isExternal)` | New tool call |
| `updateToolCall(domId, status, ...)` | Tool call status update |
| `addSubAgent(turnId, agentId, domId, displayName, colorIndex, promptHtml, ts)` | New sub-agent |
| `updateSubAgent(domId, status, resultHtml)` | Sub-agent completion |
| `addSubAgentToolCall(saDomId, toolDomId, label, params, kind, isExternal)` | Sub-agent internal tool call |
| `addSessionSeparator(ts, agent)` | Session boundary |
| `finalizeTurn(turnId, statsJson)` | Turn complete |
| `restoreBatch(base64Html)` | Full conversation restore (pre-rendered HTML) |
| `clear()` | Clear conversation (also clears event log) |

### Event Log Compaction

The event log is capped at 600 events. Streaming events are compacted when their finalizer arrives:
- `appendAgentText` events → removed when `finalizeAgentText` arrives for the same turn+agent
- `addThinkingText` events → removed when `collapseThinking` arrives for the same turn+agent

---

## Sub-agent Internal Tool Calls

Sub-agent internal tool calls (tools invoked *by* a sub-agent during its execution) are:
1. Added to the `entries` list with proper timestamp and agent
2. Registered in `toolCallEntries` runtime map for DOM click-to-expand panels
3. Serialized to both V1 and V2 formats as regular `ToolCall` entries

On batch restore, sub-agent tool calls are reconstructed from the persisted entries and
displayed as tool chips within the sub-agent's section.
