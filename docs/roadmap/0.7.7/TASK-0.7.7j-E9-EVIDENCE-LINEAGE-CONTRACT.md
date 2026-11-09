# D7-E0 — Authoritative Approval Resume Evidence & Lineage Contract (Boundary E9)

**Status:** Completed & Ratified  
**Base:** `release/0.7.0` at `7bd0252b5ef90cfaf8ec26b8ed22f43ef0e5b756`  
**Task Branch:** `task/0.7.7j-d7-e0-approval-resume-evidence-contract`  
**Scope:** Authoritative lineage, parent provenance, durable admission semantics, state transitions, crash windows, module boundary preservation, and characterization qualification for Workflow Approval Resume Evidence (**E9**).

---

## 1. Context & Mission

EPIC-0.7.7 establishes durable cancellation authority and atomic admissions across all outbound effect boundaries (E1–E9) and step boundaries (E11), with recovery re-execution fenced by lease ownership (E10). Milestones D7-A through D7-D ratified durable per-effect evidence for HTTP (E1/E2), Shell (E7), Agent CLI (Codex E5, Hermes E6), MCP (E3/E4), and External Executors (E8).

Milestone **D7-E0** establishes the architectural contract for **E9 — Workflow-Bound Approval Resume**.

When an AI service operation encounters a high-side-effect tool call requiring human authorization, execution suspends:
1. Engine captures metadata and replay history into `SuspendedInvocationStore`.
2. Engine creates a pending `ApprovalContinuation` in `ApprovalContinuationStore`.
3. The calling coroutine terminates with `ApprovalSuspendedException`, unwinding the workflow execution stack.

Hours, days, or weeks later, an operator authorizes and resumes the execution.
Prior to D7-E0, E9 acquired an admission permit (`ApprovalResumePermit`) against the workflow checkpoint row to check for concurrent cancellation, but produced **no durable per-effect evidence record** in `WorkflowEffectEvidenceRecordStore`.

The mission of D7-E0 is to establish the authoritative lineage, admission, state-transition, crash-safety, and module-decoupling semantics necessary for durable E9 evidence, **without inventing synthetic step attempts, without corrupting supervisor recovery, and without altering standalone execution semantics**.

---

## 2. Phase 0: Architectural Reconstruction & Reality

A rigorous audit of the existing codebase (`tramai-core`, `tramai-engine`, `tramai-orchestration`, `tramai-security`) reveals the concrete topology of approval suspension and resumption:

### 2.1 Suspension Topology
- **Trigger:** Inside `TramaiEngine.executeTool()`, `ApprovalPolicyEngine` returns `PolicyDecision.RequireApproval`.
- **Coordination:** `ApprovalSuspensionCoordinator` creates an `ApprovalChallenge` via `ApprovalGateCoordinator`, writes safe `SuspendedInvocationMetadata` and `SensitiveReplayEnvelope` to `SuspendedInvocationStore`, and creates an `ApprovalContinuation` (status `PENDING`, version `0L`) in `ApprovalContinuationStore`.
- **Provenance Stamping:** If the coroutine context carries `WorkflowCheckpointBindingScope` (injected by `WorkflowRunner` during workflow execution), `ApprovalSuspensionCoordinator` marks `SuspensionBindingProvenance.WORKFLOW_BOUND` and records `WorkflowCheckpointBinding(workflowName, workflowId)`.
- **Execution Termination:** `ApprovalSuspensionCoordinator` throws `ApprovalSuspendedException`.
- **Attempt Unwinding:** The coroutine stack unwinds. Any active `StepAttemptScope` (carrying `PersistedStepAttempt`) terminates. In in-process workflows (`workflow.run`), no supervisor or `StepAttemptAuthority` exists at all (`currentStepAttempt() == null`). In worker-supervised workflows (`WorkflowExecutionSupervisor`), the step attempt completes exceptionally and is closed.

### 2.2 Resume Topology
- **Entry:** Operator submits `ResumeApprovalCommand` to `TramaiEngine.resumeApproval(command)` (or `ApprovalResumeCoordinator.resume(command)`).
- **Claim:** Coordinator calls `ApprovalContinuationStore.claimForExecution(approvalId, expectedVersion, claimedBy)`.
  - Atomically transitions status from `PENDING` to `CLAIMED` and increments version to `expectedVersion + 1`.
  - Concurrent claims fail with `ApprovalContinuationConflictException`.
- **Admission:** For `WORKFLOW_BOUND` suspensions, coordinator invokes `ApprovalResumeAdmission.admitResume(...)` (implemented by `WorkflowApprovalResumeAdmission`).
  - Reads durable checkpoint from `WorkflowCheckpointStore.load(binding.workflowName, binding.workflowId)`.
  - Validates run attribution continuity (`requireGovernedRunAttributionContinuity`).
  - Issues conditional store write via `checkpointStore.admitEffect(...)` against `checkpoint.revision` and `checkpoint.checkpointGeneration`.
  - If cancelled, throws `ApprovalResumeAdmissionRefusedException`.
  - If admitted, returns `ApprovalResumePermit(approvalId, checkpointBinding, admissionSequence)`.
- **Tool Dispatch:** Coordinator resolves tool and executes continuation via `resumeExecutor.execute(tool, arguments, context)`.
- **Completion:** Coordinator marks continuation completed via `claimService.completeClaimedResume(...)` in `ApprovalContinuationStore`.
- **Store Cleanup:** Coordinator removes suspended invocation record from `SuspendedInvocationStore`.

### 2.3 Existing Field Analysis
| Identifier | Present at Resume Admission | Source / Authority |
| --- | --- | --- |
| `approvalId` | Yes | `ApprovalContinuation.approvalId` |
| `workflowName` | Yes | `WorkflowCheckpointBinding.workflowName` |
| `workflowId` (runId) | Yes | `WorkflowCheckpointBinding.workflowId` |
| `admissionSequence` | Yes | `WorkflowAdmission.sequence` from `WorkflowCheckpointStore.admit()` |
| `checkpointGeneration` | Present on row, discarded | `WorkflowCheckpoint.checkpointGeneration` |
| `stepName` / `toolName` | Yes | `metadata.toolName` / `continuation.toolName` |
| `stepAttemptId` | **ABSENT** | Does not exist at resume boundary |

---

## 3. Phase 1: Lineage Evaluation & Decision Gate

In existing effect boundaries (E1–E8), every child evidence record points to an authoritative parent `stepAttemptId` (`PersistedStepAttempt.attemptId`). For E9, three candidate lineage architectures were evaluated:

### 3.1 Option A: Preserve and Reuse Originating Step Attempt
- **Concept:** Save the originating `stepAttemptId` in `SuspendedInvocationMetadata` at suspension time, and reuse it as the parent attempt when resuming.
- **Fatal Defect 1 (In-Process Workflows):** In standard in-process workflows (`workflow.run()`), there is no worker lease, no supervisor, and no `StepAttemptAuthority`. `resolveStepAttemptAuthority(frame)` returns `null`, and `currentStepAttempt()` is `null`. Forcing an attempt ID requires inventing a fake synthetic attempt ID, violating the Non-Negotiable Invariant: *No guessed or synthetic step-attempt identities*.
- **Fatal Defect 2 (Dead Attempt Lineage):** The originating step attempt terminated when `ApprovalSuspendedException` was thrown. An asynchronous resume occurring days later under an operator's command cannot truthfully claim to execute inside an attempt that died days prior.
- **Fatal Defect 3 (Retries and Re-claims):** If a claimed resume crashes and is reclaimed under recovery, reusing the original attempt ID causes primary key collisions and violates temporal causality in `WorkflowEffectEvidenceRecordStore`.
- **Verdict:** **REJECTED**.

### 3.2 Option B: Create Dedicated Step Attempt for Resume
- **Concept:** Invoke `StepAttemptAuthority.beginAttempt(...)` inside `ApprovalResumeAdmission.admitResume(...)` to create a new step attempt specifically for the resume.
- **Fatal Defect 1 (Lease Violation):** `StepAttemptRecord` requires `workerId`, `leaseToken`, and `replayPolicy`. Approval resumes do not hold a workflow worker lease; they are operator-driven. Creating a step attempt without a lease violates the E10 fencing invariant.
- **Fatal Defect 2 (Supervisor & Recovery Corruption):** `WorkflowExecutionSupervisor` and `WorkflowRecoveryCoordinator` query `StepAttemptRecordStore` and assume attempts correspond to steps defined in the `Workflow` DAG. Injecting pseudo-step attempts (e.g. named after tools or approval IDs) corrupts workflow recovery reconciliation and step execution counting.
- **Verdict:** **REJECTED**.

### 3.3 Option C: Typed Parent Provenance (RATIFIED)
- **Concept:** Recognize that an approval resume is **not a step attempt**; it is an **approval continuation claim**.
- **Authoritative Durable Parent:** The true persisted authority for an E9 resume is `ClaimedApprovalContinuation` in `ApprovalContinuationStore` (`approvalId`, `version`, `claimedBy`, `claimedAt`).
- **Data Model:** Enhance `WorkflowEffectEvidenceRecord` to support typed parent provenance:
  - `ParentProvenance.StepAttempt(stepName, stepAttemptId)` for E1–E8, E11.
  - `ParentProvenance.ApprovalContinuation(approvalId, continuationVersion, toolName)` for E9.
- **Trusted Source & Validation Boundary:**
  - **Trusted Source:** Inside `ApprovalResumeCoordinator`, the claim version originates exclusively from `ApprovalContinuationStore.claimForExecution(approvalId, expectedVersion, claimedBy)`. This claim is taken under cryptographic presented-token verification, single-use CAS, and transition from `PENDING` to `CLAIMED`, atomically yielding the incremented `ClaimedApprovalContinuation.version`.
  - **Transmission:** `ApprovalResumeAdmissionRequest` in `tramai-core` conveys `continuationVersion: Long` (with a binary-compatible 4-argument constructor supplying `0L` for legacy compiled callers).
  - **Validation Boundary:** In D7-E1, `WorkflowApprovalResumeAdmission` accepts `continuationStore: ApprovalContinuationStore?` as a validator seam. When evidence recording is enabled (`evidenceStore != null`), the authority verifies the claim against durable continuation state:
    1. The continuation exists in the store.
    2. `continuation.status == ApprovalContinuationStatus.CLAIMED`.
    3. `continuation.version == request.continuationVersion`.
    4. Run attribution matches (`continuation.workflowRunId == request.checkpointBinding.workflowId`).
    If any check fails (such as an forged version like version 7 when the store holds version 1), admission is refused fail-closed with `ApprovalResumeAdmissionRefusedException`. False provenance cannot be admitted or persisted.
- **Advantages:**
  1. **100% Truthful:** Reflects the exact runtime and durable architecture without inventing fake entities.
  2. **Universal:** Works identically for in-process workflows and worker-supervised workflows.
  3. **Strict Causality:** Monotonic continuation versions ensure that every resume attempt has a unique, non-colliding lineage.
  4. **Zero Interference:** Completely decouples approval resumes from step-attempt leasing and supervisor state machines.
- **Verdict:** **RATIFIED**.

### 3.4 Decision Matrix
| Evaluation Criterion | Option A (Preserve Attempt) | Option B (Dedicated Attempt) | Option C (Typed Provenance) |
| --- | --- | --- | --- |
| Truthfulness of Lineage | Fails (attempt is dead) | Fails (invented step) | **Guaranteed (real claim)** |
| In-Process Workflow Support | Broken (attempt is null) | Broken (no store) | **Full Support** |
| Worker Lease Integrity | Compromised | Broken (fencing bypass) | **Preserved** |
| Supervisor Recovery Safety | At Risk | Corrupted | **Preserved** |
| Unique Across Re-claims | No (collision) | Yes | **Yes (versioned)** |

---

## 4. Phase 2: Evidence State Transitions & Semantics

Every governed E9 approval resume produces a distinct per-effect child evidence record that transitions through audited states:

```text
[Approval Claimed]
        │
        ▼
   [ADMITTED] ────────► (Refusal / Cancelled Checkpoint: No tool execution)
        │
        ▼
[Tool Execution]
   ├── Normal Return ──► [DISPATCHED] ──► [COMPLETED] ──► [Continuation Completed]
   └── Throws / Cancel ─► [UNCERTAIN]
```

### 4.1 State Definitions
- **`ADMITTED`:** Written by orchestration inside `admitResume()` before returning `ApprovalResumePermit`. Proves durable authorization against the workflow checkpoint row. In a synchronous, deterministic runtime test, we can verify that the tool has not been dispatched when admission is granted. However, in production crash recovery analysis, the presence of `ADMITTED` alone does not prove that subsequent dispatch was never attempted prior to the crash.
- **`DISPATCHED`:** Recorded when `resumeExecutor.execute(...)` returns normally. (Conservative principle: entering the tool callback does not establish outbound completion; if the tool execution fails or cancels, evidence transitions directly to `UNCERTAIN`).
- **`COMPLETED`:** Recorded immediately after `DISPATCHED` and before `completeClaimedResume(...)`. Proves the tool executed to completion.
- **`UNCERTAIN`:** Recorded if tool execution throws an exception, is cancelled, or if persistence fails after admission.

### 4.2 Outcome Separation: Tool Execution vs Continuation Completion
`ApprovalResumeCoordinator` has two sequential post-execution operations:
1. Tool execution completes (`resumeExecutor.execute(...)`).
2. Continuation completion commits (`claimService.completeClaimedResume(...)`).

If `completeClaimedResume(...)` subsequently fails (e.g., database connectivity lost or continuation conflict):
- The tool **did** execute.
- The E9 evidence record **remains `COMPLETED`**.
- It must **never** be retroactively overwritten as `UNCERTAIN` or marked as tool failure.
- The continuation remains in state `CLAIMED`. Operator recovery inspects the evidence record: seeing `COMPLETED` evidence, recovery knows the tool completed and reconciles the continuation without re-executing the tool.

### 4.3 Deterministic Assertions vs Operator Recovery
It is critical to distinguish between two analytical perspectives:
- **Deterministic Runtime Characterization (Tests):** In a controlled harness, we observe the exact sequence of in-memory calls. If a test halts execution after admission, we can deterministically assert that `tool.executions.get() == 0`.
- **Production Crash Recovery (Durable Evidence):** After an uncoordinated node crash or network partition, recovery code only has the persisted records in `ApprovalContinuationStore` and `WorkflowEffectEvidenceRecordStore`. Because a crash could occur during or immediately following dispatch before evidence is updated, missing evidence or an `ADMITTED` record cannot prove non-execution. Safe recovery requires reconciliation rather than assuming non-execution.

---

## 5. Phase 3: Approval Provenance Rules

Every governed suspension explicitly records its `SuspensionBindingProvenance`:

1. **`WORKFLOW_BOUND`:**
   - Must carry a non-null `WorkflowCheckpointBinding(workflowName, workflowId)` where `workflowId == runIdentity.runId.value`.
   - Resume requires `WorkflowApprovalResumeAdmission` to verify the durable checkpoint row.
   - Refuses resume if the checkpoint does not exist, attribution mismatches, or lifecycle is `CANCELLED`.
   - Writes durable E9 evidence record.

2. **`STANDALONE`:**
   - Created outside workflow context; carries `checkpointBinding = null`.
   - Resumes without invoking workflow admission or writing workflow evidence records.
   - Must **never** acquire synthetic workflow lineage.

3. **`LEGACY_UNKNOWN`:**
   - Pre-governed or unclassified suspension (`checkpointBinding = null`).
   - Under the workflow-authoritative profile (`WorkflowApprovalResumeAdmission` configured), it **fails closed** and refuses resume (`ApprovalResumeAdmissionRefusedException: the suspension records no binding provenance`).

---

## 6. Phase 4: Cross-Module Evidence Interface Design

The dependency hierarchy must be strictly preserved:
$$\text{tramai-core} \longleftarrow \text{tramai-engine} \longleftarrow \text{tramai-orchestration}$$

- `tramai-engine` must **never** depend on `tramai-orchestration`.
- `tramai-engine` must **never** reference `WorkflowEffectEvidenceRecordStore`.

### 6.1 SPI & Request in `tramai-core`
In `dev.tramai.core.approval`:
```kotlin
/**
 * One approval-resume admission request (E9).
 * Preserves binary compatibility with existing 4-parameter callers while conveying continuationVersion.
 */
data class ApprovalResumeAdmissionRequest(
    val approvalId: String,
    val toolName: String,
    val runIdentity: GovernedRunIdentity,
    val checkpointBinding: WorkflowCheckpointBinding,
    val continuationVersion: Long = 0L,
) {
    // Retained for binary compatibility with compiled callers
    constructor(
        approvalId: String,
        toolName: String,
        runIdentity: GovernedRunIdentity,
        checkpointBinding: WorkflowCheckpointBinding,
    ) : this(approvalId, toolName, runIdentity, checkpointBinding, 0L)
}

/**
 * Fixed diagnostic categories for durable evidence.
 * Raw exception messages, sensitive paths, or arbitrary strings must NEVER enter durable evidence.
 */
enum class WorkflowEvidenceDiagnosticCategory {
    TOOL_EXECUTION_FAILED,
    TOOL_EXECUTION_TIMEOUT,
    TOOL_EXECUTION_CANCELLED,
    PERSISTENCE_FAILED,
    RECONCILIATION_REQUIRED,
}

/**
 * Callback handle for recording evidence during approval resume execution.
 * Passed via [ApprovalResumePermit] to bridge orchestration evidence storage
 * across the engine execution boundary without violating module dependencies.
 */
interface ApprovalResumeEvidenceHandle {
    suspend fun recordDispatched()
    suspend fun recordCompleted()
    suspend fun recordUncertain(error: Throwable, category: WorkflowEvidenceDiagnosticCategory)
}
```

### 6.2 Permit Preservation, Single-Use & Runtime Compatibility
`ApprovalResumePermit` must **remain a standard class** (NOT a Kotlin `data class`).
A `data class` automatically synthesizes `.copy()`, which would allow callers to clone the permit and produce a fresh instance with an unconsumed `AtomicBoolean(false)`, silently destroying the single-use invariant that guarantees at most one dispatch per permit.

```kotlin
@ExperimentalTramaiInternalApi
class ApprovalResumePermit(
    val approvalId: String,
    val checkpointBinding: WorkflowCheckpointBinding,
    val admissionSequence: Long,
    val checkpointGeneration: String? = null,
    val evidenceHandle: ApprovalResumeEvidenceHandle? = null,
) {
    private val consumed = AtomicBoolean(false)

    // Retained 3-parameter constructor for binary compatibility
    constructor(
        approvalId: String,
        checkpointBinding: WorkflowCheckpointBinding,
        admissionSequence: Long,
    ) : this(approvalId, checkpointBinding, admissionSequence, null, null)

    /**
     * Consume the permit: true exactly once. A second call fails.
     */
    fun tryConsume(): Boolean {
        check(!consumed.getAndSet(true)) {
            "approval resume permit for '${checkpointBinding.workflowName}/${checkpointBinding.workflowId}' " +
                "approval '$approvalId' (admission $admissionSequence) was already consumed; " +
                "one permit authorizes at most one resumed invocation"
        }
        return true
    }
}
```

#### Runtime Compatibility vs Evidence Enforcement Policy
Compatibility is preserved at both binary and runtime levels:
1. **Evidence-Disabled Legacy Deployments (`evidenceStore == null`):**
   - An admission request with `continuationVersion == 0L` (e.g. from the retained 4-argument constructor) is accepted and admitted against the checkpoint row as before, returning an `ApprovalResumePermit(evidenceHandle = null)`. Existing workflows without evidence tracking continue to function with zero behavioral regression.
2. **Evidence-Enabled Deployments (`evidenceStore != null`):**
   - The authority **fails closed** if `request.continuationVersion <= 0L`: missing continuation claim provenance cannot produce durable child evidence (`ApprovalResumeAdmissionRefusedException: cannot record approval resume evidence without authoritative continuation claim version`).
   - The issued permit **must** carry a non-null `evidenceHandle`. If evidence is required and the handle is absent, the engine must fail closed rather than allowing a governed invocation to execute unrecorded.

### 6.3 Orchestration Implementation & Evidence Store Ownership
- `WorkflowApprovalResumeAdmission` accepts `evidenceStore: WorkflowEffectEvidenceRecordStore? = null` and `continuationStore: ApprovalContinuationStore? = null` directly via **constructor dependency injection** at composition time. It does **not** rely on coroutine-local context scopes like `WorkflowEffectEvidenceStoreScope`.
- In `admitResume()`, orchestration:
  1. Validates attribution continuity.
  2. If evidence is enabled (`evidenceStore != null`), validates that `request.continuationVersion > 0L` and optionally verifies against `continuationStore` (`status == CLAIMED` and `version == request.continuationVersion`).
  3. Admits against the checkpoint row (`checkpointStore.admitEffect(...)`).
  4. Writes the initial `ADMITTED` record to `evidenceStore` (if configured) with `ParentProvenance.ApprovalContinuation(request.approvalId, request.continuationVersion, request.toolName)`.
  5. Returns `ApprovalResumePermit` closing over an `ApprovalResumeEvidenceHandle` instance bound to the record key.
- `ApprovalResumeCoordinator` in engine consumes `handle.recordDispatched()`, `handle.recordCompleted()`, or `handle.recordUncertain(error, category)` at execution boundaries.

### 6.4 Phased Non-Destructive JDBC Schema Migration
Existing databases contain E1–E8 evidence records. Adding a non-null column immediately would fail on existing populated tables. Furthermore, approval IDs can be arbitrary compound strings (`$runId-appr-$uuid`), which could exceed `VARCHAR(255)` if concatenated with version suffixes.

To guarantee zero downtime and length safety:
1. **Unambiguous, Length-Safe Representation:**
   - Instead of fragile delimiter concatenation (`"$approvalId:v$continuationVersion"`), lineage is stored across two dedicated columns:
     - `parent_kind VARCHAR(32) NOT NULL DEFAULT 'STEP_ATTEMPT'`
     - `parent_id VARCHAR(512) NULL` (holds `step_attempt_id` for step attempts, or `approval_id` for approval resumes — length-safe for any approval ID without concatenation)
     - `parent_version BIGINT NULL` (holds `continuation_version` for approval resumes; NULL for step attempts)
2. **Phased Non-Destructive Upgrade Sequence:**
   - **Phase 1 (Column Addition & Nullability Relaxation):**
     ```sql
     ALTER TABLE tramai_workflow_effect_evidence ADD COLUMN parent_kind VARCHAR(32) NOT NULL DEFAULT 'STEP_ATTEMPT';
     ALTER TABLE tramai_workflow_effect_evidence ADD COLUMN parent_id VARCHAR(512) NULL;
     ALTER TABLE tramai_workflow_effect_evidence ADD COLUMN parent_version BIGINT NULL;
     ALTER TABLE tramai_workflow_effect_evidence ALTER COLUMN step_attempt_id DROP NOT NULL;
     ```
   - **Phase 2 (Backfill Existing Rows):**
     ```sql
     UPDATE tramai_workflow_effect_evidence
     SET parent_id = step_attempt_id
     WHERE parent_id IS NULL AND parent_kind = 'STEP_ATTEMPT';
     ```
   - **Phase 3 (Validation):**
     Verify `SELECT COUNT(*) FROM tramai_workflow_effect_evidence WHERE parent_id IS NULL;` returns 0.
   - **Phase 4 (Constraint Enforcement):**
     ```sql
     ALTER TABLE tramai_workflow_effect_evidence ALTER COLUMN parent_id SET NOT NULL;
     ```
3. **Fresh Schema Creation (`schema-v2`):**
   New installations create the table directly with `parent_kind`, `parent_id VARCHAR(512) NOT NULL`, `parent_version BIGINT NULL`, and nullable `step_attempt_id`.
4. **Code Compatibility (Constructors & Readers):**
   - `WorkflowEffectEvidenceRecord` retains a legacy constructor defaulting `parent = ParentProvenance.StepAttempt(stepName, stepAttemptId)` for compiling callers.
   - JDBC decoders read `parent_kind`, `parent_id`, and `parent_version`, falling back to `step_attempt_id` when reading legacy schema rows.

---

## 7. Phase 5: Crash-Window & Failure Analysis Matrix

| Window | Timing of Crash / Failure | Durable State at Recovery | Recovery / Re-execution Outcome |
| --- | --- | --- | --- |
| **E9-W1** | Crash before continuation claim | Continuation: `PENDING`<br>Evidence: None | Safe to claim; no claim was persisted. |
| **E9-W2** | Claim succeeds, crash before admission | Continuation: `CLAIMED`<br>Evidence: None | Recovery observes `CLAIMED` continuation with missing evidence. Missing evidence cannot prove non-execution in crash windows. Operator reconciliation required before replay/re-execution. |
| **E9-W3** | Admission succeeds, crash before tool dispatch | Continuation: `CLAIMED`<br>Evidence: `ADMITTED` | Recovery observes `ADMITTED` without `DISPATCHED`. While admission was recorded, `ADMITTED` evidence cannot prove the external tool was never dispatched prior to the crash. Operator reconciliation required before replay/re-execution. |
| **E9-W4** | Crash during tool execution | Continuation: `CLAIMED`<br>Evidence: `ADMITTED` (or `UNCERTAIN` if handler ran) | **Unresolved Durable State:** An uncoordinated node crash or hard kill can prevent the in-process uncertainty handler from executing. The durable evidence may remain `ADMITTED`. Recovery must classify this conservatively as an unresolved outcome requiring operator reconciliation, rather than expecting a recorded `UNCERTAIN` transition. |
| **E9-W5** | Tool execution throws exception or is cancelled | Continuation: `CLAIMED`<br>Evidence: `UNCERTAIN` | Uncaught exception recorded as `UNCERTAIN`; exception propagates to caller. |
| **E9-W6** | Tool succeeds, crash before continuation complete | Continuation: `CLAIMED`<br>Evidence: `COMPLETED` | **Operator Reconciliation Requirement:** `COMPLETED` evidence authoritatively proves the tool executed to completion. An operator or recovery tool can inspect this evidence and mark the continuation completed without re-executing the tool. Automatic continuation completion is deferred until an authorized recovery path is qualified. |
| **E9-W7** | Continuation completion fails (store error) | Continuation: `CLAIMED`<br>Evidence: `COMPLETED` | **Operator Reconciliation Requirement:** Resume reports store error to caller; `COMPLETED` evidence proves the tool ran. Operator reconciliation is required to reconcile continuation status without re-executing. |
| **E9-W8** | Checkpoint cancelled while waiting for approval | Checkpoint: `CANCELLED`<br>Continuation: `PENDING` | `admitResume()` refuses admission; tool is never dispatched. |
| **E9-W9** | Checkpoint generation rotated by retry / recovery | Checkpoint: Generation $G_2$<br>Permit requested against current row | **Policy A (Admission-Time Freshness):** Approval remains valid across generation rotations subject to fresh durable admission against the current checkpoint row. If a concurrent generation rotation races the admission, the conditional CAS fails closed with conflict before tool dispatch. |
| **E9-W10** | Concurrent resume claims | Winner: `CLAIMED`<br>Loser: Conflict Exception | Exactly one claim succeeds; exactly one tool execution occurs. |

---

## 8. Phase 6: Qualification Matrix

The qualification suite is implemented in:  
`tramai-orchestration/src/test/kotlin/dev/tramai/orchestration/WorkflowApprovalResumeEvidenceContractTest.kt`

| Test ID | Characterization Scenario | Contract Invariant Verified |
| --- | --- | --- |
| **E9-L01** | Workflow-generated suspension binding | Suspension generated inside workflow carries authentic `WORKFLOW_BOUND` and matching `WorkflowCheckpointBinding`. |
| **E9-L02** | Absence of step attempt identity | `GovernedSuspendedInvocation` and `SuspendedInvocationMetadata` carry no step-attempt identity. Proven, not assumed (asserts required fields present and attempt fields absent without brittle field enumeration). |
| **E9-L03** | In-process workflow suspension | In-process workflows suspend and bind checkpoints correctly without supervisor or step-attempt stores. |
| **E9-L04** | Worker step attempt unwinding | Real supervisor execution (`WorkflowExecutionSupervisor`) with `InMemoryWorkflowCheckpointStore`; step attempt is `STARTED` during body and unwound/terminated (`FAILED`) on suspension; `currentStepAttempt() == null` at the resume boundary. |
| **E9-L05** | Resume permit sequence, attempt isolation & single-use | Resume permit carries authoritative admission sequence from store, without synthetic step attempt identity; asserts permit is a non-data class without `copy()`, enforcing single-use `tryConsume()`. |
| **E9-L06** | Cancelled checkpoint refusal | Checkpoint row cancelled prior to resume refuses admission; tool is never dispatched. |
| **E9-L07** | Concurrent resume mutual exclusion | Real resume path via `engine.resumeApproval` with competing concurrent commands; rendezvous synchronization without exception swallowing; exactly one succeeds, one conflicts, and `tool.executions.get() == 1`. |
| **E9-L08** | Standalone suspension isolation | Standalone suspension carries `STANDALONE` provenance and null binding; never acquires synthetic workflow lineage. |
| **E9-L09** | LEGACY_UNKNOWN fail-closed | Pre-governed `LEGACY_UNKNOWN` suspension fails closed under workflow-authoritative configuration. |
| **E9-L10** | Checkpoint generation freshness & race detection | Tests real `WorkflowApprovalResumeAdmission.admitResume()` under Policy A: fresh admission succeeds, while simulated concurrent generation rotation races CAS and fails closed with conflict before tool dispatch. |
| **E9-L11** | Outcome separation on real resume path | Real workflow resume through `engine.resumeApproval` with failing continuation store completion; tool executes (`tool.executions.get() == 1`), resume fails with completion error, and continuation remains in `CLAIMED` status. |
| **E9-L12** | Module-wide dependency direction | Bytecode analysis via ASM `ClassReader` scanning every compiled class in `tramai-engine`, proving zero bytecode references to `dev/tramai/orchestration`. |
| **E9-L13** | Continuation claim validation & compatibility | Validates that a substituted claim version (e.g. version 7 instead of 1) is rejected fail-closed, legacy unversioned requests fail when evidence is enabled, and legacy requests succeed when evidence is disabled. |

---

## 9. Phase 7: D7-E1 Implementation Blueprint

The subsequent implementation task (**D7-E1**) will realize this ratified contract through the following planned modifications:

1. **`tramai-core`:**
   - Define `WorkflowEvidenceDiagnosticCategory` enum for fixed, safe diagnostic categories.
   - Define `ApprovalResumeEvidenceHandle` in `dev.tramai.core.approval` taking typed diagnostic category.
   - Update `ApprovalResumeAdmissionRequest` to add `continuationVersion: Long = 0L` with backward-compatible 4-arg constructor.
   - Update `ApprovalResumePermit` (as non-data class) to include `checkpointGeneration: String? = null` and `evidenceHandle: ApprovalResumeEvidenceHandle? = null` with backward-compatible 3-arg constructor delegating to it.

2. **`tramai-orchestration`:**
   - In `WorkflowApprovalResumeAdmission`:
     - Accept `evidenceStore: WorkflowEffectEvidenceRecordStore? = null` and `continuationStore: ApprovalContinuationStore? = null` via constructor dependency injection.
     - When evidence is enabled, validate `request.continuationVersion > 0L` and verify against `continuationStore` (`status == CLAIMED` and `version == request.continuationVersion`).
     - Persist `ADMITTED` record with compound key `(workflowName, workflowId, checkpointGeneration, sequence)` and parent provenance `ParentProvenance.ApprovalContinuation(approvalId, continuationVersion, toolName)`.
     - Return `ApprovalResumePermit` containing `ApprovalResumeEvidenceHandle` implementation bound to the evidence key.
   - Execute phased, non-destructive JDBC migration for `tramai_workflow_effect_evidence`: add `parent_kind`, `parent_id VARCHAR(512)`, `parent_version BIGINT`, relax `step_attempt_id`, backfill existing rows, enforce NOT NULL constraint, and qualify schema upgrade and fresh creation.

3. **`tramai-engine`:**
   - In `ApprovalResumeCoordinator`:
     - Thread authoritative `claimed.version` into `ApprovalResumeAdmissionRequest(continuationVersion = claimed.version)`.
     - Obtain `evidenceHandle` from `ApprovalResumePermit`.
     - Call `handle.recordDispatched()` and `handle.recordCompleted()` upon normal tool return.
     - Call `handle.recordUncertain(error, category)` on exception or cancellation using safe diagnostic category.

---

## 10. Socratic Delineation: Proven Behavior vs D7-E1 Responsibilities

To avoid confusing architectural intentions with qualified guarantees, the boundary between D7-E0 and D7-E1 is formally delineated:

| Capability / Invariant | D7-E0 Status (This Milestone) | D7-E1 Responsibility |
| --- | --- | --- |
| **Absence of Step Attempt in Suspensions** | **PROVEN** in characterization tests (E9-L02, E9-L04) | Retain existing suspension models unchanged |
| **Typed Parent Provenance (Option C)** | **RATIFIED** design contract | Add `ParentProvenance.ApprovalContinuation` to evidence records |
| **Continuation Version Transmission** | **RATIFIED** cross-module contract | Add `continuationVersion` to request & coordinator |
| **Continuation Version Validation Boundary** | **PROVEN** contract discriminator (E9-L13) | Implement validator seam in `WorkflowApprovalResumeAdmission` |
| **Permit Single-Use & Non-Data Class** | **PROVEN** in characterization tests (E9-L05) | Retain class invariant, add generation and handle |
| **Runtime Compatibility Policy** | **RATIFIED** specification (E9-L13) | Enforce handle presence when evidence enabled |
| **Diagnostic Category Typing** | **RATIFIED** specification | Implement `WorkflowEvidenceDiagnosticCategory` enum |
| **Module-Wide Bytecode Decoupling** | **PROVEN** across entire `tramai-engine` (E9-L12) | Preserve zero-dependency boundary |
| **Outcome Separation (Tool vs Store)** | **PROVEN** through real engine resume (E9-L11) | Thread `evidenceHandle.recordCompleted()` before completion |
| **JDBC Schema Upgrade & Backfill** | **RATIFIED** phased migration DDL | Implement migration script, qualify fresh/upgrade schema |
| **Automatic Recovery Completion** | **DEFERRED** (operator reconciliation required) | Design dedicated authorized recovery path in future epic |
