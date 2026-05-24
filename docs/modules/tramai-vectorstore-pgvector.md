# tramai-vectorstore-pgvector

**Version:** 0.3.1  
**Status:** Stable  
**Role:** PostgreSQL pgvector implementation of the vector store SPI.

## Purpose

This module provides a concrete implementation of `VectorStore` that connects to a PostgreSQL database with the `pgvector` extension installed. It allows you to store embeddings and perform nearest-neighbor searches directly within your relational database.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-vectorstore-pgvector:0.3.1")
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