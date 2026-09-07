# SPEC-019: RAG Pipeline, Vector Store SPI, Embedding SPI, and Google Gemini Provider

- Status: approved
- Owner: maintainer
- Last updated: 2026-05-13
- Related roadmap milestone: M5 — Knowledge Augmentation and Provider Expansion
- Related ADRs: ADR-008 (Provider SPI), ADR-010 (Provider Routing)
- Related docs: [Architecture Overview](../architecture/overview.md), [Provider Integration](./spec-003-provider-integration.md), [AGENTS.md](../../AGENTS.md)

## Problem

Tramai handles typed AI operations against chat-completion providers, but it has no built-in support for retrieval-augmented generation (RAG), vector storage, or text embeddings. Users who want RAG today must hand-roll embedding calls, vector store clients, and context-injection logic outside the library. Equally, Tramai only supports OpenAI, Anthropic, and Ollama as model providers — Google's Gemini family is the most notable gap for JVM teams targeting multi-provider deployments.

## Scope

1. **Embedding SPI** — A minimal `EmbeddingModel` interface plus built-in implementations for OpenAI (`text-embedding-3-small`, etc.) and Ollama (local embedding models). New module: `tramai-embedding`.
2. **Vector Store SPI** — A minimal `VectorStore` interface with `upsert` and `search` operations, plus the `SearchResult` model. New module: `tramai-vectorstore-spi`.
3. **Vector Store adapter modules** — `tramai-vectorstore-chroma` and `tramai-vectorstore-pgvector` as initial implementations. `tramai-vectorstore-qdrant` is deferred.
4. **RAG Pipeline** — `DocumentLoader`, `DocumentSplitter`/`Chunker`, `RagRetriever`, and `ContextInjector` in a new `tramai-rag` module that depends on `tramai-embedding` and `tramai-vectorstore-spi`.
5. **Google Gemini Provider** — A new `tramai-gemini` provider module implementing `ModelProvider` and `StreamCapable` for the Gemini API (gemini-2.0-flash, gemini-2.0-pro, gemini-2.5-pro).
6. **Vision content support** — Extend `ModelRequest.Message` (or a parallel content model) to support multi-part messages with image content, enabling vision use cases across OpenAI, Anthropic, and Gemini.

## Non-Goals

- In-process vector database implementations (the SPI is for external stores only)
- Distributed indexing pipelines or batch ingestion frameworks
- Embedding model training or fine-tuning
- Reranking SPI or cross-encoder support (deferred to a follow-up)
- Multi-modal document parsing (PDF/OCR) beyond image-in-message support
- Agentic RAG or multi-hop retrieval
- Prompt-template-heavy RAG abstractions (Tramai keeps typed contracts)
- A Qdrant adapter in the initial milestone
- LangChain or LlamaIndex compatibility layers
- Gemini on Vertex AI (deferred; initial support is the public Gemini API only)

## Functional Requirements

### Embedding SPI (tramai-embedding)

- `EmbeddingModel` must expose `embed(text: String): FloatArray` and `embedAll(texts: List<String>): List<FloatArray>`.
- The interface must be suspend-friendly (suspend functions).
- A built-in `OpenAiEmbeddingModel` implementation must support configurable model and dimensions.
- A built-in `OllamaEmbeddingModel` implementation must support local embedding models.
- The module must expose an `EmbeddingModelRegistry` (following the `ProviderRegistry` builder pattern from tramai-core) so embedding providers can be registered and resolved by name.

### Vector Store SPI (tramai-vectorstore-spi)

- `VectorStore` must expose `upsert(collection: String, vectors: List<VectorEntry>)` and `search(collection: String, query: FloatArray, topK: Int, filter: Map<String, String>?): List<SearchResult>`.
- `VectorEntry` must contain an `id: String`, `vector: FloatArray`, `content: String`, and `metadata: Map<String, String>`.
- `SearchResult` must contain `content: String`, `metadata: Map<String, String>`, and `score: Double`.
- `delete(collection: String, ids: List<String>)` must be supported for cleanup.
- `listCollections(): List<String>` must be supported for discovery.
- The SPI must live in its own module with zero runtime dependencies beyond kotlin-stdlib and coroutines.

### Vector Store Adapters

- `tramai-vectorstore-chroma` must implement `VectorStore` over the Chroma HTTP API.
- `tramai-vectorstore-pgvector` must implement `VectorStore` over JDBC with the pgvector extension.
- Both must support configurable connection parameters.
- Both must handle collection auto-creation on first `upsert`.

### RAG Pipeline (tramai-rag)

- `DocumentLoader` must support loading text from files (paths) and URLs, with a `load(source: String): Document` suspend function.
- `DocumentSplitter` must support recursive character splitting, token-aware splitting, and fixed-size splitting, each configurable by chunk size and overlap.
- `RagRetriever` must accept an `EmbeddingModel` and `VectorStore`, embed a query, and return top-K `SearchResult` entries.
- `ContextInjector` must prepend retrieved context into the system message or user message of a `ModelRequest`.
- The RAG pipeline must be usable both as a standalone builder and as a composable step in `tramai-orchestration` workflows.

### Google Gemini Provider (tramai-gemini)

- Must implement `ModelProvider` and `StreamCapable`.
- Must support at minimum `gemini-2.0-flash`, `gemini-2.0-pro`, and `gemini-2.5-pro` model identifiers.
- Must support structured output via the `response_mime_type` and `response_schema` parameters.
- Must support tool calling with function declarations.
- Must authenticate via API key (`tramai.providers.gemini.api-key` or `tramai.providers.gemini.api-key-secret-ref`).
- Must support configurable base URL (for Gemini AI Studio and future Vertex AI support).
- Must normalize responses into Tramai's `ModelResponse` with proper `FinishReason` mapping.

### Vision Content Support

- `ModelRequest.Message` must support a list of content parts (`contentParts: List<ContentPart>?`) alongside or replacing the plain-text `content` field.
- `ContentPart` must support `TextPart(text: String)` and `ImagePart(data: ByteArray, mimeType: String)` variants.
- Provider modules must map `ImagePart` to their respective image input formats (OpenAI: data URIs, Anthropic: base64 blocks, Gemini: inline data).
- The `content` field on `Message` must remain as a convenience for text-only messages.

## Quality Requirements

- All SPI interfaces must remain minimal and composable — no hidden dependencies between EmbeddingModel and VectorStore.
- Embedding dimensions must be transparent: the caller owns dimension alignment between embedding model output and vector store index.
- Provider credential handling for Gemini must reuse `SecretValueResolver` like existing providers.
- RAG pipeline components must be unit-testable against fake embeddings and in-memory vector stores.
- The Gemini provider must handle Google's 429 rate limiting with Retry-After header parsing.
- Vector store modules must use connection pooling where appropriate (PGVector via HikariCP).
- Module boundaries must ensure tramai-core does not grow — new SPIs go in `tramai-embedding` and `tramai-vectorstore-spi`.
- The `tramai-rag` module must remain optional — no new mandatory transitive dependencies for existing users.

## Design Notes

### Property / Configuration Design

Following Tramai's existing `tramai.<group>.<key>` property convention in `TramaiProperties`:

```yaml
# Embedding
tramai:
  embedding:
    provider: openai        # openai | ollama
    model: text-embedding-3-small
    dimensions: 1536
    openai:
      api-key: <key>        # or api-key-secret-ref
      base-url: https://api.openai.com/v1
    ollama:
      base-url: http://localhost:11434
      model: nomic-embed-text

# Vector Store
  vectorstore:
    type: chroma             # chroma | pgvector
    collection: default
    chroma:
      url: http://localhost:8000
      tenant: default_tenant
      database: default_database
    pgvector:
      jdbc-url: jdbc:postgresql://localhost:5432/vectors
      username: postgres
      password: <password>   # or password-secret-ref
      table-name: embeddings
      dimensions: 1536

# RAG Pipeline
  rag:
    chunker:
      type: recursive        # recursive | token | fixed
      chunk-size: 500
      chunk-overlap: 50
      separators:
        - "\n\n"
        - "\n"
        - " "
        - ""
    retriever:
      top-k: 3
      min-score: 0.5

# Gemini Provider
  providers:
    gemini:
      api-key: <key>         # or api-key-secret-ref
      base-url: https://generativelanguage.googleapis.com/v1beta
```

### New Module Structure

```
tramai-embedding (new)
  depends on: tramai-core (for SPI conventions)
  src/main/kotlin/dev/tramai/embedding/
    EmbeddingModel.kt           — SPI interface
    EmbeddingModelRegistry.kt   — Registry with builder pattern
    EmbeddingConfig.kt          — Configuration data class
    openai/
      OpenAiEmbeddingModel.kt   — OpenAI implementation
    ollama/
      OllamaEmbeddingModel.kt   — Ollama implementation

tramai-vectorstore-spi (new)
  depends on: tramai-core (for coroutines)
  src/main/kotlin/dev/tramai/vectorstore/
    VectorStore.kt              — SPI interface
    VectorEntry.kt              — Data model for upsert
    SearchResult.kt             — Data model for search results

tramai-vectorstore-chroma (new)
  depends on: tramai-vectorstore-spi, kotlinx-coroutines, jackson
  src/main/kotlin/dev/tramai/vectorstore/chroma/
    ChromaVectorStore.kt       — VectorStore impl over Chroma HTTP API
    ChromaConfig.kt            — Connection configuration

tramai-vectorstore-pgvector (new)
  depends on: tramai-vectorstore-spi, kotlinx-coroutines, HikariCP, JDBC
  src/main/kotlin/dev/tramai/vectorstore/pgvector/
    PgVectorStore.kt           — VectorStore impl over JDBC + pgvector
    PgVectorConfig.kt          — Connection configuration

tramai-rag (new)
  depends on: tramai-embedding, tramai-vectorstore-spi, tramai-core
  src/main/kotlin/dev/tramai/rag/
    Document.kt                — Data model
    DocumentLoader.kt          — Load from file/URL
    DocumentSplitter.kt        — Chunking strategies
    chunker/
      RecursiveCharacterChunker.kt
      TokenChunker.kt
      FixedSizeChunker.kt
    RagRetriever.kt            — Embed query -> search store
    ContextInjector.kt         — Inject context into ModelRequest
    RagPipeline.kt             — Builder for the full RAG chain

tramai-gemini (new)
  depends on: tramai-core, kotlinx-coroutines, jackson
  src/main/kotlin/dev/tramai/gemini/
    GeminiProvider.kt          — ModelProvider + StreamCapable impl
    GeminiConfig.kt            — Provider configuration
    GeminiModels.kt            — Supported model constants
```

### SPI Interfaces (Proposed API Surface)

#### EmbeddingModel (tramai-embedding)

```kotlin
package dev.tramai.embedding

/**
 * Abstraction for text embedding models.
 * Implementations must be thread-safe and coroutine-friendly.
 */
interface EmbeddingModel {
    /** Provider-agnostic identifier (e.g., "openai", "ollama"). */
    fun providerId(): String = this::class.simpleName ?: "unknown"

    /** The output dimensionality of this embedding model. */
    fun dimensions(): Int

    /** Embed a single text string into a float vector. */
    suspend fun embed(text: String): FloatArray

    /** Embed multiple text strings in batch (may be more efficient than sequential embed calls). */
    suspend fun embedAll(texts: List<String>): List<FloatArray>
}

/**
 * Configuration for embedding model selection.
 */
data class EmbeddingConfig(
    val provider: String = "openai",
    val model: String = "text-embedding-3-small",
    val dimensions: Int = 1536,
)

/**
 * Registry for resolving EmbeddingModel instances by configuration.
 * Follows the same builder pattern as ProviderRegistry in tramai-core.
 */
class EmbeddingModelRegistry private constructor(
    private val modelsByProvider: Map<String, EmbeddingModel>,
    private val defaultProvider: String?,
) {
    fun resolve(config: EmbeddingConfig): EmbeddingModel {
        val providerName = config.provider.takeIf { it.isNotBlank() } ?: defaultProvider
            ?: throw ConfigurationException("No embedding provider configured")
        return modelsByProvider[providerName]
            ?: throw ConfigurationException("Unknown embedding provider '$providerName'")
    }

    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val modelsByProvider = linkedMapOf<String, EmbeddingModel>()
        private var defaultProvider: String? = null

        fun provider(name: String, model: EmbeddingModel, default: Boolean = false): Builder {
            modelsByProvider[name] = model
            if (default) defaultProvider = name
            return this
        }

        fun build(): EmbeddingModelRegistry = EmbeddingModelRegistry(
            modelsByProvider = modelsByProvider.toMap(),
            defaultProvider = defaultProvider,
        )
    }
}
```

#### VectorStore (tramai-vectorstore-spi)

```kotlin
package dev.tramai.vectorstore

/**
 * Entry for upsert into a vector store.
 */
data class VectorEntry(
    val id: String,
    val vector: FloatArray,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Result of a vector search.
 */
data class SearchResult(
    val id: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val score: Double,
)

/**
 * Abstraction for vector database operations.
 * The caller is responsible for dimension alignment between
 * the EmbeddingModel output and the VectorStore index.
 */
interface VectorStore {
    /** Provider-agnostic identifier (e.g., "chroma", "pgvector"). */
    fun storeId(): String = this::class.simpleName ?: "unknown"

    /** Upsert vectors into a collection. Creates the collection if it does not exist. */
    suspend fun upsert(collection: String, vectors: List<VectorEntry>)

    /** Search for the topK most similar vectors in a collection. */
    suspend fun search(
        collection: String,
        query: FloatArray,
        topK: Int,
        filter: Map<String, String>? = null,
    ): List<SearchResult>

    /** Delete vectors by ID from a collection. */
    suspend fun delete(collection: String, ids: List<String>)

    /** List all available collections. */
    suspend fun listCollections(): List<String>
}
```

#### ContentPart for Vision Support (tramai-core)

```kotlin
package dev.tramai.core.model

/**
 * A single content part within a multi-part message.
 */
sealed interface ContentPart {
    data class TextPart(val text: String) : ContentPart
    data class ImagePart(
        val data: ByteArray,
        val mimeType: String,  // e.g., "image/png", "image/jpeg", "image/webp"
    ) : ContentPart
}

// Updated Message:
data class Message(
    val role: MessageRole,
    val content: String = "",
    val contentParts: List<ContentPart>? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
)
```

#### RAG Pipeline (tramai-rag)

```kotlin
package dev.tramai.rag

/**
 * A loaded document with metadata.
 */
data class Document(
    val id: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val source: String? = null,
)

/**
 * Interface for loading documents from various sources.
 */
interface DocumentLoader {
    suspend fun load(source: String): Document
}

/**
 * Interface for splitting documents into chunks.
 */
interface DocumentSplitter {
    suspend fun split(document: Document): List<Document>
}

/**
 * Configuration for chunking strategies.
 */
data class ChunkerConfig(
    val type: ChunkerType = ChunkerType.RECURSIVE,
    val chunkSize: Int = 500,
    val chunkOverlap: Int = 50,
    val separators: List<String> = listOf("\n\n", "\n", " ", ""),
)

enum class ChunkerType { RECURSIVE, TOKEN, FIXED }

/**
 * Retrieves relevant context by embedding a query and searching a vector store.
 */
class RagRetriever(
    private val embeddingModel: EmbeddingModel,
    private val vectorStore: VectorStore,
    private val topK: Int = 3,
    private val minScore: Double = 0.0,
) {
    suspend fun retrieve(
        query: String,
        collection: String = "default",
        filter: Map<String, String>? = null,
    ): List<SearchResult>
}

/**
 * Injects retrieved context into a ModelRequest.
 */
open class ContextInjector {
    /**
     * Prepends retrieved context as a system message before the existing messages.
     */
    open fun inject(
        request: ModelRequest,
        context: List<SearchResult>,
        template: String = "Use the following context to answer the question:\n\n{context}",
    ): ModelRequest
}

/**
 * Builder for a complete RAG pipeline.
 * Separates the indexing phase (load -> split -> embed -> upsert)
 * from the query phase (embed query -> search -> inject context).
 */
class RagPipelineBuilder {
    var loader: DocumentLoader? = null
    var splitter: DocumentSplitter? = null
    var embeddingModel: EmbeddingModel? = null
    var vectorStore: VectorStore? = null
    var contextInjector: ContextInjector = ContextInjector()
    var collection: String = "default"
    var topK: Int = 3
    var minScore: Double = 0.0

    fun build(): RagPipeline
}

class RagPipeline(
    private val loader: DocumentLoader,
    private val splitter: DocumentSplitter,
    private val embeddingModel: EmbeddingModel,
    private val vectorStore: VectorStore,
    private val contextInjector: ContextInjector,
    private val collection: String,
    private val topK: Int,
    private val minScore: Double,
) {
    /**
     * Indexes a document source: load -> split -> embed -> upsert.
     * After indexing the document is queryable via [query].
     */
    suspend fun index(source: String): Int  // returns chunk count

    /**
     * Queries the indexed collection: embed query -> search -> inject context.
     * Returns a ModelRequest with context injected into the system message.
     * The caller passes this to a ModelProvider or AiService for completion.
     */
    suspend fun query(
        queryText: String,
        request: ModelRequest,
        filter: Map<String, String>? = null,
    ): ModelRequest
}
```

#### GeminiProvider (tramai-gemini)

```kotlin
package dev.tramai.gemini

/**
 * Google Gemini model provider.
 *
 * Supports: gemini-2.0-flash, gemini-2.0-pro, gemini-2.5-pro
 * Structured output via responseSchema.
 * Streaming via server-sent events.
 */
class GeminiProvider(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : ModelProvider, StreamCapable {

    override suspend fun complete(request: ModelRequest): ModelResponse
    override suspend fun stream(request: ModelRequest): Flow<StreamChunk>
    override fun providerId(): String = "gemini"

    companion object {
        const val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/**
 * Provider configuration resolved from properties or constructor args.
 */
data class GeminiConfig(
    val apiKey: String? = null,
    val apiKeySecretRef: String? = null,
    val baseUrl: String = GeminiProvider.DEFAULT_BASE_URL,
)
```

### Google API Mapping

| Tramai Concept | Gemini API Equivalent |
|---|---|
| ModelRequest.model | `models/{model}:generateContent` |
| SYSTEM message | `system_instruction` field |
| USER message | `contents[{role: "user"}]` |
| ASSISTANT message | `contents[{role: "model"}]` |
| TOOL message | `contents[{role: "function"}]` |
| ToolDefinition | `tools[{function_declarations[...]}]` |
| Structured output | `generationConfig.responseMimeType: "application/json"` + `responseSchema` |
| Streaming | `models/{model}:streamGenerateContent` |
| Image content | `inlineData { mimeType, data }` within content parts |

### FinishReason Mapping

| Gemini Finish Reason | Tramai FinishReason |
|---|---|
| `STOP` | `STOP` |
| `MAX_TOKENS` | `LENGTH` |
| `SAFETY` | `CONTENT_FILTER` |
| `RECITATION` | `CONTENT_FILTER` |
| `OTHER` | `OTHER` |

### Module Dependency Graph (Compile-Time)

```
tramai-core
  ^          ^
  |          |
  |          +--- tramai-vectorstore-spi
  |
tramai-embedding
  ^
  |
tramai-rag ------ tramai-vectorstore-spi
  ^
  |
  +--- tramai-orchestration (optional integration, runtime)

tramai-gemini --> tramai-core
```

Key points:
- `tramai-embedding` and `tramai-vectorstore-spi` are independent SPIs. Both depend only on `tramai-core`.
- `tramai-rag` depends on both `tramai-embedding` and `tramai-vectorstore-spi`, plus `tramai-core`.
- No module depends on `tramai-rag` at compile time. `tramai-rag` is an optional convenience module.

### Relationship with Existing Modules

- `tramai-embedding` and `tramai-vectorstore-spi` sit alongside `tramai-core` as foundational SPIs.
- `tramai-rag` may be composed into `tramai-orchestration` workflows via aiStep with context-injected requests.
- `tramai-gemini` registers in the provider registry like `tramai-openai`, `tramai-anthropic`, and `tramai-ollama`.
- Vision content (`ContentPart`) requires changes to `tramai-core`'s `Message` model and updates to existing provider modules (`tramai-openai`, `tramai-anthropic`, `tramai-ollama`) to handle `ImagePart`.

### Usage Example: RAG Pipeline with Engine

```kotlin
// Index a document
val pipeline = RagPipelineBuilder()
    .apply {
        loader = FileDocumentLoader()
        splitter = RecursiveCharacterChunker(chunkSize = 500, chunkOverlap = 50)
        embeddingModel = openAiEmbeddingModel
        vectorStore = chromaVectorStore
    }
    .build()

// Index phase: load, split, embed, upsert
val chunkCount = pipeline.index("docs/knowledge-base.txt")

// Query phase: embed query, search, inject context, then execute via engine
val request = ModelRequest(
    model = "gemini-2.0-flash",
    messages = listOf(Message(MessageRole.USER, "What is Tramai?")),
)
val enrichedRequest = pipeline.query("What is Tramai?", request)
val response = modelProvider.complete(enrichedRequest)
```

### Build Configuration

New modules must be registered in `settings.gradle.kts`:

```kotlin
include(
    "tramai-embedding",
    "tramai-vectorstore-spi",
    "tramai-vectorstore-chroma",
    "tramai-vectorstore-pgvector",
    "tramai-rag",
    "tramai-gemini",
)
```

Each new module should follow the existing `build.gradle.kts` pattern from `tramai-openai` (java-library plugin, Kotlin JVM, coroutines, jackson as needed).

The BOM (`tramai-bom`) should include all new modules for version alignment.

## Acceptance Criteria

### Embedding SPI
1. `EmbeddingModel` interface compiles with `embed` and `embedAll` suspend functions.
2. `OpenAiEmbeddingModel` successfully embeds text against the OpenAI API (integration-test gated).
3. `OllamaEmbeddingModel` successfully embeds text against a local Ollama instance (integration-test gated).
4. Dimension reporting via `dimensions()` returns the correct value for each provider.

### Vector Store SPI + Adapters
5. `VectorStore` interface compiles with `upsert`, `search`, `delete`, `listCollections`.
6. A test in-memory implementation of `VectorStore` passes all SPI contract tests.
7. `ChromaVectorStore` upserts vectors and returns search results (integration-test gated against a Chroma instance).
8. `PgVectorStore` upserts vectors and returns search results (integration-test gated against a Postgres+pgvector instance).
9. Both adapters handle empty collections and missing vectors gracefully.

### RAG Pipeline
10. `DocumentLoader` loads text from both file paths and HTTP URLs.
11. All three chunker strategies produce correctly sized chunks with proper overlap.
12. `RagPipeline.index()` loads, splits, embeds, and upserts chunks into the vector store, returning the chunk count.
13. `RagPipeline.query()` embeds a query string, searches the store, and injects context into a `ModelRequest`, preserving the original messages.

### Google Gemini Provider
14. `GeminiProvider` executes a completion successfully against the Gemini API (integration-test gated with live API key).
15. `GeminiProvider` executes a streaming completion successfully.
16. Structured output via `response_mime_type: "application/json"` returns valid JSON in the content field.
17. Tool calling works: model requests a tool, and the result is returned in a follow-up message.
18. Error mapping handles 429 rate limits, 400 bad requests, and 403 auth errors distinctly.
19. `FinishReason.STOP`, `LENGTH`, and `CONTENT_FILTER` are mapped correctly from Gemini's response.

### Vision Content Support
20. `ContentPart` sealed interface compiles with `TextPart` and `ImagePart` variants.
21. `Message` with both `content` and `contentParts` is backward-compatible (existing code using `content` alone continues to work).
22. OpenAI provider maps `ImagePart` to a content-array message with data URIs.
23. Anthropic provider maps `ImagePart` to a content-block message with base64 media.
24. Gemini provider maps `ImagePart` to `inlineData` parts.

## Risks and Follow-Ups

- **Qdrant adapter** is deferred to a follow-up spec once Chroma and PGVector are stable.
- **Vector dimension mismatch** is a runtime error — there is no compile-time safety. Consider an index-registry pattern in a follow-up.
- **Gemini Vertex AI** support (OAuth2, GCP project configuration) is deferred. The configurable base URL allows early adopters to point at the Vertex endpoint, but auth will not work without additional implementation.
- **Reranking** (cross-encoder or Cohere Rerank) is intentionally excluded. A follow-up spec should add a `Reranker` SPI between retrieval and context injection.
- **Multi-modal RAG** (images in documents, hybrid search) is out of scope for the initial design.
- **Chroma auth** (API key, token-based) is not covered in v1. The adapter assumes an unauthenticated local Chroma instance.
- **PGVector index configuration** (HNSW/IVFFlat parameters) is exposed as optional config but not auto-tuned.
- **Embedding caching** is not part of this spec. A future improvement could cache embeddings by text hash to reduce API costs.
- **Spring Boot auto-configuration** for `tramai-embedding`, `tramai-vectorstore-*`, `tramai-rag`, and `tramai-gemini` should be added in a follow-up to `TramaiProperties`.
