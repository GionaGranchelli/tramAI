# SPEC-021: Chat Memory v1.1 — Tool-Call Memory, Token-Count Window, Persistent Backends

- Status: proposed
- Owner: maintainer
- Last updated: 2026-05-14
- Related roadmap milestone: Phase 12 — Memory v1.1
- Related ADRs: ADR-008 (Provider SPI)
- Related docs: [SPEC-020 Chat Memory v1.0](../specs/spec-020-chat-memory.md), [Memory Board v1.0](../board/memory-board.md)

---

## 1. Executive Summary

Chat Memory v1.0 (SPEC-020) shipped the foundation: ChatMemory SPI, MessageWindowChatMemory with LRU eviction, MemoryInterceptor, @ConversationId handler integration, and 78 tests. Three features were deferred to v1.1:

1. **Tool-call memory** — Intermediate tool call/result messages from the engine's internal tool loop are discarded today. On subsequent turns, the model has no memory of prior tool results, making tool-using agents unreliable beyond a single turn.
2. **Token-count window** — `maxMessages` is a message count, not a token budget. Verbose messages can overflow the model's context window even when `maxMessages` is low.
3. **Persistent backends** — `MessageWindowChatMemory` is in-memory only. Restart the JVM, lose all history.

These are independent features — each can be shipped in any order.

---

## 2. Security Considerations

- **Tool-call memory** — Tool results may contain sensitive data (customer PII, API keys, database records). The memory store is in-process, same as v1.0. No new attack surface beyond what v1.0 already accepts via `ChatMemory`.
- **Token-count window** — Tokenization is local (no network calls). Codec counts may differ slightly between the truncation logic and the LLM provider's tokenizer. This is documented as an approximation.
- **Persistent backends** — JDBC storage introduces database credentials. Use environment variables or vault, same pattern as existing `JdbcWorkflowLeaseStore`. Redis requires a connection string. Both follow existing credential conventions in tramai-orchestration.

---

## 3. Domain Models

### Tool-Call Memory

No new domain models. The `MemoryInterceptor.interceptResponse()` signature already accepts `ModelResponse` which carries `toolCalls: List<ToolCall>?`. The gap is in `TramaiInvocationHandler.executeWithTools()` — the engine's internal tool loop appends assistant+tool messages to the mutable message list but never exposes them to the memory layer.

The fix: modify `executeWithTools()` to return the full message list (including intermediate tool rounds), then save ALL messages (not just original user + final assistant) through the memory interceptor.

### Token-Count Window

```kotlin
class TokenAwareChatMemory(
    private val maxTokens: Int = 4096,
    private val maxConversations: Int = 1000,
    private val tokenizer: Tokenizer = DefaultTokenizer(),
) : ChatMemory {
    // Evicts oldest non-system messages until total tokens <= maxTokens
    // System messages preserved and counted separately or exempted
}
```

Where `Tokenizer` is:

```kotlin
fun interface Tokenizer {
    fun countTokens(content: String): Int
}
```

Default implementation: rough estimate (`content.length / 4`) or use `java.text.BreakIterator` for character-boundary counting. A `TokenAwareChatMemory` with a cheap approximate tokenizer is better than a message-count window for preventing context overflows.

### Persistent Backends

```kotlin
interface ChatMemoryStore {
    fun getMessages(conversationId: String): List<Message>
    fun appendMessages(conversationId: String, messages: List<Message>)
    fun deleteConversation(conversationId: String)
    fun listConversations(limit: Int, offset: Int): List<String>
}
```

`ChatMemoryStore` is the persistence SPI. `ChatMemory` remains the user-facing interface. `PersistentChatMemory` wraps a `ChatMemoryStore`:

```kotlin
class PersistentChatMemory(
    private val store: ChatMemoryStore,
    private val cache: ChatMemory? = null, // optional in-memory cache layer
) : ChatMemory {
    // Delegates get/add/clear to store, with optional LRU caching
}
```

---

## 4. Implementation Design

### Feature 1: Tool-Call Memory

**Problem:** `TramaiInvocationHandler.executeWithTools()` loops tool calls internally. The assistant+tool messages appended during the loop are invisible to the memory layer. Only the original request messages and the final `ModelResponse` reach the interceptor.

**Solution:** After `executeWithTools()` returns, the passed-in `MutableList<Message>` already contains all intermediate tool call/result messages appended in-place. No need to modify `ProviderCallResult`. In both `executeRaw` and `executeStructured`, persist using `effectiveMessages` (the history-injected, tool-loop-mutated message list) filtered to exclude history messages, rather than `originalMessages`.

```kotlin
// In executeRaw — use the already-mutated effectiveMessages
val result = executeWithTools(operation, effectiveMessages, tokenBudgetTracker)

// Persist all messages (user + assistant + tool rounds) except history
if (chatMemory != null && conversationId != null) {
    val nonHistoryMessages = effectiveMessages.drop(history.size)
    chatMemory.add(conversationId, nonHistoryMessages)
}
```

**Changes:**
1. No changes to `ProviderCallResult` — it remains a private data class
2. In `executeRaw()`: persist `effectiveMessages` (after `executeWithTools` has mutated it inline) instead of `originalMessages`
3. In `executeStructured()`: same pattern, persist `messages` (which includes tool rounds + final assistant response) on `StructuredOutputResult.Success`
4. `MemoryInterceptor.interceptResponse` gets a new overload accepting the full message list, or the engine bypasses the interceptor and calls `chatMemory.add()` directly for tool-call messages

**Edge case:** Structured output retry loop. Each retry adds assistant+user feedback messages to `messages`. After retry exhaustion, the messages contain both tool rounds and retry-feedback rounds. Only persist the successful attempt's messages.

### Feature 2: Token-Count Window

```kotlin
class TokenAwareChatMemory(
    private val maxTokens: Int = 4096,
    private val maxConversations: Int = 1000,
    private val tokenizer: Tokenizer = roughTokenizer(),
) : ChatMemory {

    private val conversations = ConcurrentHashMap<String, ConcurrentLinkedDeque<TokenCountMessage>>()
    // Stores messages with their token count for efficient eviction

    override fun get(conversationId: String): List<Message> { ... }
    override fun add(conversationId: String, message: Message) { ... }
    override fun add(conversationId: String, messages: List<Message>) { ... }
    override fun clear(conversationId: String) { ... }

    private fun evictTokens(deque: ConcurrentLinkedDeque<TokenCountMessage>) {
        // Count tokens of non-system messages using an accumulator
        var tokenTotal = 0
        for (entry in deque) {
            if (entry.message.role != MessageRole.SYSTEM) {
                tokenTotal += entry.tokenCount
            }
        }
        // Remove oldest non-system messages until total <= maxTokens
        val iterator = deque.iterator()
        while (iterator.hasNext() && tokenTotal > maxTokens) {
            val entry = iterator.next()
            if (entry.message.role != MessageRole.SYSTEM) {
                iterator.remove()
                tokenTotal -= entry.tokenCount
            }
        }
    }
}

private data class TokenCountMessage(
    val message: Message,
    val tokenCount: Int,
)
```

**Default tokenizer** — a rough approximation to avoid depending on a specific LLM's tokenizer library:

```kotlin
fun roughTokenizer(): Tokenizer = Tokenizer { content ->
    // ~4 chars per token for English text, with buffer
    (content.length / 3).coerceAtLeast(1)
}
```

Document that the count is approximate. Users can provide a more accurate tokenizer via the constructor if they have one available (e.g., `tiktoken` for OpenAI models).

### Feature 3: Persistent Backends

**Module structure:** `tramai-memory-store` (new dedicated module, following the same pattern as `tramai-vectorstore-chroma` and `tramai-vectorstore-pgvector`). The in-memory `tramai-memory` module stays dependency-free; persistence lives in its own module.

**SPI in tramai-core:**

```kotlin
package dev.tramai.core.memory

interface ChatMemoryStore {
    fun getMessages(conversationId: String): List<Message>
    fun appendMessages(conversationId: String, messages: List<Message>)
    fun deleteConversation(conversationId: String)
    fun listConversations(limit: Int, offset: Int): List<String>
}
```

**PersistentChatMemory in tramai-memory:**

```kotlin
class PersistentChatMemory(
    private val store: ChatMemoryStore,
    private val cache: MessageWindowChatMemory? = null,
) : ChatMemory {
    override fun get(conversationId: String): List<Message> {
        val cached = cache?.get(conversationId)
        if (cached != null && cached.isNotEmpty()) return cached
        return store.getMessages(conversationId)
    }

    override fun add(conversationId: String, messages: List<Message>) {
        store.appendMessages(conversationId, messages)
        cache?.add(conversationId, messages)
    }

    override fun add(conversationId: String, message: Message) {
        add(conversationId, listOf(message))
    }

    override fun clear(conversationId: String) {
        store.deleteConversation(conversationId)
        cache?.clear(conversationId)
    }
}
```

**JDBC Store** (tramai-memory module, optional via build config):
- Table: `chat_memory` (conversation_id TEXT, message_blob JSONB, created_at TIMESTAMP, ordinal INT)
- Query: `SELECT message_blob FROM chat_memory WHERE conversation_id = ? ORDER BY ordinal ASC`
- Insert: batch insert with ordinal tracking

**Redis Store** (tramai-memory module, optional via build config):
- Key: `chat:{conversationId}`
- Type: Redis List
- Push: `RPUSH chat:{convId} {serializedMessage}`
- Read: `LRANGE chat:{convId} 0 -1`

---

## 5. Implementation Phases

| Phase | Feature | Effort | Deps |
|-------|---------|--------|------|
| 1 | Tool-call memory | 0.5d | SPEC-020 (v1.0 handler) |
| 2 | Token-count window (TokenAwareChatMemory) | 0.75d | ChatMemory SPI |
| 3 | Persistent backends — ChatMemoryStore SPI + PersistentChatMemory | 0.5d | ChatMemory SPI |
| 4 | JDBC backend | 0.5d | Phase 3 |
| 5 | Redis backend | 0.5d | Phase 3 |
| 6 | Tests (all phases) | 1.0d | Phases 1-5 |

---

## 6. Files to Modify/Create

### Phase 1 — Tool-Call Memory
- `tramai-engine/.../TramaiEngine.kt` — In `executeRaw()` and `executeStructured()`: persist the already-mutated `effectiveMessages`/`messages` list (after `executeWithTools()` returns) instead of `originalMessages`. For structured output: take a message-list snapshot **before** each `executeWithTools()` call in the retry loop so only the successful attempt's tool rounds are persisted. Use the existing `chatMemory.add()` call pattern (no MemoryInterceptor changes needed — the engine already calls `chatMemory.add()` directly).

### Phase 2 — Token-Count Window
- `tramai-core/.../Tokenizer.kt` — NEW: fun interface
- `tramai-memory/.../TokenAwareChatMemory.kt` — NEW: class implementing ChatMemory
- `tramai-memory/.../MessageWindowChatMemory.kt` — no changes

### Phase 3 — Persistent Backends
- `tramai-core/.../memory/ChatMemoryStore.kt` — NEW: SPI interface
- `tramai-memory/.../PersistentChatMemory.kt` — NEW: ChatMemory wrapper (in tramai-memory as it only depends on the SPI)

### Phase 4 — JDBC
- `tramai-memory-store/.../JdbcChatMemoryStore.kt` — NEW: JDBC implementation in new module

### Phase 5 — Redis
- `tramai-memory-store/.../RedisChatMemoryStore.kt` — NEW: Redis implementation in new module (optional, using Jedis or Lettuce)

### Phase 6 — Tests
- `tramai-memory/.../TokenAwareChatMemoryTest.kt` — NEW
- `tramai-memory/.../PersistentChatMemoryTest.kt` — NEW
- `tramai-engine/.../TramaiEngineTest.kt` — add tool-call memory integration tests

---

## 7. Acceptance Criteria

- [ ] Tool-call memory persists intermediate tool call/result messages on successful turns
- [ ] Tool-call memory does NOT persist intermediate tool messages on structured-output retry exhaustion
- [ ] TokenAwareChatMemory evicts oldest non-system messages when total tokens exceed maxTokens
- [ ] TokenAwareChatMemory preserves system messages regardless of token count
- [ ] TokenAwareChatMemory defaults to a cheap approximate tokenizer (no external dependency)
- [ ] ChatMemoryStore interface compiles with get/append/delete/list methods
- [ ] PersistentChatMemory delegates to store and optionally caches reads via MessageWindowChatMemory
- [ ] JdbcChatMemoryStore round-trips messages through PostgreSQL
- [ ] RedisChatMemoryStore round-trips messages through Redis
- [ ] Existing v1.0 tests still pass with no regressions

---

## 8. Known Limitations

- Tool-call memory persists ALL intermediate tool rounds. For tools returning large payloads, this can grow quickly. Token-count window helps mitigate this.
- Tokenizer is approximate. Users of specific LLMs may want to inject a model-specific tokenizer (e.g., `tiktoken`). Documented as a configuration point.
- Redis store has no TTL-based expiry for conversations (deferred to v2).
- JDBC store does not support sharding or partitioning (deferred to v2).

---

## 9. Risk Register

| Risk | Mitigation |
|------|-----------|
| Tool-call memory causes context overflow on agents with many tool rounds | Token-count window eviction handles this. Document that tool-heavy agents should use lower maxTokens. |
| TokenAwareChatMemory tokenizer diverges from provider's actual tokenizer | Document as approximation. Provide constructor injection point for custom tokenizers. |
| JDBC schema migrations in production | Use Flyway migrations following existing tramai-server convention. |
| Redis connection failure causes memory loss | PersistentChatMemory can degrade gracefully (cache fallback) or fail fast based on configuration. |
