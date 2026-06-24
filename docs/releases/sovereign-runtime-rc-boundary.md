# Sovereign Runtime Release Candidate Boundary

## Purpose

This document declares the current Sovereign Runtime Release Candidate boundary on `master`.

It is **not** a stable 1.0 API declaration.
It is **not** a Maven Central release announcement.
It is **not** a production deployment certification.

## Release Candidate Definition

The Sovereign Runtime RC is the set of TramAI capabilities that allow a JVM application to run governed AI workflows with policy enforcement, DLP, sovereign routing, replay-safe approval flows, local encrypted persistence, audit/outbox recovery, and operator-facing observability.

## Included

- Policy enforcement
- DLP and redaction
- Sovereign routing and trust zones
- Approval gates
- Replay-safe resume
- Encrypted file-backed persistence
- Audit chain
- Audit outbox persistence and dispatch
- Background worker recovery and dispatch
- Worker observer SPI
- Composite observer pipeline
- Optional Actuator worker status endpoint
- Optional Actuator worker health component
- Micrometer worker metrics
- OpenTelemetry worker metrics
- Worker observability runbook
- PromQL examples and alert examples
- Sovereign document intelligence reference workflow
- Release evidence index
- Consumer-resolution smoke test
- Canonical release-candidate verification task

## Verification

The full local release-candidate verification chain is:

```bash
./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks
```

This validates the release-candidate evidence locally. It does **not** publish remotely, create a tag, or claim Maven Central availability.

## Trying the RC

For a practical first integration path, see [Sovereign Runtime Quickstart](../guides/sovereign-runtime-quickstart.md).

For a domain-level example of how the RC applies to regulated workflows, see [Regulated Claim Triage Reference Scenario](../scenarios/regulated-claim-triage.md).

## Explicit Non-Goals

The following are intentionally **not included** in this RC:

- Stable 1.0 public API
- Maven Central release
- Production deployment certification
- Key rotation
- Broad REST/Actuator control-plane endpoints
- Production dashboard
- Complete API reference documentation

> This document describes the **original** Sovereign Runtime RC boundary established before JDBC persistence, worker coordination, and the regulated JDBC E2E proof were completed.
>
> For the **current** completed roadmap boundary, see [Sovereign Runtime Closure Boundary](./sovereign-runtime-closure-boundary.md).

## Historical Context

The original post-RC roadmap listed:
1. JDBC/database-backed persistence and outbox ✅
2. Distributed worker coordination ✅
3. Key rotation *(deferred)*
4. Production deployment guide ✅
5. End-to-end regulated workflow examples ✅
6. API stabilization *(next roadmap)*

Items 1, 2, 4, and 5 are now complete. Item 3 (key rotation) is deferred to the future GA roadmap. See the [Sovereign Runtime Closure Boundary](./sovereign-runtime-closure-boundary.md) for the full closure boundary.
