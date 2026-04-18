# SPEC-009: Streaming Responses

- Status: proposed
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: Phase 4 / post-0.1.0
- Related ADRs: [ADR-013](../adr/adr-013.md), [ADR-001](../adr/adr-001.md), [ADR-008](../adr/adr-008.md)
- Related docs: [Roadmap Summary](../roadmap.md), [Current Limitations](../reference/limitations.md)

## Problem

Aurora currently supports only request/response-style completion. That is sufficient for many backend workflows, but it prevents low-latency UI use cases, token-by-token progressive rendering, and early cancellation of long-running responses.

## Scope

- streaming support for raw text responses
- provider contract changes needed for streaming execution
- engine orchestration for streaming operations
- cancellation, completion, and observability expectations for streaming calls
- explicit Kotlin-first streaming surface

## Non-Goals

- streamed structured output in the first streaming milestone
- tool calling during the first streaming milestone
- memory or conversation state management
- solving every provider's transport quirk in a single abstraction layer
- auto-generated Java streaming wrappers in the first pass

## Functional Requirements

- Aurora must support streaming operations as a first-class execution path rather than as an afterthought on top of buffered completion.
- The initial streaming milestone must be limited to raw text streaming rather than streamed structured output.
- The engine must preserve ownership of orchestration, cancellation, and terminal failure behavior for streaming calls.
- Streaming operations must surface incremental text events through a dedicated streaming contract rather than by concatenating hidden internal buffers.
- Provider modules must be able to emit stream chunks and a terminal completion signal through a shared contract.
- Streaming cancellation must stop further provider work promptly when the consumer stops the stream.
- Observability for streaming calls must preserve operation identity and capture completion or cancellation outcomes.

## Quality Requirements

- The streaming abstraction must be small enough for future providers to implement.
- The first streaming design must not force non-streaming users to pay additional complexity costs.
- The library must avoid promising streamed structured output before it has a coherent validation and retry story.
- The public contract should leave room for richer future streaming events without forcing a breaking redesign immediately after launch.

## Design Notes

- Kotlin should be the primary streaming surface in the first milestone.
- Java-facing streaming should be treated as a follow-up design once the Kotlin streaming contract is validated.
- The streaming path should stay aligned with Aurora's interface-method abstraction rather than introducing a separate chain-style API.
- The first streaming milestone should bias toward correctness and cancellation semantics over provider feature maximalism.

## Acceptance Criteria

- A streaming operation can emit incremental text from a provider without buffering the full response first.
- Consumer cancellation stops the underlying provider stream.
- Non-streaming operations continue to work unchanged.
- Streaming behavior is covered by automated tests for chunk delivery, cancellation, and terminal completion.

## Risks and Follow-Ups

- Providers expose streaming differently, so the shared contract must avoid accidental bias toward a single provider's protocol.
- Streamed structured output may require a later spec rather than an extension of this first milestone.
