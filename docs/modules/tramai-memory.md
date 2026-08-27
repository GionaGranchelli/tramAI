# tramai-memory


> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Conversational memory: chat-memory implementations (`MessageWindowChatMemory`, `PersistentChatMemory`, `TokenAwareChatMemory`) and the `MemoryInterceptor` engine hook.

### Public entry points

- `MessageWindowChatMemory`, `PersistentChatMemory`, `TokenAwareChatMemory` — `ChatMemory` implementations
- `MemoryInterceptor` — engine observation hook wiring memory

Verify against `tramai-memory/api/tramai-memory.api`.

### Internal extension points

- New memory implementations (the public core memory SPI is listed under Public entry points)

### Significant dependencies

- `api(tramai-core)` only — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Memory instances are caller-owned; no process lifecycle owned here

### Thread-safety and concurrency

- Memory implementations must be safe for concurrent engine access

### Failure semantics

- Memory persistence failures surface as typed errors; injection must not break the happy path

### Contract tests / TCKs

- `MemoryInterceptorTest`, `MessageWindowChatMemoryTest`, `PersistentChatMemoryTest`, `TokenAwareChatMemoryTest`

### Do not

- Do not add provider/Spring dependencies here

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — higher-capabilities layer

## Purpose

The `tramai-memory` module provides production-ready implementations of the `ChatMemory` interface. While `tramai-core` offers the SPI, this module brings the actual memory implementations needed to retain conversational state across multiple requests (multi-turn chat) while staying within LLM context windows.

## Core Concepts

TramAI models conversation history around bounded scopes. Rather than infinitely accumulating messages until the LLM crashes from token limits, this module provides bounded memory tracking out of the box.

### `TokenAwareChatMemory`

An in-memory sliding window that retains conversation history strictly based on an estimated token count.

- **System Deduplication**: System messages (`MessageRole.SYSTEM`) are never evicted and are deduplicated.
- **LRU Eviction**: Unbounded memory growth is prevented by evicting older entire conversations based on a Least Recently Used policy.
- **Token Estimation**: Uses a `Tokenizer` interface. By default, it applies a `roughTokenizer` (~3 characters per token) to prevent token exhaustion. 
- **Thread Safety**: Backed by `ConcurrentHashMap` with single-lock protection across conversation state.

### `PersistentChatMemory`

Connects a `ChatMemoryStore` (database) with an optional in-memory cache (`MessageWindowChatMemory` or similar). Use this when your conversation context needs to survive server restarts or distributed worker pools.

## Dependencies

```kotlin
// build.gradle.kts
// tramaiVersion is the canonical version property (see gradle.properties)
val tramaiVersion: String by project

dependencies {
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
    implementation("dev.tramai:tramai-memory")
    // For durable storage (Postgres, Redis): also add tramai-memory-store
    implementation("dev.tramai:tramai-memory-store")
}
```

## Quick Start: TokenAwareChatMemory

```kotlin
import dev.tramai.memory.TokenAwareChatMemory
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole

val memory = TokenAwareChatMemory(
    maxTokens = 4096,           // Evict oldest non-system messages once 4k tokens breached
    maxConversations = 1000     // Purge entire chats after 1000 active sessions
)

// Add messages
memory.add("chat-123", Message(MessageRole.USER, "Hello, can you remember things?"))
memory.add("chat-123", Message(MessageRole.ASSISTANT, "Yes, I am maintaining context."))

// Retrieve context
val history = memory.get("chat-123")

// Clear context
memory.clear("chat-123")
```

## When to use this module

* You are building conversational agents, chatbots, or customer support AI.
* You need context retention across sequential invocations.
* You need protection against context-window exhaustion via token counting.

## When NOT to use this module

* You are building single-turn extraction tools (e.g. summarizing a block of text).
* Your use case is stateless.