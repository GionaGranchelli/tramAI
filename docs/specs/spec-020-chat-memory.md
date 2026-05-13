# SPEC-020: Chat Memory

- Status: proposed
- Owner: maintainer
- Last updated: 2026-05-13
- Related roadmap milestone: M13 — Memory
- Related ADRs: ADR-008 (Provider SPI)
- Related docs: [Roadmap Summary](../roadmap.md)

## Problem

TramAI's `@AiService` proxy and `@Operation` methods are fully stateless. Every call builds messages from scratch, sends them to the provider, and discards the result. Applications that need conversational state — chat UIs, multi-turn interactions, session-based agents — must manually manage message history, handle token budgets, and wire conversation IDs themselves.

LangChain4j ships 5+ ChatMemory implementations and Spring AI ships 6 persistence backends. TramAI has zero.

## Scope

1. **ChatMemory SPI** — A minimal interface in `tramai-core` for reading and writing conversation history.
2. **MessageWindowChatMemory** — In-memory implementation in a new `tramai-memory` module with configurable max message count, system message preservation, and conversation-ID partitioning.
3. **MemoryInterceptor** — An `OperationInterceptor` implementation that prepends history to messages before execution.
4. **Conversation ID resolution** — Via `@ConversationId` parameter annotation resolved at the `TramaiInvocationHandler` level (where method arguments and annotations are accessible).
5. **Conversation-level eviction** — Configurable `maxConversations` cap to prevent memory leaks.
6. **Builder integration** — `Tramai.Builder.memory(chatMemory)` for explicit setup (no magic auto-wiring).
7. **Roadmap and board** for v1 delivery and future v1.1 features.

## Non-Goals

- Token-counting window (deferred to v1.1 — message-count window is sufficient for v1)
- Persistent backends (deferred to v1.1 — in-memory only for v1)
- RAG-backed episodic memory (deferred — reuse `tramai-rag` when needed)
- Structured fact extraction (deferred — too fragile, duplicates `tramai-structured`)
- LLM-based summarization (deferred until hallucination guardrails are proven in production)
- `@Memory` annotation (over-engineered for a single integer config — use builder instead)
- Tool-calling memory (documented known limitation — intermediate tool call/result messages are not persisted in v1. The engine loops tool calls internally and only exposes the final response to the interceptor.)
- `ChatMemoryStore` SPI (YAGNI until v1.1 — in-memory only for now)

## Architecture

### Design Decision: Handler-Level Memory, Not Interceptor

Memory injection happens at the `TramaiInvocationHandler` level, not in the `OperationInterceptor`. Rationale:

- `OperationInterceptor.interceptRequest()` receives `List<Message>`, not `ModelRequest`. No `request.copy(messages = ...)` is possible.
- `OperationCallContext` has no method arguments, no annotation metadata, and no reflection access. The interceptor cannot resolve `@ConversationId` from the available data.
- `TramaiInvocationHandler` has access to `method: Method` (for annotation detection) and `args: List<Any>` (for argument extraction), making it the correct level for annotation-driven behavior.

The handler delegates to `MemoryInterceptor` internally after resolving the conversation ID.

### Integration Flow

```
User calls: chatService.chat(sessionId="abc", message="Hello")

1. TramaiInvocationHandler.invoke() called with method + args
2. Handler checks if a ChatMemory is configured on the engine
3. If yes:
   a. Resolves conversationId = "abc" from @ConversationId parameter
   b. Calls MemoryInterceptor.interceptRequest(convId, messages)
      - Loads history from ChatMemory
      - Prepends history to operation messages
   c. Engine executes the operation (may loop for tool calls internally)
   d. Calls MemoryInterceptor.interceptResponse(convId, requestMessages, response)
      - Appends user messages + assistant response to ChatMemory
4. Result returned to caller
```

## Functional Requirements

### ChatMemory SPI (tramai-core)

```kotlin
package dev.tramai.core.memory

/**
 * Stores and retrieves conversation history.
 * All implementations must be thread-safe.
 */
interface ChatMemory {
    fun get(conversationId: String): List<Message>
    fun add(conversationId: String, messages: List<Message>)
    fun add(conversationId: String, message: Message)
    fun clear(conversationId: String)
}
```

- Synchronous (no `suspend`) — memory operations are in-memory and instantaneous.
- Thread-safe contract: implementors must handle concurrent access.
- SPI lives in `tramai-core` so it's available to the engine without depending on `tramai-memory`.

### MessageWindowChatMemory (tramai-memory)

```kotlin
class MessageWindowChatMemory(
    private val maxMessages: Int = 20,
    private val maxConversations: Int = 1000,
) : ChatMemory {
    // Backed by ConcurrentHashMap<String, ConcurrentLinkedDeque<Message>>
    // Evicts oldest non-system messages when size exceeds maxMessages
    // Evicts oldest idle conversations when count exceeds maxConversations
    // System messages (role=SYSTEM) are always kept, one per conversation
}
```

- **Message eviction:** When `conversation.size() > maxMessages`, the oldest non-system messages are removed until `size <= maxMessages`. System messages are never evicted within a conversation.
- **Conversation eviction:** On `add()`, if the number of tracked conversations exceeds `maxConversations`, the least recently used conversation is removed in its entirety. This prevents unbounded memory growth.
- **Data structure:** `ConcurrentLinkedDeque` — O(1) appends and removals, suited to the append-at-end, evict-from-front access pattern.
- **Thread safety:** `ConcurrentHashMap` for conversation map. Per-conversation operations are synchronized on the deque instance.
- **System message dedup:** If `add()` receives a system message and one already exists in the window, the old one is replaced. This handles changing system prompts across calls.

### @ConversationId Annotation (tramai-core)

```kotlin
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConversationId
```

Marks a method parameter as the conversation identifier. Resolved at invocation time by `TramaiInvocationHandler`.

For methods without `@ConversationId`, a `ConversationIdProvider` SPI provides a default:

```kotlin
fun interface ConversationIdProvider {
    fun resolve(): String
}
```

Default: `UuidConversationIdProvider` — generates a new UUID per conversation (effectively single-turn).

### MemoryInterceptor (tramai-memory)

```kotlin
class MemoryInterceptor(
    private val chatMemory: ChatMemory,
) {
    fun interceptRequest(
        conversationId: String,
        messages: List<Message>,
    ): List<Message> {
        val history = chatMemory.get(conversationId)
        if (history.isEmpty()) return messages
        // Remove system message from current request if history already has one
        val currentSystem = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        val dedupedMessages = if (currentSystem != null && history.any { it.role == MessageRole.SYSTEM }) {
            messages.filter { it.role != MessageRole.SYSTEM }
        } else {
            messages
        }
        return history + dedupedMessages
    }

    fun interceptResponse(
        conversationId: String,
        requestMessages: List<Message>,
        response: ModelResponse,
    ) {
        val userMessages = requestMessages.filter { it.role == MessageRole.USER }
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = response.content,
            toolCalls = response.toolCalls,
        )
        chatMemory.add(conversationId, userMessages + assistantMessage)
    }
}
```

Note: `MemoryInterceptor` is NOT an `OperationInterceptor` implementation. It's a standalone class that the handler calls directly with the resolved conversation ID. This avoids the signature mismatch with `OperationInterceptor` and gives the handler full control over argument resolution, annotation detection, and error handling.

### Builder Integration (tramai-engine)

```kotlin
class Tramai {
    class Builder {
        private var chatMemory: ChatMemory? = null

        fun memory(chatMemory: ChatMemory): Builder {
            this.chatMemory = chatMemory
            return this
        }

        fun build(): Tramai {
            val engine = TramaiEngine(...)
            if (chatMemory != null) {
                engine.chatMemory = chatMemory
            }
            return Tramai(engine)
        }
    }
}
```

No auto-wiring. No `@Memory` annotation. Users opt in explicitly:

```kotlin
Tramai.create()
    .memory(MessageWindowChatMemory(maxMessages = 10))
    .build()
```

### TramaiInvocationHandler Changes (tramai-engine)

The handler (already has access to `method` + `args`) detects `@ConversationId` and delegates to `MemoryInterceptor`:

```kotlin
// Inside TramaiInvocationHandler.execute()
val conversationId = resolveConversationId(method, args)

if (chatMemory != null) {
    val memoryInterceptor = MemoryInterceptor(chatMemory)
    val memoryMessages = memoryInterceptor.interceptRequest(conversationId, messages)
    // Replace messages with memory-enhanced list
    messages = memoryMessages
}

// ... existing execution logic ...

if (chatMemory != null) {
    memoryInterceptor.interceptResponse(conversationId, originalMessages, response)
}
```

The conversation ID resolution:

```kotlin
private fun resolveConversationId(method: Method, args: List<Any>): String {
    val parameters = method.parameters
    for (i in parameters.indices) {
        if (parameters[i].isAnnotationPresent(ConversationId::class.java)) {
            return args[i]?.toString() ?: throw IllegalArgumentException(
                "@ConversationId parameter '${parameters[i].name}' at index $i is null"
            )
        }
    }
    return conversationIdProvider.resolve()
}
```

### Module Structure

```
tramai-core (modified):
  - ChatMemory interface (new)
  - @ConversationId annotation (new)
  - ConversationIdProvider interface (new)
  - UuidConversationIdProvider (new)

tramai-memory (new module):
  - MessageWindowChatMemory
  - MemoryInterceptor
  - build.gradle.kts (depends only on tramai-core)

tramai-engine (modified):
  - Tramai.Builder.memory() builder method
  - TramaiInvocationHandler.chatMemory field
  - resolveConversationId() in handler
  - Memory delegation in execute()
```

No new dependencies introduced anywhere. `tramai-memory` depends only on `tramai-core` (stdlib only).

### Build Configuration

```kotlin
// tramai-memory/build.gradle.kts
plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":tramai-core"))
    testImplementation(kotlin("test"))
}
```

Register `tramai-memory` in `settings.gradle.kts` and `tramai-bom`.

## Quality Requirements

- **Thread safety:** All `ChatMemory` implementations must be safe for concurrent access from multiple coroutines.
- **Memory boundedness:** `MessageWindowChatMemory` must never exceed `maxConversations * (maxMessages + 1)` tracked messages.
- **No blocking I/O:** Memory operations are synchronous (in-memory) — no coroutine dispatching needed.
- **Deterministic eviction:** Given the same sequence of adds, the same messages are always evicted.
- **Production safety:** Conversation eviction prevents unbounded memory growth even under sustained load.

## Acceptance Criteria

1. `ChatMemory` interface compiles with `get`, `add`, `clear` methods.
2. `MessageWindowChatMemory` correctly keeps the most recent `maxMessages` (excluding system messages).
3. System messages are never evicted from their conversation.
4. System messages are deduplicated when a new system message arrives.
5. Empty conversations return an empty list.
6. Conversation eviction triggers when `maxConversations` is exceeded.
7. `MemoryInterceptor.interceptRequest()` prepends history and deduplicates system messages.
8. `MemoryInterceptor.interceptResponse()` persists user + assistant messages.
9. `@ConversationId` annotation is resolved from method parameters at invocation time.
10. Conversation ID falls back to `ConversationIdProvider` then UUID.
11. Builder integration: `Tramai.create().memory(...)` compiles and wires correctly.
12. All operations are thread-safe under concurrent access.
13. No regressions in existing module tests.

## Known Limitations (v1)

- **Tool calls not persisted:** Intermediate tool call/result messages from the engine's tool loop are not saved to memory. On subsequent turns, the model won't see prior tool results. This is a v1.1 target.
- **Message-count window, not token-count:** `maxMessages` is a count, not a token budget. This can overflow small context windows with verbose messages. Token-aware window is v1.1.
- **In-memory only:** No persistence across restarts. Persistent backends are v1.1.

## Risks and Follow-Ups

- **Token-counting window** (v1.1): Replace `maxMessages` with `maxTokens` using tokenizer utilities.
- **Persistent backends** (v1.1): `ChatMemoryStore` SPI for PostgreSQL, Redis, etc.
- **Tool-calling memory** (v1.1): Expose intermediate tool call/result messages to the interceptor so they can be persisted.
- **RAG episodic memory** (v1.2): Reuse `tramai-rag` to embed and retrieve semantically relevant past turns.
- **Structured fact extraction** (v2.0): Use `tramai-structured` for typed fact extraction with explicit confidence reporting.
- **Summarization** (v2.0): Optional LLM-based summarization for compressing very long histories, with documented hallucination guardrails.
- **Observation attributes** (v1.0): Add memory-specific span attributes to `tramai-observability` for debugging memory injection.
