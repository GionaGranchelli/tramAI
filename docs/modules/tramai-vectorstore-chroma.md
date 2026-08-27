# tramai-vectorstore-chroma

**Status:** Stable  
**Role:** ChromaDB implementation of the vector store SPI.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Chroma vector-store adapter: `ChromaVectorStore` implementing the vector-store SPI.

### Public entry points

- `ChromaVectorStore` — `VectorStore` implementation
- `ChromaException`

Verify against `tramai-vectorstore-chroma/api/tramai-vectorstore-chroma.api`.

### Internal extension points

- Vector-store SPI implementation slot

### Significant dependencies

- `api(tramai-vectorstore-spi)`; coroutines + Jackson (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Store borrows caller-supplied HTTP client; no process lifecycle owned here

### Thread-safety and concurrency

- Store must be safe for concurrent access

### Failure semantics

- Store failures as `ChromaException` with context

### Contract tests / TCKs

- Covered via vector-store SPI TCK where enrolled; integration tests in module

### Do not

- Do not add provider/engine dependencies here

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — higher-capabilities layer

## Purpose

This module provides a concrete implementation of `VectorStore` that connects to a ChromaDB instance. It handles the HTTP client communication for adding documents and performing similarity searches.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-vectorstore-chroma")  // version from the TramAI BOM
}
```

## Quick Start

```kotlin
import dev.tramai.vectorstore.chroma.ChromaVectorStore
import dev.tramai.embedding.openai.OpenAiEmbeddingModel

val store = ChromaVectorStore(
    embeddingModel = OpenAiEmbeddingModel(apiKey = "sk-..."),
    collectionName = "my-docs",
    host = "http://localhost:8000"
)

// Add documents
store.add(listOf(
    Document(pageContent = "Chroma is a vector database.")
))

// Search
val results = store.similaritySearch("What is Chroma?", k = 1)
```

## When to use this module

* You are using ChromaDB as your vector store.
* You want a fast, local, file-backed vector database for development.

## When NOT to use this module

* You are using a different vector database like Postgres with pgvector.