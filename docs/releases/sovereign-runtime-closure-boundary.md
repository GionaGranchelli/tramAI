# Sovereign Runtime Closure Boundary

> This document defines the formal closure boundary for the Sovereignty roadmap.
> The Sovereign Runtime is considered functionally complete as an RC+ / enterprise proof milestone.
> It is **not** a GA-certified production release.
>
> This is not a GA-certified production release.

---

## Status

Sovereign Runtime is **functionally complete as an enterprise proof / RC+ milestone**.

**This is not a GA-certified production release.** The next roadmap focuses on API stabilization and workflow ergonomics.

---

## Included Capabilities

The following capabilities are included in the closure boundary:

- Policy enforcement (allow / deny / suspend)
- Data classification and DLP-aware routing
- Sovereign routing and trust zones (local-only, EU/trusted, approved cloud, denied)
- Human approval request persistence (`ApprovalStore`)
- Suspended invocation persistence (`SuspendedInvocationStore`)
- Approval continuation persistence (`ApprovalContinuationStore`)
- Tamper-evident audit chain (`AuditStore`)
- Durable audit outbox (`SovereignOpsAuditOutboxStore`)
- Transactional approval mutation + audit outbox boundary (`SovereignOpsApprovalMutationStore`)
- JDBC-backed PostgreSQL persistence for all sovereign stores
- Encrypted file-backed persistence for all sovereign stores
- Background worker dispatch loop for audit outbox recovery and dispatch
- Worker lease coordination for multi-node deployments
- Worker observer SPI and composite observer pipeline
- Spring Boot auto-configuration for JDBC persistence (`type=jdbc`)
- Spring Boot auto-configuration for file-backed persistence (`type=file`)
- Optional Actuator worker status endpoint (`/actuator/tramaiSovereignOpsWorker`)
- Optional Actuator worker health component
- Micrometer / Prometheus metrics bridge
- OpenTelemetry worker metrics
- Worker observability runbook with PromQL examples and alert definitions
- Sovereign document intelligence reference workflow (file-backed example)
- Regulated claim triage executable JDBC E2E proof
- CI-backed E2E verification
- Production deployment runbook (JDBC persistence stack)
- Release-candidate evidence index generation
- Consumer-resolution smoke test
- Canonical `verifySovereignRuntimeReleaseCandidate` verification task
- Release readiness documentation and module matrix
- Preview approval decision/resume control plane
- Preview REST control plane
- Preview reviewer UI (disabled by default)
- Internal encrypted resume credential custody
- Approved-continuation auto-resume worker
- Approved-resume worker lifecycle/health/status/queue snapshot/metrics
- Approved-resume lifecycle JDBC E2E proof

---

## Closure Evidence

The Sovereign Runtime closure boundary is verified by:

```bash
./gradlew verifySovereignRuntimeClosure
```

This task depends on:

- `./gradlew check` — full test suite
- `./gradlew verifySovereignRuntimeReleaseCandidate` — release-candidate verification chain
- `./gradlew :examples:spring-sovereign-starter:e2eTest` — JDBC E2E restart proof
- closure documentation consistency checks

These gates prove that all included capabilities are functional, the release evidence chain is intact, the regulated JDBC scenario works end-to-end, and the closure documentation is internally consistent.

---

## API Stability Boundary

The Sovereign Runtime closure boundary does not imply that every TramAI API is stable.

For the classification of stable, preview, internal, and deferred surfaces, see:

[Sovereign Runtime API Stability Boundary](../architecture/sovereign-api-stability-boundary.md)

---

## Explicit Non-Goals

The closure boundary explicitly does **not** include:

- Production-grade reviewer UI / enterprise IAM integration
- Production certification (HIPAA, GDPR, or any regulatory certification)
- Maven Central release of sovereign runtime modules
- Key rotation
- Production-grade admin REST surface beyond preview control-plane endpoints
- Broad workflow DSL
- Customer-specific policy packs
- Stable 1.0 API across all TramAI modules
- Complete API reference documentation

---

## Deferred Work

### Deferred: Key Rotation

Key rotation is explicitly deferred to the future Sovereign Runtime GA / production-certification roadmap.

Rationale:
- The current Sovereignty milestone goal is RC+ / enterprise proof, not production-certified security.
- Minimal key rotation (key ID in records, multiple active keys, re-encryption) would add 3–5 PRs.
- Deferring keeps the closure timeline achievable and prevents scope creep into another long roadmap.

### Post-Closure Additions (PRs #103–#117)

All post-closure work has been completed and is reflected in the included capabilities above:

| Area | PRs |
|------|-----|
| Preview approval decision/resume control plane | #104–#106 |
| Preview REST control plane | #104–#106 |
| Preview reviewer UI (disabled by default) | #110 |
| Internal encrypted resume credential custody | #111 |
| Approved-continuation auto-resume worker | #112 |
| Worker lifecycle, status store, health indicator, queue snapshot, metrics | #113–#116 |
| Approved-resume lifecycle JDBC E2E proof | #117 |

These additions extend the closure boundary with preview and internal surfaces without changing the overall RC+ / enterprise proof milestone classification.

---

## What Comes Next

After this closure boundary, the next roadmap is **API stabilization and workflow ergonomics**.

Potential directions (decided separately):

| Option | Focus |
|--------|-------|
| Workflow Runtime | Workflow DSL, suspension/resume ergonomics, approval gateway abstraction |
| Enterprise Integration | REST control plane, reviewer UI hooks, audit export, admin APIs |
| Sovereign Runtime GA | Key rotation, API compatibility, Maven Central release, security review |

See the full enterprise vision in [`ROADMAP.md`](../../ROADMAP.md).

---

## References

- [Sovereign Runtime RC Boundary (historical)](./sovereign-runtime-rc-boundary.md)
- [Regulated Claim Triage Scenario](../scenarios/regulated-claim-triage.md)
- [Sovereign JDBC Production Deployment Runbook](../runbooks/sovereign-jdbc-production-deployment.md)
- [Project Status](../STATUS.md)
- [Enterprise Roadmap](../../ROADMAP.md)
