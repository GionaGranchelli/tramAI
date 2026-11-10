# D7-F — Cross-Boundary Cancellation & Evidence Integration Closure

**Status:** Completed & Qualified (Strict Merge Hold)  
**Base:** `release/0.7.0` at `13ae6583a9272922b7a75fcd632ad94c3e8f75a3`  
**Working Branch:** `task/0.7.7l-d7-f-integration-closure`  
**Authority:** `docs/roadmap/0.7.0/EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md`, `docs/roadmap/0.7.0/QUALITY-GATES.md`, `docs/roadmap/0.7.0/EPIC-0.7.5-TIMELINE-RECONSTRUCTION.md`, ratified D7-A through D7-E1 contracts, `AGENTS.md`  
**Scope:** Integration qualification across all outbound effect boundaries (E1–E9), recovery lease fencing (E10), and step admission (E11); end-to-end qualification suite (D7-F01–D7-F15); cross-family evidence correlation proof; read-only timeline reconstruction adapter; adversarial qualification suite (ADV-01–ADV-12); and Epic 0.7.7 acceptance matrix.

---

## 1. Executive Summary & Core Invariant

Milestones D7-A through D7-E1 established the individual mechanisms for durable run cancellation authority, CAS-fenced checkpoints, atomic effect admission slots, lease-fenced recovery, and typed per-effect evidence across outbound families (HTTP E1, LLM E2, MCP E3, DB E4, Message E5, Filesystem E6, Process E7, Generic E8, Approval Resume E9).

Milestone **D7-F** delivers the cross-boundary integration closure, providing formal qualification that these mechanisms operate coherently together across all supported governed workflow boundaries.

### 1.1 The Primary Invariant

```text
cancelled(run) => no subsequent authoritative execution or effect admission(run)
cancelled(run) != already-produced external effects compensated or reversed
```

Specifically:
1. **Absolute Post-Cancellation Admission Fence:** Once cancellation commits durably in `WorkflowCheckpointStore`, no subsequent step admission (E11), outbound effect admission (E1–E8), approval continuation claim (E9), or workflow resume attempt may succeed. All future admission attempts fail closed with `WorkflowAdmissionRefusedException`.
2. **Honest In-Flight Accounting:** TramAI never claims that cancelling a workflow magically rolls back, recalls, or compensates an external side effect (e.g. HTTP POST, CLI subprocess execution, database commit) that was already admitted and in-flight before the cancellation linearized.
3. **Deterministic Uncertainty Triaging:** If any outbound effect was admitted (`admission_seq > 0`) before cancellation committed, the workflow transitions to `WorkflowLifecycleState.CANCELLED_UNCERTAIN`. Such runs require operator reconciliation. Conversely, if cancellation commits prior to any effect admission, the workflow transitions to `WorkflowLifecycleState.CANCELLED_CLEAN`.
4. **Non-Data-Class Single-Use Permits:** Admission permits (`WorkflowAdmissionPermit`) are strictly single-use reference tokens that cannot be copied or replayed.
5. **No Synthetic Attempt Identities:** Approval resume records (E9) carry authentic `ParentProvenance.ApprovalContinuation` linking directly to the durable `ClaimedApprovalContinuation`, preventing synthetic attempt pollution.

---

## 2. Phase 1 — Comprehensive Integration Matrix

The following matrix documents the interaction across all governed boundaries, their authoritative storage components, enforcement points, evidence representations, and failure handling semantics under cancellation.

| Boundary | Name | Authority Component | Enforcement Point | Evidence Schema & Parent | Cancellation Impact | Reconciliation Required? |
|---|---|---|---|---|---|---|
| **E1** | HTTP Outbound | `WorkflowCheckpointStore` | `admitEffect(HTTP, idempotentKey)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | If admitted prior to cancel: run commits `CANCELLED_UNCERTAIN`. HTTP call may finish or timeout. Evidence marked `UNCERTAIN` or `COMPLETED`. | Yes, if admitted |
| **E2** | Model Invocation | `WorkflowCheckpointStore` | `admitEffect(MODEL, modelName)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Admitted invocation may finish or abort; subsequent token generation or tool dispatch rejected. | Yes, if unverified tokens generated |
| **E3** | MCP Tool Call | `WorkflowCheckpointStore` | `admitEffect(MCP, toolCallId)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Admitted MCP call may complete externally. Evidence marked `UNCERTAIN` on cancellation timeout. Subsequent MCP calls refused. | Yes, if admitted |
| **E4** | Database Mutation | `WorkflowCheckpointStore` | `admitEffect(DATABASE, txId)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Non-checkpoint DB tx admitted before cancel may commit. Checkpoint CAS rejects post-cancel commits. | Yes, if admitted |
| **E5** | Message / Event | `WorkflowCheckpointStore` | `admitEffect(MESSAGE, messageId)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Outbound message published if already dispatched; post-cancel publication refused. | Yes, if admitted |
| **E6** | Filesystem Mutation | `WorkflowCheckpointStore` | `admitEffect(FILESYSTEM, path)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | File write started before cancel may complete; subsequent writes rejected. | Yes, if admitted |
| **E7** | Process / CLI | `WorkflowCheckpointStore` | `admitEffect(PROCESS, pid/key)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Subprocess spawned before cancel may terminate or run to completion. Subprocess manager marks `UNCERTAIN`. Subsequent spawn refused. | Yes, if admitted |
| **E8** | Generic Side Effect | `WorkflowCheckpointStore` | `admitEffect(CUSTOM, effectKey)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Effect admitted before cancel proceeds; post-cancel admission refused. | Yes, if admitted |
| **E9** | Approval Resume | `ApprovalContinuationStore` + `WorkflowCheckpointStore` | 5-dimension claim check + `admitEffect(APPROVAL_RESUME)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.ApprovalContinuation` | Claimed continuation before cancel transitions run to `CANCELLED_UNCERTAIN`. Post-cancel resume claim refused. | Yes, if claimed & admitted |
| **E10** | Recovery Re-execution | `WorkflowLeaseStore` | `admitUnderLeaseFence(lease)` | Transactional lease verification | Stale worker presenting expired lease rejected with `WorkflowAdmissionRefusedException`. Fencing survives process restart. | No (fenced cleanly) |
| **E11** | Step Admission | `WorkflowCheckpointStore` | `admit(request)` | Checkpoint revision & `admission_seq` | Post-cancel step admission rejected with `WorkflowAdmissionRefusedException`. Run state remains terminal. | No (fenced cleanly) |

---

## 3. Phase 2 — End-to-End Scenarios Qualification (D7-F01 – D7-F15)

The qualification suite is implemented in `WorkflowCrossBoundaryIntegrationTest.kt` using real database persistence (`JdbcWorkflowCheckpointStore`, `JdbcWorkflowEffectEvidenceRecordStore`, `JdbcWorkflowLeaseStore` against an in-memory MySQL/PostgreSQL schema backed by HikariCP).

All 15 scenarios were verified and passed cleanly:

### Scenario Results

- **D7-F01: Pre-Execution Cancellation**
  - *Setup:* Workflow created in `PENDING` state with zero effects admitted (`admission_seq = 0`).
  - *Action:* `checkpointStore.cancel(...)` executed before first step admission.
  - *Outcome:* Workflow transitions directly to `WorkflowLifecycleState.CANCELLED_CLEAN`. First step admission attempt fails immediately with `WorkflowAdmissionRefusedException`. Zero evidence records or side effects emitted.

- **D7-F02: Mid-Execution Step Boundary Cancellation**
  - *Setup:* Step 1 completes normally. Before Step 2 admission is requested, `cancel(...)` commits.
  - *Action:* Step 2 attempts `checkpointStore.admit(...)`.
  - *Outcome:* Step 2 admission refused with `WorkflowAdmissionRefusedException`. Run remains terminated.

- **D7-F03: Concurrently Admitted Effect During Cancellation**
  - *Setup:* Outbound effect (E1 HTTP) admits successfully (`admission_seq = 1`).
  - *Action:* Concurrently, `cancel(...)` is invoked with `expectedRevision == saved.revision`.
  - *Outcome:* Because `admission_seq > 0`, the store transitions to `WorkflowLifecycleState.CANCELLED_UNCERTAIN`. The admitted effect evidence transitions to `UNCERTAIN`. Subsequent admissions refused.

- **D7-F04: In-Flight Effect Timeout During Cancellation**
  - *Setup:* Effect admitted and dispatched. Tool coroutine experiences timeout during cancellation.
  - *Action:* Evidence handle executes `recordUncertain("HTTP call timed out during cancellation")`.
  - *Outcome:* Evidence state committed as `UNCERTAIN` without replacing or masking the primary cancellation exception.

- **D7-F05: Cancelled Run Resume Attempt**
  - *Setup:* Workflow reaches terminal cancellation (`CANCELLED_CLEAN`).
  - *Action:* Worker attempts `tramaiWorkflow(engine).resume(...)` using governed identity and attribution metadata.
  - *Outcome:* Resume fails closed with `WorkflowAdmissionRefusedException`. No step body executed.

- **D7-F06: Stale Recovery Worker Lease Takeover**
  - *Setup:* Worker A acquires lease for 1,000ms. Clock advances 1,500ms; Worker B claims lease.
  - *Action:* Worker A attempts `admitUnderLeaseFence(...)` presenting its stale lease.
  - *Outcome:* Stale lease rejected with `WorkflowAdmissionRefusedException`. Worker B's ownership preserved.

- **D7-F07: Late Approval Completion on Cancelled Run**
  - *Setup:* Approval continuation created. Run is cancelled.
  - *Action:* Operator approves continuation via `continuationStore.complete(...)`. Engine attempts `claim(...)` and admission.
  - *Outcome:* Admission authority checks checkpoint status, detects terminal cancellation, and refuses resume.

- **D7-F08: Concurrently Admitted Approval Resume (E9) During Cancellation**
  - *Setup:* Approval continuation claimed and E9 effect admitted (`admission_seq = 1`).
  - *Action:* Cancellation arrives concurrently and commits.
  - *Outcome:* Run transitions to `CANCELLED_UNCERTAIN`. Continuation evidence marked `UNCERTAIN`. No unverified execution inferred.

- **D7-F09: Repeated Idempotent Cancellation**
  - *Setup:* Workflow cancelled to `CANCELLED_CLEAN` at revision 1.
  - *Action:* Repeated `checkpointStore.cancel(...)` called with current revision.
  - *Outcome:* Idempotent success returned. State remains `CANCELLED_CLEAN`. Revision remains stable.

- **D7-F10: Process Restart Preserves Terminal State**
  - *Setup:* Workflow cancelled using `store1`.
  - *Action:* Process restarts: fresh `store2` connected to the same underlying JDBC database.
  - *Outcome:* `store2.load(...)` returns `CANCELLED_CLEAN`. Subsequent admissions via `store2` are refused.

- **D7-F11: Checkpoint Removal After Cancellation**
  - *Setup:* Workflow cancelled to `CANCELLED_CLEAN`. Checkpoint row removed or migrated. Evidence records preserved.
  - *Action:* Admission attempted on missing checkpoint.
  - *Outcome:* Fails closed with `WorkflowAdmissionRefusedException`. Historical evidence remains auditable.

- **D7-F12: Multiple Concurrent Heterogeneous Effects (E1, E3, E7, E9)**
  - *Setup:* Workflow concurrently admits HTTP (E1), MCP (E3), Process (E7), and Approval Resume (E9).
  - *Action:* Cancellation commits while effects are in various states (`COMPLETED`, `DISPATCHED`, `ADMITTED`).
  - *Outcome:* Run transitions to `CANCELLED_UNCERTAIN`. Evidence records reflect distinct execution phases. Gaps are preserved.

- **D7-F13: Approval Continuation Expiry During Cancellation Window**
  - *Setup:* Approval continuation expires (`approvalExpiresAt` in the past).
  - *Action:* Resume requested.
  - *Outcome:* Continuation claim fails closed with `ApprovalContinuationExpiredException`. Zero admissions granted.

- **D7-F14: Mismatched Approval Arguments Digest**
  - *Setup:* Continuation created with arguments digest $D_1$. Resume presented with altered arguments producing $D_2$.
  - *Action:* Claim verification performed.
  - *Outcome:* Claim rejected with `ApprovalContinuationSecurityException`. No evidence handle issued.

- **D7-F15: Evidence Store Outage During Admission**
  - *Setup:* Evidence store throws persistence exception on `recordAdmitted()`.
  - *Action:* `WorkflowApprovalResumeAdmission.admitApprovalResume(...)` invoked.
  - *Outcome:* Throws `ApprovalResumeEvidencePersistenceException`. No single-use permit leaked. Continuation remains in `CLAIMED` status for operator triage.

---

## 4. Phase 3 — Cross-Family Evidence Correlation Proof

Implemented and verified in `CrossFamilyEvidenceCorrelationTest.kt`:

1. **Heterogeneous Family Correlation:**
   - Exercised HTTP (`E1`), MCP (`E3`), Process (`E7`), and Approval Resume (`E9`) records within a single workflow execution.
   - Proved that all records share the authoritative `(workflowName, workflowId)` context and strictly monotonic `admissionSeq`.
2. **Provenance Differentiation:**
   - E1, E3, and E7 records link to `ParentProvenance.StepAttempt` carrying step attempt identifiers.
   - E9 records link to `ParentProvenance.ApprovalContinuation` carrying `approvalId`, `continuationVersion`, and `toolName`, with `stepAttemptId == null`.
3. **Canary Secret Redaction:**
   - Verified that sensitive payloads (Bearer tokens in HTTP, auth tokens in MCP, API keys in subprocess env, secret approval arguments) are sanitized.
   - Only hashed arguments digests (`argumentsDigest`) and redacted headers appear in evidence records.
4. **Lifecycle State Integrity:**
   - Proved proper sequencing: `ADMITTED` $\to$ `DISPATCHED` $\to$ `COMPLETED` / `UNCERTAIN`.
   - On cancellation arrival, in-flight records transition to `UNCERTAIN` without corrupting timestamps.

---

## 5. Phase 4 — Historical Timeline & Timeline Reconstruction

Implemented in `WorkflowHistoricalEvidenceTimeline.kt` and tested in `WorkflowHistoricalEvidenceTimelineTest.kt`:

### 5.1 Architecture

```kotlin
internal data class WorkflowHistoricalEvidenceTimeline(
    val workflowName: String,
    val workflowId: String,
    val lifecycleState: WorkflowLifecycleState,
    val revision: Long,
    val lastAdmissionSeq: Long,
    val cancellationReason: String?,
    val cancelledAtEpochMillis: Long?,
    val records: List<WorkflowEffectEvidenceRecord>,
    val admissionGaps: List<Long>,
    val reconciliationRequired: Boolean,
)
```

### 5.2 Reconstruction Guarantees

1. **Chronological Event Ordering:** `WorkflowHistoricalEvidenceReader.readTimeline` reconstructs the complete lifecycle history from the authoritative checkpoint store and evidence store.
2. **Gap Detection:** Detects any sequence disparity where `admission_seq` was committed in the checkpoint store but no corresponding evidence record was written (e.g., due to post-admission crash or store outage).
3. **Reconciliation Flagging (`reconciliationRequired`):** Automatically evaluates to `true` if:
   - The workflow lifecycle state is `CANCELLED_UNCERTAIN`.
   - Any evidence record remains in `ADMITTED`, `DISPATCHED`, or `UNCERTAIN` state.
   - Any admission gaps exist.
   - Any associated approval continuation is in `CLAIMED` state.
4. **Read-Only Non-Invasive Adapter:** Kept strictly `internal` in `tramai-orchestration`, preserving binary API compatibility (`apiCheck` unchanged).

---

## 6. Phase 5 — Adversarial Qualification (ADV-01 – ADV-12)

Implemented and verified in `WorkflowCrossBoundaryAdversarialTest.kt`. Each test exercises a targeted discriminator proving that the governing invariant fails closed against mutational deviations:

1. **ADV-01 (Terminal State Removal Discriminator):** Proves that without terminal state enforcement, a cancelled run would erroneously accept new admissions.
2. **ADV-02 (Transition Guard Inversion Discriminator):** Proves that an inverted transition guard rejecting valid states or allowing terminal states to transition back to `PENDING`/`RUNNING` is detected.
3. **ADV-03 (Approval Continuation Bypass Discriminator):** Proves that an unverified continuation without valid claim cannot bypass checkpoint admission.
4. **ADV-04 (Transient In-Memory Cancellation Discriminator):** Proves that an in-memory cancel flag that is lost across store instances cannot protect against post-restart execution.
5. **ADV-05 (Missing Checkpoint Permissive Resume Discriminator):** Proves that deleting or losing a checkpoint row fails closed rather than treating absence as permission to run.
6. **ADV-06 (Checkpoint CAS Revision Bypass Discriminator):** Proves that an out-of-order or stale revision CAS update is rejected with `WorkflowCheckpointConflictException`.
7. **ADV-07 (Lease Fence Stale Worker Discriminator):** Proves that a worker whose lease expired cannot execute admissions under an expired lease token.
8. **ADV-08 (Corrupted Continuation Version Discriminator):** Proves that presenting an incremented or mismatched continuation version rejects claim verification fail-closed.
9. **ADV-09 (Unbounded Timeout Masking Discriminator):** Proves that evidence update timeouts do not suppress or replace underlying coroutine cancellation exceptions.
10. **ADV-10 (Evidence Store Downtime Permissive Admission Discriminator):** Proves that failure to persist evidence never results in a permissive admission permit.
11. **ADV-11 (Canary Secret Sanitization Discriminator):** Proves that raw secret strings in continuation arguments are never stored in plain text.
12. **ADV-12 (Single-Use Permit Reuse Discriminator):** Proves that `WorkflowAdmissionPermit` cannot be reused for multiple effect dispatches.

---

## 7. Epic 0.7.7 Acceptance Matrix

| Epic 0.7.7 Acceptance Criterion | Status | Verified Evidence |
|---|---|---|
| **1. Cancelled persisted run can never resume authoritative execution.** | **PASS (VERIFIED)** | D7-F01, D7-F02, D7-F05, ADV-01: Every resume and step admission attempt fails closed with `WorkflowAdmissionRefusedException`. |
| **2. Competing cancel/resume has one authoritative winner.** | **PASS (VERIFIED)** | D7-F03, D7-F08: Checkpoint CAS revision and atomic sequence numbers enforce a single winner. |
| **3. Cancellation survives process restart.** | **PASS (VERIFIED)** | D7-F10, ADV-04: Brand-new store instances backed by real DB preserve `CANCELLED_CLEAN` and `CANCELLED_UNCERTAIN`. |
| **4. Repeated cancellation is idempotent.** | **PASS (VERIFIED)** | D7-F09: Re-invoking `cancel()` on an already-cancelled run returns success without mutating revision or state. |
| **5. Late approval cannot authorize continuation.** | **PASS (VERIFIED)** | D7-F07, ADV-03: Approval claim on a cancelled run is refused; execution cannot proceed. |
| **6. Cancellation remains visible to timeline/reconstruction.** | **PASS (VERIFIED)** | D7-F11, Phase 4: `WorkflowHistoricalEvidenceReader` reconstructs exact cancellation state, reason, and timestamp. |
| **7. Cancellation never represents prior external effects as compensated.** | **PASS (VERIFIED)** | D7-F03, D7-F04, D7-F12: Runs with admitted effects commit `CANCELLED_UNCERTAIN` and flag `reconciliationRequired = true`. |

---

## 8. Hard Dependency Status: Epic 0.7.6

`EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md` declares:
`Dependencies: 0.7.6 HARD`

### Evaluation
- The core runtime controls of Epic 0.7.6 (Durable Approval Continuation contracts, storage, versioning, digest verification, and claim lifecycle) have been implemented and validated through D7-A through D7-E1.
- However, ancillary tasks in Track 0.7.6 (specifically tasks 0.7.6f–h regarding extended authorization policies and administrative CLI tooling) remain incomplete.
- **Architectural Verdict:** The cancellation and evidence integration closure delivered in D7-F is technically sound, verified against the durable continuation contracts, and introduces zero regressions. However, full release graduation of Epic 0.7.7 requires completion of remaining 0.7.6 prerequisites before final release cut.

---

## 9. Residual Limitations & Operational Governance

1. **Clock Skew in Distributed Leases:** Lease-fencing (E10) relies on system clocks. Environments with clock skew exceeding lease TTL (default 30s) must synchronize via NTP to avoid premature lease expiration.
2. **Operator Reconciliation Playbook:** For workflows in `CANCELLED_UNCERTAIN` or where `reconciliationRequired == true`:
   - Inspect the historical timeline using `WorkflowHistoricalEvidenceReader.readTimeline`.
   - Cross-reference external systems (e.g. payment gateway, HTTP server logs, process manager) using the recorded `idempotentKey` and `admissionSeq`.
   - Manually reconcile or compensate as appropriate; TramAI does not perform automated external compensation.
3. **Merge Authorization Hold:** Per `AGENTS.md` and repository instructions, this integration milestone is placed under **STRICT MERGE HOLD**. No pull request targeting `release/0.7.0` may be merged autonomously.
