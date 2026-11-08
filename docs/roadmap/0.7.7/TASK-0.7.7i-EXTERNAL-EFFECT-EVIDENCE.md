# D7-D — Durable External Executor Evidence (Boundary E8) Design Contract

**Status:** Completed & Ratified  
**Base:** `release/0.7.0` at `926c3fc6423094f80c7745c0667d040b4711a2f5`  
**Implementation Branch:** `task/0.7.7i-d7-d-external-effect-evidence`  
**Scope:** Durable effect evidence, authoritative step-attempt lineage, state transitions, fail-closed admission ordering, crash windows, zero secret leakage, and truth-in-observability guarantees for the External Step Executor Invocation boundary (**E8**).

---

## 1. Context & Motivation

EPIC-0.7.7 establishes durable cancellation authority and atomic admissions across all outbound effect boundaries (E1–E9) and step boundaries (E11), with recovery re-execution fenced by lease ownership (E10). Milestone D7-A ratified per-effect child evidence provenance for outbound HTTP (E1/E2), D7-B1 ratified durable process evidence for Shell execution (E7), D7-B2 ratified process evidence for built-in Agent CLI boundaries (Codex E5 and Hermes E6), and D7-C ratified evidence for Model Context Protocol boundaries (Server Start E4 and Tool Call E3).

Milestone D7-D extends TramAI's ratified D7 durable per-effect evidence architecture to **E8 — External Step Executor Invocation** (`PluginWorkflowStep.kt`).

`ExternalStepExecutor` represents an extension point allowing third-party code and plugins to execute within a workflow step. Prior to D7-D, `PluginWorkflowStep` acquired an in-memory `WorkflowExternalPermit` without checkpoint generation tracking and without writing child evidence records to `WorkflowEffectEvidenceRecordStore`.

D7-D establishes durable evidence records for every governed E8 invocation, guaranteeing that:
1. Every governed external executor invocation has a fresh durable admission sequence.
2. Every invocation is authoritatively attributed to its parent step-attempt lineage.
3. Every invocation produces a distinct per-effect child evidence record.
4. Execution state transitions reflect truthful, conservative observable boundaries.
5. Ordinary ungoverned workflows incur zero overhead and remain fully compatible.

---

## 2. Core Architectural Principle: TramAI Governs One Invocation

> [!IMPORTANT]
> **Primary Invariant:** TramAI governs **one invocation** into the external executor, not the arbitrary operations performed inside that executor.

Because `ExternalStepExecutor` is an opaque third-party callback interface, TramAI cannot inspect, intercept, count, or fence internal side effects (such as internal HTTP requests, file writes, threads, or child processes) that the executor executes within its own code. TramAI makes no claim to contain or fence operations internal to the plugin. What TramAI governs and proves is strictly that **the call into the executor was authorized, dispatched, and confirmed completed (or outcome-uncertain)**.

Furthermore:
- **No resolver callback executes before the E8 admission decision.**  
  `ExternalStepExecutorResolver` is a public extension interface. Invoking `isRegistered()` or `create()` before durable admission would allow third-party code to run on cancelled or unauthorized runs, and would introduce race conditions with mutable registries. Admission is decided by the durable authority first; the resolver is consulted only after the permit has been granted and durable evidence has been persisted.

---

## 3. Evidence States & Semantics

Every governed external step executor invocation (E8) transitions through audited states:

| State | Conservative Observable Guarantee |
| --- | --- |
| `ADMITTED` | Proves effect authorization via `WorkflowExternalPermit` and child evidence record written to `WorkflowEffectEvidenceRecordStore`. Neither `executorResolver.create()` nor `executor.execute()` has been invoked yet. |
| `DISPATCHED` | The external invocation returned normally: `executor.execute(config)` completed execution without throwing an exception. TramAI does not claim `DISPATCHED` on entering the callback, because an opaque callback's entry does not establish observable outbound progress. |
| `COMPLETED` | The executor invocation returned a result and completion evidence was successfully recorded in `WorkflowEffectEvidenceRecordStore`. Recorded **before** `merge(state, result, context)`. |
| `UNCERTAIN` | Failure occurred after admission: resolver/factory exception, executor exception, coroutine cancellation, timeout, evidence persistence error, or worker crash before completion evidence write. |

### Outcome Separation: Merge Callback vs External Effect Completion

`PluginWorkflowStep` has two distinct phases:
1. Calling the external executor (`executor.execute(config)`).
2. Merging the executor result into the workflow state (`merge(state, result, context)`).

`merge` is application-level state transformation, not part of the external executor execution. Therefore:
- The E8 child evidence record transitions to `COMPLETED` immediately when `executor.execute(config)` returns and its completion is persisted.
- If `merge(state, result, context)` subsequently fails (e.g. throws an exception), the step execution fails, but the E8 child evidence record **remains `COMPLETED`**.
- A subsequent merge failure must never retroactively invalidate or mark `UNCERTAIN` an external invocation that already completed.

---

## 4. Record Schema & Identity

Each child evidence record is identified by the ratified compound primary key:

$$\text{Key} = (workflowName, workflowId, checkpointGeneration, admissionSeq)$$

- `workflowName`, `workflowId`: Owning workflow identification.
- `checkpointGeneration`: Authoritative generation string from `WorkflowPersistenceSession` (via `WorkflowExternalPermit`), preventing collision across multi-generation checkpoint lifecycles.
- `admissionSeq`: Durable monotonic sequence number returned by `WorkflowCheckpointStore.admit()`.

**Fields:**
- `key`: Primary compound key.
- `stepName`: Step name from workflow definition.
- `stepAttemptId`: Authoritative parent attempt ID from `PersistedStepAttempt`.
- `effectKind`: `externalEffectKind(typeId)` $\implies$ `"EXTERNAL:<typeId>"`.
- `state`: `ADMITTED`, `DISPATCHED`, `COMPLETED`, `UNCERTAIN`.
- `createdAtEpochMillis`: Monotonic clock sample at record creation.
- `completedAtEpochMillis`: Monotonic clock sample at normal completion (null until `COMPLETED`).
- `diagnosticSummary`: Fixed, safe diagnostic category (never raw configs, payloads, or exception messages).

---

## 5. Execution Pipeline & Admission Ordering

The execution pipeline in `PluginWorkflowStep.execute()` follows this strict order:

```text
Governed Workflow Run
       │
       ▼
1. Resolve Execution Context
   - currentExternalAdmission()
   - currentEffectEvidenceStore()
   - currentStepAttempt()
       │
       ▼
2. Fail-Closed Lineage Validation (if evidenceStore != null)
   - requireEvidenceLineageIfEnabled(...)
   - Throws WorkflowEvidenceResolutionException if authority or attempt is absent
       │
       ▼
3. Acquire E8 Permit
   - externalAdmission.admitExternalStep(name, type)
       │
       ▼
4. Persist ADMITTED Child Record
   - recordAdmitted(permit, evidenceStore, stepAttempt, ...)
   - Fails closed on persistence failure (ExternalEvidencePersistenceException)
       │
       ▼
5. Consume Permit
   - permit.tryConsume() (one-shot consumption enforced)
       │
       ▼
6. Resolve Executor
   - executorResolver.create(type)
   - On error: record UNCERTAIN (external_resolution_failed) and rethrow
       │
       ▼
7. Execute External Callback
   - executor.execute(config)
   - On error: record UNCERTAIN (external_execution_failed) and rethrow
   - On cancellation: record UNCERTAIN (external_cancelled) and rethrow
       │
       ▼
8. Persist Confirmed Outcome
   - updateState(DISPATCHED)
   - updateState(COMPLETED)
   - On error: record UNCERTAIN (external_evidence_failed) and rethrow
       │
       ▼
9. Apply Workflow Merge
   - merge(state, result, context)
   - E8 record remains COMPLETED regardless of merge outcome
```

---

## 6. Sensitive Data Isolation & Safe Fixed Diagnostics

To eliminate secret leakage (API tokens, database credentials, confidential payloads, or stack trace paths), the evidence store records **only** fixed safe category strings in `diagnosticSummary`:

- `external_resolution_failed`: Executor resolver could not find or instantiate the requested plugin type.
- `external_execution_failed`: External executor threw an exception during `execute()`.
- `external_cancelled`: Execution was interrupted by coroutine cancellation.
- `external_evidence_failed`: Evidence store threw an exception during state transition updates.

Neither the configuration map, nor the execution result map, nor raw exception messages ever reach durable evidence records.

---

## 7. Crash Windows & Recovery

| Window | Event | Observable Outcome |
| --- | --- | --- |
| **W1** | Crash after admission sequence increment, before `ADMITTED` child record write | Step checkpoint sequence incremented; child evidence store has no record for this sequence. Lineage is unclassified. |
| **W2** | Crash after `ADMITTED` child record write, before `executor.execute()` | Child evidence record remains `ADMITTED`. Recovery inspects the record; without `DISPATCHED` or `COMPLETED`, the effect is classified as `UNCERTAIN`. |
| **W3** | Crash during or immediately after `executor.execute()`, before `COMPLETED` write | Record remains `ADMITTED` or `DISPATCHED`. Recovery treats outcome as `UNCERTAIN`. |
| **W4** | Crash after `COMPLETED` write, before step checkpoint commit | Record is `COMPLETED`. Step recovery can observe that the external effect completed successfully. |

No automatic retries are performed at the E8 boundary. Because an external executor is opaque, re-executing an outcome-uncertain invocation risks duplicating third-party side effects.

---

## 8. Qualification Matrix Summary

The deterministic test suite `WorkflowExternalEffectEvidenceTest.kt` verifies:

| Test ID | Scenario | Verified Property |
| --- | --- | --- |
| **EXT-01** | Successful governed execution | End-to-end execution with real `WorkflowExecutionSupervisor`, H2 JDBC checkpoint, lease, step attempt, and evidence stores. Proves `COMPLETED` state and verified direct JDBC row attributes. |
| **EXT-02** | Missing authority | Governed run with evidence store but missing admission throws `WorkflowEvidenceResolutionException` before admission; resolver never invoked. |
| **EXT-03** | Missing step attempt | Governed run with missing `PersistedStepAttempt` throws `WorkflowEvidenceResolutionException` before admission; resolver never invoked. |
| **EXT-04** | ADMITTED write failure | Evidence store write error throws `ExternalEvidencePersistenceException`; permit unconsumed; resolver never created. |
| **EXT-05** | Resolver failure | Resolver creation failure records `UNCERTAIN` (`external_resolution_failed`); no false `DISPATCHED`. |
| **EXT-06** | Execution failure | Executor failure records `UNCERTAIN` (`external_execution_failed`); no false `COMPLETED`. |
| **EXT-07** | Successful execution | State monotonically transitions `ADMITTED` $\to$ `DISPATCHED` $\to$ `COMPLETED` with timestamps. |
| **EXT-08** | COMPLETED write failure | Evidence store failure on completion update surfaces `ExternalEvidencePersistenceException`; invocation not repeated; record marked `UNCERTAIN` (`external_evidence_failed`). |
| **EXT-09** | Merge failure | Exception in `merge` lambda surfaces; E8 evidence record remains `COMPLETED`. |
| **EXT-10** | Parent cancellation | Coroutine cancellation preserves `CancellationException` identity; records `UNCERTAIN` (`external_cancelled`). |
| **EXT-11** | Multiple E8 effects | Two plugin executions in one attempt have distinct admission sequences and share parent `stepAttemptId`. |
| **EXT-12** | Nested plugin step | Nested execution inherits parent step attempt context and `checkpointGeneration`. |
| **EXT-13** | Retried step | Retried attempt gets fresh permit, new admission sequence, and distinct attempt lineage. |
| **EXT-14** | Ungoverned workflow | Ordinary workflows execute with zero evidence enforcement, zero writes, and full backward compatibility. |
| **EXT-15** | Unknown plugin type | No `isRegistered()` callback precedes admission. Resolver throws `ExternalStepExecutorNotRegisteredException` after admission; record is `UNCERTAIN` (`external_resolution_failed`). |
| **EXT-16** | Sensitive data isolation | Injected secrets in config and exceptions do not leak into durable evidence. |
| **EXT-17** | Missing child evidence lookup | Missing evidence record during state update fails closed with `WorkflowEvidenceResolutionException`. |
| **EXT-18** | Duplicate permit consumption | Attempting to consume an already-consumed `WorkflowExternalPermit` fails with `IllegalStateException`. |

---

## 9. Negative-Control Mutation Proofs

| Mutation | Mutation Description | Discriminating Test | Observed Failure |
| --- | --- | --- | --- |
| **M1** | Remove pre-admission lineage validation | `EXT-02` | `AssertionFailedError: Expected WorkflowEvidenceResolutionException to be thrown, but nothing was thrown` |
| **M2** | Move ADMITTED persistence after resolver creation | `EXT-04` | `AssertionFailedError: expected 0 but was 1` (resolver created despite ADMITTED write failure) |
| **M3** | Call resolver pre-check before admission | `EXT-15` | `AssertionFailedError: expected 0 but was 1` (`isRegistered` called before admission) |
| **M4** | Skip DISPATCHED recording | `EXT-07` | `AssertionFailedError: expected DISPATCHED to be present in observed states` |
| **M5** | Mark execution exception as COMPLETED | `EXT-06` | `AssertionFailedError: expected UNCERTAIN but was COMPLETED` |
| **M6** | Skip evidence completion | `EXT-07` | `AssertionFailedError: expected COMPLETED but was DISPATCHED` |
| **M7** | Allow missing child evidence record to succeed | `EXT-17` | `AssertionFailedError: Expected WorkflowEvidenceResolutionException to be thrown, but nothing was thrown` |
| **M8** | Reuse previous permit (disable `consumed` check) | `EXT-18` | `AssertionFailedError: Expected IllegalStateException to be thrown, but nothing was thrown` |
| **M9** | Persist raw exception messages | `EXT-16` | `AssertionFailedError: expected external_execution_failed but was raw secret message` |
| **M10** | Mark invocation UNCERTAIN if merge subsequently fails | `EXT-09` | `AssertionFailedError: expected COMPLETED but was UNCERTAIN` |
