# tramai-rag

**Version:** 0.3.0  
**Status:** Stable  
**Role:** Retrieval-Augmented Generation context pipeline.

## Purpose

The `tramai-rag` module provides a comprehensive pipeline for injecting internal knowledge into LLM contexts. It handles document loading, text chunking, embedding generation, and vector retrieval.

## Core Concepts

### Document Pipeline

* **Loaders**: Ingest external data (PDFs, Markdown, Web, DB rows) into raw `Document` objects.
* **Chunkers**: Break large documents into semantically meaningful chunks (e.g. `RecursiveCharacterTextChunker`) ensuring context limits are respected and overlapping boundaries preserve meaning.

### Retrieval Pipeline

* **Embeddings**: Interfaces with `tramai-embedding` to translate text chunks into high-dimensional vectors.
* **Vector Store Integration**: Leverages `tramai-vectorstore-spi` to query databases like Chroma or Postgres pgvector for semantic similarity.
* **Context Injection**: Dynamically injects retrieved chunks into an `@Operation` context right before execution.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-rag:0.3.0")
    
    // RAG pipelines usually require embedding and a vector store
    implementation("dev.tramai:tramai-embedding:0.3.0")
    implementation("dev.tramai:tramai-vectorstore-chroma:0.3.0") // Or pgvector
}
```

## When to use this module

* You want the AI to answer questions based on your company's internal PDFs, documentation, or data.
* You are building a "Chat with your data" feature.

## When NOT to use this module

* The AI only needs its base training knowledge to answer queries.
* You are not executing semantic search.