# SPEC-001: Core Engine and Proxy Execution

- Status: implemented
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M1
- Related ADRs: [ADR-001](../adr/adr-001.md), [ADR-002](../adr/adr-002.md), [ADR-008](../adr/adr-008.md), [ADR-009](../adr/adr-009.md)
- Related docs: [Architecture Overview](../architecture/overview.md), [Module Overview](../architecture/modules.md)

## Problem

Aurora needs a minimal but correct execution core that turns annotated interface methods into working AI operations. Without this layer, the primary product abstraction does not exist.

## Scope

- `@AiService`, `@Operation`, and `@SystemPrompt` as the initial annotation surface
- Runtime proxy generation for annotated interfaces
- Method dispatch based on return type and suspend awareness
- Shared request and response contracts for model execution
- Base exception hierarchy for engine-level failures

## Non-Goals

- Structured output parsing and validation
- Real network provider implementations
- OpenTelemetry instrumentation
- Framework adapters
- Streaming, tool calling, memory, or agent workflows

## Functional Requirements

- Aurora must validate that only annotated interfaces are turned into AI service proxies.
- Each `@Operation` method must resolve its prompt, model, and method signature metadata into an executable operation descriptor.
- The runtime must detect whether a method is suspend-based and dispatch accordingly.
- Return types must be classified at least into `String`, `Unit`, and structured non-primitive object returns.
- The engine must expose a provider-facing request model and consume a provider response model.
- The engine must own operation orchestration and remain the sole owner of retry policy, even when later modules provide structured-output failure analysis.
- Startup-time configuration errors must fail fast with typed exceptions.

## Quality Requirements

- Proxy creation failures must be explicit and actionable.
- The engine must preserve a small public API surface.
- The engine must not take on parsing or validation rules that belong to `aurora-structured`.
- Unit tests must cover proxy creation, method dispatch routing, and exception propagation.
- The core design must remain compatible with future blocking wrappers for Java consumers.

## Design Notes

- The implementation should treat the operation method as the canonical source of behavior.
- Runtime proxies are sufficient for v1 and reduce early complexity compared with compile-time generation.
- The engine should isolate dispatch logic from provider implementations so later modules can add structured parsing and observability without changing the consumer-facing abstraction.
- The engine-structured seam must keep orchestration in `aurora-engine` and structured analysis in `aurora-structured`.

## Acceptance Criteria

- A stub `ModelProvider` can back an `@AiService` proxy and return a raw `String` from a suspend method.
- Unsupported or malformed service definitions fail with a typed configuration or engine exception.
- Unit tests cover return type routing and suspend detection behavior.
- The engine design leaves room for a later structured result contract without embedding parse logic into the engine.

## Risks and Follow-Ups

- Reflection-heavy code may later complicate native image support.
- Method metadata modeling must stay stable enough to support later structured output and observability hooks.
