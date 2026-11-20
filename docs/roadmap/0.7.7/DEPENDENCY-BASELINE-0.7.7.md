# Epic 0.7.7 — Dependency Baseline & Integration Rebaseline

**Status:** FINALIZED — 0.7.6 SECURITY RISKS RATIFIED BY REPOSITORY OWNER
**Target:** `release/0.7.0`
**Verified Baseline:** `19bb92c944a91fec98f83896c5c58f07f2fde113`
**Governing Epics:**
- [EPIC-0.7.6-AUTH-RUNTIME-CONTROL.md](../0.7.0/EPIC-0.7.6-AUTH-RUNTIME-CONTROL.md)
- [EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md](../0.7.0/EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md)
**Governing Integration Milestones:**
- D7-F: [TASK-0.7.7l-D7-F-INTEGRATION-CLOSURE.md](TASK-0.7.7l-D7-F-INTEGRATION-CLOSURE.md) (PR #591, `b97e4179`)
- 0.7.6f: [TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md](../0.7.6/TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md) (PR #592, `6f2854ad`)
- 0.7.6g2: [TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md](../0.7.6/TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md) (PR #593, `226f7894`)
- 0.7.6h: [TASK-0.7.6h-INTEGRATION-CLOSURE.md](../0.7.6/TASK-0.7.6h-INTEGRATION-CLOSURE.md) (PR #594, `19bb92c9`)
- 0.7.6i: [ACCEPTANCE-0.7.6-SECURITY-RISKS.md](../0.7.6/ACCEPTANCE-0.7.6-SECURITY-RISKS.md)

---

## 1. Executive Summary & Purpose

In [EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md](../0.7.0/EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md), Epic 0.7.7 declares a **HARD dependency on Epic 0.7.6**:
> `Dependencies: 0.7.6 HARD` — *Persisted cancellation is an authoritative privileged lifecycle command.*

When milestone D7-F ([TASK-0.7.7l-D7-F-INTEGRATION-CLOSURE.md](TASK-0.7.7l-D7-F-INTEGRATION-CLOSURE.md), PR #591) was integrated, PRs #592 (0.7.6f), #593 (0.7.6g2), and #594 (0.7.6h) were still pending merge. Consequently, D7-F noted that Track 0.7.6 remained incomplete.

With the subsequent merge of PRs #592, #593, and #594 into `release/0.7.0`, the technical contracts of Epic 0.7.6 are fully integrated. This document establishes the formal, reconciled dependency baseline between Epic 0.7.6 and Epic 0.7.7:
1. Reconciles D7-F against the finalized 0.7.6 baseline.
2. Identifies the exact architectural contracts Epic 0.7.7 authoritatively inherits.
3. Explicitly defines the negative boundaries Epic 0.7.7 **must not assume**.
4. Highlights an **explicit release blocker for Epic 0.7.7**: the absence of an authenticated, authorized, typed cancellation REST endpoint in the supported library control plane.
5. Establishes the finalized status of this dependency handoff following repository-owner ratification of 0.7.6 security risks F3, F5, and L4.

---

## 2. Baseline Reconciliation: D7-F and 0.7.6 Delivery Chain

The sequence of integrations on `release/0.7.0` is:
- **PR #591 (`b97e4179`):** D7-F Cross-Boundary Cancellation & Evidence Integration Closure. Delivered E1–E9 effect admission fencing, recovery lease fencing (E10), step admission fencing (E11), and 16 integration scenarios (D7-F01–D7-F16).
- **PR #592 (`6f2854ad`):** 0.7.6f Privileged Evidence & Durable Attribution. Delivered V10 relational schema, V3 outbox payload, `(issuer, subject)` attribution, and canonical `ActorIdentityDigest`.
- **PR #593 (`226f7894`):** 0.7.6g2 Privileged Control Boundary Adversarial Qualification. Delivered 14 adversarial bypass tests, PostgreSQL rollback proof, and mutation qualification (`M1`–`M8`).
- **PR #594 (`19bb92c9`):** 0.7.6h Headless Control API, Security Surface Inventory & Epic Acceptance Gate. Published headless integration guide, whole-repo security surface inventory, database least-privilege matrix, and F3 audit reconciliation.

D7-F's section 8 evaluated 0.7.6 as having incomplete tasks (0.7.6f–h). **That evaluation is now superseded.** All implementation and qualification tasks of 0.7.6 are delivered on `release/0.7.0`.

---

## 3. Authoritatively Inherited Contracts (What 0.7.7 May Rely On)

Epic 0.7.7 can authoritatively rely on the following contracts delivered by Epic 0.7.6:

1. **Verified Operator Identity & Durable Issuer Attribution:**
   - Standardized RS256 OIDC JWT verification via Spring Security OAuth2 resource server (`NimbusJwtDecoder`).
   - Domain-separated, length-prefixed canonical UTF-8 digest:
     $$\text{ActorIdentityDigest} = \text{SHA-256}(\text{"tramai-actor-identity-digest-v1" } \parallel \text{len}(iss) \parallel iss \parallel \text{len}(sub) \parallel sub)$$
   - Relational persistence in V10 schema columns (`decision_actor_issuer`, `decision_actor_identity_digest`) and V3 transactional outbox records.

2. **Server-Controlled Capability & Approver-Role Governance:**
   - Closed, immutable `OperatorCapability` enum (`DECIDE_APPROVAL`, `RESUME_APPROVAL`, `INSPECT_GOVERNANCE`, `ADMINISTER_OPERATORS`) strictly separated from tool, MCP, or runtime scopes.
   - Server-side `OperatorAuthority` minting via `OperatorPrincipalFactory` and `OperatorPrincipalResolver`, validating claims against server configuration and ignoring client assertions.
   - Fail-closed configuration validation preventing startup with insecure or unsatisfiable policies.

3. **Typed Version Preconditions & Concurrency Fencing:**
   - Required caller-observed `expectedVersion >= 0` precondition in control commands.
   - Stale commands rejected with 409 `STALE` (`ApprovalDecisionResult.Stale`) before invoking storage mutations.
   - Database-level CAS version predicate (`WHERE version = ?`) preventing concurrent update corruption.

4. **Transactional Evidence Coupling:**
   - Atomic coupling between state mutation and outbox record persistence within a single database transaction.
   - Refusal isolation: authorization refusals commit refusal audit records without corrupting state or leaking unverified execution.
   - Sanitization: credentials, raw arguments, and sensitive headers are redacted; canary secrets never appear in plaintext.

5. **Trusted In-Process Boundary Assumption:**
   - In-process host application code is assumed trusted.
   - AST/PSI ratchet `verifyDirectApprovalMutations` guards internal direct-store SPI calls during build.

---

## 4. Negative Boundaries (What 0.7.7 Must NOT Assume)

To prevent security drift, Epic 0.7.7 and any subsequent cancellation features must **NOT** assume the following:

1. **No Universal Authentication on Prototype Endpoints:**
   - `tramai-server` internal endpoints (`WorkflowController`, `WorkloadControlPlaneController`, etc.) remain **unauthenticated, unsupported platform prototypes**.
   - Epic 0.7.7 must **not** treat `tramai-server` controllers as production-ready secure control endpoints.

2. **No Per-Resource Read Authorization (Risk F3):**
   - Read endpoints in the supported REST starter (`GET /tramai/sovereign/approvals/{id}`) enforce authentication (HTTP 401 if unauthenticated) but do not filter access by operator role or workflow ownership.
   - Epic 0.7.7 must not assume that workflow cancellation status queries are partitioned by caller identity.

3. **No Dynamic Approver-Role Relational Columns (Risk F5):**
   - Dynamic per-instance required roles requested at runtime are stored in metadata, not relational columns.
   - Policy checks are bound to server-configured policy versions.

4. **No JVM Runtime Sandboxing (Risk L4):**
   - The JVM does not enforce JPMS module boundaries or classloader sandboxing against in-process code.
   - Hostile in-process plugins are outside the threat model.

---

## 5. Critical Gap: 0.7.7 Cancellation Control Entry Point Authority

### 5.1 The Finding

In Epic 0.7.7's charter ([EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md](../0.7.0/EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md)), Task 0.7.7c specifies:
> *Task 0.7.7c: Authoritative cancel command — Integrate 0.7.6 authorization/control path with persisted lifecycle authority.*

The whole-repository security surface inventory conducted in 0.7.6h ([TASK-0.7.6h-INTEGRATION-CLOSURE.md](../0.7.6/TASK-0.7.6h-INTEGRATION-CLOSURE.md) §3.1) revealed:
- `WorkflowCheckpointStore.cancel(...)` is an internal engine SPI method in `:tramai-orchestration`.
- The only existing HTTP endpoint triggering workflow cancellation is `DELETE /workflows/{name}/runs/{id}` in `WorkflowController.kt` (module `tramai-server`).
- As proven in 0.7.6h, `tramai-server` is an **unsupported, unpublished internal prototype** with **zero authentication and zero authorization**.
- **No cancellation endpoint exists in `tramai-spring-boot-starter-sovereign-ops-rest`** (the supported privileged control plane library).

### 5.2 Release Gate Blocker Resolution: TASK-0.7.7m

The release gate blocker identified in 0.7.6h and Section 5.1 has been **formally resolved by Task 0.7.7m** ([TASK-0.7.7m-CANCELLATION-CONTROL-BOUNDARY.md](TASK-0.7.7m-CANCELLATION-CONTROL-BOUNDARY.md)).

Task 0.7.7m implemented and qualified the full authenticated and authorized REST cancellation boundary in `tramai-spring-boot-starter-sovereign-ops-rest`:
1. **Dedicated Endpoint:** `POST /tramai/sovereign/workflows/{workflowName}/runs/{runId}/cancel` secured by Spring Security OAuth2 resource server with RS256 JWT verification.
2. **Dedicated Capability:** `OperatorCapability.CANCEL_WORKFLOW` (`"cancel-workflow"`), completely decoupled from `ADMINISTER_OPERATORS`.
3. **Target-Scoped Policy:** `OperatorWorkflowCancellationPolicy` binding target `workflowName`s to authorized `ApproverRole`s, evaluated server-side.
4. **Typed Optimistic Revision Fencing:** Request carries `expectedRevision >= 0` and non-blank `reason`. Outdated revisions receive `409 Conflict` (`STALE`) before executing storage mutations.
5. **Transactional Evidence Coupling:** Checkpoint CAS cancellation commits atomically in a single PostgreSQL transaction with transactional outbox evidence (`WORKFLOW_CANCELLED` and `AUTHORIZATION_REFUSAL`).
6. **Execution Proof:** Adversarial REST bypass test suite and multi-threaded barrier concurrency qualification verified the control boundary.

---

## 6. D7-F Delivered Guarantees & Residuals

Milestone D7-F demonstrated robust engine-level and store-level mechanics for suspended run cancellation:

### 6.1 Delivered Core Invariant

```text
cancelled(run) => no subsequent authoritative execution or effect admission(run)
cancelled(run) != already-produced external effects compensated or reversed
```

1. **Absolute Post-Cancellation Admission Fencing (E1–E9, E11):** Once cancellation commits durably in `WorkflowCheckpointStore`, all subsequent step admissions (E11), outbound effect admissions (E1–E8), and approval continuation claims (E9) are refused fail-closed with `WorkflowAdmissionRefusedException`.
2. **Lease Fencing (E10):** Expired recovery workers cannot admit steps or effects; lease fencing survives process restarts.
3. **Late Approval Exclusion (E9):** Late operator approval completions on cancelled runs fail closed; no execution resumes.
4. **Deterministic Uncertainty Triaging (`CANCELLED_UNCERTAIN`):**
   - If `admission_seq == 0` when cancelled $\to$ transitions to `WorkflowLifecycleStatus.CANCELLED`.
   - If `admission_seq > 0` when cancelled $\to$ transitions to `WorkflowLifecycleStatus.CANCELLED_UNCERTAIN`.
   - Admitted effects cannot be assumed rolled back. External compensation requires operator reconciliation playbooks.
5. **Timeline Reconstruction Adapter:** `WorkflowHistoricalEvidenceReader.readTimeline` extracts lifecycle history without re-executing workflow effects; detects sequence gaps with bounded enumeration; flags `reconciliationRequired == true` fail-closed. However, durable continuation inspection is **not yet guaranteed to be side-effect-free or mutation-free** (see §6.2.2).

### 6.2 D7-F Residual Limitations

1. **Cross-Family Multi-Runtime Composition (PARTIAL):** Pairwise compositions (E11 with E9, E1, E3/E4, E7) are qualified in D7-F01–D7-F16. Simultaneous multi-family runtime pipelines across all 9 families within a single active graph are deferred.
2. **Durable Continuation Forensic Inspection (PARTIAL):**
   - Timeline reconstruction extracts historical events without re-executing workflow effects. However, when inspecting durable continuations, `WorkflowHistoricalEvidenceReader.readTimeline` delegates to `ApprovalContinuationStore.get`.
   - Existing store implementations (`JdbcApprovalContinuationStore`, `FileApprovalContinuationStore`) perform lazy status mutation (transitioning expired `PENDING` continuations to `EXPIRED`, incrementing version, and scrubbing payloads) upon inspection.
   - Consequently, durable continuation inspection is not yet guaranteed to be mutation-free. The `ReadOnlyApprovalContinuationLookup` interface abstraction is established in `:tramai-orchestration`, but native non-mutating query methods on durable stores require cross-module SPI changes and are deferred to a dedicated continuation-store enhancement task.
3. **Operator Reconciliation Playbook Required:**
   - For runs in `CANCELLED_UNCERTAIN`, operators must inspect timelines and reconcile external systems manually using `idempotentKey` and `admissionSeq`.

---

## 7. Baseline Finalization & Ratification Boundary

This dependency baseline is **FINALIZED**.

With the repository owner's explicit ratification of the 0.7.6 security risks record ([ACCEPTANCE-0.7.6-SECURITY-RISKS.md](../0.7.6/ACCEPTANCE-0.7.6-SECURITY-RISKS.md)) on 2026-11-15, and the delivery and qualification of the authenticated and authorized workflow cancellation control boundary in [TASK-0.7.7m-CANCELLATION-CONTROL-BOUNDARY.md](TASK-0.7.7m-CANCELLATION-CONTROL-BOUNDARY.md), the Epic 0.7.7 release blocker is resolved.

```text
========================================================================================
EPIC 0.7.7 DEPENDENCY REBASELINE RATIFICATION
========================================================================================
0.7.6 Hard Dependency Delivery Status:     DELIVERED (PRs #592, #593, #594)
0.7.6 Security Risk Acceptance Status:      RATIFIED & CLOSED BY REPOSITORY OWNER (2026-11-15)
0.7.7 Cancellation Control REST Boundary:   RESOLVED (TASK-0.7.7m Delivered)
Overall Dependency Handoff Verdict:         FINALIZED DEPENDENCY BASELINE — READY FOR 0.7.0 RELEASE
========================================================================================
```
