# tramai-rag


> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

RAG pipeline: chunking, document loading, retrieval, context injection — composing embeddings + vector stores.

### Public entry points

- `RagPipeline`, `RagRetriever`, `ContextInjector`, `Document`
- Chunkers: `FixedSizeChunker`, `RecursiveCharacterChunker`, `TokenAwareChunker`
- Loaders: `FileDocumentLoader`, `UrlDocumentLoader`
- `RagPipelineException`

Verify against `tramai-rag/api/tramai-rag.api`.

### Internal extension points

- New chunker/document-loader implementations (the public chunking / loading SPIs are listed under Public entry points)

### Significant dependencies

- `api(tramai-core)`, `api(tramai-embedding)`, `api(tramai-vectorstore-spi)`; coroutines (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Pipeline/retriever instances are caller-owned; no process lifecycle owned here

### Thread-safety and concurrency

- Components are safe for concurrent use (stateless beyond configuration)

### Failure semantics

- Pipeline failures as `RagPipelineException` with context

### Contract tests / TCKs

- `ChunkersTest`, `ContextInjectorTest`, `RagPipelineTest`, `RagRetrieverTest`

### Do not

- Do not couple RAG to a specific vector store/provider — use the SPIs

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — higher-capabilities layer

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
// tramaiVersion is the canonical version property (see gradle.properties)
val tramaiVersion: String by project

dependencies {
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
    implementation("dev.tramai:tramai-rag")
    // RAG pipelines usually require embedding and a vector store
    implementation("dev.tramai:tramai-embedding")
    implementation("dev.tramai:tramai-vectorstore-chroma") // or pgvector
}
```

## When to use this module

* You want the AI to answer questions based on your company's internal PDFs, documentation, or data.
* You are building a "Chat with your data" feature.

## When NOT to use this module

* The AI only needs its base training knowledge to answer queries.
* You are not executing semantic search.