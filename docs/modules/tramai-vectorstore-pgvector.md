# tramai-vectorstore-pgvector

**Status:** Stable  
**Role:** PostgreSQL pgvector implementation of the vector store SPI.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

PostgreSQL pgvector adapter: `PgVectorStore` implementing the vector-store SPI.

### Public entry points

- `PgVectorStore` — `VectorStore` implementation
- `PgVectorException`

Verify against `tramai-vectorstore-pgvector/api/tramai-vectorstore-pgvector.api`.

### Internal extension points

- Vector-store SPI implementation slot

### Significant dependencies

- `api(tramai-vectorstore-spi)`; PostgreSQL driver, HikariCP, coroutines, Jackson (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Store borrows caller-supplied connection pool; ownership remains with the caller

### Thread-safety and concurrency

- Store must be safe for concurrent access; connection handling is store-scoped

### Failure semantics

- Store failures as `PgVectorException` with context

### Contract tests / TCKs

- Covered via vector-store SPI TCK where enrolled; integration tests in module

### Do not

- Do not add provider/engine dependencies here

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — higher-capabilities layer

## Purpose

This module provides a concrete implementation of `VectorStore` that connects to a PostgreSQL database with the `pgvector` extension installed. It allows you to store embeddings and perform nearest-neighbor searches directly within your relational database.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-vectorstore-pgvector")  // version from the TramAI BOM
    // Depending on your stack, you'll need a JDBC driver (e.g. org.postgresql:postgresql)
}
```

## Quick Start

```kotlin
import dev.tramai.vectorstore.pgvector.PgVectorStore
import dev.tramai.embedding.openai.OpenAiEmbeddingModel
import javax.sql.DataSource

val dataSource: DataSource = // ... your DB configuration ...

val store = PgVectorStore(
    dataSource = dataSource,
    embeddingModel = OpenAiEmbeddingModel(apiKey = "sk-..."),
    tableName = "document_embeddings",
    vectorDimensions = 1536 // Matches openai text-embedding-3-small
)

// The store can auto-initialize the table and vector extension if configured
store.initSchema() 

// Search
val results = store.similaritySearch("Find documents about PostgreSQL", k = 3)
```

## When to use this module

* You are already using PostgreSQL as your primary database.
* You prefer to keep relational data and vector data in the same transactional boundary.
* You do not want to manage a separate standalone vector database infrastructure.

## When NOT to use this module

* Your Postgres instance does not support the `pgvector` extension.
* You are operating at an extremely massive vector scale where specialized cluster DBs (like Milvus or Pinecone) might be required.