# Grounded Memory — Architecture & Implementation Plan

> Evolve memory from "what was said" to "verified claims grounded in the codebase."

---

## Problem

The semantic memory system ([SEMANTIC-MEMORY.md](SEMANTIC-MEMORY.md)) stores information
derived from conversations. This works well for preferences and decisions, but memories
**drift from the actual codebase over time**:

- A class gets renamed → memory still references the old name
- A dependency is removed → memory claims the project uses it
- A method is deleted → memory says "X calls Y" but Y no longer exists

The agent has no way to distinguish verified facts from stale assumptions.

---

## Solution: Memory as Claims with Evidence

Each memory becomes a **claim** backed by **evidence** — file paths, fully-qualified
symbol names (FQNs), or line references. The system validates evidence against the
live codebase using IntelliJ PSI, and tracks three verification states:

| State | Meaning | Retrieval behavior |
|---|---|---|
| `unverified` | No evidence, or evidence not yet checked | Normal ranking |
| `verified` | All evidence validated against codebase | Boosted ranking |
| `stale` | Evidence partially or fully invalidated | Deprioritized or excluded |

---

## Architecture

### Current Memory Flow

```
chat turn → ExchangeChunker → QualityFilter → MemoryClassifier
         → RoomDetector → EmbeddingService → MemoryStore (Lucene)
                                           → TripleExtractor → KnowledgeGraph (SQLite)
```

### Grounded Memory Flow (additions in bold)

```
chat turn → ExchangeChunker → QualityFilter → MemoryClassifier
         → RoomDetector → EmbeddingService → MemoryStore (Lucene)
                                           → TripleExtractor → KnowledgeGraph (SQLite)
                                           → **EvidenceExtractor** → attach evidence refs
                                                                      │
                                     ┌─────────────────────────────────┘
                                     ▼
              **SymbolValidator** (PSI)  ◄── triggered by:
                      │                      - file changes (BulkFileListener)
                      │                      - explicit memory_validate / memory_refresh
                      │                      - refactors (RefactoringEventListener)
                      ▼
              **MemoryValidator** → update verificationState
```

### New Components

| Component | File | Responsibility |
|---|---|---|
| `EvidenceExtractor` | `memory/validation/EvidenceExtractor.java` | Extract file:line refs and FQNs from text |
| `SymbolValidator` | `memory/validation/SymbolValidator.java` | Resolve FQNs and file paths via PSI ReadAction |
| `MemoryValidator` | `memory/validation/MemoryValidator.java` | Orchestrate validation, update states |
| `MemoryStalenessTrigger` | `memory/validation/MemoryStalenessTrigger.java` | BulkFileListener → mark affected memories stale |
| `MemoryRefactorListener` | `memory/validation/MemoryRefactorListener.java` | RefactoringEventListener → update KG triples |
| `MemoryValidateTool` | `psi/tools/memory/MemoryValidateTool.java` | MCP tool: validate memories against PSI |
| `MemoryRefreshTool` | `psi/tools/memory/MemoryRefreshTool.java` | MCP tool: re-scan and update memories for a topic |

---

## Schema Changes

### DrawerDocument (Lucene)

Three new indexed fields added to `MemoryStore`:

```java
// New fields in DrawerDocument record
String evidence,             // JSON array: ["com.example.UserService", "AuthController.kt:42"]
String verificationState,    // "unverified" | "verified" | "stale"
Instant lastVerifiedAt       // nullable — when last validated
```

Lucene field types:
- `evidence` → `StoredField` (not searchable, retrieved on read)
- `verificationState` → `StringField` (exact-match filterable)
- `lastVerifiedAt` → `StringField` (stored as ISO 8601)

### KgTriple (SQLite)

New column via migration:

```sql
ALTER TABLE triples ADD COLUMN evidence TEXT;
-- JSON array, same format as DrawerDocument evidence
-- e.g., '["com.example.UserService.authenticate", "AuthController.kt:42"]'
```

### Evidence Reference Formats

Evidence strings follow a simple convention:

| Format | Example | Validation method |
|---|---|---|
| FQN (class) | `com.example.UserService` | `JavaPsiFacade.findClass()` |
| FQN (method) | `com.example.UserService.authenticate` | Find class → find method |
| File path | `src/main/java/UserService.java` | `VirtualFileManager.findFileByUrl()` |
| File:line | `UserService.java:42` | Find file → check line count |
| Simple name | `UserService` | `search_symbols` equivalent |

The `SymbolValidator` tries each format in order, returning `valid`, `invalid`, or
`moved` (found under a different path/name).

---

## Evidence Extraction

### EvidenceExtractor

A stateless utility class that extracts code references from text using pattern
matching. Runs as a post-processing step after drawer storage (non-blocking).

**Patterns detected:**

1. **Backtick file references**: `` `FileName.kt:42` ``, `` `FileName.kt:42-80` ``
2. **FQN references**: `com.example.package.ClassName`, `com.example.Class.method()`
3. **Simple class names** (heuristic): capitalized words matching `[A-Z][a-zA-Z0-9]+`
   followed by `.kt`, `.java`, or similar extensions
4. **Tool result references**: file paths from `read_file`, `search_text`,
   `find_references` tool outputs embedded in conversation text

**Not detected** (too noisy):
- Bare method names without class context (too ambiguous)
- Variable names
- Package names without a class

### Tool Result Evidence (Phase 5)

The `ExchangeChunker` already sees tool call results as part of conversation entries.
In Phase 5, tool-specific patterns extract structured evidence:

| Tool | Evidence extracted |
|---|---|
| `read_file` | File path |
| `search_text` | File:line matches |
| `find_references` | File:line usage locations |
| `search_symbols` | FQN of found symbols |
| `go_to_declaration` | Target file:line |
| `get_file_outline` | Class/method FQNs |
| `git_diff` | Changed file paths |

---

## Validation

### SymbolValidator

Resolves evidence references against the live codebase using IntelliJ PSI. All
lookups run inside `ReadAction.compute()` on a background thread.

```java
public class SymbolValidator {

    public enum ValidationResult { VALID, INVALID, MOVED }

    /** Validate a single evidence reference. */
    public ValidationResult validate(Project project, String evidenceRef) {
        // 1. Try as FQN class: JavaPsiFacade.findClass(fqn, GlobalSearchScope.projectScope())
        // 2. Try as file path: VirtualFileManager → LocalFileSystem.findFileByPath()
        // 3. Try as file:line: find file → check document line count
        // 4. Try as simple name: PsiShortNamesCache.getClassesByName()
        // Falls through → INVALID
    }

    /** Validate all evidence for a drawer. */
    public Map<String, ValidationResult> validateAll(Project project, List<String> evidence) { ... }
}
```

**Threading**: Must run inside `ReadAction`. Callers are responsible for scheduling
on a background thread (never on EDT).

**Performance**: Each FQN lookup is O(1) via IntelliJ's index. File lookups use the
VFS cache. A drawer with 5 evidence refs takes <10ms.

### MemoryValidator

Orchestrates the full validation cycle:

1. Load drawer/triple with evidence
2. Call `SymbolValidator.validateAll()`
3. Determine new state:
   - All refs `VALID` → `verified`
   - Any ref `INVALID` → `stale`
   - Any ref `MOVED` → `stale` (could auto-update in future)
   - No evidence → stays `unverified`
4. Update state and `lastVerifiedAt` in store

---

## Staleness Detection

### File Change Trigger

`MemoryStalenessTrigger` implements `BulkFileListener` and listens to
`VirtualFileManager.VFS_CHANGES` on the project message bus.

**Flow:**
1. File change event arrives (create, modify, delete, rename)
2. Extract changed file paths
3. Debounce: collect changes for 5 seconds
4. Query `MemoryStore.findByEvidence(filePaths)` and `KnowledgeGraph.findByEvidence(filePaths)`
5. For each matched memory: if state was `verified`, downgrade to `stale`

**Index support**: `MemoryStore` needs a reverse lookup — given a file path, find all
drawers whose `evidence` JSON array contains that path. This can be a Lucene
`TermQuery` on a new multi-valued `evidence_file` field, or a post-filter on stored
`evidence` JSON.

**Cost**: Only runs when files change. Only checks memories with matching evidence.
No periodic background work.

### Refactor Listener

`MemoryRefactorListener` implements `RefactoringEventListener` and listens to
refactoring events on the project message bus.

**Supported refactors:**
- **Rename** (class, method, field) → update KG triples where subject/object matches old name
- **Move** (class to different package) → update evidence FQNs

**Flow:**
1. `refactoringDone(RefactoringEventData)` fires
2. Extract old name → new name mapping
3. `KnowledgeGraph.updateSubject(oldName, newName)` and `updateObject(oldName, newName)`
4. For drawers: update `evidence` JSON array entries, keep state as `verified`

This is the only mechanism that can **preserve** verified state through refactors.
Without it, every rename would mark memories as stale.

---

## New MCP Tools

### `memory_validate`

Validate specific memories or all memories for a topic against the live codebase.

```json
{
  "drawer_id": "optional — validate a specific drawer",
  "room": "optional — validate all drawers in a room",
  "wing": "optional — default: current project wing"
}
```

**Returns**: validation summary with per-drawer state transitions.

### `memory_refresh`

Re-scan a topic: re-extract evidence from existing drawers, re-validate, update states.
More thorough than `memory_validate` — also discovers new evidence refs in existing text.

```json
{
  "topic": "required — topic keyword or room name",
  "wing": "optional — default: current project wing"
}
```

**Returns**: refreshed drawer count, new evidence found, state transitions.

---

## Retrieval Changes

### Scoring Adjustments

The 4-layer memory stack applies a verification boost/penalty:

| Layer | Change |
|---|---|
| L1 (Essential Story) | Skip `stale` drawers entirely |
| L2 (On-Demand) | Boost `verified` by 1.2x, penalize `stale` by 0.5x |
| L3 (Deep Search) | Same as L2 |
| KG Query | Filter out triples whose evidence is all invalid |

### Search Tool Output

`memory_search` and `memory_recall` results include new fields:

```json
{
  "content": "UserService handles authentication",
  "score": 0.87,
  "verification_state": "verified",
  "evidence": ["com.example.UserService.authenticate", "AuthController.kt:42"],
  "last_verified_at": "2026-04-14T15:00:00Z"
}
```

Agents see at a glance whether a memory is trustworthy.

---

## Implementation Phases

Each phase is independently mergeable and useful. One branch per phase.

### Phase 1: Schema + Evidence Extraction

**Goal**: Add evidence fields and extract references from mined text.

| Action | File | Details |
|---|---|---|
| Add fields | `DrawerDocument.java` | `evidence`, `verificationState`, `lastVerifiedAt` |
| Index fields | `MemoryStore.java` | New Lucene fields, include in search results |
| Add column | `KgTriple.java`, `KnowledgeGraph.java` | `evidence` column with migration |
| Create | `EvidenceExtractor.java` | Regex-based ref extraction |
| Wire | `TurnMiner.java` | Call EvidenceExtractor after storing drawer |
| Wire | `TripleExtractor.java` | Attach evidence to extracted triples |
| Test | `EvidenceExtractorTest.java` | Pattern matching coverage |
| Test | `DrawerDocumentTest.java` | Serialization round-trip |
| Test | `MemoryStoreTest.java` | Index/search with new fields |

### Phase 2: Symbol Validation

**Goal**: Validate evidence against live codebase via PSI.

| Action | File | Details |
|---|---|---|
| Create | `SymbolValidator.java` | PSI ReadAction FQN/file resolution |
| Create | `MemoryValidator.java` | Orchestrate validation, update state |
| Create | `MemoryValidateTool.java` | MCP tool for explicit validation |
| Create | `MemoryRefreshTool.java` | MCP tool for topic refresh |
| Register | `MemoryToolFactory.java` | Add new tools |
| Add | `MemoryStore.java` | `updateVerificationState()` method |
| Add | `KnowledgeGraph.java` | `updateEvidence()` method |
| Test | `SymbolValidatorTest.java` | Mock PSI, test resolution |
| Test | `MemoryValidatorTest.java` | State transition logic |

### Phase 3: Staleness Detection

**Goal**: React to file changes and refactors automatically.

| Action | File | Details |
|---|---|---|
| Create | `MemoryStalenessTrigger.java` | `BulkFileListener` → mark stale |
| Create | `MemoryRefactorListener.java` | `RefactoringEventListener` → update KG |
| Wire | `MemoryService.java` | Initialize/dispose trigger and listener |
| Add | `MemoryStore.java` | `findByEvidence(filePath)` reverse lookup |
| Add | `KnowledgeGraph.java` | `findByEvidence(filePath)` query |
| Test | `MemoryStalenessTriggerTest.java` | File change → stale marking |
| Test | `MemoryRefactorListenerTest.java` | Rename → KG triple update |

### Phase 4: Retrieval Integration

**Goal**: Use verification state in ranking and display.

| Action | File | Details |
|---|---|---|
| Update | `MemorySearchTool.java` | Include state in results, filter option |
| Update | `MemoryRecallTool.java` | Prefer verified, deprioritize stale |
| Update | `MemoryWakeUpTool.java` | Skip stale in session-start context |
| Update | `MemoryKgQueryTool.java` | Include evidence in output |
| Update | `OnDemandLayer.java`, `DeepSearchLayer.java` | Scoring adjustments |

### Phase 5: Tool Result Evidence

**Goal**: Extract evidence from tool call results automatically.

| Action | File | Details |
|---|---|---|
| Enhance | `EvidenceExtractor.java` | Tool-specific patterns |
| Update | `ExchangeChunker.java` | Preserve tool result refs in metadata |
| Update | `TurnMiner.java` | Pass tool evidence through pipeline |
| Test | `EvidenceExtractorTest.java` | Tool output patterns |

---

## Design Decisions

### Why FQN strings, not PSI elements

PSI elements (`PsiClass`, `PsiMethod`) are transient in-memory objects tied to the
current IDE session. They cannot be serialized, stored in Lucene, or persisted to
SQLite. After an IDE restart, all PSI element references would be invalid.

Instead, we store **fully-qualified names** (FQNs) as strings and resolve them on
demand via `JavaPsiFacade.findClass()`. This gives:
- Persistence across IDE restarts
- Rename survival through `MemoryRefactorListener` (updates the stored FQN)
- Cross-session validity

### Why three states, not float confidence

A float confidence score (0.0–1.0) creates problems:
- What threshold distinguishes "trustworthy" from "stale"? 0.5? 0.7?
- How do you combine multiple evidence validations into one number?
- Every consumer needs to decide its own threshold

Three states (`unverified` / `verified` / `stale`) are:
- Unambiguous — no threshold debates
- Easy to filter — `WHERE state = 'verified'`
- Clear to agents — "this memory is stale" vs "confidence 0.43"

### Why lazy validation

The mining pipeline runs on every turn completion. Adding PSI resolution to every
mine would:
- Block the mining thread on ReadAction scheduling
- Slow down turn-to-turn performance
- Validate memories that may never be retrieved

Instead, validation triggers on:
- **File change** — memories whose evidence files changed
- **Explicit request** — agent calls `memory_validate` or `memory_refresh`
- **Retrieval** — optionally, validate on read (configurable)

### Why file-change trigger, not periodic

A periodic job (e.g., every 5 minutes) would:
- Waste CPU re-checking unchanged memories
- Miss changes between intervals
- Run during idle periods with no benefit

A `BulkFileListener` debounced to 5 seconds:
- Only runs when files actually change
- Catches all changes immediately
- Zero cost when the developer is reading or thinking

---

## Not Planned

These items from the original feature request were deliberately excluded:

| Item | Reason |
|---|---|
| Framework pattern detection | Project analysis ≠ memory validation. "Uses Spring Boot" is a one-off lookup. |
| Periodic background revalidation | Wasteful. File-change-triggered is cheaper and more timely. |
| PSI element serialization | PSI elements are transient. FQN strings are the right abstraction. |
| Float confidence scores | Three-state enum is simpler and sufficient. |
| "Challenge Before Use" agent flow | Prompt engineering, not a system feature. |
| Separate observed/inferred stores | `verificationState` achieves the same with less complexity. |

---

## Risks

### 1. Evidence Extraction Quality

Regex-based extraction will miss some references and produce false positives.

**Mitigation**: Start conservative (high-precision patterns only). False negatives
just leave memories as `unverified` — harmless. False positives waste validation
cycles but don't corrupt data. Improve patterns iteratively based on real usage.

### 2. PSI Availability

`ReadAction` may be blocked by long-running write actions (refactors, reformat).

**Mitigation**: All validation runs on background threads with `ReadAction.compute()`.
If blocked, the debounce timer naturally retries. Validation is never on the critical
path — memories remain usable in their current state while validation is pending.

### 3. Lucene Schema Migration

Adding fields to existing Lucene documents requires reindexing.

**Mitigation**: New fields are optional (nullable). Existing documents without the
new fields are treated as `verificationState = "unverified"` with empty evidence.
No migration needed — new fields are written on next update.

### 4. KG Column Migration

Adding `evidence` column to SQLite `triples` table.

**Mitigation**: `ALTER TABLE ADD COLUMN` with `DEFAULT NULL` — non-breaking,
instant, no data migration needed. Same pattern used for `source_closet` column.
