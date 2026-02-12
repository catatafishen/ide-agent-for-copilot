# JSON-RPC Protocol Specification

## Overview
Communication between the IntelliJ plugin and the Go sidecar uses JSON-RPC 2.0 over HTTP.

## Base URL
`http://localhost:<dynamic-port>/rpc`

## Request Format
```json
{
  "jsonrpc": "2.0",
  "id": "unique-request-id",
  "method": "methodName",
  "params": { ... }
}
```

## Response Format
```json
{
  "jsonrpc": "2.0",
  "id": "unique-request-id",
  "result": { ... }
}
```

## Error Response
```json
{
  "jsonrpc": "2.0",
  "id": "unique-request-id",
  "error": {
    "code": -32600,
    "message": "Invalid Request",
    "data": "Additional error details"
  }
}
```

## Methods

### session.create
Create a new Copilot agent session.

**Request:**
```json
{
  "method": "session.create",
  "params": {}
}
```

**Response:**
```json
{
  "result": {
    "sessionId": "uuid-v4-session-id",
    "createdAt": "2026-02-11T18:00:00Z"
  }
}
```

### session.close
Close an existing session.

**Request:**
```json
{
  "method": "session.close",
  "params": {
    "sessionId": "uuid-v4-session-id"
  }
}
```

**Response:**
```json
{
  "result": {
    "closed": true
  }
}
```

### session.send
Send a prompt to the agent within a session.

**Request:**
```json
{
  "method": "session.send",
  "params": {
    "sessionId": "uuid-v4-session-id",
    "prompt": "User prompt text",
    "context": [
      {
        "file": "src/Main.java",
        "startLine": 10,
        "endLine": 25,
        "content": "code snippet...",
        "symbol": "methodName"
      }
    ],
    "model": "gpt-5-mini",
    "permissions": {
      "git.commit": "ask",
      "git.push": "deny",
      "fs.write": "ask"
    }
  }
}
```

**Response:**
```json
{
  "result": {
    "messageId": "uuid-v4-message-id",
    "streamUrl": "/stream/{sessionId}"
  }
}
```

### session.stream
Server-Sent Events (SSE) endpoint for streaming agent events.

**GET** `/stream/{sessionId}`

**Event Types:**
- `plan.start`, `plan.step`, `plan.complete`
- `timeline.message`, `timeline.toolCall`
- `tool.approval` (requires user interaction)
- `error`

**Example Events:**
```
event: plan.step
data: {"stepId": "1", "description": "Analyze codebase", "status": "running"}

event: tool.approval
data: {"toolName": "git.commit", "args": {...}, "requiresApproval": true}

event: timeline.message
data: {"role": "assistant", "content": "I'll help you with that..."}
```

### models.list
List available Copilot models.

**Request:**
```json
{
  "method": "models.list",
  "params": {}
}
```

**Response:**
```json
{
  "result": {
    "models": [
      {
        "id": "gpt-5-mini",
        "name": "GPT-5 Mini",
        "capabilities": ["code", "chat"],
        "contextWindow": 128000
      },
      {
        "id": "gpt-5",
        "name": "GPT-5",
        "capabilities": ["code", "chat", "vision"],
        "contextWindow": 200000
      }
    ]
  }
}
```

## Tool Callbacks (Sidecar → Plugin)

The sidecar will make HTTP POST requests back to the plugin for tool execution:

**POST** `http://localhost:<plugin-port>/tool-callback`

**Request:**
```json
{
  "sessionId": "uuid",
  "toolName": "git.commit",
  "callId": "tool-call-uuid",
  "args": {
    "type": "feat",
    "scope": "api",
    "description": "add user endpoint",
    "body": "Detailed description...",
    "breaking": false
  }
}
```

**Response (if approved):**
```json
{
  "success": true,
  "result": {
    "commitSha": "abc123...",
    "message": "feat(api): add user endpoint"
  }
}
```

**Response (if denied):**
```json
{
  "success": false,
  "error": "User denied tool execution",
  "userMessage": "Operation cancelled by user"
}
```

## Error Codes

| Code   | Message              | Description                          |
|--------|----------------------|--------------------------------------|
| -32700 | Parse error          | Invalid JSON                         |
| -32600 | Invalid Request      | Missing required fields              |
| -32601 | Method not found     | Unknown RPC method                   |
| -32602 | Invalid params       | Parameter validation failed          |
| -32603 | Internal error       | Server-side error                    |
| -32000 | Session not found    | Invalid or expired session ID        |
| -32001 | Tool execution failed| Tool callback error                  |
| -32002 | SDK error            | Copilot SDK internal error           |
