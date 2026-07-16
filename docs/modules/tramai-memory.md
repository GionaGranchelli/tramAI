# tramai-memory

**Version:** 0.3.1  
**Status:** Stable  
**Role:** In-memory context and token-aware multi-turn conversational chat persistence.

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
dependencies {
    implementation("dev.tramai:tramai-memory:0.5.0")
    // If you need durable storage (Postgres, Redis, File):
    implementation("dev.tramai:tramai-memory-store:0.5.0")
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