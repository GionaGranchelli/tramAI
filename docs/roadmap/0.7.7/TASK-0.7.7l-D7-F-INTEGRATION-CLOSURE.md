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
3. **Deterministic Uncertainty Triaging:** If any outbound effect was admitted (`admission_seq > 0`) before cancellation committed, the workflow transitions to `WorkflowLifecycleStatus.CANCELLED_UNCERTAIN`. Such runs require operator reconciliation. Conversely, if cancellation commits prior to any effect admission, the workflow transitions to `WorkflowLifecycleStatus.CANCELLED`.
4. **Non-Data-Class Single-Use Permits:** Admission permits (`WorkflowAdmissionPermit`) are strictly single-use reference tokens that cannot be copied or replayed.
5. **No Synthetic Attempt Identities:** Approval resume records (E9) carry authentic `ParentProvenance.ApprovalContinuation` linking directly to the durable `ClaimedApprovalContinuation`, preventing synthetic attempt pollution.

---

## 2. Phase 1 — Comprehensive Integration Matrix

The following matrix documents the interaction across all governed boundaries, their authoritative storage components, enforcement points, evidence representations, and failure handling semantics under cancellation.

| Boundary | Name | Authority Component | Enforcement Point | Evidence Schema & Parent | Cancellation Impact | Reconciliation Required? |
|---|---|---|---|---|---|---|
| **E1/E2** | HTTP Client / Stream | `WorkflowCheckpointStore` | `admitEffect(HTTP, idempotentKey)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | If admitted prior to cancel: run commits `CANCELLED_UNCERTAIN`. HTTP call may finish or timeout. Evidence marked `UNCERTAIN` or `COMPLETED`. | Yes, if admitted |
| **E3/E4** | MCP Client / Tool Call | `WorkflowCheckpointStore` | `admitEffect(MCP, toolCallId)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Admitted MCP call may complete externally. Evidence marked `UNCERTAIN` on cancellation timeout. Subsequent MCP calls refused. | Yes, if admitted |
| **E5/E6** | Agent CLI / Process Invocation | `WorkflowCheckpointStore` | `admitEffect(PROCESS, pid/key)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Subprocess spawned before cancel may terminate or run to completion. Process manager marks `UNCERTAIN`. Subsequent spawn refused. | Yes, if admitted |
| **E7** | Process Execution | `WorkflowCheckpointStore` | `admitEffect(PROCESS, pid/key)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Subprocess spawned before cancel may terminate or run to completion. Evidence marked `UNCERTAIN` on timeout. Subsequent spawn refused. | Yes, if admitted |
| **E8** | External Executor | `WorkflowCheckpointStore` | `admitEffect(CUSTOM, effectKey)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.StepAttempt` | Effect admitted before cancel proceeds; post-cancel admission refused. | Yes, if admitted |
| **E9** | Approval Resume | `ApprovalContinuationStore` + `WorkflowCheckpointStore` | 5-dimension claim check + `admitEffect(APPROVAL_RESUME)` | `WorkflowEffectEvidenceRecord`<br>`ParentProvenance.ApprovalContinuation` | Claimed continuation before cancel transitions run to `CANCELLED_UNCERTAIN`. Post-cancel resume claim refused. | Yes, if claimed & admitted |
| **E10** | Recovery Lease | `WorkflowLeaseStore` | `admitUnderLeaseFence(lease)` | Transactional lease verification | Stale worker presenting expired lease rejected with `WorkflowAdmissionRefusedException`. Fencing survives process restart. | No (fenced cleanly) |
| **E11** | Step Admission | `WorkflowCheckpointStore` | `admit(request)` | Checkpoint revision & `admission_seq` | Post-cancel step admission rejected with `WorkflowAdmissionRefusedException`. Run state remains terminal. | No (fenced cleanly) |

---

## 3. Phase 2 — End-to-End Scenarios Qualification (D7-F01 – D7-F16)

The qualification suite is implemented in `WorkflowCrossBoundaryIntegrationTest.kt` using real database persistence (`JdbcWorkflowCheckpointStore`, `JdbcWorkflowEffectEvidenceRecordStore`, `JdbcWorkflowLeaseStore` against an in-memory MySQL/PostgreSQL schema backed by HikariCP).

All 16 scenarios were verified and passed cleanly:

### Scenario Results

- **D7-F01: Pre-Execution Cancellation**
  - *Setup:* Workflow created in `PENDING` state with zero effects admitted (`admission_seq = 0`).
  - *Action:* `checkpointStore.cancel(...)` executed before first step admission.
  - *Outcome:* Workflow transitions directly to `WorkflowLifecycleStatus.CANCELLED`. First step admission attempt fails immediately with `WorkflowAdmissionRefusedException`. Zero evidence records or side effects emitted.

- **D7-F02: Mid-Execution Step Boundary Cancellation**
  - *Setup:* Step 1 completes normally. Before Step 2 admission is requested, `cancel(...)` commits.
  - *Action:* Step 2 attempts `checkpointStore.admit(...)`.
  - *Outcome:* Step 2 admission refused with `WorkflowAdmissionRefusedException`. Run remains terminated.

- **D7-F03: Concurrently Admitted Effect During Cancellation**
  - *Setup:* Outbound effect (E1 HTTP) admits successfully (`admission_seq = 1`).
  - *Action:* Concurrently, `cancel(...)` is invoked with `expectedRevision == saved.revision`.
  - *Outcome:* Because `admission_seq > 0`, the store transitions to `WorkflowLifecycleStatus.CANCELLED_UNCERTAIN`. The admitted effect evidence transitions to `UNCERTAIN`. Subsequent admissions refused.

- **D7-F04: In-Flight Effect Timeout During Cancellation**
  - *Setup:* Effect admitted and dispatched. Tool coroutine experiences timeout during cancellation.
  - *Action:* Evidence handle executes `recordUncertain("HTTP call timed out during cancellation")`.
  - *Outcome:* Evidence state committed as `UNCERTAIN` without replacing or masking the primary cancellation exception.

- **D7-F05: Cancelled Run Resume Attempt**
  - *Setup:* Workflow reaches terminal cancellation (`WorkflowLifecycleStatus.CANCELLED`).
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
  - *Outcome:* Run transitions to `WorkflowLifecycleStatus.CANCELLED_UNCERTAIN`. Continuation evidence marked `UNCERTAIN`. No unverified execution inferred.

- **D7-F09: Repeated Idempotent Cancellation**
  - *Setup:* Workflow cancelled to `WorkflowLifecycleStatus.CANCELLED` at revision 1.
  - *Action:* Repeated `checkpointStore.cancel(...)` called with current revision.
  - *Outcome:* Idempotent success returned. State remains `WorkflowLifecycleStatus.CANCELLED`. Revision remains stable.

- **D7-F10: Process Restart Preserves Terminal State**
  - *Setup:* Workflow cancelled using `store1`.
  - *Action:* Process restarts: fresh `store2` connected to the same underlying JDBC database.
  - *Outcome:* `store2.load(...)` returns `WorkflowLifecycleStatus.CANCELLED`. Subsequent admissions via `store2` are refused.

- **D7-F11: Checkpoint Removal After Cancellation**
  - *Setup:* Workflow cancelled to `WorkflowLifecycleStatus.CANCELLED`. Checkpoint row removed or migrated. Evidence records preserved.
  - *Action:* Admission attempted on missing checkpoint.
  - *Outcome:* Fails closed with `WorkflowAdmissionRefusedException`. Historical evidence remains auditable.

- **D7-F12: Multiple Concurrent Heterogeneous Effects (E1, E3, E7, E9)**
  - *Setup:* Workflow concurrently admits HTTP (E1), MCP (E3), Process (E7), and Approval Resume (E9).
  - *Action:* Cancellation commits while effects are in various states (`COMPLETED`, `DISPATCHED`, `ADMITTED`).
  - *Outcome:* Run transitions to `WorkflowLifecycleStatus.CANCELLED_UNCERTAIN`. Evidence records reflect distinct execution phases. Gaps are preserved.

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

- **D7-F16: Real GovernedRun Workflow Step Composition with E9 Approval Resume Under Cancellation**
  - *Setup:* GovernedRun workflow step with real step admission and E9 approval resume coordination.
  - *Action:* Cancellation commits on workflow checkpoint; worker attempts E9 approval resume admission.
  - *Outcome:* E9 admission refused with `ApprovalResumeAdmissionRefusedException`; tool executor never invoked; zero E9 child evidence persisted; checkpoint records terminal `WorkflowLifecycleStatus.CANCELLED_UNCERTAIN`; timeline reader reconstructs exact state.
  - *Scope Distinction:* D7-F16 exercises targeted admission fencing of a `GovernedRun` workflow step under terminal cancellation, while complementary full engine-to-executor resume paths with `ApprovalResumeCoordinator` are validated in `E9WorkflowEngineResumeIntegrationTest` and `WorkflowApprovalResumeEvidenceContractTest`.

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
    val lifecycle: WorkflowLifecycleState?,
    val records: List<WorkflowEffectEvidenceRecord>,
    val admissionGaps: List<Long>,
    val requiresReconciliation: Boolean,
    val completeness: TimelineCompleteness = TimelineCompleteness.COMPLETE,
    val isGapsTruncated: Boolean = false,
)
```

### 5.2 Reconstruction Guarantees

1. **Chronological Event Ordering:** `WorkflowHistoricalEvidenceReader.readTimeline` reconstructs the complete lifecycle history from the authoritative checkpoint store and evidence store.
2. **Gap Detection:** Detects any sequence disparity where `admission_seq` was committed in the checkpoint store but no corresponding evidence record was written (e.g., due to post-admission crash or store outage). Gap enumeration is bounded (`DEFAULT_MAX_GAP_LIMIT = 1000`) with accurate truncation metadata evaluated at exact limit, one below, and one above.
3. **Reconciliation Flagging (`reconciliationRequired`):** Automatically evaluates to `true` (fail-closed) if:
   - The workflow lifecycle state is `CANCELLED_UNCERTAIN`.
   - The completeness classification is not `COMPLETE` (`UNKNOWN`, `GAPS_DETECTED`, or `INTEGRITY_VIOLATION`).
   - Any evidence record remains in `ADMITTED`, `DISPATCHED`, or `UNCERTAIN` state.
   - Any admission gaps exist.
   - Any associated approval continuation is in `CLAIMED` state or has missing continuation store authority.
   - Any completed E9 evidence mismatches the authoritative continuation identity: requires matching workflow run ID, matching tool name, and strict lineage compatibility where `continuation.version == parent.continuationVersion + 1L` (equal-version completion is rejected, reflecting the mandatory version increment on claim completion across all supported stores).
4. **Read-Only Non-Invasive Adapter & Forensic Inspection:** Kept strictly `internal` in `tramai-orchestration`, preserving binary API compatibility (`apiCheck` unchanged). Provides `ReadOnlyApprovalContinuationLookup` for authoritative side-effect-free forensic inspection, ensuring that evaluating an expired `PENDING` continuation does not trigger lazy expiry mutations, version increments, or payload scrubbing on durable continuation state. When callers pass an `ApprovalContinuationStore` directly, inspection delegates to `store.get`, which is subject to the store's lazy expiry lifecycle contract. Verified side-effect free using write-throwing store decorators and unmutated-pending tests.
5. **Multi-Generation Effect Preservation:** Multi-generation historical effect preservation is qualified via controlled database-fixture testing in `WorkflowHistoricalEvidenceTimelineTest` demonstrating cross-generation timeline reading without record loss; generation authenticity and incarnation fencing are maintained by store-level recovery authority rather than inferred by the timeline reader.

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

| Criterion | Status | Required evidence |
|---|---|---|
| **Authoritative cancellation** | **MET** | Real checkpoint and cancel path (D7-F01, D7-F02, D7-F05, ADV-01). Real `JdbcWorkflowCheckpointStore` sets terminal `WorkflowLifecycleStatus.CANCELLED` or `WorkflowLifecycleStatus.CANCELLED_UNCERTAIN`; subsequent admission fails closed with `WorkflowAdmissionRefusedException`. |
| **Effect admission fencing** | **MET** | E1–E9 and E11 coverage (D7-F01–D7-F04, D7-F08, D7-F12, D7-F16, ADV-01, ADV-12). Monotonic admission sequences committed atomically in checkpoint store. Post-cancel admissions refused fail-closed. D7-F16 exercises targeted admission fencing in `GovernedRun` under cancellation, complemented by end-to-end engine resume validation in `E9WorkflowEngineResumeIntegrationTest` and `WorkflowApprovalResumeEvidenceContractTest`. |
| **Recovery lease fencing** | **MET** | E10 tests (D7-F06, ADV-07). `JdbcWorkflowLeaseStore` with `admitUnderLeaseFence`; expired lease presented by stale worker refused with `WorkflowAdmissionRefusedException`. |
| **Restart survival** | **MET** | Persisted restart scenario (D7-F10, ADV-04). Re-instantiated store connected to existing JDBC database preserves terminal state and refuses execution. |
| **Late approval exclusion** | **MET** | E9 real resume path (D7-F07, D7-F13, D7-F16, ADV-03). `WorkflowApprovalResumeAdmission` inspects checkpoint before issuing permit; rejects resume on cancelled run; expired continuation fails closed. (Note: D7-F16 exercises targeted admission fencing in `GovernedRun` under cancellation; full engine-to-executor resume paths with `ApprovalResumeCoordinator` are validated in `E9WorkflowEngineResumeIntegrationTest` and `WorkflowApprovalResumeEvidenceContractTest`.) |
| **Cross-family evidence store correlation** | **MET** | Durable evidence records (Phase 3, `CrossFamilyEvidenceCorrelationTest`). HTTP (E1/E2), MCP (E3/E4), Process (E7), and Approval Resume (E9) records persist with correct run ID, generation, sequence, and typed parent provenance (`StepAttempt` vs `ApprovalContinuation`). |
| **Cross-family multi-runtime composition** | **PARTIAL** | End-to-end integration across individual boundaries and pairwise compositions (E11 step admission with E9 approval resume, HTTP E1, MCP E3/E4, and process E7 under cancellation) verified in D7-F01–D7-F16. Full simultaneous multi-family runtime pipeline composition across all 9 families within a single active execution graph deferred to multi-family orchestration milestone. |
| **Historical reconstruction truthfulness** | **MET** | Read-only reconstruction proof (Phase 4, `WorkflowHistoricalEvidenceTimelineTest`). `WorkflowHistoricalEvidenceReader.readTimeline` extracts exact lifecycle without side-effects or external tool execution; identifies admission gaps with bounded enumeration and exact-limit truncation accuracy; flags `reconciliationRequired` fail-closed on unknown completeness, sequence integrity violation, or continuation identity mismatch. Multi-generation effect preservation is verified via controlled database-fixture qualification, with generation incarnation authenticity governed by store-level recovery fences. |
| **Sensitive-data protection** | **MET** | Adversarial canary evidence (Phase 3, ADV-11). Canary secret strings in arguments, headers, and environment variables are never committed in plaintext to evidence stores or exception messages; SHA256 digests used. |
| **Integration and release gates** | **MET** | Exact-head qualification. CI suite runs clean on head `d07b94cf` (all 16 jobs in `CI` green: `tests`, `consumer-smoke`, `static-safety`, `static-analysis`, `compiler-warnings`, `governance-integration`, `hygiene`, `formatting`, `api-check`, `zero-egress`). Base-red disposition adjudicated for pre-existing `verify060Architecture` failure: 9 of 10 checks green; the 3 `API_COMPATIBILITY_FAILED` diagnostics on `:tramai-core` migration hashes are verified identical to pristine base commit `13ae6583a9272922b7a75fcd632ad94c3e8f75a3`. |

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
3. **Architecture Authority Gate Disposition:** The single failing architecture check (`api-architecture`) exhibits 3 exact diagnostic identities:
   - Module: `:tramai-core`
   - Hash transition 1: `86add99d12d4d989cbfa7599cb84f67ff4691452a8e7e727581fb1849a62372c → e3d294b2dcba1450a6e4dffabde0a256958b4382c448bb994c6326e0b04bc5f1`
   - Hash transition 2: `bbb2b420eaee60100aa749666014e768f760161479261ffcbb8cf263d91cfbd8 → ef6c0c685b5c9285038ec10d48a129033e54b6d5c52c2089408ecda44a95fb82`
   - Hash transition 3: `e3d294b2dcba1450a6e4dffabde0a256958b4382c448bb994c6326e0b04bc5f1 → bbb2b420eaee60100aa749666014e768f760161479261ffcbb8cf263d91cfbd8`
   All 3 diagnostics are verified identical to the pristine base commit `13ae6583a9272922b7a75fcd632ad94c3e8f75a3`. D7-F introduces zero modifications to `:tramai-core` or its public API; formal baseline exception disposition is deferred to the owning track. The architecture check is NOT suppressed or weakened.
4. **Merge Authorization Hold:** Per `AGENTS.md` and repository instructions, this integration milestone is placed under **STRICT MERGE HOLD**. No pull request targeting `release/0.7.0` may be merged autonomously.
