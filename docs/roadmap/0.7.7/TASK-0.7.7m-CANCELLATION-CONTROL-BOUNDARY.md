# TASK-0.7.7m — Authenticated & Authorized Workflow Cancellation Control Boundary

**Status:** Completed & Integrated (PR #596, merge commit `baad07bc037af28854b94ac95942426aab62347a`)
**Base:** `release/0.7.0` at `c27d3a15a13deb7fa95e6eb464f0b24f470bb823`
**Working Branch:** `task/0.7.7m-cancellation-control-boundary`
**Target Branch:** `release/0.7.0`
**Authority:** [ADR-021: Authenticated & Authorized Workflow Cancellation Control Boundary](../../adr/adr-021.md), [DEPENDENCY-BASELINE-0.7.7.md](DEPENDENCY-BASELINE-0.7.7.md), [EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md](../0.7.0/EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md), [AGENTS.md](../../../AGENTS.md)

---

## 1. Executive Summary & Purpose

During the Whole-Repository Security Surface Inventory in Milestone 0.7.6h ([TASK-0.7.6h-INTEGRATION-CLOSURE.md](../0.7.6/TASK-0.7.6h-INTEGRATION-CLOSURE.md)), an explicit release blocker for Epic 0.7.7 was identified and formalized in [DEPENDENCY-BASELINE-0.7.7.md](DEPENDENCY-BASELINE-0.7.7.md) §5.2:

> While internal engine-level cancellation was fully fenced and qualified in Milestone D7-F (`WorkflowCheckpointStore.cancel(...)`), the only HTTP endpoint triggering workflow cancellation was `DELETE /workflows/{name}/runs/{id}` in `tramai-server`. As proven in 0.7.6h, `tramai-server` is an unsupported, unauthenticated internal prototype. No authenticated or authorized cancellation endpoint existed in `tramai-spring-boot-starter-sovereign-ops-rest` (the supported library control plane).

**Task 0.7.7m resolves this release blocker** by implementing a headless, authenticated, authorized, and transactionally coupled workflow cancellation REST command boundary in `tramai-spring-boot-starter-sovereign-ops-rest` governed by [ADR-021](../../adr/adr-021.md).

---

## 2. Core Architecture & Architectural Guardrails (ADR-021)

Task 0.7.7m enforces strict adherence to TramAI's non-negotiable security and governance guardrails:

```text
HTTP Client (CLI / External Operator / Automation)
   │
   ▼ POST /tramai/sovereign/workflows/{workflowName}/runs/{runId}/cancel
     Authorization: Bearer <JWT>
     { "expectedRevision": 1, "reason": "Incident triage", "correlationId": "tx-123" }
[ Spring Security OAuth2 Filter Chain ]
   │ 1. Verify RS256 signature against trusted JWKS
   │ 2. Validate issuer, audience, and expiration
   ▼
[ OperatorPrincipalResolver ]
   │ 3. Extract claims (iss, sub, roles)
   │ 4. Map server-configured OperatorCapability and ApproverRole
   │ 5. Issue immutable OperatorAuthority
   ▼
[ WorkflowCancellationControlPlaneController ]
   │ 6. Validate DTO preconditions (expectedRevision >= 0, reason non-blank) -> 400 Bad Request
   ▼
[ SovereignOpsWorkflowCancellationControlPlane ]
   │ 7. L2 Check: OperatorCapability.CANCEL_WORKFLOW present? -> If NO: 403 Forbidden (missing-capability)
   │ 8. L3 Check: Look up OperatorWorkflowCancellationPolicy for target workflowName.
   │              Does caller hold permitted ApproverRole?
   │              If NO policy: 403 Forbidden (workflow-policy-unavailable)
   │              If NO role:   403 Forbidden (role-not-permitted)
   │ 9. Refusal Isolation: Emit isolated refusal evidence to outbox on 403.
   │ 10. Checkpoint Precondition: Load checkpoint; verify expectedRevision == checkpoint.revision -> If NO: 409 Conflict (STALE)
   │ 11. Idempotency Check: Already cancelled? -> If YES: 200 OK (alreadyCancelled = true)
   ▼
[ JdbcSovereignOpsWorkflowCancellationStore ] (Single PostgreSQL Transaction)
   │ 12. Insert audit_outbox in PREPARED state
   │ 13. Execute CAS compare-and-set on tramai_workflow_checkpoint:
   │     UPDATE tramai_workflow_checkpoint
   │     SET revision = revision + 1,
   │         lifecycle_status = ?,
   │         cancelled_by = ?,
   │         cancelled_at_epoch_millis = ?,
   │         cancel_reason = ?
   │     WHERE workflow_name = ? AND workflow_id = ? AND revision = ?
   │ 14. Update audit_outbox to PENDING state (WORKFLOW_CANCELLED evidence)
   │ 15. Commit transaction (or full rollback on error/conflict)
   ▼
HTTP 200 OK (WorkflowCancellationResponse)
```

### 2.1 Key Design Decisions

1. **Dedicated Capability (`OperatorCapability.CANCEL_WORKFLOW`):**
   Decoupled from `ADMINISTER_OPERATORS` to eliminate authority coupling between user administration and workload termination.
2. **Target-Scoped Policy (`OperatorWorkflowCancellationPolicy`):**
   Evaluated against the target `workflowName` and server-configured `ApproverRole` mappings before querying database state. The authority model enforces workflow-wide administrative authority (an authorized role permits cancelling any run of that workflow). TramAI does not implement individual per-run user ACLs or run-level ownership checks at this boundary.
3. **Existence-Blind Refusals:**
   L2 capability refusal and L3 workflow policy refusal fail closed with `403 Forbidden` without querying the database, preventing adversarial existence probing.
4. **Typed Optimistic Revision Fencing:**
   Callers must provide `expectedRevision >= 0` and non-blank `reason`. Mismatched revisions fail with `409 Conflict` (`STALE`) before attempting storage mutations.
5. **Single-Transaction Atomic Outbox Coupling:**
   PostgreSQL CAS update on `tramai_workflow_checkpoint` and outbox write on `audit_outbox` execute within a single connection transaction (`conn.autoCommit = false`), guaranteeing zero orphaned state or phantom audit events.
6. **Deterministic Uncertainty Triaging:**
   Preserves the invariant proven in D7-F: runs with `admissionSequence == 0` transition to `CANCELLED`, while runs with `admissionSequence > 0` transition to `CANCELLED_UNCERTAIN`.

---

## 3. Implementation Inventory

The implementation spanned 5 modules with zero regressions to existing APIs or schemas:

| Subproject | Files / Additions | Purpose |
| :--- | :--- | :--- |
| **`tramai-core`** | [OperatorCapability.kt](../../../tramai-core/src/main/kotlin/dev/tramai/core/operator/OperatorCapability.kt)<br>[OperatorCapabilityContractTest.kt](../../../tramai-core/src/test/kotlin/dev/tramai/core/operator/OperatorCapabilityContractTest.kt) | Added `OperatorCapability.CANCEL_WORKFLOW("cancel-workflow")` and contract tests. |
| **`tramai-orchestration`** | [JdbcWorkflowCheckpointStore.kt](../../../tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/JdbcWorkflowCheckpointStore.kt) | Exported connection-participating SPI methods: `cancelInConnection`, `lifecycleInConnection`, `load(conn, ...)`. |
| **`tramai-spring-boot-starter-sovereign-ops`** | [OperatorWorkflowCancellationPolicy.kt](../../../tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/OperatorWorkflowCancellationPolicy.kt)<br>[WorkflowCancellationControlPlane.kt](../../../tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/WorkflowCancellationControlPlane.kt)<br>[SovereignOpsWorkflowCancellationStore.kt](../../../tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/outbox/SovereignOpsWorkflowCancellationStore.kt)<br>[SovereignOpsWorkflowCancellationControlPlane.kt](../../../tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/SovereignOpsWorkflowCancellationControlPlane.kt)<br>[WorkflowCancellationControlPlaneAutoConfiguration.kt](../../../tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/WorkflowCancellationControlPlaneAutoConfiguration.kt) | Domain control plane, target authorization policy, outbox store SPI, refusal evidence, and auto-configuration. |
| **`tramai-spring-boot-starter-sovereign-persistence-jdbc`** | [JdbcSovereignOpsWorkflowCancellationStore.kt](../../../tramai-spring-boot-starter-sovereign-persistence-jdbc/src/main/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/JdbcSovereignOpsWorkflowCancellationStore.kt)<br>[SovereignJdbcPersistenceAutoConfiguration.kt](../../../tramai-spring-boot-starter-sovereign-persistence-jdbc/src/main/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/SovereignJdbcPersistenceAutoConfiguration.kt) | JDBC single-transaction CAS cancellation and outbox persistence (`PREPARED` $\to$ `PENDING`). |
| **`tramai-spring-boot-starter-sovereign-ops-rest`** | [WorkflowCancellationDtos.kt](../../../tramai-spring-boot-starter-sovereign-ops-rest/src/main/kotlin/dev/tramai/spring/sovereign/ops/rest/WorkflowCancellationDtos.kt)<br>[WorkflowCancellationControlPlaneController.kt](../../../tramai-spring-boot-starter-sovereign-ops-rest/src/main/kotlin/dev/tramai/spring/sovereign/ops/rest/WorkflowCancellationControlPlaneController.kt)<br>[OperatorSecurityProperties.kt](../../../tramai-spring-boot-starter-sovereign-ops-rest/src/main/kotlin/dev/tramai/spring/sovereign/ops/rest/security/OperatorSecurityProperties.kt)<br>[OperatorAuthenticationAutoConfiguration.kt](../../../tramai-spring-boot-starter-sovereign-ops-rest/src/main/kotlin/dev/tramai/spring/sovereign/ops/rest/security/OperatorAuthenticationAutoConfiguration.kt)<br>[ApprovalControlPlaneRestAutoConfiguration.kt](../../../tramai-spring-boot-starter-sovereign-ops-rest/src/main/kotlin/dev/tramai/spring/sovereign/ops/rest/ApprovalControlPlaneRestAutoConfiguration.kt) | REST controller mapping `POST .../cancel`, DTOs, fail-closed startup validation for cancellation policies, and auto-configuration. |

---

## 4. Verification & Qualification Suite

All new and existing test suites were verified cleanly:

### 4.1 Unit & Contract Tests

- **`OperatorCapabilityContractTest` (`tramai-core`):**
  Verified canonical wire name `"cancel-workflow"` and enum stability.
- **`SovereignOpsWorkflowCancellationControlPlaneTest` (`tramai-spring-boot-starter-sovereign-ops`):**
  Verified 7 unit test scenarios: missing capability refusal (403), unconfigured policy refusal (403), role mismatch refusal (403), not found (404), stale revision conflict (409), successful cancellation (200), and idempotent replay (200).
- **`JdbcSovereignOpsWorkflowCancellationStoreTest` (`tramai-spring-boot-starter-sovereign-persistence-jdbc`):**
  Verified 5 store scenarios against PostgreSQL: atomic commit, rollback on CAS version conflict, rollback on payload encoding failure, derivation of `CANCELLED_UNCERTAIN` when effects exist, and idempotent cancellation.
- **`WorkflowCancellationControlPlaneControllerTest` (`tramai-spring-boot-starter-sovereign-ops-rest`):**
  Verified 8 HTTP contract scenarios: 200 OK (fresh), 200 OK (idempotent replay), 400 Bad Request (missing/negative expectedRevision, blank reason), 403 Forbidden (missing capability, missing role), 404 Not Found, 409 Conflict (stale).

### 4.2 Adversarial REST Bypass Suite

Implemented in [PrivilegedWorkflowCancellationRestAdversarialTest.kt](../../../tramai-spring-boot-starter-sovereign-ops-rest/src/test/kotlin/dev/tramai/spring/sovereign/ops/rest/PrivilegedWorkflowCancellationRestAdversarialTest.kt) (Spring Boot + MockMvc + Security Filter Chain):

- **ADV-REST-01:** Unauthenticated request rejected with `401 Unauthorized`.
- **ADV-REST-02:** Token signed by untrusted foreign key rejected with `401 Unauthorized`.
- **ADV-REST-03:** Expired token rejected with `401 Unauthorized`.
- **ADV-REST-04:** Missing `CANCEL_WORKFLOW` capability rejected existence-blind with `403 Forbidden` (`missing-capability`).
- **ADV-REST-05:** Valid capability but unconfigured workflow policy rejected with `403 Forbidden` (`workflow-policy-unavailable`).
- **ADV-REST-06:** Fully authorized operator successfully cancels run, returns `200 OK`.
- **ADV-REST-07:** Stale revision rejected with `409 Conflict` (`tramai-workflow-revision-stale`).

### 4.3 Multi-Threaded Barrier Concurrency & Admission Fencing

Implemented in [PrivilegedWorkflowCancellationConcurrencyQualificationTest.kt](../../../tramai-spring-boot-starter-sovereign-persistence-jdbc/src/test/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/PrivilegedWorkflowCancellationConcurrencyQualificationTest.kt) (real PostgreSQL Testcontainers instance):

- **CONC-01 (Concurrent Barrier Race):**
  5 concurrent threads release simultaneously at a `CyclicBarrier` attempting to cancel a workflow at revision 1. Exactly **1 winning thread commits** the linearization point, advancing the checkpoint to revision 2 with status `CANCELLED`. Exactly **1 outbox row** is persisted in `audit_outbox` with status `PENDING`. The other 4 threads receive either `alreadyCancelled = true` or `WorkflowCancellationResult.Stale`.
- **CONC-02 (Post-Cancellation Admission Fencing):**
  Following cancellation, subsequent effect admission requests via `checkpointStore.admit(...)` are strictly fenced (`admitted = false`, `cancellation.status = CANCELLED`).
- **CONC-03 (Uncertain Effect Derivation):**
  A run with admitted effects (`admissionSequence > 0`) is cancelled. The terminal state transitions deterministically to `CANCELLED_UNCERTAIN`, and outbox evidence records `CANCELLED_UNCERTAIN`.

---

## 5. Acceptance Matrix

| Criterion | Target Requirement | Verified Result | Verdict |
| :--- | :--- | :--- | :--- |
| **AC-01** | Dedicated cancellation capability | `OperatorCapability.CANCEL_WORKFLOW("cancel-workflow")` | **PASS** |
| **AC-02** | Target-scoped operator authorization | `OperatorWorkflowCancellationPolicy` evaluated against target `workflowName` | **PASS** |
| **AC-03** | Fail-closed authentication & authorization | 401 for invalid JWT, 403 for missing capability / unpermitted role | **PASS** |
| **AC-04** | Typed optimistic revision precondition | Rejects missing/negative revision (400) and stale revision (409) | **PASS** |
| **AC-05** | Transactional outbox coupling | Checkpoint CAS and `audit_outbox` commit in single JDBC transaction | **PASS** |
| **AC-06** | Deterministic lifecycle uncertainty | `CANCELLED` (`admissionSeq == 0`), `CANCELLED_UNCERTAIN` (`admissionSeq > 0`) | **PASS** |
| **AC-07** | Idempotent replay | Returns 200 with `alreadyCancelled: true`, preserving original attributes | **PASS** |
| **AC-08** | Post-cancellation admission fencing | All subsequent step and effect admissions fail closed | **PASS** |
| **AC-09** | Concurrency safety | Multi-threaded barrier test proves exactly one linearization point commits | **PASS** |
| **AC-10** | Release blocker closure | Supported REST endpoint in `tramai-spring-boot-starter-sovereign-ops-rest` | **PASS** |

---

## 6. Conclusion & Handoff

Task 0.7.7m has formally resolved the Epic 0.7.7 release blocker. The privileged workflow cancellation boundary is fully implemented, executably qualified, and documented. Epic 0.7.7 is ready for final release closure and merge into `release/0.7.0`.
