# Current Limitations

This page is intentionally blunt. It documents what Tramai does not do yet.

## Status Level

Tramai is a strong alpha.

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
- streaming failover retries only before the first emitted token; Tramai does not attempt partial mid-stream recovery across providers
- secret references are extensible through `SecretValueResolver`, but bundled AWS/Vault resolvers are not shipped yet

## Practical Consequences

Before using Tramai in a serious service, assume you still need to make decisions about:

- how aggressive your fallback topology should be for your workload
- whether you want custom cloud secret resolvers beyond `env:` and `file:`
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
- OpenTelemetry metrics for attempt latency, token usage, parse failures, and engine events
- engine-owned token budget controls based on provider-reported usage
- deterministic test support

## Recommended Usage Today

Tramai is in its best shape for:

- internal tools
- developer platforms
- service-side extraction/classification workloads
- early production pilots with clear guardrails

If you need heavy agent capabilities, streaming-first UI support, or mature provider policy handling, wait for future milestones or build those layers explicitly on top.
