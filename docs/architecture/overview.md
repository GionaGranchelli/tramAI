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
- Narrow v1 scope with explicit exclusions for autonomous agent loops, RAG, and hidden orchestration

## Major Layers

- Application layer: consumer-defined `@AiService` interfaces
- Proxy layer: runtime-generated implementations that intercept method calls
- Operation engine: prompt rendering, provider dispatch, streaming, tool calling, parsing, retry, and error handling
- Observability layer: OpenTelemetry spans, metrics, and semantic attributes
- Provider layer: Anthropic, Ollama, OpenAI, and OpenAI-compatible providers
- Optional orchestration layer: typed workflow coordination above `tramai-engine`

## v1 Boundaries

TramAI v1 deliberately does not include:

- RAG or vector stores
- autonomous agent loops
- conversation memory
- prompt dashboards or registries
- fine-tuning workflows
