# tramai-vectorstore-spi

**Version:** 0.3.1  
**Status:** Stable  
**Role:** Contract layer for Vector Database operations.

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
    implementation("dev.tramai:tramai-vectorstore-spi:0.4.0")
}
```

## When to use this module

* You are building a custom vector database adapter.
* You need an in-memory semantic search solution for unit tests.

## When NOT to use this module

* You just want to use a specific database out of the box (depend on `tramai-vectorstore-chroma` or `tramai-vectorstore-pgvector` directly instead).