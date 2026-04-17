# SPEC-003: Provider Integration and Routing

- Status: proposed
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M3
- Related ADRs: [ADR-007](../adr/adr-007.md), [ADR-008](../adr/adr-008.md)
- Related docs: [Architecture Overview](../architecture/overview.md), [Module Overview](../architecture/modules.md)

## Problem

Aurora needs real provider integrations that make the core execution path usable beyond stubs. v1 depends on at least one local-first provider and one cloud provider, plus routing logic that maps model names to the right backend.

## Scope

- `ModelProvider` implementations for Ollama and Anthropic
- Shared HTTP execution expectations for provider modules
- Model-prefix provider resolution with explicit override support
- Provider-level timeout and retry behavior
- Integration testing strategy for local and env-gated providers

## Non-Goals

- Full multi-provider feature parity
- OpenAI support in this milestone
- Streaming transport support
- Provider-specific DSLs beyond Aurora's shared operation model

## Functional Requirements

- Provider modules must implement the shared provider contract and return normalized `ModelResponse` data.
- Aurora must route model names by prefix unless an explicit provider override is configured.
- Provider retries must apply to transient failures such as rate limits, service unavailability, and network timeouts.
- Authentication errors, invalid model names, and non-retryable provider errors must fail fast.
- Timeouts must be configurable per provider and overridable per operation.
- Integration tests must exist for Ollama and Anthropic, with execution guarded appropriately for CI and local environments.

## Quality Requirements

- Provider modules should be coroutine-native and avoid forcing blocking I/O into the engine.
- Retries must use bounded exponential backoff.
- Error mapping should preserve enough raw provider context for debugging without leaking provider-specific types into the core API.

## Design Notes

- Prefix-based routing is acceptable for v1 because it keeps the consumer model simple.
- The routing design should still isolate registry behavior from provider implementations so it can evolve later.
- The provider contract should remain small enough for future community providers to implement without depending on framework modules.

## Acceptance Criteria

- A real `@AiService` operation executes successfully against Ollama and Anthropic.
- Model-prefix routing selects the expected provider for `claude-*` and common local model families.
- Retry and timeout behavior are covered by automated tests at the provider or engine integration level.

## Risks and Follow-Ups

- Model naming conventions may drift and force registry redesign.
- Provider response normalization may need extension fields later for advanced capabilities.
