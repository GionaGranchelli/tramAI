# Module Overview

The current planned module layout is based on the roadmap and design documents. Names and exact packaging may evolve, but the separation of concerns is already clear.

## Planned Modules

- `aurora-core`: annotations, shared contracts, request and response models, common exceptions
- `aurora-engine`: proxy generation, method dispatch, operation execution, retry and error handling
- `aurora-structured`: schema generation, response parsing, validation integration, structured retry feedback
- `aurora-observability`: OpenTelemetry integration and semantic convention mapping
- `aurora-anthropic`: Anthropic provider implementation
- `aurora-ollama`: Ollama provider implementation
- `aurora-openai`: OpenAI provider implementation, planned for later milestones
- `aurora-standalone`: minimal framework-free entry point and builder APIs
- `aurora-spring`: Spring Boot autoconfiguration and bean registration
- `aurora-testing`: mock providers, assertion helpers, and test support
- `aurora-bom`: BOM for consumer dependency management

## Dependency Direction

- `aurora-core` should remain as close to zero-dependency as practical.
- `aurora-engine` depends on `aurora-core`.
- `aurora-structured` depends on `aurora-core` and integrates with the engine's execution flow.
- `aurora-observability` is optional and should remain decoupled from the core happy path when OpenTelemetry is absent.
- Provider modules depend on shared request and response contracts and plug into the engine through the provider interface.
- `aurora-standalone` composes the minimal runtime for non-framework users: `aurora-core`, `aurora-engine`, and `aurora-structured`.
- Observability should be added by depending on `aurora-observability`, not by making `aurora-standalone` transitively heavier.
- Framework adapters such as `aurora-spring` should be thin integration layers, not alternate runtimes.

## Design Constraint

The same core operation semantics should apply everywhere:

- standalone applications
- Spring Boot applications
- future framework adapters
- tests using mock providers
