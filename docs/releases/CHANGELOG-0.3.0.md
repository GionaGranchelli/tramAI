# 0.3.0 — Memory, Multimodal, RAG, and Provider Expansion

0.3.0 is the repository milestone that expanded TramAI beyond the original typed service, provider, and orchestration surfaces into memory, multimodal input, RAG, vector stores, and a broader provider matrix.

## New Modules

### tramai-memory
Production-ready chat memory primitives for multi-turn AI services.

- `MessageWindowChatMemory` for bounded recent-message windows
- `TokenAwareChatMemory` for token-budget-aware conversation history
- `PersistentChatMemory` for memory backed by an external store
- system-message deduplication and deterministic eviction behavior

### tramai-memory-store
External persistence SPI support for chat memory.

- durable conversation storage boundary
- JDBC-backed store support
- table DDL helpers for operational setup

### tramai-rag
Retrieval-augmented generation pipeline support.

- document loading
- chunking
- retrieval
- context injection into AI operations

### tramai-embedding
Embedding model SPI and provider-backed embedding implementations.

- OpenAI embedding model support
- Ollama embedding model support
- registry support for embedding use cases

### tramai-vectorstore-spi
Vector store abstractions for semantic retrieval.

- text embedding storage contracts
- metadata-aware query models
- in-memory implementation for tests and simple local flows

### tramai-vectorstore-chroma
ChromaDB vector store adapter.

- collection creation and lookup
- document upsert
- similarity query support

### tramai-vectorstore-pgvector
PostgreSQL pgvector adapter.

- relational vector storage
- pgvector-backed similarity queries
- fit for applications already using PostgreSQL

### tramai-azure-openai
Azure OpenAI provider integration.

- Azure endpoint support
- API-key authentication
- model request and response mapping
- usage metric extraction

### tramai-bedrock
AWS Bedrock provider integration.

- Bedrock runtime request mapping
- provider error normalization
- image-capable request support where supported by the target model

### tramai-gemini
Google Gemini provider integration.

- Gemini request and response mapping
- multimodal content support
- provider error normalization

### tramai-deepseek
DeepSeek provider integration.

- DeepSeek chat API support
- provider error normalization
- OpenAI-style request semantics where compatible

## Core Features

### Multimodal Content

Core message modeling now supports additive content parts instead of only plain text.

- `ContentPart`
- `TextPart`
- `ImagePart`
- `ImageUrlContent`
- `ImageDetail` with `LOW`, `HIGH`, and `AUTO`

The engine validates provider capabilities before execution so image input sent to a non-vision provider fails explicitly.

### Image Downloading

TramAI includes built-in image download support for URL-backed image content.

- 20MB response-size limit
- 10s connect timeout
- 30s request timeout
- MIME detection from URL extension

### Usage Metrics

Usage reporting was expanded for multimodal workloads.

- image count tracking
- estimated image token tracking
- provider-specific usage normalization

## Provider Updates

The existing providers were updated for multimodal serialization and capability reporting.

- OpenAI
- Azure OpenAI
- Anthropic
- Bedrock
- Gemini
- Ollama
- DeepSeek

## Observability Updates

Worker and distributed execution observability gained broader event coverage.

- shutdown started
- drain progress
- lease renewed
- worker heartbeat
- workflow abandoned
- graceful shutdown bounds

## Notes

- TramAI `0.3.x` targets Java `21+`.
- Structured output remains the default contract for non-`String` return types.
- Provider routing remains explicit and registry-based.
- Runtime and platform modules remain optional.
