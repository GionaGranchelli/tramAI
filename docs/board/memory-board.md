# Chat Memory Board (SPEC-020)

Board for the chat memory feature implementation.

- Board owner: maintainer
- Last updated: 2026-05-13
- Related spec: [SPEC-020 Chat Memory](../specs/spec-020-chat-memory.md)

## Phase Dependency Graph

```text
v1.0 (Foundation)
    └── ChatMemory SPI + MessageWindowChatMemory + @ConversationId
v1.1 (Persistence + Tool Memory)
    └── depends on v1.0
v1.2 (RAG Episodic Memory)
    └── depends on v1.0 and tramai-rag
v2.0 (Advanced)
    └── depends on v1.0, v1.1, tramai-structured
```

## Status Legend

⬜ TODO | 🔄 IN PROGRESS | ✅ DONE | ❌ BLOCKED

---

## v1.0 — Foundation

Estimated effort: 1-2 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-079 | Define `ChatMemory` SPI in `tramai-core` — get/add/clear methods | §ChatMemory SPI | — | 0.25d | ⬜ |
| TASK-080 | Define `@ConversationId` annotation and `ConversationIdProvider` in `tramai-core` | §Conversation ID | — | 0.25d | ⬜ |
| TASK-081 | Implement `MessageWindowChatMemory` in `tramai-memory` with concurrent deque, message eviction, conversation eviction, system message dedup | §MessageWindowChatMemory | TASK-079 | 0.5d | ⬜ |
| TASK-082 | Implement `MemoryInterceptor` — interceptRequest (prepend history, dedup system) + interceptResponse (save user + assistant) | §MemoryInterceptor | TASK-079 | 0.25d | ⬜ |
| TASK-083 | Integrate into `TramaiInvocationHandler` — conversation ID resolution from @ConversationId, memory delegation in execute() | §Handler Changes | TASK-080, TASK-082 | 0.5d | ⬜ |
| TASK-084 | Add `Tramai.Builder.memory()` builder method | §Builder Integration | TASK-081, TASK-083 | 0.25d | ⬜ |
| TASK-085 | Add tests: window eviction, system message handling, conversation eviction, thread safety, @ConversationId resolution | §Acceptance Criteria | TASK-081, TASK-083 | 1.0d | ⬜ |
| TASK-086 | Register `tramai-memory` in settings.gradle.kts + bom + create build.gradle.kts | §Build Config | TASK-081 | 0.25d | ⬜ |

## v1.1 — Persistence + Tool Memory

Estimated effort: 1-2 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-087 | Add `ChatMemoryStore` SPI and persistent backends (JDBC, Redis) | §v1.1 | TASK-079 | 1.0d | ⬜ |
| TASK-088 | Expose intermediate tool call/result messages to MemoryInterceptor | §Known Limitations | TASK-082 | 0.5d | ⬜ |
| TASK-089 | Replace message-count window with token-count window | §v1.1 | TASK-081 | 0.5d | ⬜ |

## v1.2 — RAG Episodic Memory

Estimated effort: 1-2 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-090 | Embed conversation turns via tramai-embedding and store in tramai-vectorstore-spi | §v1.2 | TASK-079, tramai-rag | 1.0d | ⬜ |

## v2.0 — Advanced

Estimated effort: 2-3 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-091 | Structured fact extraction using tramai-structured | §v2.0 | TASK-079, tramai-structured | 1.0d | ⬜ |
| TASK-092 | LLM-based summarization with hallucination guardrails | §v2.0 | TASK-091 | 1.0d | ⬜ |

---

## Progress Tracking

| Phase | Status | Tasks |
|-------|--------|-------|
| v1.0 — Foundation | ⬜ Not started | 8 |
| v1.1 — Persistence + Tool Memory | ⬜ Not started | 3 |
| v1.2 — RAG Episodic Memory | ⬜ Not started | 1 |
| v2.0 — Advanced | ⬜ Not started | 2 |
| **Total** | | **14** |
