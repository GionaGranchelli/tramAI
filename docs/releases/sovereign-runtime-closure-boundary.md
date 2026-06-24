# Sovereign Runtime Closure Boundary

> This document defines the formal closure boundary for the Sovereignty roadmap.
> The Sovereign Runtime is considered functionally complete as an RC+ / enterprise proof milestone.
> It is **not** a GA-certified production release.

---

## Status

Sovereign Runtime is **functionally complete as an enterprise proof / RC+ milestone**.

It is not yet a GA-certified production release. The next roadmap focuses on API stabilization and workflow ergonomics.

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

---

## Closure Evidence

The Sovereignty roadmap is considered **closed** when the following verification commands pass:

```bash
# Full test suite
./gradlew check

# Release-candidate verification chain
./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks

# JDBC E2E restart proof
./gradlew :examples:spring-sovereign-starter:e2eTest
```

These gates prove that all included capabilities are functional, the release evidence chain is intact, and the regulated JDBC scenario works end-to-end.

---

## Explicit Non-Goals

The closure boundary explicitly does **not** include:

- Production reviewer UI
- Production certification (HIPAA, GDPR, or any regulatory certification)
- Maven Central release of sovereign runtime modules
- Key rotation
- Full REST control plane (write / admin endpoints)
- Broad workflow DSL
- Enterprise identity / IAM integration
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

All other items listed under the original post-RC roadmap are now complete:
- JDBC/database-backed persistence and outbox ✅
- Distributed worker coordination (worker leases) ✅
- Production deployment guide (runbook) ✅
- End-to-end regulated workflow examples (JDBC E2E) ✅

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
