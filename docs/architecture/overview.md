# Architecture Overview

TramAI is a structured-first, observability-native AI workflow library for the JVM. The core product goal is to let application code interact with AI through typed interface methods instead of framework-specific chains, agents, or prompt orchestration objects.

## Core Flow

1. Application code defines an `@AiService` interface.
2. TramAI generates a proxy for that interface at startup.
3. A method call is translated into an AI operation.
4. Inputs are rendered into model messages.
5. If the return type is structured, TramAI generates or loads a cached schema and validates the response.
6. The provider call is wrapped in observability instrumentation when OpenTelemetry is available.
7. The provider returns raw model output plus metadata.
8. TramAI either returns raw text, streams chunks, or parses and validates structured output.
9. Parse failures enter a retry loop with validation feedback.

## Architectural Priorities

- Typed contracts over prompt-heavy application code
- Framework-agnostic core with optional adapters
- Automatic observability with OpenTelemetry semantic conventions
- Kotlin-first APIs with Java-compatible entry points
- Explicit module boundaries so higher-level runtime features do not leak into the core library contract

## Major Layers

- Application layer: consumer-defined `@AiService` interfaces
- Proxy layer: runtime-generated implementations that intercept method calls
- Operation engine: prompt rendering, provider dispatch, streaming, tool calling, parsing, retry, and error handling
- Observability layer: OpenTelemetry spans, metrics, and semantic attributes
- Provider layer: Anthropic, Ollama, OpenAI, and OpenAI-compatible providers
- Optional orchestration layer: typed workflow coordination above `tramai-engine`
- Optional retrieval and memory layer: RAG, embeddings, vector stores, and bounded chat memory
- Optional runtime/platform layer: scheduling, HTTP APIs, MCP exposure, tenancy, and dashboard operations

## Core Library Boundaries

The core `@AiService` runtime deliberately does not turn application code into an autonomous agent framework. In particular:

- autonomous agent loops
- hidden cross-step orchestration
- peer-to-peer agent chat
- fine-tuning workflows

Those concerns, where they exist in this repository, live in optional modules above the core library surface. The design goal is composability: application teams can use typed AI services alone, or add memory, RAG, orchestration, scheduling, server, MCP, or platform layers without changing the core execution model.
