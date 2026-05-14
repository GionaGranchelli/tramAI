# Memory v1.1 + Distributed Execution Remaining Board

Board for the two concurrent workstreams.

- Last updated: 2026-05-14
- Related specs: [SPEC-021](../specs/spec-021-chat-memory-v11.md), [SPEC-016a](../specs/spec-016a-distributed-remaining.md)

---

## Status Legend

⬜ TODO | 🔄 IN PROGRESS | ✅ DONE | ❌ BLOCKED

---

## Workstream A: Chat Memory v1.1 (SPEC-021)

| ID | Task | Spec &sect; | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-093 | Tool-call memory: persist intermediate tool loop messages | &sect;3, &sect;4.1 | SPEC-020 engine | 0.5d | ✅ |
| TASK-094 | Token-count window: TokenAwareChatMemory + Tokenizer SPI | &sect;3, &sect;4.2 | ChatMemory SPI | 0.75d | ✅ |
| TASK-095 | ChatMemoryStore SPI + PersistentChatMemory wrapper | &sect;3, &sect;4.3 | ChatMemory SPI | 0.5d | ✅ |
| TASK-096 | JdbcChatMemoryStore | &sect;4.4 | TASK-095 | 0.5d | ✅ |
| TASK-097 | RedisChatMemoryStore | &sect;4.5 | TASK-095 | 0.5d | ✅ |
| TASK-098 | Tests: token eviction, persistence round-trip, tool-call integration, regressions | &sect;6 | TASK-093-097 | 1.0d | ✅ |

## Workstream B: Distributed Execution Remaining (SPEC-016a)

| ID | Task | Spec &sect; | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-099 | SIGTERM shutdown hook + shutdown observer events | &sect;3 TASK-037F | TASK-037 | 0.25d | ⬜ |
| TASK-100 | PartitionAssignmentStrategy interface + ModHashPartitionStrategy | &sect;3 TASK-037G | TASK-037 | 0.25d | ⬜ |
| TASK-101 | LoggingTramaiWorkerObserver + missing events + OpenTelemetryTramaiWorkerObserver | &sect;3 TASK-037H | TASK-037 | 0.5d | ⬜ |
| TASK-102 | Tests: shutdown hook, partitioning, OTel bridge, all events | &sect;5 | TASK-099-101 | 0.5d | ⬜ |

---

## Progress Tracking

| Workstream | Status | Tasks |
|------------|--------|-------|
| A: Chat Memory v1.1 | ✅ 6/6 done | 6 |
| B: Distributed Execution Remaining | ⬜ Not started | 4 |
| **Total** | | **10** |
