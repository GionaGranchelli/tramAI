# tramai-embedding

**Status:** Stable  
**Role:** Text embedding generation.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Embedding model integration: `EmbeddingModelRegistry`, `EmbeddingModel` SPI, concrete Ollama and OpenAI embedding models.

### Public entry points

- `EmbeddingModelRegistry` (+ builder), `EmbeddingModel` SPI
- `OllamaEmbeddingModel`, `OpenAiEmbeddingModel`
- `EmbeddingConfig`, `EmbeddingException`

Verify against `tramai-embedding/api/tramai-embedding.api`.

### Internal extension points

- New embedding-provider implementations (the public model SPI is listed under Public entry points)

### Significant dependencies

- No project deps beyond coroutines + Jackson (implementation) — models use direct HTTP. See [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Registry/model instances are caller-owned; no process lifecycle owned here

### Thread-safety and concurrency

- Registry and models are safe for concurrent use (stateless beyond configuration)

### Failure semantics

- Embedding failures as `EmbeddingException` with context

### Contract tests / TCKs

- `EmbeddingModelRegistryTest`

### Do not

- Do not couple embedding models to the chat-provider SPI — this is a separate capability

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — higher-capabilities layer

## Purpose

The `tramai-embedding` module converts raw text into numerical vector arrays (embeddings). These embeddings power semantic search and clustering, forming the translation layer between plain text and the `tramai-vectorstore-spi` modules.

## Core Concepts

### `EmbeddingModel`

The core SPI interface. Translates lists of strings into lists of vectors (e.g. `List<FloatArray>`).

This module bundles standard integrations natively:
* **OpenAI Embeddings** (`text-embedding-3-small`, `text-embedding-ada-002`)
* **Ollama Embeddings** (Local models like `nomic-embed-text`)

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-embedding")  // version from the TramAI BOM
}
```

## Quick Start

```kotlin
import dev.tramai.embedding.openai.OpenAiEmbeddingModel

val model = OpenAiEmbeddingModel(apiKey = "sk-...")

// Generate vectors
val embeddings = model.embedAll(listOf(
    "What is the capital of France?",
    "How do I sort an array in Kotlin?"
))

println("Vector dimensions: ${embeddings[0].size}") 
```

## When to use this module

* You are configuring a RAG pipeline.
* You need to calculate document similarity, cluster text, or perform semantic search.

## When NOT to use this module

* You are strictly generating text (chat/completions) without any vector search components.