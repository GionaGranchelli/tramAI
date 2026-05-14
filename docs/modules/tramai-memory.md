# Module: `tramai-memory`

> **One-liner:** In-memory conversational memory — message windows, history injection, and response persistence for multi-turn AI interactions.
> **Module type:** `optional`

---

## L1: Quick Start (30-second read)

### What

`tramai-memory` provides the production implementation of Tramai's `ChatMemory` SPI. It ships two classes:

- **`MessageWindowChatMemory`** — thread-safe, bounded, in-memory store keyed by conversation ID. Configurable `maxMessages` (sliding window) and `maxConversations` (LRU eviction). System messages are retained and deduplicated.
- **`MemoryInterceptor`** — standalone helper that prepends stored history to outgoing requests (with system message dedup) and persists user+assistant responses after each turn.

### Why

Without `tramai-memory`, every `@AiService` call is stateless — the model has no memory of previous turns. Adding conversational memory enables chat UIs, session-based agents, and multi-turn interactions.

### When to use

When you need the engine to inject conversation history into requests and save responses automatically. Wire it through `TramaiEngine.chatMemory` or `Tramai.Builder.memory()`.

### How to add

**Gradle:**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-memory:0.2.0")
}
```

**Bill of Materials:**

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.2.0"))
implementation("dev.tramai:tramai-memory")
```

### Where to go next

- [SPEC-020 Chat Memory](../specs/spec-020-chat-memory.md) — full spec with design decisions and integration flow
- [Memory Board](../board/memory-board.md) — implementation progress and backlog
- [tramai-engine module](./tramai-engine.md) — how to wire memory into the engine

---

## L2: Usage Guide (5-minute read)

### Basic setup

```kotlin
val memory = MessageWindowChatMemory(
    maxMessages = 20,        // non-system messages per conversation
    maxConversations = 1_000, // active conversations before LRU eviction
)

Tramai.builder()
    .provider(openAiProvider)
    .model("gpt-4o", "openai")
    .memory(memory)
    .build()
```

### Annotate conversation IDs

Mark the parameter that identifies the conversation:

```kotlin
@AiService
interface SupportChat {
    @Operation(prompt = "Answer the user's question", model = "gpt-4o")
    suspend fun chat(
        @ConversationId ticketId: String,
        message: String,
    ): String
}
```

### Window configuration

```kotlin
// Small window — keeps only the last 5 user/assistant exchanges
val tightWindow = MessageWindowChatMemory(maxMessages = 5)

// Large deployment — up to 10K conversations, 50 messages each
val largeDeployment = MessageWindowChatMemory(
    maxMessages = 50,
    maxConversations = 10_000,
)
```

### Using MemoryInterceptor directly

For custom integration outside `TramaiEngine`:

```kotlin
val interceptor = MemoryInterceptor(chatMemory)

// Before calling the provider:
val enhancedMessages = interceptor.interceptRequest("conv-1", messages)

// After receiving the response:
interceptor.interceptResponse("conv-1", originalMessages, response)
```

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-memory` is deliberately minimal. It provides:

1. A thread-safe, bounded in-memory store (`MessageWindowChatMemory`)
2. A stateless interceptor that transforms message lists (`MemoryInterceptor`)

The engine does not import `tramai-memory` directly — it uses the `ChatMemory` SPI from `tramai-core`. Users add `tramai-memory` to their dependency graph explicitly.

### Thread safety

`MessageWindowChatMemory` uses a single global lock protecting all mutable operations (conversation map mutations, deque mutations, LRU tracker updates, eviction). This avoids deadlocks from two-phase locking (per-entry + global lock). All operations are `synchronized(lock)`.

### Eviction semantics

| Trigger | What happens |
|---------|-------------|
| `maxMessages` exceeded | Oldest non-system messages removed within the conversation |
| New system message arrives | Old system message replaced (dedup, one per conversation) |
| `maxConversations` exceeded | Entire least-recently-written conversation evicted |

`ConversationIdProvider` is `UuidConversationIdProvider` by default in the engine — every call without `@ConversationId` creates a new UUID, effectively single-turn.

### Known limitations (v1)

- Tool-call intermediate messages are not persisted (v1.1)
- Token-count window not yet available (message-count only; v1.1)
- No persistent backends (in-memory only; v1.1)

---

## L4: API Reference

### `MessageWindowChatMemory`

```kotlin
class MessageWindowChatMemory(
    maxMessages: Int = 20,
    maxConversations: Int = 1_000,
) : ChatMemory
```

| Method | Returns | Description |
|--------|---------|-------------|
| `get(conversationId)` | `List<Message>` | Snapshot of conversation history, empty if none |
| `add(conversationId, message)` | `Unit` | Append one message, triggers eviction + LRU update |
| `add(conversationId, messages)` | `Unit` | Append multiple messages |
| `clear(conversationId)` | `Unit` | Remove all history for a conversation |

### `MemoryInterceptor`

```kotlin
class MemoryInterceptor(chatMemory: ChatMemory)
```

| Method | Returns | Description |
|--------|---------|-------------|
| `interceptRequest(conversationId, messages)` | `List<Message>` | Prepends history, deduplicates system messages |
| `interceptResponse(conversationId, requestMessages, response)` | `Unit` | Persists user messages + assistant response |
