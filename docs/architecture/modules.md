# Module Overview

These are the current repository modules and their intended boundaries.

## Current Modules

- `tramai-core`: annotations, shared contracts, request and response models, common exceptions
- `tramai-engine`: proxy generation, method dispatch, operation execution, retry and error handling
- `tramai-structured`: schema generation, response parsing, validation integration, structured retry feedback
- `tramai-observability`: OpenTelemetry integration and semantic convention mapping
- `tramai-orchestration`: typed workflow composition, checkpoint/resume coordination, and optional lease-aware execution, currently experimental
- `tramai-anthropic`: Anthropic provider implementation
- `tramai-ollama`: Ollama provider implementation
- `tramai-openai`: OpenAI and OpenAI-compatible provider implementation
- `tramai-standalone`: minimal framework-free entry point and builder APIs
- `tramai-spring`: Spring Boot autoconfiguration and bean registration
- `tramai-testing`: mock providers, assertion helpers, and test support
- `tramai-bom`: BOM for consumer dependency management

## Dependency Direction

- `tramai-core` should remain as close to zero-dependency as practical.
- `tramai-engine` depends on `tramai-core`.
- `tramai-structured` depends on `tramai-core` and integrates with the engine's execution flow.
- `tramai-observability` is optional and should remain decoupled from the core happy path when OpenTelemetry is absent.
- Provider modules depend on shared request and response contracts and plug into the engine through the provider interface.
- `tramai-standalone` composes the minimal runtime for non-framework users: `tramai-core`, `tramai-engine`, and `tramai-structured`.
- Observability should be added by depending on `tramai-observability`, not by making `tramai-standalone` transitively heavier.
- Framework adapters such as `tramai-spring` should be thin integration layers, not alternate runtimes.

## Design Constraint

The same core operation semantics should apply everywhere:

- standalone applications
- Spring Boot applications
- future framework adapters
- tests using mock providers
