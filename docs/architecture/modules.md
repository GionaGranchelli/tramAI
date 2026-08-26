# Module Overview

These are the current repository modules and their intended boundaries.

The important distinction is not just module name, but module tier:

- stable consumer modules: normal application dependencies with the clearest compatibility expectations
- runtime/platform modules: operational surfaces that exist in the repo but evolve faster

## Published Consumer Modules

- `tramai-core`: annotations, shared contracts, request and response models, common exceptions
- `tramai-engine`: proxy generation, method dispatch, operation execution, retry and error handling
- `tramai-structured`: schema generation, response parsing, validation integration, structured retry feedback
- `tramai-observability`: OpenTelemetry integration and semantic convention mapping
- `tramai-orchestration`: typed workflow composition, checkpoint/resume coordination, and optional lease-aware execution
- `tramai-anthropic`: Anthropic provider implementation
- `tramai-ollama`: Ollama provider implementation
- `tramai-openai`: OpenAI and OpenAI-compatible provider implementation
- `tramai-standalone`: minimal framework-free entry point and builder APIs
- `tramai-spring-boot-starter`: unified Spring Boot starter composing `tramai-spring-core` (standard) and `tramai-spring-sovereign` (sovereign); `tramai.profile` selects the runtime
- `tramai-spring`: legacy Spring facade over `tramai-spring-core`; not the onboarding entry point
- `tramai-testing`: mock providers, assertion helpers, and test support
- `tramai-bom`: BOM for consumer dependency management
- `tramai-memory`: bounded conversation memory implementations
- `tramai-embedding`: embedding SPI and provider integrations
- `tramai-rag`: RAG pipeline and retrieval helpers
- `tramai-vectorstore-spi`: vector store abstractions
- `tramai-vectorstore-chroma`: Chroma adapter
- `tramai-vectorstore-pgvector`: pgvector adapter

## Runtime And Platform Modules

- `tramai-memory-store`: durable memory-store implementations and supporting persistence SPI
- `tramai-scheduler`: cron scheduling, delay-step timing, and durable schedule stores
- `tramai-server`: HTTP API surface, webhooks, run persistence views, OpenAPI, and SSE endpoints
- `tramai-mcp`: MCP server adapter that exposes workflows as tools
- `tramai-platform`: tenancy, API keys, rate limiting, plugin registry, and audit logging
- `tramai-dashboard`: optional Vue 3 admin UI packaged as runtime-served static assets

## Dependency Direction

- `tramai-core` should remain as close to zero-dependency as practical.
- `tramai-engine` depends on `tramai-core`.
- `tramai-structured` depends on `tramai-core` and integrates with the engine's execution flow.
- `tramai-observability` is optional and should remain decoupled from the core happy path when OpenTelemetry is absent.
- Provider modules depend on shared request and response contracts and plug into the engine through the provider interface.
- `tramai-standalone` composes the minimal runtime for non-framework users: `tramai-core`, `tramai-engine`, and `tramai-structured`.
- Observability should be added by depending on `tramai-observability`, not by making `tramai-standalone` transitively heavier.
- Framework adapters such as `tramai-spring` should be thin integration layers, not alternate runtimes.
- `tramai-orchestration` owns workflow execution semantics and persistence boundaries.
- `tramai-scheduler` sits above orchestration and should not backflow scheduling concerns into the engine.
- `tramai-server` sits above orchestration and scheduler, exposing operational APIs rather than redefining workflow semantics.
- `tramai-mcp` sits above server/orchestration concerns and should remain an adapter, not a second orchestration engine.
- `tramai-platform` owns tenancy, governance, and plugin/runtime policy concerns above the server layer.
- `tramai-dashboard` is a UI packaging module and must remain optional at runtime.

## Design Constraint

The same core operation semantics should apply everywhere:

- standalone applications
- Spring Boot applications
- future framework adapters
- tests using mock providers

That same rule extends upward: the operational modules should compose the core runtime, not fork it.
