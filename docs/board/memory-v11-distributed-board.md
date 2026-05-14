# Memory v1.1 + Distributed Execution Remaining Board

Board for the two concurrent workstreams.

- Last updated: 2026-05-14
- Related specs: [SPEC-021](../specs/spec-021-chat-memory-v11.md), [SPEC-016a](../specs/spec-016a-distributed-remaining.md)

---

## Status Legend

⬜ TODO | 🔄 IN PROGRESS | ✅ DONE | ❌ BLOCKED

---

## Workstream A: Chat Memory v1.1 (SPEC-021)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-093 | Tool-call memory: modify `executeWithTools()` to return tool loop messages, persist through MemoryInterceptor | §3, §4.1 | SPEC-020 engine | 0.5d | ⬜ |
| TASK-094 | `Tokenizer` interface + `TokenAwareChatMemory` implementation with token eviction | §3, §4.2 | ChatMemory SPI | 0.75d | ⬜ |
| TASK-095 | `ChatMemoryStore` SPI in tramai-core + `PersistentChatMemory` wrapper | §3, §4.3 | ChatMemory SPI | 0.5d | ⬜ |
| TASK-096 | `JdbcChatMemoryStore` — JDBC backend | §4.4 | TASK-095 | 0.5d | ⬜ |
| TASK-097 | `RedisChatMemoryStore` — Redis backend | §4.5 | TASK-095 | 0.5d | ⬜ |
| TASK-098 | Tests: tool-call integration, token eviction, persistence round-trip, regressions | §6 | TASK-093–097 | 1.0d | ⬜ |

### Dependency Graph

```text
TASK-093 ──┐
TASK-094 ──┤
TASK-095 ──┼── TASK-096 ── TASK-098
           │── TASK-097 ──┘
```

### Quick-Start Sequence

The quickest path to value: **TASK-093** (tool-call memory, fixes the immediate bug) → **TASK-094** (token-count window, prevents context overflow) → **TASK-095** (persistence SPI, foundation for backends) → **TASK-096** (JDBC, highest-demand backend) → **TASK-098** (tests).

---

## Workstream B: Distributed Execution Remaining (SPEC-016a)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-099 | SIGTERM shutdown hook + shutdown observer events | §3 TASK-037F | TASK-037 | 0.25d | ⬜ |
| TASK-100 | Extract `PartitionAssignmentStrategy` interface + `ModHashPartitionStrategy` | §3 TASK-037G | TASK-037 | 0.25d | ⬜ |
| TASK-101 | `LoggingTramaiWorkerObserver` + missing events + `OpenTelemetryTramaiWorkerObserver` | §3 TASK-037H | TASK-037 | 0.5d | ⬜ |
| TASK-102 | Tests: shutdown hook, partitioning strategy, OTel bridge, all events | §5 | TASK-099–101 | 0.5d | ⬜ |

### Dependency Graph

```text
TASK-099 ──┐
TASK-100 ──┼── TASK-102
TASK-101 ──┘
```

---

## Sub-Task Reconciliation (SPEC-016 Sub-tasks)

| Original Sub-task | New Status | Notes |
|-------------------|-----------|-------|
| TASK-037A Work Queue Store | ✅ CLOSED — WONTFIX | Existing `WorkflowLeaseStore` + `WorkflowCheckpointCatalog` covers the need. No `ClaimResult` sealed class needed — lease operations throw `WorkflowLeaseConflictException` which is functionally equivalent. |
| TASK-037B Worker Registry | ✅ CLOSED — DONE | Implemented. Deviation: uses delete (not DEREGISTERED status). Acceptable. |
| TASK-037C Lease Fencing | ✅ CLOSED — DONE | Implemented. Deviation: tokens are UUIDs not sequential ints. Acceptable. |
| TASK-037D Worker Poll Loop | ✅ CLOSED — DONE | Implemented. Deviation: no `maxClaimedPerPoll`. Worker claims all eligible workflows. Acceptable for v1. |
| TASK-037E Crash Recovery | ✅ CLOSED — DONE | Fully implemented and tested (8 tests). |
| TASK-037F Graceful Shutdown | 🔄 TASK-099 | Drain + release + unregister implemented. Missing: JVM shutdown hook, shutdown observer events. |
| TASK-037G Partitioning | 🔄 TASK-100 | SHA-256 logic exists inline. Missing: standalone interface + class. |
| TASK-037H Distributed Observability | 🔄 TASK-101 | Core observer interface exists. Missing: OTel bridge, log fallback, 4 event types. |

---

## Progress Tracking

| Workstream | Status | Tasks |
|------------|--------|-------|
| A: Chat Memory v1.1 | ⬜ Not started | 6 |
| B: Distributed Execution Remaining | ⬜ Not started | 4 |
| **Total** | | **10** |

---

## How to Use This Board

1. Start with Workstream A, TASK-093 (tool-call memory) — fixes the most impactful bug
2. Run TASK-093 through the role-rotation pipeline: Codex implement → Copilot review → Gemini review
3. TASK-094 and TASK-095 are independent — both can run in parallel after TASK-093
4. Workstream B can run independently at any time (no dependency on Workstream A)
