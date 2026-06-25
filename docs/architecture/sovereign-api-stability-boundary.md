# Sovereign Runtime API Stability Boundary

This document defines the API stability boundary for the TramAI Sovereign Runtime RC+ / enterprise proof milestone.

The Sovereign Runtime roadmap is functionally complete, but this does not mean every API is stable or production-certified.

**This is not a GA-certified production release.**

---

## Stability Levels

| Level | Meaning |
|---|---|
| RC+ Stable | Safe to build examples and integrations against during the Sovereign Runtime RC+ milestone |
| Preview | Usable, but API shape may still change |
| Internal | Implementation detail; no compatibility promise |
| Deferred | Explicitly outside this roadmap |

---

## RC+ Stable Surface

These are the APIs that form the Sovereign Runtime contract surface for the closed roadmap. They are safe to build against during the RC+ / enterprise proof milestone, but are **not** guaranteed as a 1.0 public API.

### Store and Runtime SPIs

- `ApprovalStore`
- `SuspendedInvocationStore`
- `ApprovalContinuationStore`
- `AuditStore`
- `SovereignOpsAuditOutboxStore`
- `SovereignOpsApprovalMutationStore`
- `SovereignOpsWorkerLeaseStore`

### Operational DTOs

- `SovereignOpsAuditOutboxRecord`
- `SovereignOpsAuditOutboxStatus`
- Worker status and health DTOs
- Audit event contracts
- Approval request and decision contracts

### Spring Boot Configuration

- `tramai.sovereign.enabled`
- `tramai.sovereign.persistence.type`
- `tramai.sovereign.persistence.encryption.key-file`
- `tramai.sovereign.allowed-models`
- `tramai.sovereign.allowed-providers`
- `tramai.sovereign.provider-zones.*`
- `tramai.sovereign.models.*`

### Verification Tasks

- `verifySovereignRuntimeClosure`
- `verifySovereignRuntimeReleaseCandidate`
- `:examples:spring-sovereign-starter:e2eTest`

---

## Preview Surface

The following capabilities are proven by working examples, but the developer-facing API is **not** final.

- `ApprovalGateway` — front-door contract for non-blocking human approval (`dev.tramai.core.approval.gateway`)
- `ApprovalRequestResult` — sealed type for approval request outcomes (`dev.tramai.core.approval.gateway`)
- `SovereignWorkflowResult` — sealed type for workflow-level outcomes (`dev.tramai.core.workflow`)
- Workflow ergonomics
- Approval gateway abstractions
- Human suspension / resume workflow shape
- State-machine style workflow expression
- Regulated claim triage workflow harness
- Example-specific fake / test components

---

## Internal Implementation Details

The following should **not** be treated as public API. Consumers should depend on SPI contracts and Spring Boot auto-configuration, not on concrete implementation classes.

- Concrete JDBC store implementations (`JdbcApprovalStore`, `JdbcSuspendedInvocationStore`, `JdbcApprovalContinuationStore`, `JdbcAuditStore`, `JdbcSovereignOpsAuditOutboxStore`, `JdbcSovereignOpsApprovalMutationStore`, `JdbcSovereignOpsWorkerLeaseStore`)
- JDBC schema migration helper classes
- Embedded PostgreSQL test support
- E2E test harness internals
- Fake scenario components

---

## Deferred to Future Roadmaps

The following are explicitly outside the closed Sovereignty roadmap:

- Key rotation
- Production certification (HIPAA, GDPR, or any regulatory certification)
- Reviewer UI
- Full REST control plane (write / admin endpoints)
- Enterprise identity / IAM integration
- Maven Central release of sovereign runtime modules
- Stable 1.0 API across all TramAI modules
- Broad workflow DSL
- Customer-specific policy packs

---

## Compatibility Promise

For the Sovereign Runtime RC+ milestone:

- **RC+ Stable** APIs should not be renamed or semantically changed without updating this document.
- **Preview** APIs may change without notice.
- **Internal** APIs may change without notice.
- **Deferred** capabilities are not part of the closure boundary.

This document will be updated when APIs move between stability levels.
