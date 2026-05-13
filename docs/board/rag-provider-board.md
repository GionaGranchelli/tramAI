# RAG & Provider Expansion Board (SPEC-019)

This board tracks the RAG pipeline, vector store SPI, embedding SPI, Google Gemini provider, and vision content support for TramAI.

- Board owner: maintainer
- Last updated: 2026-05-13
- Related spec: [SPEC-019 RAG & Provider Expansion](../specs/spec-019-rag-and-provider-expansion.md)

## Phase Dependency Graph

```text
Phase 1 (Foundation SPIs: Embedding + Vector Store)
    └── no dependencies — can be built independently
Phase 2 (RAG Pipeline)
    └── depends on Phase 1 (Embedding SPI + Vector Store SPI)
Phase 3 (Gemini Provider + Vision)
    └── depends on Phase 1 vision content changes in tramai-core
    └── independent of Phase 2
```

## Status Legend

⬜ TODO | 🔄 IN PROGRESS | ✅ DONE | ❌ BLOCKED

---

## Phase 1 — Foundation SPIs: Embedding + Vector Store

Estimated effort: 2-3 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-056 | Define `EmbeddingModel` interface in `tramai-embedding` with `embed()` and `embedAll()` suspend functions, plus `dimensions()` reporting | §Embedding SPI | — | 0.5d | ✅ |
| TASK-057 | Define `EmbeddingModelRegistry` with provider registration, a builder pattern (following `ProviderRegistry`), and named resolution | §Embedding SPI | TASK-056 | 0.25d | ✅ |
| TASK-058 | Implement `OpenAiEmbeddingModel` with configurable model/dimensions against OpenAI Embeddings API | §Embedding SPI | TASK-056 | 0.5d | ✅ |
| TASK-059 | Implement `OllamaEmbeddingModel` for local embedding models via Ollama API | §Embedding SPI | TASK-056 | 0.5d | ✅ |
| TASK-060 | Define `VectorStore` interface in `tramai-vectorstore-spi` with `upsert()`, `search()`, `delete()`, `listCollections()` | §Vector Store SPI | — | 0.5d | ✅ |
| TASK-061 | Define `VectorEntry` and `SearchResult` data models in `tramai-vectorstore-spi` | §Vector Store SPI | — | 0.25d | ✅ |
| TASK-062 | Implement in-memory `VectorStore` test double for SPI contract testing | §Vector Store SPI | TASK-060 | 0.25d | ✅ |
| TASK-063 | Implement `ChromaVectorStore` adapter (Chroma HTTP API) | §Vector Store Adapters | TASK-060 | 0.75d | ✅ |
| TASK-064 | Implement `PgVectorStore` adapter (JDBC + pgvector extension) | §Vector Store Adapters | TASK-060 | 0.75d | ✅ |
| TASK-065 | Register new modules in `settings.gradle.kts` + `tramai-bom` + create basic `build.gradle.kts` for each | §Build Config | all above | 0.25d | ✅ |

## Phase 2 — RAG Pipeline

Estimated effort: 2-3 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-066 | Define `Document` model and `DocumentLoader` interface with file and URL loaders | §RAG Pipeline | — | 0.5d | ✅ |
| TASK-067 | Implement `RecursiveCharacterChunker`, `TokenAwareChunker`, and `FixedSizeChunker` with configurable chunk size and overlap | §RAG Pipeline | TASK-066 | 0.75d | ✅ |
| TASK-068 | Implement `RagRetriever` (embeds query, searches vector store, returns top-K results) | §RAG Pipeline | TASK-056, TASK-060 | 0.5d | ✅ |
| TASK-069 | Implement `ContextInjector` as open class that prepends context into system message or user message of `ModelRequest` | §RAG Pipeline | TASK-068 | 0.5d | ✅ |
| TASK-070 | Implement `RagPipeline` with separate `index()` and `query()` phases, bundling loader → splitter → embedder → store | §RAG Pipeline | TASK-066–069 | 0.75d | ✅ |
| TASK-071 | Add RAG pipeline tests: index roundtrip, query with context injection, empty collection, chunker edge cases | §RAG Pipeline AC 10-13 | TASK-070 | 1.0d | ✅ |

## Phase 3 — Gemini Provider + Vision Content

Estimated effort: 2-3 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-072 | Extend `tramai-core` `Message` model with `ContentPart` sealed interface (`TextPart`, `ImagePart`) — backward-compatible | §Vision Content | — | 0.5d | ✅ |
| TASK-073 | Update OpenAI provider to handle `ImagePart` → content-array with data URIs | §Vision Content | TASK-072 | 0.5d | ✅ |
| TASK-074 | Update Anthropic provider to handle `ImagePart` → content-block with base64 media | §Vision Content | TASK-072 | 0.5d | ✅ |
| TASK-075 | Implement `GeminiProvider` with text completions, streaming, structured output, tool calling | §Gemini Provider | — | 1.5d | ✅ |
| TASK-076 | Implement Gemini API error mapping (429, 400, 403 → Tramai exceptions) and finish reason mapping | §Gemini Provider | TASK-075 | 0.5d | ✅ |
| TASK-077 | Implement Gemini vision support (`ImagePart` → `inlineData` parts) | §Vision/Gemini | TASK-072, TASK-075 | 0.5d | ✅ |
| TASK-078 | Add Gemini provider tests: completion, streaming, structured output, tool calling, error mapping, vision | §Gemini AC 14-24 | TASK-075-077 | 1.0d | ✅ |

---

## Progress Tracking

| Phase | Status | Tasks |
|-------|--------|-------|
| Phase 1 — Foundation SPIs | ✅ Done | 10 |
| Phase 2 — RAG Pipeline | ✅ Done | 6 |
| Phase 3 — Gemini + Vision | ✅ Done | 7 |
| **Total** | | **23** |
