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
- **Lineage Transmission via Request:**
  - `ApprovalResumeCoordinator` in `tramai-engine` claims continuation via `ApprovalContinuationStore.claimForExecution(approvalId, expectedVersion, claimedBy)`.
  - The returned `ClaimedApprovalContinuation` provides the authoritative `continuation.version` (which is `expectedVersion + 1`).
  - `ApprovalResumeAdmissionRequest` in `tramai-core` conveys `continuationVersion: Long` (with a binary-compatible 4-argument constructor for existing compiled callers).
  - `WorkflowApprovalResumeAdmission` validates `request.continuationVersion > 0L` (when governed/versioned) and constructs `ParentProvenance.ApprovalContinuation(request.approvalId, request.continuationVersion, request.toolName)`.
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
 * Callback handle for recording evidence during approval resume execution.
 * Passed via [ApprovalResumePermit] to bridge orchestration evidence storage
 * across the engine execution boundary without violating module dependencies.
 */
interface ApprovalResumeEvidenceHandle {
    suspend fun recordDispatched()
    suspend fun recordCompleted()
    suspend fun recordUncertain(error: Throwable, diagnostic: String)
}
```

### 6.2 Permit Preservation & Single-Use Invariant
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

### 6.3 Orchestration Implementation & Evidence Store Ownership
- `WorkflowApprovalResumeAdmission` accepts `evidenceStore: WorkflowEffectEvidenceRecordStore? = null` directly via **constructor dependency injection** at composition time. It does **not** rely on coroutine-local context scopes like `WorkflowEffectEvidenceStoreScope`.
- In `admitResume()`, orchestration:
  1. Validates attribution continuity and checks that `request.continuationVersion > 0L` (when governed).
  2. Admits against the checkpoint row (`checkpointStore.admitEffect(...)`).
  3. Writes the initial `ADMITTED` record to `evidenceStore` (if configured) with `ParentProvenance.ApprovalContinuation(request.approvalId, request.continuationVersion, request.toolName)`.
  4. Returns `ApprovalResumePermit` closing over an `ApprovalResumeEvidenceHandle` instance bound to the record key.
- `ApprovalResumeCoordinator` in engine consumes `handle.recordDispatched()`, `handle.recordCompleted()`, or `handle.recordUncertain(...)` at execution boundaries.

### 6.4 Persistence Schema Migration & In-Memory Parity
To support typed parent provenance across all effect families without schema drift:
1. **JDBC Table Migration (`tramai_workflow_effect_evidence`):**
   - `step_attempt_id VARCHAR(255) NULL` (altered from NOT NULL to NULL).
   - Add `parent_kind VARCHAR(32) NOT NULL DEFAULT 'STEP_ATTEMPT'`
   - Add `parent_id VARCHAR(255) NOT NULL` (populated with `step_attempt_id` for step attempts, or `"$approvalId:v$continuationVersion"` for approval resumes).
   - Historical rows decode with `ParentProvenance.StepAttempt` for backward compatibility.
2. **In-Memory Parity:**
   - `InMemoryWorkflowEffectEvidenceRecordStore` mirrors the typed parent provenance indexing and lookup contracts, guaranteeing full behavioral parity with JDBC.

---

## 7. Phase 5: Crash-Window & Failure Analysis Matrix

| Window | Timing of Crash / Failure | Durable State at Recovery | Recovery / Re-execution Outcome |
| --- | --- | --- | --- |
| **E9-W1** | Crash before continuation claim | Continuation: `PENDING`<br>Evidence: None | Safe to claim; no claim was persisted. |
| **E9-W2** | Claim succeeds, crash before admission | Continuation: `CLAIMED`<br>Evidence: None | Recovery observes `CLAIMED` continuation with missing evidence. Missing evidence cannot prove non-execution in crash windows. Operator reconciliation required before replay/re-execution. |
| **E9-W3** | Admission succeeds, crash before tool dispatch | Continuation: `CLAIMED`<br>Evidence: `ADMITTED` | Recovery observes `ADMITTED` without `DISPATCHED`. While admission was recorded, `ADMITTED` evidence cannot prove the external tool was never dispatched prior to the crash. Operator reconciliation required before replay/re-execution. |
| **E9-W4** | Crash during tool execution | Continuation: `CLAIMED`<br>Evidence: `ADMITTED` | Timeout / uncertainty handler flags `UNCERTAIN`. Operator reconciliation required before retry. |
| **E9-W5** | Tool execution throws exception or is cancelled | Continuation: `CLAIMED`<br>Evidence: `UNCERTAIN` | Uncaught exception recorded as `UNCERTAIN`; exception propagates to caller. |
| **E9-W6** | Tool succeeds, crash before continuation complete | Continuation: `CLAIMED`<br>Evidence: `COMPLETED` | Recovery sees `COMPLETED` evidence; marks continuation completed **without** re-executing tool. |
| **E9-W7** | Continuation completion fails (store error) | Continuation: `CLAIMED`<br>Evidence: `COMPLETED` | Resume reports error; evidence proves tool executed. Recovery reconciles continuation without re-executing tool. |
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
| **E9-L07** | Concurrent resume mutual exclusion | Real resume path via `engine.resumeApproval` with competing concurrent commands; exactly one succeeds, one conflicts, and `tool.executions.get() == 1`. |
| **E9-L08** | Standalone suspension isolation | Standalone suspension carries `STANDALONE` provenance and null binding; never acquires synthetic workflow lineage. |
| **E9-L09** | LEGACY_UNKNOWN fail-closed | Pre-governed `LEGACY_UNKNOWN` suspension fails closed under workflow-authoritative configuration. |
| **E9-L10** | Checkpoint generation freshness & race detection | Tests real `WorkflowApprovalResumeAdmission.admitResume()` under Policy A: fresh admission succeeds, while simulated concurrent generation rotation races CAS and fails closed with conflict before tool dispatch. |
| **E9-L11** | Outcome separation on real resume path | Real workflow resume through `engine.resumeApproval` with failing continuation store completion; tool executes (`tool.executions.get() == 1`), resume fails with completion error, and continuation remains in `CLAIMED` status. |
| **E9-L12** | Module dependency direction | Bytecode analysis via ASM `ClassReader` proving `TramaiEngine`, `ApprovalResumeCoordinator`, and engine classes have zero bytecode references to `dev/tramai/orchestration`. |

---

## 9. Phase 7: D7-E1 Implementation Blueprint

The subsequent implementation task (**D7-E1**) will realize this ratified contract through the following planned modifications:

1. **`tramai-core`:**
   - Define `ApprovalResumeEvidenceHandle` in `dev.tramai.core.approval`.
   - Update `ApprovalResumeAdmissionRequest` to add `continuationVersion: Long = 0L` with backward-compatible 4-arg constructor.
   - Update `ApprovalResumePermit` (as non-data class) to include `checkpointGeneration: String? = null` and `evidenceHandle: ApprovalResumeEvidenceHandle? = null` with backward-compatible 3-arg constructor delegating to it.

2. **`tramai-orchestration`:**
   - In `WorkflowApprovalResumeAdmission`:
     - Accept `evidenceStore: WorkflowEffectEvidenceRecordStore? = null` via constructor dependency injection.
     - Validate `request.continuationVersion > 0L` on governed requests.
     - Persist `ADMITTED` record with compound key `(workflowName, workflowId, checkpointGeneration, sequence)` and parent provenance `ParentProvenance.ApprovalContinuation(approvalId, continuationVersion, toolName)`.
     - Return `ApprovalResumePermit` containing `ApprovalResumeEvidenceHandle` implementation bound to the evidence key.
   - Migrate persistence schema for `tramai_workflow_effect_evidence` to make `step_attempt_id` nullable, add `parent_kind` and `parent_id`, and ensure backward compatibility for reading legacy records.

3. **`tramai-engine`:**
   - In `ApprovalResumeCoordinator`:
     - Thread authoritative `claimed.version` into `ApprovalResumeAdmissionRequest(continuationVersion = claimed.version)`.
     - Obtain `evidenceHandle` from `ApprovalResumePermit`.
     - Call `handle.recordDispatched()` and `handle.recordCompleted()` upon normal tool return.
     - Call `handle.recordUncertain(...)` on exception or cancellation.
