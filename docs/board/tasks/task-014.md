# TASK-014: Implement Streaming Responses

- Status: done
- Priority: high
- Primary spec: [SPEC-009](../../specs/spec-009-streaming-responses.md)
- Related ADRs: [ADR-013](../../adr/adr-013.md)
- Last updated: 2026-04-19

## Purpose

Implement raw text streaming support across the core engine and major providers.

## Implementation Summary

- Defined `StreamCapable` provider capability and `StreamChunk` message types.
- Updated `TramaiEngine` to detect and handle `Flow<StreamChunk>` return types.
- Implemented SSE (Server-Sent Events) streaming in `OpenAiProvider` and `OllamaProvider`.
- Implemented Anthropic's event-based streaming in `AnthropicProvider`.
- Modernized Spring Boot example to support asynchronous Flow streaming in controllers.

## Exit Criteria

- [x] `StreamCapable` interface defined in `tramai-core`.
- [x] `TramaiEngine` dispatches streaming calls correctly.
- [x] `OpenAiProvider` supports streaming.
- [x] `OllamaProvider` supports streaming.
- [x] `AnthropicProvider` supports streaming.
- [x] Automated tests verify chunk delivery, cancellation propagation, and terminal completion behavior.
