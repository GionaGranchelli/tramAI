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

## 2. Implemented Architecture

### 2.1 Option C: Typed Parent Provenance (`ParentProvenance.ApprovalContinuation`)

In `tramai-orchestration`, `WorkflowEffectEvidenceRecord` was enhanced with the sealed hierarchy `ParentProvenance`:

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

### 2.2 5-Dimension Fail-Closed Claim Verification

In `WorkflowApprovalResumeAdmission`, durable claim verification is mandatory whenever evidence tracking is enabled (`evidenceStore != null`):
1. **Continuation existence:** `continuationStore.get(request.approvalId)` must return a non-null record.
2. **CLAIMED status:** `stored.status == ApprovalContinuationStatus.CLAIMED`.
3. **Continuation version match:** `stored.version == request.continuationVersion`.
4. **Run attribution match:** `stored.workflowRunId == binding.workflowId`.
5. **Tool name match:** `stored.toolName == request.toolName`.

If any of these conditions fails, admission is refused immediately with `ApprovalResumeAdmissionRefusedException`. If `continuationStore` is null when evidence is enabled, admission fails closed.

### 2.3 Evidence Policy Separation from Claim Version

`TramaiEngine` and `ApprovalResumeCoordinator` distinguish between claim provenance being available and durable evidence being enforced:
- Resumes always carry `continuationVersion` from the claimed continuation.
- `TramaiEngine` exposes `approvalResumeEvidenceRequired: Boolean = false` (configurable via `EngineComponents`).
- When `approvalResumeEvidenceRequired == true`, `ApprovalResumeCoordinator` validates `requireNotNull(permit.evidenceHandle)`; if the handle is missing, it refuses to dispatch and fails closed.
- When `approvalResumeEvidenceRequired == false`, resumes proceed normally even if the permit has no evidence handle (preserving legacy runtime compatibility).

### 2.4 Cross-Module Evidence Handle

`tramai-core` defines the narrow `ApprovalResumeEvidenceHandle` interface:
```kotlin
interface ApprovalResumeEvidenceHandle {
    suspend fun recordDispatched()
    suspend fun recordCompleted()
    suspend fun recordUncertain(
        category: WorkflowEvidenceDiagnosticCategory,
        diagnostic: String? = null,
    )
}
```

`WorkflowApprovalResumeEvidenceHandle` in `tramai-orchestration`:
- Guards transitions using atomic terminal-state CAS flags.
- Executes persistence updates inside `withContext(NonCancellable)` so cancellation does not abort terminal evidence recording.
- Uses fixed, safe diagnostic categories (`WorkflowEvidenceDiagnosticCategory`) with sanitized summaries, preventing secret or payload leakage.

### 2.5 Phased JDBC Schema Migration & Dual-Writing

`JdbcWorkflowEffectEvidenceRecordStore` supports rolling schema upgrades:
- Phase 1: Add columns `parent_kind`, `parent_id`, `parent_version`, and drop `NOT NULL` from `step_attempt_id`.
- Phase 2: Dual-writing inserts and backfills `parent_id = step_attempt_id` for existing records.
- Phase 3: Reads prioritize `parent_kind` and `parent_id`, falling back to `step_attempt_id` if missing.
- Phase 4: Enforce `NOT NULL` constraint on `parent_id`.

---

## 3. Crash Semantics & Lifecycle Windows

| Window | Timing | Conservative Observable Guarantee |
| --- | --- | --- |
| **W1** | Crash before admission | No checkpoint admission, no evidence record created. |
| **W2** | Crash after checkpoint admission, before initial evidence write | Checkpoint admission rolls back; no orphan evidence record. |
| **W3** | Crash after initial evidence write (`ADMITTED`), before executor dispatch | Evidence remains `ADMITTED`. Recovery treats tool execution as unknown. |
| **W4** | Tool execution begins; worker crashes | Evidence remains `ADMITTED` or `DISPATCHED`. Recovery treats tool execution as uncertain. |
| **W5** | Tool execution succeeds; worker crashes before `recordCompleted()` | Evidence remains `DISPATCHED`. Recovery treats tool execution as completed externally but outcome unrecorded. |
| **W6** | `recordCompleted()` succeeds; worker crashes before continuation completion | Evidence is `COMPLETED`. Recovery knows the external effect completed safely and avoids re-execution. |
| **W7** | Tool throws unhandled exception | Evidence transitions to `UNCERTAIN` with `TOOL_EXECUTION_FAILED`. Exception rethrown. |
| **W8** | Coroutine cancelled before executor returns | Evidence transitions to `UNCERTAIN` with `COROUTINE_CANCELLED`. `CancellationException` preserved and rethrown. |
| **W9** | Continuation reclamation under recovery | New claim increments version; new resume creates distinct evidence record. Prior evidence record untouched. |
| **W10** | Permitted replay with identical admission sequence | Duplicate write rejected fail-closed; state machine immutability preserved. |

---

## 4. Verification & Qualification Results

All 49 comprehensive qualification tests pass in `WorkflowApprovalResumeEffectEvidenceTest.kt`:

### 4.1 End-to-End Scenarios (E9-E01 to E9-E24)
- **E9-E01:** Full happy-path lifecycle: `ADMITTED -> DISPATCHED -> COMPLETED`.
- **E9-E02:** Option C lineage verification: typed `ParentProvenance.ApprovalContinuation`.
- **E9-E03:** 5-dimension claim verification: continuation must exist in store.
- **E9-E04:** 5-dimension claim verification: continuation must be `CLAIMED`.
- **E9-E05:** 5-dimension claim verification: version mismatch rejected fail-closed.
- **E9-E06:** 5-dimension claim verification: workflowRunId mismatch rejected fail-closed.
- **E9-E07:** 5-dimension claim verification: toolName mismatch rejected fail-closed.
- **E9-E08:** Missing `continuationStore` fails closed when evidence enabled.
- **E9-E09:** Tool execution failure transitions record to `UNCERTAIN` (`TOOL_EXECUTION_FAILED`).
- **E9-E10:** Coroutine cancellation transitions record to `UNCERTAIN` (`COROUTINE_CANCELLED`).
- **E9-E11:** Timeout cancellation transitions record to `UNCERTAIN` (`TIMEOUT`).
- **E9-E12:** Standalone resumes (ungoverned) execute without evidence recording.
- **E9-E13:** Evidence policy disabled (`evidenceRequired == false`) allows resume with null handle.
- **E9-E14:** Evidence policy enabled (`evidenceRequired == true`) rejects resume with null handle.
- **E9-E15:** Checkpoint cancellation causes admission refusal; no evidence recorded.
- **E9-E16:** Single-use permit: permit cannot be re-admitted or reused.
- **E9-E17:** Non-data-class invariant on `ApprovalResumePermit`.
- **E9-E18:** Diagnostic summary sanitization: zero secret leakage in diagnostics.
- **E9-E19:** Terminal state idempotency on `ApprovalResumeEvidenceHandle`.
- **E9-E20:** Phased JDBC migration Phase 1 DDL validity.
- **E9-E21:** Phased JDBC migration Phase 2 backfill SQL validity.
- **E9-E22:** Phased JDBC migration Phase 3 dual-write & read compatibility.
- **E9-E23:** Phased JDBC migration Phase 4 constraint enforcement.
- **E9-E24:** Binary compatibility preserved on public constructors.

### 4.2 Crash Window & Lifecycle Scenarios (E9-W1 to E9-W10)
- **E9-W1:** Crash before admission leaves zero store side effects.
- **E9-W2:** Crash between checkpoint admission and evidence write rolls back.
- **E9-W3:** Crash after `ADMITTED` leaves conservative `ADMITTED` record.
- **E9-W4:** Worker crash during tool execution leaves conservative record.
- **E9-W5:** Tool success before completion record leaves `DISPATCHED` record.
- **E9-W6:** Completion recorded before continuation store update prevents re-execution.
- **E9-W7:** Tool exception records `UNCERTAIN` and rethrows.
- **E9-W8:** Cancellation records `UNCERTAIN` in `NonCancellable` context and rethrows.
- **E9-W9:** Reclaimed continuation uses incremented version and creates distinct child record.
- **E9-W10:** Duplicate admission sequence write rejected fail-closed.

### 4.3 Adversarial Mutation Kills (M01 to M15)
- **M01 (Kill):** Bypass 5-dimension validation $\implies$ killed by version mismatch discriminator.
- **M02 (Kill):** Omit initial `ADMITTED` persistence $\implies$ killed by existence check before dispatch.
- **M03 (Kill):** Skip `recordDispatched()` transition $\implies$ killed by state verification before execution.
- **M04 (Kill):** Mask cancellation exception on failure $\implies$ killed by `assertFailsWith<CancellationException>`.
- **M05 (Kill):** Mask timeout exception on failure $\implies$ killed by `assertFailsWith<TimeoutCancellationException>`.
- **M06 (Kill):** Omit `NonCancellable` in handle $\implies$ killed by cancelled context persistence test.
- **M07 (Kill):** Swap `stepAttemptId` for synthetic attempt ID $\implies$ killed by `parent` type discriminator.
- **M08 (Kill):** Relax `evidenceRequired` policy check $\implies$ killed by null handle rejection discriminator.
- **M09 (Kill):** Duplicate permit consumption $\implies$ killed by single-use permit consumption discriminator.
- **M10 (Kill):** Allow direct `ADMITTED -> COMPLETED` jump without `DISPATCHED` $\implies$ killed by state machine validator.
- **M11 (Kill):** Mutate `parent` provenance during update $\implies$ killed by parent immutability validator.
- **M12 (Kill):** Leak raw error message into diagnostic summary $\implies$ killed by sanitization discriminator.
- **M13 (Kill):** Omit rollback on initial evidence failure $\implies$ killed by transactional boundary check.
- **M14 (Kill):** Permit negative or zero continuation version $\implies$ killed by version precondition check.
- **M15 (Kill):** Misattribute workflow run ID $\implies$ killed by binding mismatch discriminator.

---

## 5. Non-Negotiable Invariants Audit

1. **Option C Lineage Preserved:** Resumes produce child evidence with `ParentProvenance.ApprovalContinuation`. No synthetic step attempt IDs are invented.
2. **5-Dimension Fail-Closed Admission:** In evidence-enabled configurations, every approval continuation claim is verified against durable storage before admission is granted.
3. **Fail-Closed Evidence Policy:** When `approvalResumeEvidenceRequired == true`, dispatch without an evidence handle is impossible.
4. **Cancellation Safety Preserved:** Cancellation exceptions are never swallowed, caught as general exceptions, or converted into permanent tool failures.
5. **Static Safety Guards Compliant:** `SupervisorJob` and `CoroutineScope` invocations remain within approved static analysis thresholds.
6. **Binary & JVM Compatibility Preserved:** Existing constructors on `ApprovalResumeAdmissionRequest`, `ApprovalResumePermit`, and `TramaiEngine` are preserved.
