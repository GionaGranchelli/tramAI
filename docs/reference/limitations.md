# Current Limitations

This page is intentionally blunt. It documents what Aurora does not do yet.

## Status Level

Aurora is a strong alpha.

It is usable for:

- typed service-style AI integration
- structured extraction and classification
- local and cloud provider experiments
- Spring Boot and standalone integration
- tests with deterministic fake providers

It is not yet a production-complete `1.0`.

## Not Implemented Yet

These features are not implemented in the current runtime:

- streaming responses
- tool calling
- conversation memory
- generated proxy code or KSP support
- provider-native structured output optimizations

For the explicitly frozen first-release scope, see [Release 0.1.0 Scope and Checklist](./release-0.1.0.md).

## Partially Implemented Or Reserved

These concepts exist in the API shape or planning docs but are not fully realized:

- OpenAI/Codex auth-file support exists, but it is experimental

## Practical Consequences

Before using Aurora in a serious service, assume you still need to make decisions about:

- your own fallback strategy
- your own deployment and secret management model
- how much provider-specific behavior you are willing to accept

## What Is Solid Already

These parts are already coherent and tested:

- proxy generation
- structured-output schema generation and retry flow
- explicit provider registry behavior
- provider retry behavior for transient failures
- per-attempt timeout enforcement
- standalone builder
- Spring integration
- OpenTelemetry observer seam
- deterministic test support

## Recommended Usage Today

Aurora is in its best shape for:

- internal tools
- developer platforms
- service-side extraction/classification workloads
- early production pilots with clear guardrails

If you need heavy agent capabilities, streaming-first UI support, or mature provider policy handling, wait for future milestones or build those layers explicitly on top.
