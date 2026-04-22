# Current Limitations

This page is intentionally blunt. It documents what TramAI does not do yet.

## Status Level

TramAI is a strong alpha moving toward `0.1.0`.

It is usable for:

- typed service-style AI integration
- structured extraction and classification
- local and cloud provider experiments
- Spring Boot and standalone integration
- tests with deterministic fake providers

It is not yet a production-complete `1.0`.

## Not Implemented Yet

These features are not implemented in the current runtime:

- conversation memory
- generated proxy code or KSP support
- provider-native structured output optimizations

For the explicitly frozen first-release scope, see [Release 0.1.0 Scope and Checklist](./release-0.1.0.md).

## Partially Implemented Or Reserved

These concepts exist in the API shape or planning docs but are not fully realized:

- OpenAI/Codex auth-file support exists, but it is experimental
- streaming failover retries only before the first emitted token; TramAI does not attempt partial mid-stream recovery across providers
- bundled Vault and AWS Secrets Manager resolvers ship in `tramai-spring`; standalone usage still resolves secrets explicitly before provider construction
- `tramai-orchestration` is stable, but it remains intentionally bounded to explicit workflows, step-boundary checkpointing, and optional lease-aware coordination

## Practical Consequences

Before using TramAI in a serious service, assume you still need to make decisions about:

- how aggressive your fallback topology should be for your workload
- whether the built-in Spring secret resolvers are sufficient or you need custom secret resolution behavior
- how much provider-specific behavior you are willing to accept

## What Is Solid Already

These parts are already coherent and tested:

- proxy generation
- structured-output schema generation and retry flow
- explicit provider registry behavior
- provider retry behavior for transient failures
- per-attempt timeout enforcement
- raw text streaming with explicit terminal failure semantics
- engine-owned tool calling
- standalone builder
- Spring integration
- OpenTelemetry observer seam
- OpenTelemetry metrics for attempt latency, token usage, parse failures, and engine events
- engine-owned token budget controls based on provider-reported usage
- deterministic test support

## Recommended Usage Today

TramAI is in its best shape for:

- internal tools
- developer platforms
- service-side extraction/classification workloads
- early production pilots with clear guardrails

If you need heavy agent capabilities, conversation memory, or highly autonomous multi-agent behavior, wait for future milestones or build those layers explicitly on top.
