# SPEC-003: Provider Integration and Routing

- Status: implemented
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M3
- Related ADRs: [ADR-008](../adr/adr-008.md), [ADR-010](../adr/adr-010.md)
- Related docs: [Architecture Overview](../architecture/overview.md), [Module Overview](../architecture/modules.md)

## Problem

Aurora needs real provider integrations that make the core execution path usable beyond stubs. v1 depends on at least one local-first provider and one cloud provider, plus a deterministic routing mechanism that maps operations and models to the right backend.

## Scope

- `ModelProvider` implementations for Ollama and Anthropic
- Shared HTTP execution expectations for provider modules
- Explicit provider registry and operation-level provider selection
- Provider-level timeout and retry behavior
- Integration testing strategy for local and env-gated providers

## Non-Goals

- Full multi-provider feature parity
- Streaming transport support
- Provider-specific DSLs beyond Aurora's shared operation model

## Functional Requirements

- Provider modules must implement the shared provider contract and return normalized `ModelResponse` data.
- Aurora must resolve providers through an explicit provider registry rather than model-prefix routing.
- Aurora must support deterministic model-to-provider resolution and explicit provider selection when an operation chooses a provider directly.
- Provider retries must apply to transient failures such as rate limits, service unavailability, and network timeouts.
- Authentication errors, invalid model names, and non-retryable provider errors must fail fast.
- Unknown or unregistered models must produce explicit resolution errors rather than silent fallback.
- Timeouts must be configurable per provider and overridable per operation.
- Integration tests must exist for Ollama and Anthropic, with execution guarded appropriately for CI and local environments.

## Quality Requirements

- Provider modules should be coroutine-native and avoid forcing blocking I/O into the engine.
- Retries must use bounded exponential backoff.
- Error mapping should preserve enough raw provider context for debugging without leaking provider-specific types into the core API.
- Registry behavior must remain explicit, testable, and free of hidden fallback rules.

## Design Notes

- The registry should be the routing authority from day one rather than an eventual replacement for prefix heuristics.
- Routing behavior should remain isolated from provider implementations so registry logic can evolve without changing provider modules.
- The provider contract should remain small enough for future community providers to implement without depending on framework modules.

## Acceptance Criteria

- A real `@AiService` operation executes successfully against Ollama and Anthropic.
- Explicit registry resolution selects the expected provider for registered models and explicit provider selections.
- Retry and timeout behavior are covered by automated tests at the provider or engine integration level.

## Risks and Follow-Ups

- Registry configuration needs a clean consumer-facing API.
- Provider response normalization may need extension fields later for advanced capabilities.
- Implementation already exceeded the original minimal milestone by adding OpenAI and OpenAI-compatible providers early. That broader provider surface should be reflected in later planning rather than treated as the new minimum for every future milestone.
