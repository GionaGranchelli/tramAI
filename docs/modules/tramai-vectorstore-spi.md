# tramai-vectorstore-spi

**Status:** Stable  
**Role:** Contract layer for Vector Database operations.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Vector-store SPI: `VectorStore` contract, `VectorEntry`, `SearchResult` — the seam concrete stores (Chroma, pgvector) implement.

### Public entry points

- `VectorStore` — SPI contract
- `VectorEntry`, `SearchResult` — data types

Verify against `tramai-vectorstore-spi/api/tramai-vectorstore-spi.api`.

### Internal extension points

- New store implementations (the public vector-store contract is listed under Public entry points)

### Significant dependencies

- Coroutines (implementation) only — no project deps. See [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- No process lifecycle owned here; store instances are caller-owned

### Thread-safety and concurrency

- Contract defines no blanket guarantee; each store documents its own concurrency behavior

### Failure semantics

- Contract-level failure types; concrete stores define their own exceptions

### Contract tests / TCKs

- `InMemoryVectorStoreTest`; concrete stores run their own integration/TCK tests

### Do not

- Do not add provider/engine dependencies here

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — higher-capabilities layer

## Purpose

This module provides the core `VectorStore` interfaces required by `tramai-rag`. By keeping this layer separate from concrete implementations (Chroma, PGVector), you can switch out vector databases without altering your application's RAG orchestration code.

## Core Concepts

* `VectorStore`: Interface dictating `add(documents)` and `similaritySearch(query, limit)`.
* `Document`: The core data class encompassing the text chunk and arbitrary `metadata` mapping.
* `InMemoryVectorStore`: A volatile implementation useful for rapid local testing without spinning up Docker containers.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-vectorstore-spi")  // version from the TramAI BOM
}
```

## When to use this module

* You are building a custom vector database adapter.
* You need an in-memory semantic search solution for unit tests.

## When NOT to use this module

* You just want to use a specific database out of the box (depend on `tramai-vectorstore-chroma` or `tramai-vectorstore-pgvector` directly instead).