# D7-E1 — Durable Approval Resume Evidence (Boundary E9) Implementation Report

**Status:** Completed & Qualified  
**Base:** `release/0.7.0` at `b4d1b1695f1cfdab5aac1684c619a6b67bf14935`  
**Working Branch:** `task/0.7.7k-d7-e1-approval-resume-evidence`  
**Authority:** `docs/roadmap/0.7.7/TASK-0.7.7j-E9-EVIDENCE-LINEAGE-CONTRACT.md`  
**Scope:** Production implementation, binary compatibility qualification, durable persistence proof, crash-window handling, and qualification suite for Workflow Approval Resume Evidence (**E9**).

---

## 1. Context & Mission

EPIC-0.7.7 establishes durable cancellation authority and atomic admissions across all outbound effect boundaries (E1–E9) and step boundaries (E11), with recovery re-execution fenced by lease ownership (E10). Milestone D7-E0 established the ratified design contract for **E9 — Workflow-Bound Approval Resume**.

Milestone **D7-E1** delivers the production implementation across `tramai-core`, `tramai-engine`, and `tramai-orchestration`.

Every evidence-enabled, workflow-bound approval resume must:
1. Be associated with an authentic, durably claimed approval continuation.
2. Be admitted by the workflow's authoritative checkpoint row.
3. Persist an E9 `ADMITTED` child evidence record before executor invocation.
4. Record the observed execution outcome through a narrow, type-safe cross-module evidence handle.
5. Preserve cancellation, claim recovery, single-use permit safety, and binary compatibility across all public contracts.

---

## 2. Implemented Architecture & Review Resolutions

### 2.1 Option C: Typed Parent Provenance (`ParentProvenance.ApprovalContinuation`)

In `tramai-orchestration`, `WorkflowEffectEvidenceRecord` provides typed parentage through the sealed hierarchy `ParentProvenance`:

```kotlin
sealed interface ParentProvenance {
    val parentKind: String
    val parentId: String
    val parentVersion: Long?
    val stepName: String

    data class StepAttempt(
        override val stepName: String,
        val stepAttemptId: String,
    ) : ParentProvenance {
        override val parentKind: String get() = "STEP_ATTEMPT"
        override val parentId: String get() = stepAttemptId
        override val parentVersion: Long? get() = null
    }

    data class ApprovalContinuation(
        val approvalId: String,
        val continuationVersion: Long,
        val toolName: String,
    ) : ParentProvenance {
        override val parentKind: String get() = "APPROVAL_CONTINUATION"
        override val parentId: String get() = approvalId
        override val parentVersion: Long get() = continuationVersion
        override val stepName: String get() = toolName
    }
}
```

- E9 resumes carry `ParentProvenance.ApprovalContinuation` pointing directly to the durable `ClaimedApprovalContinuation`.
- Synthetic step attempt identities are strictly avoided (`stepAttemptId` is `null` for E9 records).
- Backward compatibility is maintained for existing call sites via primary constructor defaults and secondary constructor overloads.
- Immutability of `parent` is defended across both `InMemoryWorkflowEffectEvidenceRecordStore` and `JdbcWorkflowEffectEvidenceRecordStore`.

### 2.2 Explicit Rollout Control at Orchestration Composition

To prevent E9 evidence records from being written prematurely during rolling deployments where E1–E8 evidence is enabled on the checkpoint store:
- `WorkflowApprovalResumeAdmission` provides explicit constructors taking `approvalResumeEvidenceEnabled: Boolean`.
- The public 1-argument `(WorkflowCheckpointStore)` and 2-argument `(WorkflowCheckpointStore, ApprovalContinuationStore)` constructors explicitly default `approvalResumeEvidenceEnabled = false`.
- Callers must pass `approvalResumeEvidenceEnabled = true` to activate E9 recording. When disabled, `evidenceHandle` remains `null` and zero E9 records are written, preserving compatibility with legacy readers.

### 2.3 Post-Admission Initial Evidence Failure (`ApprovalResumeEvidencePersistenceException`)

Initial evidence write occurs *after* `checkpointStore.admitEffect()` has committed an admission slot. Therefore:
- Failures during initial evidence persistence are **not** misclassified as admission refusals.
- `ApprovalResumeEvidencePersistenceException` is introduced in `tramai-core` (implementing `CopyableThrowable`).
- `WorkflowApprovalResumeAdmission` wraps initial persistence failures into this typed exception (rethrowing `CancellationException` directly).
- `ApprovalResumeCoordinator` catches `ApprovalResumeEvidencePersistenceException` and rethrows without invoking `refuseClaimedResume()` or emitting an uncertain execution outcome. The continuation remains in `CLAIMED` status, marking an unresolved gap for operator reconciliation rather than falsely treating it as a refused admission or an executed tool.
- Cross-store semantics: Checkpoint admission and evidence write are distinct operations; post-admission evidence persistence failure leaves an unresolved admission sequence for reconciliation rather than asserting distributed rollback across separate stores.

### 2.4 Bounded Evidence Operations & Non-Masking Uncertain Recording

`WorkflowApprovalResumeEvidenceHandle`:
- Bounds all store updates (`recordDispatched()`, `recordCompleted()`, `recordUncertain()`) using `withContext(NonCancellable) { withTimeoutOrNull(timeoutMillis) { ... } }` (defaulting to 5,000ms).
- In `recordUncertain()`, safely catches secondary update failures (rethrowing cancellation) so that underlying tool execution or cancellation failures are never masked by evidence store downtime.

### 2.5 JDBC Contradictory Parent Provenance Validation

In `JdbcWorkflowEffectEvidenceRecordStore.resolveEvidenceParent`:
- Records declaring `parentKind == "APPROVAL_CONTINUATION"` must have `step_attempt_id == null`. Non-null legacy attempt IDs are rejected with `IllegalStateException`.
- Records declaring `parentKind == "STEP_ATTEMPT"` with both `parent_id` and `step_attempt_id` present must have `parent_id == step_attempt_id`. Contradictory attempt IDs are rejected with `IllegalStateException`.
- Reading operations wrapped in `persistenceBoundary` report `WorkflowPersistenceFailureException` while delivering the underlying invariant violation to `PersistenceFailureDiagnosticObserver`.

### 2.6 5-Dimension Fail-Closed Claim Verification

In `WorkflowApprovalResumeAdmission`, durable claim verification is mandatory whenever evidence tracking is enabled (`approvalResumeEvidenceEnabled == true`):
1. **Continuation existence:** `continuationStore.get(request.approvalId)` must return a non-null record.
2. **CLAIMED status:** `stored.status == ApprovalContinuationStatus.CLAIMED`.
3. **Continuation version match:** `stored.version == request.continuationVersion`.
4. **Run attribution match:** `stored.workflowRunId == binding.workflowId`.
5. **Tool name match:** `stored.toolName == request.toolName`.

If any of these conditions fails, admission is refused immediately with `ApprovalResumeAdmissionRefusedException`. If `continuationStore` is null when evidence is enabled, admission fails closed.

---

## 3. Crash Semantics & Lifecycle Windows

| Window | Timing | Conservative Observable Guarantee |
| --- | --- | --- |
| **W1** | Crash before admission | No checkpoint admission, no evidence record created. |
| **W2** | Crash after checkpoint admission, before initial evidence write | Checkpoint sequence consumed; initial evidence absent; continuation remains `CLAIMED` for reconciliation. |
| **W3** | Crash after initial evidence write (`ADMITTED`), before executor dispatch | Evidence remains `ADMITTED`. Recovery treats tool execution as unknown. |
| **W4** | Tool execution begins; worker crashes | Evidence remains `ADMITTED` or `DISPATCHED`. Recovery treats tool execution as uncertain. |
| **W5** | Tool execution succeeds; worker crashes before `recordCompleted()` | Evidence remains `DISPATCHED`. Recovery treats tool execution as completed externally but outcome unrecorded. |
| **W6** | `recordCompleted()` succeeds; worker crashes before continuation completion | Evidence is `COMPLETED`. Recovery knows the external effect completed safely and avoids re-execution. |
| **W7** | Tool throws unhandled exception | Evidence transitions to `UNCERTAIN` with `TOOL_EXECUTION_FAILED`. Exception rethrown. |
| **W8** | Coroutine cancelled before executor returns | Evidence transitions to `UNCERTAIN` with `TOOL_EXECUTION_CANCELLED`. `CancellationException` preserved and rethrown. |
| **W9** | Continuation reclamation under recovery | New claim increments version; new resume creates distinct evidence record. Prior evidence record untouched. |
| **W10** | Permitted replay with identical admission sequence | Duplicate write rejected fail-closed; state machine immutability preserved. |

---

## 4. Verification & Qualification Results

All 56 comprehensive qualification tests pass in `WorkflowApprovalResumeEffectEvidenceTest.kt`, plus 2 integration tests in `E9WorkflowEngineResumeIntegrationTest.kt` and 9 tests in `WorkflowApprovalResumeAdmissionTest.kt`.

### 4.1 End-to-End Scenarios (E9-E01 to E9-E29)
- **E9-E01:** Happy path approval resume produces authoritative ADMITTED-DISPATCHED-COMPLETED evidence with Option C ParentProvenance.
- **E9-E02:** Tool execution failure records UNCERTAIN evidence with TOOL_EXECUTION_FAILED category.
- **E9-E03:** Tool execution cancellation records UNCERTAIN evidence with TOOL_EXECUTION_CANCELLED category.
- **E9-E04:** Tool execution timeout records UNCERTAIN evidence with TOOL_EXECUTION_TIMEOUT category.
- **E9-E05:** Outcome separation — Continuation completion store failure preserves COMPLETED evidence without degrading to UNCERTAIN.
- **E9-E06:** Cancelled checkpoint refuses resume admission, tool never executed, and no evidence written.
- **E9-E07:** Evidence disabled deployment remains fully compatible without writing evidence.
- **E9-E07b:** Public constructor with checkpoint evidence enabled but E9 disabled writes zero E9 records.
- **E9-E07c:** Public constructor with E9 explicitly enabled records E9 evidence.
- **E9-E08:** Misconfiguration — evidenceRequired is true but admission authority has null handle fails closed before tool execution.
- **E9-E09:** Evidence enabled but continuationStore missing from authority fails closed at admission.
- **E9-E10:** Evidence enabled but continuationVersion is non-positive fails closed at admission.
- **E9-E11:** 5-dimension validation — forged continuation version (e.g. 7 vs 1) refused fail-closed.
- **E9-E12:** 5-dimension validation — mismatched tool name refused fail-closed.
- **E9-E13:** 5-dimension validation — mismatched workflow run ID refused fail-closed.
- **E9-E14:** 5-dimension validation — continuation not in CLAIMED status refused fail-closed.
- **E9-E15:** 5-dimension validation — continuation not found in store refused fail-closed.
- **E9-E16:** Standalone suspension executes without workflow admission or evidence.
- **E9-E17:** Single-use permit — second consume throws IllegalStateException.
- **E9-E18:** Concurrent resume mutual exclusion — exactly one claim succeeds and exactly one tool execution occurs.
- **E9-E19:** JDBC persistence round-trip preserves Option C dedicated parent columns and deserializes typed ParentProvenance.
- **E9-E20:** JDBC phased schema migration, backfill, and mixed reading of legacy E1-E8 step attempts and new E9 records.
- **E9-E21:** Monotonicity — invalid state transitions are rejected by store.
- **E9-E22:** Immutability — key, parent, and createdAt are immutable during update.
- **E9-E23:** Non-data-class permit — ApprovalResumePermit does not expose copy() method.
- **E9-E24:** Module boundary decoupling — tramai-engine contains zero bytecode references to tramai-orchestration.
- **E9-E25:** Post-admission initial evidence failure throws typed PersistenceException and leaves continuation CLAIMED.
- **E9-E26:** Contradictory parent decoding in JDBC evidence store fails closed.
- **E9-E27:** Engine resume round-trip with real JDBC stores verifies all 14 database columns (H2 in PostgreSQL mode).
- **E9-E28:** Evidence handle bounded execution with stalling store.
- **E9-E29:** Evidence handle recordUncertain safely swallows secondary update errors.

### 4.2 Lifecycle & Crash Scenarios (E9-W1 to E9-W10)
- **E9-W1:** Crash before continuation claim — continuation remains PENDING and evidence is absent.
- **E9-W2:** Claim succeeds, crash before admission — continuation is CLAIMED and evidence is absent.
- **E9-W3:** Admission succeeds, crash before tool dispatch — evidence is ADMITTED, tool never executed.
- **E9-W4:** Crash during tool execution — evidence remains ADMITTED when process dies before uncertain handler.
- **E9-W5:** Tool execution throws exception — evidence transitions to UNCERTAIN.
- **E9-W6:** Tool succeeds, crash before continuation complete — evidence is COMPLETED and continuation is CLAIMED.
- **E9-W7:** Continuation completion fails — operator reconciliation required, tool executed once.
- **E9-W8:** Checkpoint cancelled while waiting for approval — admitResume refuses and tool never dispatched.
- **E9-W9:** Checkpoint generation rotated by retry or recovery — admission against current row succeeds.
- **E9-W10:** Concurrent resume claims — exactly one claim succeeds, loser fails closed with conflict.

### 4.3 Regression Discriminators (M01 to M15)
- **M01 (Discriminator):** Bypassing continuation store validation when evidence is enabled fails closed.
- **M02 (Discriminator):** Forged continuation claim version cannot bypass validation.
- **M03 (Discriminator):** Mismatched tool name cannot bypass validation.
- **M04 (Discriminator):** Mismatched workflow run ID cannot bypass validation.
- **M05 (Discriminator):** Non-CLAIMED continuation cannot be admitted.
- **M06 (Discriminator):** Missing continuation cannot be admitted.
- **M07 (Discriminator):** Non-positive continuationVersion fails closed before checkpoint write.
- **M08 (Discriminator):** Engine with evidenceRequired fails closed when permit has null evidenceHandle.
- **M09 (Discriminator):** Evidence handle records ADMITTED before tool execution begins.
- **M10 (Discriminator):** Tool execution records DISPATCHED before tool returns.
- **M11 (Discriminator):** Tool failure cannot record COMPLETED evidence.
- **M12 (Discriminator):** completeClaimedResume failure does not overwrite COMPLETED evidence to UNCERTAIN.
- **M13 (Discriminator):** Cancelled checkpoint refuses before tool execution or evidence write.
- **M14 (Discriminator):** Standalone suspension never requires workflow evidence.
- **M15 (Discriminator):** ApprovalResumePermit is strictly single-use and cannot be cloned or re-consumed.

---

## 5. Non-Negotiable Invariants Audit

1. **Option C Lineage Preserved:** Resumes produce child evidence with `ParentProvenance.ApprovalContinuation`. No synthetic step attempt IDs are invented.
2. **5-Dimension Fail-Closed Admission:** In evidence-enabled configurations, every approval continuation claim is verified against durable storage before admission is granted.
3. **Fail-Closed Evidence Policy:** When `approvalResumeEvidenceRequired == true`, dispatch without an evidence handle is impossible.
4. **Post-Admission Persistence Failure Separation:** Initial evidence persistence failure throws `ApprovalResumeEvidencePersistenceException` leaving continuation in `CLAIMED` status without dispatching the tool.
5. **Bounded Execution:** Evidence handle operations are bounded by timeout and do not mask primary execution or cancellation errors.
6. **Cancellation Safety Preserved:** Cancellation exceptions are never swallowed, caught as general exceptions, or converted into permanent tool failures.
7. **Static Safety Guards Compliant:** Detekt baseline unchanged (0 added, 0 removed; ceiling respected).
8. **Binary & JVM Compatibility Preserved:** Public constructors on `WorkflowApprovalResumeAdmission`, `ApprovalResumeAdmissionRequest`, `ApprovalResumePermit`, and `TramaiEngine` are preserved and verified via `apiCheck`.
