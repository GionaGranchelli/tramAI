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

## Explicit Non-Goals

The following are intentionally **not included** in this RC:

- Stable 1.0 public API
- Maven Central release
- Production deployment certification
- Database-backed persistence or outbox
- Distributed worker leader election
- Key rotation
- Broad REST/Actuator control-plane endpoints
- Production dashboard
- Complete API reference documentation

## Post-RC Roadmap

After this RC boundary, the next roadmap area is production hardening:

1. JDBC/database-backed persistence and outbox
2. Distributed worker coordination
3. Key rotation
4. Production deployment guide
5. End-to-end regulated workflow examples
6. API stabilization based on integration feedback
