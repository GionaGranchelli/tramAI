# tramai-memory-store

**Status:** Stable  
**Role:** Service Provider Interface (SPI) for persistent chat history.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Persistent chat-memory stores: `JdbcChatMemoryStore`, `RedisChatMemoryStore` implementing the core memory-store contract.

### Public entry points

- `JdbcChatMemoryStore`, `RedisChatMemoryStore` — `ChatMemoryStore` implementations
- `JdbcChatMemoryTable` (schema)

Verify against `tramai-memory-store/api/tramai-memory-store.api`.

### Internal extension points

- New memory-store implementations (the public core memory-store SPI is listed under Public entry points)

### Significant dependencies

- `api(tramai-core)`; Jedis, Jackson (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Stores borrow caller-supplied connections/pools; ownership remains with the caller

### Thread-safety and concurrency

- Stores must be safe for concurrent access; JDBC/Redis connection handling is store-scoped

### Failure semantics

- Store failures surface as typed errors; no silent partial writes

### Contract tests / TCKs

- `JdbcChatMemoryStoreTckTest`, `RedisChatMemoryStoreTckTest` — enrolled in the memory-store TCK

### Do not

- Do not add engine/Spring dependencies here

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — higher-capabilities layer

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
    fun listConversations(limit: Int, offset: Int): List<String>
}
```

The SPI promises: implementations are thread-safe; conversation IDs are
nonblank; missing conversations return `emptyList()`; reads are snapshots;
listing is ordered by most recent append/activity descending and paginated.
A successful `appendMessages()` adds its complete batch exactly once and
contiguously — concurrent appends never interleave mid-batch.

Bundled implementations (both enrolled in the shared
`ChatMemoryStoreTck` compatibility contract, Epic 8.1h):

- `JdbcChatMemoryStore` — generic JDBC, ordinal-per-message rows, optimistic
  whole-transaction retry on the `(conversation_id, ordinal)` uniqueness race
  (SQLState 23505) so concurrent valid appends never fail on ordinal
  allocation.
- `RedisChatMemoryStore` — Redis Lists per conversation plus a sorted-set
  activity index at the exact key prefix; every append is one atomic
  `MULTI` (single `RPUSH` of the whole batch + `ZADD`), every delete is
  `MULTI` (`DEL` + `ZREM`); pre-index legacy lists stay readable and
  discoverable and are enrolled on their next append. Deployment note: the
  key at the exact prefix must be a Redis sorted set (the store creates it
  on first append) — a pre-existing key of another type at the prefix makes
  appends/deletes fail loudly with `JedisDataException` (WRONGTYPE), never
  silently degrade.

Implementations of this interface can be bound to Postgres, Redis, MongoDB,
or even the local filesystem. They plug directly into `PersistentChatMemory`
found in the `tramai-memory` module.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-memory-store")  // version from the TramAI BOM
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
