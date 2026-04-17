# Diff Review for Agent Edits

> **Tracking issue**: [#232](https://github.com/catatafishen/agentbridge/issues/232)
> **Status**: Implemented

When an AI agent edits files, it's easy to lose track of what actually changed. Diff Review
turns every agent-originated edit into a reviewable change — with persistent diff highlights
in the editor, a per-file Review panel, one-click Accept / Revert, and automatic gating of
destructive git operations until review is complete.

The feature is **off by default** and is opt-in through the **Diff Review** toggle in the
Review tool window's toolbar (or via `Settings → Tools → AgentBridge → Review agent edits`).

---

## Table of Contents

1. [At a glance](#at-a-glance)
2. [UI surfaces](#ui-surfaces)
3. [Session lifecycle](#session-lifecycle)
4. [Accept / Revert semantics](#accept--revert-semantics)
5. [Git gating](#git-gating)
6. [Settings](#settings)
7. [Architecture](#architecture)
8. [Edge cases](#edge-cases)

---

## At a glance

| Capability                   | Behaviour                                                                                                                                                                                   |
|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **What triggers a review**   | The first agent-originated edit after Diff Review is enabled.                                                                                                                               |
| **What's tracked**           | File *modifications*, *additions*, and *deletions* by agent tools.                                                                                                                          |
| **What's ignored**           | User typing, branch switches, reformats not triggered by the agent.                                                                                                                         |
| **Where changes show up**    | The **Review** tab in the AgentBridge tool window, a banner at the top of each edited editor, and persistent diff highlights (green / yellow / red) in the gutter.                          |
| **How you respond**          | **Accept** (keep change), **Revert** (restore pre-edit content + optional nudge), or navigate next / previous. Bulk *Accept All* / *Reject All* from the toolbar.                           |
| **When it blocks the agent** | Destructive git operations (commit, merge, rebase, reset --hard, pull, stash pop/apply, branch switch/create, revert, cherry-pick) block until all items are resolved (or 10 minutes pass). |
| **When it ends**             | You explicitly end the session, disable Diff Review, or the worktree changes underneath it (e.g. external branch switch).                                                                   |

---

## UI surfaces

### 1. Review tab (side panel)

The **Review** tab in the AgentBridge tool window is the primary surface.
It lists every file with pending changes as a row with:

- A **status icon** (Added, Modified, or Deleted).
- The file name, with the full project-relative path as a tooltip.
- Per-row buttons: **Open**, **Accept**, **Revert** — always visible, no hover reveal.
- Clicking a row **selects** it and opens the file in an editor; opening a tracked file
  another way (project view, Go To File, etc.) reverse-selects its row.

The toolbar at the bottom of the panel exposes:

- **Diff Review toggle** — the on/off switch for the whole feature. Turning it off while
  a session is active prompts for confirmation and discards the current review.
- **Accept All** / **Reject All** — bulk actions. *Reject All* asks for a single reason
  that is forwarded to the agent as a nudge.
- **Stop/Play toggle** — ends or restarts the review session without disabling the feature.

When the panel is empty, it says either *"No agent edits to review"* (Diff Review on,
nothing pending) or *"Diff Review is off."* (feature disabled).

### 2. Editor notification banner

Every file with a pending review gets a sticky banner at the top of its editor
(`AgentEditNotificationProvider`). The banner reads, for example:

```
Review: File 3/7 · 5 changes
  [Show diff]  [Accept]  [Previous]  [Next]  [Revert…]
```

- **Show diff** opens IntelliJ's diff viewer with the captured before-content on the left
  and the current document on the right.
- **Accept** removes this file from tracking and clears its highlights.
- **Previous** / **Next** navigate across all changes in all tracked files
  (`ChangeNavigator`), jumping between files when you reach the end of one.
- **Revert…** opens a dialog that asks for an optional reason. If supplied, the reason is
  sent to the agent as a nudge so the next turn can try a different approach.

### 3. Persistent diff highlights

`AgentEditHighlighter` computes line-level ranges between the before-snapshot and the
current document and attaches `RangeHighlighter`s at `HighlighterLayer.SELECTION − 1`:

| Change type                                  | Colour                  |
|----------------------------------------------|-------------------------|
| **Added** lines                              | Green background        |
| **Modified** lines                           | Amber/yellow background |
| **Deleted** lines (marker on following line) | Red background          |

User selection still paints on top of the highlights. Highlights update automatically when
the document changes and are cleared when the file is accepted, reverted, or the session
ends.

---

## Session lifecycle

### Automatic start

There is **no explicit "start" button**. The first agent tool call that writes or creates a
file (e.g., `write_file`, `edit_text`, `replace_symbol_body`, `create_file`) calls
`AgentEditSession.ensureStarted()`. If `Review agent edits` is enabled in settings, the
session transitions to *active*; otherwise the call is a no-op.

When the session becomes active, it installs:

- A project-wide `DocumentListener` that snapshots the before-content of any file the agent
  is about to modify (filtered using a `ThreadLocal` marker — see *What counts as an agent
  edit?* below).
- A `BulkFileListener` on `VFS_CHANGES` to detect creations, deletions, and renames driven
  by agent tools.

### What counts as an agent edit?

Only changes made from inside an agent tool call are tracked. Tools flip a `ThreadLocal`
flag (`AgentEditSession.markAgentEditStart()` / `markAgentEditEnd()`) around the
`WriteCommandAction`. The document listener only captures snapshots when that flag is set,
so the following are **not** tracked:

- User typing.
- IDE-initiated reformats, optimise-imports, or refactorings you invoke manually.
- Branch switches, `git pull`, or any other VCS-level content changes.
- Edits made from a terminal running outside a tool call.

### Active state

While active:

- Each modified file has its before-content stored in a `ConcurrentHashMap<String, String>`
  (path → content). Only the **first** capture per path is kept (`putIfAbsent`) — all later
  edits accumulate against the original baseline.
- Files larger than **5 MB** are skipped to avoid memory bloat. They're still edited, but
  they won't appear in the Review panel.
- Files outside the project scope are skipped (determined by `ProjectFileIndex`).
- New files are recorded in a separate `newFiles` set; deletions go into a
  `deletedFiles` map keyed by path → content (so revert can restore them).

### End of session

The session ends when any of these happen:

1. **Accept All** or **Reject All** (via panel or bulk action) resolves every tracked item.
2. The user toggles **Diff Review** off (with a confirm dialog if there are pending items).
3. The user clicks the Stop toggle in the Review toolbar.
4. The working tree changes out from under us — a branch switch, `git reset --hard`, or
   similar — detected via `invalidateOnWorktreeChange()`. Existing snapshots would
   otherwise be invalid.
5. The project is closed (session is a project service registered for `Disposer`).

When a session ends, all highlights, banners, and tracked state are cleared.

---

## Accept / Revert semantics

| Status                          | **Accept**                                        | **Revert**                                                                                                                   |
|---------------------------------|---------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| **MODIFIED** (snapshot exists)  | Drop the snapshot, keep the current file content. | Run `document.setText(snapshot)` inside a `WriteCommandAction` named *Reject Agent Edit* (undoable), save, clear highlights. |
| **ADDED** (in `newFiles`)       | Remove from `newFiles`, keep the file.            | Delete the file via VFS inside *Delete Agent-Created File*. Parent directory is not touched.                                 |
| **DELETED** (in `deletedFiles`) | Keep the deletion.                                | Recreate the file at its original path with the captured content.                                                            |

Each accept/reject clears the row, updates the editor banner for that file (if open), and
fires `ReviewSessionTopic.reviewStateChanged` so the panel refreshes.

**Reject reasons** are optional. If you supply one, it's forwarded to the agent as a
nudge prefixed with `[User rejected agent edits]: …\nPlease try a different approach.`,
giving the next turn context to change direction.

---

## Git gating

Destructive git operations are **blocked** until the review is complete. This prevents
common foot-guns like the agent committing edits you haven't reviewed, or rebasing while
half the working tree is under review.

Each gated tool calls:

```java
String reviewError = AgentEditSession.getInstance(project)
        .awaitReviewCompletion("<operation description>");
if(reviewError !=null)return reviewError;
```

### Gated operations

| Tool              | Gated action                |
|-------------------|-----------------------------|
| `git_commit`      | Always.                     |
| `git_merge`       | Always.                     |
| `git_rebase`      | Always.                     |
| `git_pull`        | Always.                     |
| `git_reset`       | Only when `mode` is `hard`. |
| `git_stash`       | Only for `pop` and `apply`. |
| `git_branch`      | For `create` and `switch`.  |
| `git_cherry_pick` | Always.                     |
| `git_revert`      | Always.                     |

`git_stage`, `git_unstage`, `git_status`, `git_diff`, `git_log`, `git_show`, `git_blame`,
`git_push`, `git_fetch`, `git_tag`, and `git_remote` are **not** gated — they don't alter
tracked working-tree content.

### How the block behaves

1. On the first gated call with pending review, `awaitReviewCompletion`:
    - Fires a balloon notification + an OS-level notification naming the blocked operation.
    - Expands the Review panel so the user sees exactly what's pending.
2. The tool thread then waits on a `CompletableFuture` that completes when
   `hasChanges()` goes to false (via accept/reject) or the session ends.
3. The timeout is **10 minutes**. On timeout, the tool returns an actionable error:
   > Error: Timed out after 10 minutes waiting for the user to review N file(s) before
   > `<operation>`. Accept or revert the pending changes in the Review panel and retry.

### Why the wait yields locks

The wait releases two locks held by `PsiBridgeService.callTool`:

- The **global write-tool semaphore** (otherwise all other tool calls starve).
- The **per-tool sync lock** (otherwise a second call to the same tool from another
  mcp-http thread deadlocks — it would acquire the semaphore but block on the sync lock
  this thread still holds while waiting on the future).

Both are re-acquired in the original order (semaphore → sync lock) before the tool
finishes. `writeSemaphore.acquireUninterruptibly()` is intentional so the `callTool`
finally block doesn't over-release if the thread is interrupted during the wait.

---

## Settings

| Setting                                    | Default | Effect                                                                         |
|--------------------------------------------|---------|--------------------------------------------------------------------------------|
| `Tools → AgentBridge → Review agent edits` | **off** | Master switch. When off, no snapshots are captured and all gating is bypassed. |

The toggle is also exposed in the Review panel's toolbar for quick access. Disabling it
while a session is active shows a confirm dialog (*"You have N unreviewed file(s).
Disabling Diff Review will discard the review session."*) before discarding state.

---

## Architecture

Package: `com.github.catatafishen.agentbridge.psi.review` (non-UI) and
`com.github.catatafishen.agentbridge.ui.review` (UI).

| Class                                                         | Responsibility                                                                                                                                                                             |
|---------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AgentEditSession`                                            | Project service. Owns snapshots / newFiles / deletedFiles. Exposes `ensureStarted`, `captureBeforeContent`, `acceptFile`, `rejectFile`, `acceptAll`, `rejectAll`, `awaitReviewCompletion`. |
| `AgentEditHighlighter`                                        | Project service. Attaches/updates range highlighters on any open editor for tracked files.                                                                                                 |
| `AgentEditNotificationProvider`                               | `EditorNotificationProvider` that injects the per-editor banner.                                                                                                                           |
| `ReviewItem`                                                  | Immutable record of `(path, relativePath, status, beforeContent)`.                                                                                                                         |
| `ReviewSessionTopic`                                          | Message-bus topic fired when any review state changes. Subscribed by the Review panel and editor notifications.                                                                            |
| `ReviewChangesPanel`                                          | Swing panel that renders the table, buttons, and toolbar. Lives in the side-panel Review tab.                                                                                              |
| `ReviewPanelController`                                       | Project service that brokers "expand the Review panel" requests from non-UI code (the UI component registers its expand callback once built).                                              |
| `RevertReasonDialog`                                          | Modal dialog that collects an optional reason before reverting a file.                                                                                                                     |
| `NextAgentEditChangeAction` / `PreviousAgentEditChangeAction` | Editor actions for cross-file change navigation.                                                                                                                                           |
| `RevertAgentEditsAction`                                      | Editor action that reverts the current file.                                                                                                                                               |
| `ChangeNavigator`                                             | Pure helper that computes the ordered next/previous change across files.                                                                                                                   |
| `ChangeRange`                                                 | Record of `(afterLine, afterLineEnd, type, beforeLine, beforeCount)`.                                                                                                                      |

File-edit tool hooks:

- `FileTool.writeFile` (all variants), `FileTool.createFile`, `FileTool.deleteFile` call
  `AgentEditSession.ensureStarted()` and `captureBeforeContent` / `registerNewFile` /
  `registerDeletedFile`.
- These hooks run *inside* `markAgentEditStart/End` so the document listener knows the
  edit is agent-originated.

Unit coverage:

- `ReviewPendingMessageTest` — verifies the timeout error wording.
- `ChangeNavigatorTest` — cross-file navigation.
- `AgentEditSessionRangesTest` — `computeRanges(String, String)` pure helper.

---

## Edge cases

- **Large files (> 5 MB)** — skipped entirely. Edits still happen, but the file won't
  appear in the Review panel. This is a deliberate memory-bloat guard.
- **Binary files** — treated like any other file via VFS; diff is computed on the raw
  text. The diff engine may bail with `FilesTooBigForDiffException`, in which case the
  file appears in the panel but `computeRanges` returns empty (no line highlights).
- **Same file edited multiple times** — only the first snapshot is kept. The diff you see
  is always "original → current", not "previous edit → current".
- **File modified outside agent control** — the document listener only snapshots when the
  agent-edit ThreadLocal is set, so IDE reformats, your own edits, and branch switches do
  **not** create review items. If you edit a file that the agent has already modified,
  your edits become part of the "current" side of the diff. Reverting in that case will
  also revert your manual edits — this is intentional: you're restoring to the
  pre-session baseline.
- **Renames** — tracked via `VFilePropertyChangeEvent.PROP_NAME` with an
  `OLD_PATH_KEY` marker; snapshots are re-keyed to the new path.
- **Worktree-changing git operations** — any `git_branch switch`, `git_reset --hard`,
  `git_rebase`, `git_merge`, `git_pull`, `git_stash pop/apply`, `git_revert`, or
  `git_cherry_pick` that is *executed* (after review gating has been resolved) calls
  `invalidateOnWorktreeChange` afterward. Snapshots taken against the old working-tree
  content would be stale, so the session is ended cleanly rather than allowing half-valid
  reverts.
- **New file then deleted in the same session** — appears once in the panel as
  DELETED (since we reconcile newFiles and deletedFiles when building `getReviewItems`).
  Revert re-creates it with the originally captured content if any; otherwise it stays
  deleted.
- **Deleted file with no prior snapshot** — content captured at deletion time is used as
  the "original" for restore. This covers files that existed before the session but were
  never modified before deletion.
- **Session ending with unresolved items** — disabling Diff Review or clicking Stop
  discards all tracked state after confirmation. The files on disk are not touched — only
  the review overlays disappear.
- **Second gated tool call during review** — multiple gated operations may be waiting on
  the same future. All are released together when `hasChanges()` becomes false. If new
  edits arrive *while* the wait is active (agent keeps working), a fresh future is created
  and the wait restarts, giving the user another 10 minutes to review the fresh batch.
- **Closing the project mid-review** — the session is a `Disposable` registered with the
  project; pending futures complete exceptionally as the project disposes.
