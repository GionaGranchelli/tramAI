# tramai-vectorstore-chroma

**Version:** 0.3.0  
**Status:** Stable  
**Role:** ChromaDB implementation of the vector store SPI.

## Purpose

This module provides a concrete implementation of `VectorStore` that connects to a ChromaDB instance. It handles the HTTP client communication for adding documents and performing similarity searches.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-vectorstore-chroma:0.3.0")
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