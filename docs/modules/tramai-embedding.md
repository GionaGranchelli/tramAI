# tramai-embedding

**Version:** 0.3.1  
**Status:** Stable  
**Role:** Text embedding generation.

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
    implementation("dev.tramai:tramai-embedding:0.5.0")
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