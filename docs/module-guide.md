# TramAI Module Guide

> **Purpose:** Help you choose the right TramAI modules for your JVM project.
> **Reading time:** L1 (30s) → L2 (10min) → L3 (20min)
> **Build coordinates:** `dev.tramai:<module>:0.3.1`

This guide covers both published consumer modules and repository runtime/platform modules. Treat the latter as opt-in operational surfaces, not as the default starting point for every application.

---

## L1: Quick Start — Decision Flowchart

```
┌───────────────────────────────────────────┐
│  I want to add AI to my JVM application   │
└─────────────────────┬─────────────────────┘
                      │
                      ▼
┌───────────────────────────────────────────┐
│  Which framework are you using?            │
├─────────────────────┬─────────────────────┤
│  Spring Boot        │  None / Ktor / Javalin / CLI │
└─────────┬───────────┴──────────┬──────────┘
          │                      │
          ▼                      ▼
  Add tramai-spring       Add tramai-standalone
  (auto-discovers         (builder API, no
   @AiService beans)       framework needed)
          │                      │
          └──────────┬───────────┘
                     ▼
    ┌────────────────────────────────────┐
    │  Which LLM provider?               │
    ├──────────┬──────────┬──────────────┤
    │  Local   │  OpenAI  │  Anthropic   │
    │  Ollama  │  API     │  Claude API  │
    ├──────────┼──────────┼──────────────┤
    │ ollama   │ openai   │ anthropic    │
    └──────────┴──────────┴──────────────┘
                     │
                     ▼
    ┌────────────────────────────────────┐
    │  Do you need structured output?    │
    │  (return types other than String)  │
    ├────────────────┬───────────────────┤
    │  YES           │  NO               │
    ├────────────────┴───────────────────┤
    │  Add tramai-structured             │
    └────────────────┬───────────────────┘
                     │
                     ▼
    ┌────────────────────────────────────┐
    │  Do you need conversation history? │
    ├────────────────┬───────────────────┤
    │  YES           │  NO               │
    ├────────────────┴───────────────────┤
    │  Add tramai-memory                 │
    └────────────────┬───────────────────┘
                     │
                     ▼
    ┌────────────────────────────────────┐
    │  Do you need multi-step workflows? │
    ├────────────────┬───────────────────┤
    │  YES           │  NO               │
    ├────────────────┴───────────────────┤
    │  Add tramai-orchestration          │
    └────────────────┬───────────────────┘
                     │
                     ▼
    ┌────────────────────────────────────┐
    │  Production deployment needs?      │
    ├──────────┬──────────┬──────────────┤
    │Scheduling│ REST API │ Multi-tenant │
    ├──────────┼──────────┼──────────────┤
    │scheduler │  server  │  platform    │
    └──────────┴──────────┴──────────────┘
```

### Quick Decision Table

| Your situation | Required modules | Optional modules |
|---------------|-----------------|-----------------|
| Local model, quick script | core, engine, ollama | structured, testing |
| Spring Boot + OpenAI | core, engine, spring, openai | structured, observability, testing |
| Multi-turn Chat Bot | core, engine, openai, memory | memory-store |
| Document Q&A (RAG) | core, engine, rag, embedding | vectorstore-chroma, vectorstore-pgvector |
| Production REST API | core, engine, spring, openai, server, scheduler | platform, mcp |
| Data extraction pipeline | core, engine, ollama, structured | orchestration |
| Multi-step agent workflow | core, engine, ollama, orchestration | scheduler, server |
| SaaS / multi-tenant | core, engine, server, orchestration, platform | dashboard |

### Minimal Dependency Template

```kotlin
// build.gradle.kts — always start here
repositories {
    mavenCentral()
}

dependencies {
    // Always needed
    implementation("dev.tramai:tramai-core:0.3.1")
    implementation("dev.tramai:tramai-engine:0.3.1")

    // Pick ONE provider
    implementation("dev.tramai:tramai-ollama:0.3.1")   // local models
    // implementation("dev.tramai:tramai-openai:0.3.1") // OpenAI / compatible APIs
    // implementation("dev.tramai:tramai-anthropic:0.3.1") // Claude

    // Optional: structured output
    implementation("dev.tramai:tramai-structured:0.3.1")

    // Pick adapter
    // implementation("dev.tramai:tramai-standalone:0.3.1") // No framework
    // implementation("dev.tramai:tramai-spring:0.3.1")     // Spring Boot
}
```

---

## L2: Module Reference

### Core & Logic Modules

| Module | Layer | Purpose | Depends on | Artifact | When to use | When NOT to use |
|--------|-------|---------|-----------|----------|-------------|----------------|
| `tramai-core` | Core | Annotations (`@AiService`, etc.), Models (`Message`, `ContentPart`), SPIs (`ModelProvider`, `Capability`), UsageMetrics. | none | `tramai-core:0.3.1` | **Always.** Every TramAI project needs this. | Never. |
| `tramai-engine` | Core | Proxy generation, Method dispatch, Capability validation, Retry, Tool registries. | core | `tramai-engine:0.3.1` | **Always** in production. | Never. |
| `tramai-structured` | Core | JSON Schema generation from Kotlin types → validate → retry on failure. | core | `tramai-structured:0.3.1` | Method returns `data class`, `enum`, `List<T>`, etc. | Only returning `String`. |
| `tramai-memory` | Core | Multi-turn chat persistence: `TokenAwareChatMemory`, `PersistentChatMemory`. | core | `tramai-memory:0.3.1` | Conversational agents, multi-turn contexts. | Stateless endpoints. |
| `tramai-memory-store` | Core | SPI for external persistence of conversation history. | core | `tramai-memory-store:0.3.1` | When `ChatMemory` must outlive JVM restarts. | In-memory use cases. |
| `tramai-rag` | Core | Pipeline for ingestion, chunking, retrieval, and RAG context injection. | core | `tramai-rag:0.3.1` | Document Q&A or internal knowledge base flows. | No document loading. |
| `tramai-embedding`| Core | Core SPI for text embedding models. | core | `tramai-embedding:0.3.1` | When using RAG or `vectorstore` modules. | No RAG usage. |
| `tramai-vectorstore-spi`| Core | Interfaces for storing and querying text embeddings and metadata. | embedding | `tramai-vectorstore-spi:0.3.1` | Any semantic search feature. | No RAG usage. |
| `tramai-vectorstore-chroma`| Impl | ChromaDB implementation of the vector store SPI. | vectorstore-spi | `tramai-vectorstore-chroma:0.3.1` | You want to run Chroma DB locally or remotely. | Postgres/No RAG. |
| `tramai-vectorstore-pgvector`| Impl | PostgreSQL pgvector implementation of the vector store SPI. | vectorstore-spi | `tramai-vectorstore-pgvector:0.3.1` | You are using Postgres for your application data. | Chroma/No RAG. |
| `tramai-observability` | Tooling| OpenTelemetry spans, Worker Events (`onLeaseRenewed`, etc.). | core, orchestration | `tramai-observability:0.3.1` | Distributed tracing, metrics, audit trails. | No OTEL infrastructure. |
| `tramai-testing` | Tooling | Mock providers, Assertion helpers, Failure simulation, Request recording. | core | `tramai-testing:0.3.1` | **Always in tests.** | Never in production. |
| `tramai-bom` | Tooling | Version alignment BOM. Zero code. | none | `tramai-bom:0.3.1` | Multi-module projects. | Single-module project. |

### Model Providers

| Module | Purpose | Requires API Key? | Use Cases |
|--------|---------|--------------------|-----------|
| `tramai-ollama` | Local LLM hosting. Supports text and images. | No | Dev, privacy, air-gapped systems |
| `tramai-openai` | OpenAI + compatible APIs (Together, Groq, etc.). | Yes | Standard GPT-4o deployments |
| `tramai-azure-openai`| Azure OpenAI API endpoints. | Yes | Enterprise internal deployments |
| `tramai-anthropic` | Anthropic Messages API (Claude). | Yes | High-intelligence routing, coding |
| `tramai-bedrock` | AWS Bedrock API interface. | Yes (AWS IAM) | Projects hosted on AWS |
| `tramai-gemini` | Google Gemini via REST endpoints. | Yes | Deep Google Cloud integrations |
| `tramai-deepseek` | DeepSeek API wrapper. | Yes | Math, logic, alternative providers |

### Infrastructure Adapters

| Module | Purpose | When to use |
|--------|---------|-------------|
| `tramai-standalone` | Framework-free entry point via `Tramai.builder()`. | CLI tools, library code, Ktor, http4k, plain Kotlin. |
| `tramai-spring` | Spring Boot auto-configuration (`@EnableTramai`). | Spring Boot 3.x applications. |

### Platform Modules

| Module | Layer | Purpose | Depends on | Artifact | When to use | When NOT to use |
|--------|-------|---------|-----------|----------|-------------|----------------|
| `tramai-orchestration` | Orchestration | Multi-step workflows (`aiStep`, `parallelStep`, etc.). Distributed Worker Pool (leases, heartbeat, graceful shutdown). | core | `tramai-orchestration:0.3.1` | Chained AI calls, conditional branching, human-in-loop, distributed execution. | Simple request-response. |
| `tramai-scheduler` | Platform | Cron/delay scheduling for workflows via `ScheduledWorkflowTimer`. | orchestration | repository module | Time-based workflow triggers. | User-driven only workflows. |
| `tramai-server` | Platform | HTTP API for workflows. Webhooks, SSE streams. | orchestration, scheduler | repository module | REST access to TramAI pipelines. | Embedded usage only. |
| `tramai-mcp` | Platform | Model Context Protocol adapter. | server, structured | repository module | Exposing workflows as MCP tools. | No MCP ecosystem usage. |
| `tramai-platform` | Platform | Multi-tenancy, rate limiting, API keys, audit logs, plugins. | orchestration, server | repository module | SaaS product, heavy tenant isolation. | Single-tenant early dev. |
| `tramai-dashboard` | Platform | Vue 3 admin UI. Visual workflow debugging. | none (UI only) | repository module | Need a GUI for observability. | Headless deployments. |

---

## L3: Architecture Overview

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Annotations over YAML/JSON config** | `@AiService` + `@Operation` give you compile-time checks, refactoring support, and discoverability. No DSL to learn. |
| **Capability Enforcement** | Engine uses `supportsCapability(VISION)` before firing requests, ensuring fail-fast behavior with multimodal input. |
| **Explicit Content Sequences** | Parallel `ContentPart` lists avoid nested string replacement, natively supporting tool responses alongside text and images. |
| **Provider registry, not heuristics** | Explicit provider selection blocks fragile model-name-prefix matching. |
| **Kotlin-first, Java-friendly** | Uses Kotlin features internally, provides JVM-friendly overloads explicitly. |
| **Opt-in observability** | Worker Observer and Workflow Span listeners are completely transparent via SPI interfaces. |
