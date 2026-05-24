# tramai-memory-store

**Version:** 0.3.1  
**Status:** Stable  
**Role:** Service Provider Interface (SPI) for persistent chat history.

## Purpose

The `tramai-memory-store` module acts as the persistence bridge for the TramAI conversation context. If `tramai-memory` defines *how* context should be managed in RAM, `tramai-memory-store` defines *where* it should be stored out of RAM.

## Core Concepts

### `ChatMemoryStore`

An SPI interface requiring implementation for durability.

```kotlin
interface ChatMemoryStore {
    fun getMessages(conversationId: String): List<Message>
    fun appendMessages(conversationId: String, messages: List<Message>)
    fun deleteConversation(conversationId: String)
}
```

Implementations of this interface can be bound to Postgres, Redis, MongoDB, or even the local filesystem. They plug directly into `PersistentChatMemory` found in the `tramai-memory` module.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-memory-store:0.3.1")
}
```

## Quick Start: Using a Store

```kotlin
import dev.tramai.memory.PersistentChatMemory
import dev.tramai.core.memory.ChatMemoryStore
import dev.tramai.core.model.Message

// 1. You or a library provides a store implementation
class JdbcMemoryStore : ChatMemoryStore {
    // ... JDBC insert/select logic ...
}

// 2. Wire it into the persistence layer
val durableMemory = PersistentChatMemory(store = JdbcMemoryStore())

// 3. Transparently use memory across restarts
durableMemory.add("user-id-42", Message(MessageRole.USER, "Save this to DB."))
```

## When to use this module

* You are writing a custom database integration for chat persistence.
* Your chat memory must outlive process restarts.
* You are operating a multi-node cluster and need conversation state available globally.
