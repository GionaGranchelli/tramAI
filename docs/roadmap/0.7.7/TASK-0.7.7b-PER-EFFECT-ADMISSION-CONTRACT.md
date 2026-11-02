# TASK-0.7.7b — Per-Effect Execution Admission Wiring

Status: **contract ratified; stages 1 and 2 implemented.** D6-D8 ruled by the owner (review 5730382997), the
admission-receipt defect that review found is fixed in the store (`admit()` now derives its receipt from a locked
read inside one transaction), **E11 step admission is wired** (see §10b) and **E5-E7 process permits are wired** (see
§10d). The remaining external-effect edges E1-E4, E8 and E9 are still unwired: nothing takes a permit for HTTP, MCP,
an external executor or an approval continuation yet.
Base: `release/0.7.0` @ `d68fab5b` (PR #575 merged: durable cancellation authority + store-level admission).
Supersedes nothing. Extends `TASK-0.7.7a-CANCELLATION-AUTHORITY-CONTRACT.md` §11 from "the boundary is defined"
to "each boundary is named, guarded and tested".

---

## 1. Why this exists

0.7.7a made cancellation durable and terminal, and made admission an atomic act against the run's own row. It
did not connect either to an execution path. Today, in `release/0.7.0`:

- `WorkflowCheckpointStore.admit()` is called by nobody.
- "Cancellation" at runtime means `Job.cancel()` → coroutine `CancellationException`. That unwinds a coroutine; it
  does not stop a `HttpClient.send`, a `client.callTool`, or a `ProcessBuilder.start` that is already running, and
  it certainly does not stop one that starts a microsecond later.
- `JdbcCancellationSupport` is unrelated: it propagates *coroutine* cancellation into JDBC statements.

So the invariant `cancelled(run) ⇒ no future authoritative execution(run)` is decidable, tested at store level, and
unenforced. This contract wires it, effect by effect, and bounds the claim.

## 2. Terms

| Term | Meaning |
| --- | --- |
| **Step admission** | One `admit()` for a step execution. Establishes that this worker may *begin* step N of the run. **Not** permission for the external effects that step will trigger. |
| **Effect admission** | One `admit()` per outbound effect, immediately before that effect's dispatch. Fresh, atomic, against the run's durable lifecycle state. |
| **Permit** | The value returned by a granted effect admission. Carries the run key, checkpoint generation, step name, attempt id, effect kind and `admission_seq`. |
| **Dispatch** | The instant the effect leaves the process: `send`, `callTool`, `ProcessBuilder.start`, model call, tool invocation on resume. |
| **Effect completion** | The remote side finished. Not observable as a single instant, and not what cancellation orders against. |
| **`admission_seq`** | The run's monotonic admission counter. Evidence that admission happened historically. **Never** evidence that an effect is outstanding or in flight. |

For the MCP edge the ratified ordering is four steps, and each is separately admitted:

```
admit MCP_SERVER_START  ->  start process / establish transport
admit MCP_TOOL          ->  dispatch callTool
```

A permit for server startup cannot authorize subsequent tool calls, and a server that receives no call is still an
admitted effect: starting an arbitrary command can create files, open sockets or run anything the command does.

A **step admission is not an authorization for every external effect the step may trigger.** A step that loops over
twenty tool calls needs twenty permits or it is not fenced. This is the whole point of the task: one step-level
permit authorizing an unbounded sequence of later calls would reproduce, in code, the defect the store slice fixed.

## 3. Edge map — every outbound execution boundary

Measured on `d68fab5b`. These are the only places execution leaves the process for a run; the contract is binding
on this list, so a new step type that dispatches externally must add itself here in the same PR.

| # | Boundary | Site | Guard to install |
| --- | --- | --- | --- |
| E1 | HTTP step, transport path | `HttpStep.kt:131` `httpClient.send(...)` | effect permit `HTTP` immediately before `send` |
| E2 | HTTP step, dispatcher path | `HttpStep.kt:328` `execution.transport.send(...)` | same permit; E1 and E2 must not both fire for one permit |
| E3 | MCP tool call | `McpStep.kt:393` `client.callTool(...)` | effect permit `MCP_TOOL` immediately before the call |
| E4 | MCP server spawn | `McpStep.kt:155` `ProcessBuilder(toolCall.serverCommand)` | effect permit `MCP_SERVER_START` before `start()`; cannot authorize the later `callTool` (D8) |
| E5 | Codex CLI dispatch | `CodexStep.kt:93` `ProcessBuilder(...)` | effect permit `PROCESS` before `start()` |
| E6 | Hermes CLI dispatch | `HermesStep.kt:92` `ProcessBuilder(...)` | effect permit `PROCESS` before `start()` |
| E7 | Shell command | `ShellStep.kt:219` `ProcessBuilder(...)` | effect permit `PROCESS` before `start()` |
| E8 | External step executor | `WorkflowStepExecution.kt:179` `ExternalStepExecutor.execute(spec)` | effect permit `EXTERNAL`, kind from the registered type id — **and see §3a: a permit here governs the call into the executor, not the effects it makes internally** |
| E9 | Approval continuation | `ApprovalResumeCoordinator.kt:174` `executeClaimedResume` | effect permit `APPROVAL_RESUME` before the resumed tool invocation |
| E10 | Worker recovery re-execution | `WorkflowRecoveryCoordinator` → `WorkflowRunner` | step admission only; every effect inside re-admits at E1–E9 |
| E11 | Step dispatch choke point | `WorkflowStepExecutor.kt:36` `executeStep(...)` | place where step admission is taken, and where a cancelled run is refused before any step work |

### 3a-bis. Caller-supplied callbacks are opaque too

`LocalWorkflowStep.transform` and `AiWorkflowStep.invoke` run caller-supplied code inside a step. That code can perform
arbitrary outbound work without passing through E1-E8. E11 still holds for it — the callback *invocation* is admitted
— but its internal effects are not individually governed, exactly like E8.

So the edge inventory is a list of the boundaries this runtime owns, not a claim that every external operation a
workflow can cause is enumerated. The 0.7.7 guarantee is limited to instrumented boundaries, and no wording may imply
otherwise.

### 3a. The opaque extension point at E8

`ExternalStepExecutor` is a third-party extension point. A permit taken at its entry authorizes **one call into that
executor**; it cannot authorize, observe or count the outbound operations the executor performs inside its own code.
An instrumented built-in edge and an opaque plugin edge therefore do not carry the same guarantee, and the runtime
cannot distinguish one admitted dispatch from an arbitrary number of effects that dispatch triggers.

That asymmetry is a stated limit, not a gap to paper over:

- The 0.7.7 guarantee is scoped to **instrumented boundaries** — E1-E7, E9, E11 — where the permit is taken
  immediately before the transport or process leaves the process.
- At E8 the guarantee is only "this executor invocation was admitted". Third-party executors that perform their own
  outbound work are **outside** the enforcement claim unless they consume a per-effect admission capability
  themselves. Providing that capability is future work; claiming their effects are fenced today is not permitted.
- The contract therefore permits one claim and forbids another. Permitted: **"after cancellation commits, no new
  instrumented-effect admission can succeed; previously admitted effects may still dispatch or complete and remain
  conservatively uncertain."** Forbidden: "no *instrumented* effect begins after cancellation commits" — an effect
  admitted before cancellation can physically start afterwards — and equally forbidden: "no external effect begins
  after cancellation commits", while E8 remains opaque.

The model call of a step is dispatched by the same step executor as E8 for remote executors and inside the CLI
steps for E5–E7; there is no separate model-dispatch site in `tramai-orchestration` on this base. If one appears,
it is an edge and it enters this table.

## 4. Permit identity, consumption, retries

**Identity.** A permit is identified by `(workflowName, workflowId, checkpointGeneration, stepName, attemptId,
effectKind, admissionSeq)`. `admissionSeq` is the durable part of that tuple, because it is the only component the
authority issues; the rest is attribution, and attribution is evidence, not authority.

**Consumption: one permit authorizes at most one dispatch attempt.** The permit is a one-shot token: `tryConsume()`
returns true exactly once, and the guard at E1–E9 dispatches only if it returns true. A second call on the same
permit is a programming error and must fail — not silently re-dispatch.

**Consumption is a local governance property, and this contract refuses to inflate it.** It holds within one
process for one attempt. It is *not* durable: a worker that dies after dispatch and restarts will admit again and
receive a new `admissionSeq`, which is correct and not a leak. Concretely, exactly-once permit consumption must
never be presented as, or relied upon for, exactly-once external execution. A remote side may have processed the
admitted call the first time. That is why the outcome is `CANCELLED_UNCERTAIN`, not "not executed".

**A grant is only a grant if the write took.** The increment must affect exactly one row; zero affected rows means
no durable grant exists, and returning `admitted = true` there would hand out a permit for a sequence that was never
recorded. The store refuses in that case (a conflict), and the transaction rolls back so no partial increment
survives. The admission is proven by its own write, never by the locked read that preceded it.

**Retries need fresh admission.** An attempt that fails and is retried calls `admit()` again. Reusing an earlier
permit is forbidden even if no dispatch happened, because the retry is a new decision point: cancellation may have
committed in between, and the store is the only authority that can answer whether it did.

## 5. Ordering: what cancellation does and does not guarantee

The guarantee is over **admission ordering**, never over physical effect completion:

- Admission that commits first ⇒ the dispatch is permitted, even if it physically starts after cancellation
  commits. The run's outcome is then `CANCELLED_UNCERTAIN`.
- Cancellation that commits first ⇒ every later admission is refused. The guarantee is therefore about **new
  admissions**, not about physical dispatch: "after cancellation commits, no new instrumented-effect admission can
  succeed; previously admitted effects may still dispatch or complete and remain conservatively uncertain." Saying
  "no instrumented effect begins after cancellation" would be a stronger claim than the mechanism supports.
- An effect that completes later cannot be undone. No claim of "stopped" is made for it.

Two consequences the implementation must respect, because both are easy to get subtly wrong:

1. **`admission_seq > 0` means "this run admitted work at some point".** It does not mean "a call is in flight".
   The store's conservative derivation (`CANCELLED_UNCERTAIN` when any admission exists) is deliberate: it prefers
   over-reporting uncertainty to under-reporting it. This contract preserves that, and explicitly rejects any
   refinement that would report a clean `CANCELLED` based on a permit being consumed or an attempt being closed.
2. **A read is not a fence.** No guard may be "read lifecycle, then dispatch". The fence is the store's conditional
   write, taken immediately before dispatch, and its refusal is the only thing that prevents a dispatch.

## 6. Fail-closed matrix

| Situation | Required behaviour |
| --- | --- |
| Store implements no admission support | refuse the effect: `WorkflowCancellationUnsupportedException`. Never dispatch unfenced. |
| Store cannot decide atomically (markdown, file) | **fail closed (D6 ratified)**: the same refusal. These profiles do not dispatch instrumented effects until a single-node admission is separately qualified as durable and atomic. Introducing a weaker local fallback now would create exactly the "looks equivalent, is not" failure D5 rejected. |
| Admission refused because the run is cancelled | do not dispatch; surface the run's recorded cancellation state to the caller. |
| Admission refused because the revision/generation precondition is stale | do not dispatch; the worker is stale. |
| Store unreachable / admission throws | do not dispatch. An effect that cannot be authorized must not be sent. |
| Admission granted | dispatch exactly once, through the permit. |

## 7. Evidence states

Every effect attempt is observable in exactly one of these states, recorded as a **per-effect child record under the
step-attempt lineage (D7 ratified)** — one step attempt can produce several outbound effects, so effect evidence
cannot live on the attempt itself:

| State | Meaning |
| --- | --- |
| `ADMITTED` | The authority granted a permit; dispatch has not happened yet. |
| `DISPATCHED` | The permit was consumed and the call was handed to the transport/process. |
| `COMPLETED` | The attempt returned normally. |
| `UNCERTAIN` | An effect was admitted (and possibly dispatched) but the run was cancelled. Never reported as "not executed". |

The distinction between `ADMITTED` and `DISPATCHED` is required, not cosmetic: it is what allows an operator to see
that a permit existed and was never used, without that being confused with an effect that happened.

**Evidence can be missing, and its absence proves nothing (P2).** A durable admission can commit and the process can
die before the child record is written, so no child record may be read as "this call was never admitted" or "this
call was never dispatched". The `admission_seq` counter is the durable source of truth for the *existence* of
earlier grants; the child records are a best-effort account of what those grants were used for. An operator reading
evidence must therefore start from the counter — a sequence exists that has no child record — and treat the effect
as uncertain, never as absent.

## 8. Mandatory adversarial tests

Deterministic synchronization only. **No sleeps, no timing assertions.** The technique is a blocking store
decorator: a `WorkflowCheckpointStore` wrapper whose `admit()`/`cancel()` signal a `CountDownLatch` and block on
another, so the interleaving is forced rather than hoped for.

| # | Test | Forced interleaving |
| --- | --- | --- |
| T1 | Cancellation before effect admission prevents dispatch | cancel commits, then admit → latch-ordered; assert the transport/process was never invoked |
| T2 | Effect admission before cancellation yields `CANCELLED_UNCERTAIN` | admit granted, cancel commits before dispatch → assert dispatch happened, outcome uncertain |
| T3 | A step admitted earlier cannot authorize a new effect after cancellation | step admit, cancel, then effect admit → refused; assert no second dispatch |
| T4 | A consumed permit cannot authorize another invocation | one permit, two `tryConsume()` calls → the second throws and the transport sees one call |
| T5 | Retry requires fresh admission and cannot reuse a permit | attempt 1 fails, attempt 2 re-admits → two distinct `admissionSeq` values; reuse path is refused |
| T6 | A stale worker or checkpoint generation cannot obtain admission | admit with an old generation → refused, no dispatch |
| T7 | Late approval and delayed wakeup cannot bypass run-level authority | resume path forced after cancel → refused at E9 |
| T8 | Restart and concurrent workers cannot resurrect execution | two workers, both admit after cancel commits → both refused; row remains terminal |

Each critical guard needs a **mutation kill**, not just a green test: removing the guard from `admit()` call sites
must fail a named test, and the mutants must be applied to the actual call site (E1–E9), not to a helper.

## 9. Non-goals

- No second policy engine, no retries machinery, no new scheduler, no dashboard.
- No claim that cancellation stops an in-flight effect.
- No claim of exactly-once external execution.
- No change to the 0.7.7a store semantics: this contract consumes the authority, it does not extend it.
- Recovery re-execution (E10) keeps its current lease semantics; this contract only requires that its effects pass
  through the same guards.

## 10. Decisions (ratified, review 5730382997)

| ID | Decision | Ruling |
| --- | --- | --- |
| **D6** | File/markdown profile behaviour | **Fail closed.** Single-node admission is not yet proven durable and atomic; qualify it separately rather than shipping a weaker fallback. |
| **D7** | Evidence location | **Step-attempt lineage, per-effect child records.** One attempt can produce several effects. |
| **D8** | MCP server spawn | **An external effect with its own permit**, ordered before the tool call; a startup permit never authorizes `callTool`. |

### 10a. Admission-receipt correction (P1, fixed in this PR)

The permit identity includes `admissionSeq`, so a sequence must be unique to the grant that produced it. The
implementation incremented `admission_seq` and then read it back on an autocommit connection; two concurrent grants
could both observe the later value and return the same sequence. `admit()` now reads the counter under a row lock
inside one transaction and returns `observed + 1`, so the receipt is the grant's own increment and cannot be another
caller's observation.

Qualified by two tests and two mutants:

- **Concurrency:** eight callers on separate connections, released by a `CountDownLatch`, must receive distinct
  sequences `1..8`, and the stored counter must equal the number of grants.
- **Statement order:** a recording `DataSource` asserts that no `SELECT` of the counter follows the `UPDATE` inside a
  granted admission — the deterministic form of the same requirement, independent of interleaving.
- Mutants: receipt taken from a read-back after the write (killed by the ordering test); `FOR UPDATE` removed from
  the locked read (killed by the concurrency test).

## 10b. Stage 1 as implemented (E11, step admission)

`WorkflowStepExecutor.executeStep` -- the single choke point every built-in step passes through -- asks the durable
authority for admission before the step exists as work, so a refusal happens at the boundary rather than somewhere
inside a step that already began.

The authority reaches the executor as a **capability**, never as a persistence session. `WorkflowStepExecutionServices`
carries an `internal WorkflowStepAdmission`, established once per execution path from the run's own persistence session
in `WorkflowRunner`, and `executeStep` calls `services.stepAdmission?.admit(step.name)`. Nested steps inherit it through
the services they already receive, while their `persistenceSession` stays `null`: a nested step is admitted without
becoming a checkpoint boundary, so admission authority is carried but checkpoint authority is not. The capability is
`internal` on purpose -- its consumers are this module's execution infrastructure, and publishing it would be an API
promise with no external caller behind it.

Two limits are part of the design, not gaps to be closed later:

- **Only governed runs are fenced.** `governedRunIdentity` is the caller's declaration that the run claims the durable
  guarantees. An ordinary run carries no such identity, has no cancellation authority to respect, and keeps working
  untouched: fencing every run would break execution modes that never asked for the guarantee, and would claim an
  authority those modes do not have.
- **A governed run with no persisted row is not yet fenced.** Cancellation is a write to a row, so a run with no row
  cannot have been cancelled. Admission becomes mandatory from the first checkpoint onwards, which is exactly when the
  authority can start refusing.

Refusals are terminal for the attempt: `WorkflowAdmissionRefusedException` names the run, the step and the recorded
lifecycle status, and the caller stops rather than retrying the same attempt. A store that cannot decide admission
refuses a governed step outright (`WorkflowCancellationUnsupportedException`), which is the D6 ruling applied to
execution rather than only to cancellation.

Proven at two levels. `WorkflowStepAdmissionTest` (four session-level cases: cancelled governed run refused, ordinary run
unaffected, no-row run unaffected, admitting-incapable store refused) with three mutants killed -- fencing every run,
ignoring the refusal, and admitting a made-up revision. `WorkflowRunnerStepAdmissionTest` (three runner-level cases on
the real runner): a nested step after cancellation commits is refused and its body never runs; an ordinary run with no
cancellation completes its nested steps; an ordinary run *with* cancellation executes the nested step -- so no admission
refusal applies to a run that never claimed the guarantee -- and then fails at the terminal checkpoint row, which proves
cancellation still committed and terminal checkpoint authority was not overwritten. That last case deliberately does not
expect the run to finish.

## 10c. Stage 2 discipline (ratified)

A per-effect permit must be a **distinct capability** from step admission. An E11 step admission is never
authorization to start a process: the step was admitted, the effect was not. E5-E7 therefore take their own permit
immediately before `ProcessBuilder.start()`, single-use, with cancellation tested on both sides of the admission.

## 10d. Stage 2 as implemented (E5-E7, process permits)

The authority reaches the execution paths as a **separate capability**, `WorkflowProcessAdmission`,
installed once for each run or resume by `WorkflowRunner` in a `WorkflowProcessAdmissionScope` coroutine-context
element. Each dispatch site reads it with `currentProcessAdmission()`. Nested steps inherit that context without
receiving a checkpoint session or extra execution privileges. `WorkflowStepAdmission` instead travels on
`WorkflowStepExecutionServices`: these are different capabilities, and a step grant never authorizes a process.

**One grant per dispatch.** `ShellWorkflowStep.executeCommand` (E7) and the shared
`AgentCliSupport.executeAgentCli` path (Codex E5 and Hermes E6) each call
`currentProcessAdmission()?.admitProcess(stepName, PROCESS_EFFECT_KIND)?.tryConsume()` immediately before
`startOwnedProcess { start() }`, outside the launch `try` block. `startOwnedProcess` keeps its `NonCancellable`
acquisition unchanged: the durable act happens before it, and the one-shot permit governs the start that follows.
`tryConsume()` returns true exactly once and throws on a second call, so a permit cannot authorize two dispatches.

**Refusals are not launch failures.** `WorkflowAdmissionRefusedException` and `WorkflowCancellationUnsupportedException`
are re-thrown ahead of every wrapper that would otherwise convert a step-body error: the shell step's catch, the two
CLI steps' `codeForCliFailure` catch, and `WorkflowStepExecutor`'s sanitising catch. A refusal therefore never surfaces
as `AgentCliStartException`, `START_FAILED` or a sanitised step failure -- it names the run, the step and the effect.

**Fail-closed, including when there is nothing to ask.** `WorkflowPersistenceSession.effectAdmissions()` returns
null for an ordinary run (backward compatible: no authority is consulted at all). Its process and HTTP capabilities
refuse admission when a governed session has no revision to present. A governed run whose execution has no persistence session at all receives
`refusingProcessAdmission(...)` from `WorkflowRunner`, so "no authority installed" is never read as permission.
A store that cannot decide admission refuses through its own default, which propagates.

**One boundary deliberately left out.** §7's per-effect child records (`ADMITTED`/`DISPATCHED`) are **not** emitted
yet: this slice records the durable grant (`admission_seq`) and nothing about its use. That is the D7 evidence slice
(§11 step 4), and until it lands no operator may read the absence of an effect record as "never dispatched" -- §7's own
rule already forbids that reading.

Proven by `WorkflowProcessAdmissionTest` (12 cases): a consumed permit cannot be reused; an ordinary run dispatches and
asks no authority; a cancelled governed run is stopped at the step fence; an admitted step does not authorize the
process it wants; an effect admitted before cancellation still dispatches and leaves the run `CANCELLED_UNCERTAIN`; a
store that cannot admit refuses the effect; a governed run with no durable session never dispatches; two dispatches take
two distinct grants; and the codex and hermes boundaries each take their own grant and each refuse the process instead
of reporting a failed launch. Ordering is forced by a store decorator, never by sleeps, and the cases that must prove a
process did *not* run use an executable that cannot start, so a launch attempt would surface as `START_FAILED` instead
of an authoritative refusal.

## 10e. Stage 3 as implemented (E1-E2, HTTP permits)

**Topology first, because it decides the design.** `HttpTransport` is an `internal` interface; the only production
constructor of `JdkHttpTransport` is `WorkflowRunner.executionServices()`; and the only production caller of
`HttpTransport.send` is `HttpWorkflowStep.executeRequest` (E2). E1 therefore sits **behind** E2 on every runtime-owned
path, and admitting at E2 covers the default JDK transport, any internal transport implementation, and the JDK
`send(...)` behind them — with nothing left for E1 to ask. Taking a second grant at E1 would charge one HTTP attempt
twice. So the single authoritative admission point is E2, immediately before the transport is invoked.

That topology is not left as a comment: `WorkflowHttpAdmissionTest` asserts it from the sources — one `.admitHttp(`
call site in the whole main tree, in `HttpStep.kt`; no other main file calls `.transport.send(`; and no admission call
inside `JdkHttpTransport`. Re-introducing an E1 admission fails a named test instead of quietly double counting.

**A third capability, not a reuse.** `WorkflowHttpAdmission` / `WorkflowHttpPermit` are distinct types from both
`WorkflowStepAdmission` and `WorkflowProcessAdmission`, installed by `WorkflowRunner` in a
`WorkflowHttpAdmissionScope` coroutine-context element **in the same `withContext` as the process scope** (one context,
two authorities). Neither an admitted step nor a process permit can be presented at an HTTP send site, and the HTTP
capability cannot authorize a process. Nested steps inherit the frame as they do for E5-E7.

**One grant per attempt, retries included.** `HttpWorkflowStep.executeRequest` calls
`currentHttpAdmission()?.admitHttp(name, HTTP_EFFECT_KIND)?.tryConsume()` immediately before
`execution.transport.send(...)`, outside any block that rewrites a thrown error into a step failure code. Because
`executeRequest` is called once per attempt inside the retry loop, the retry that follows a retryable status asks
again and receives a new `admissionSeq`; the previous permit is not reused, and a cancellation committed between
attempts refuses the next attempt before the transport is reached.

**One authority implementation, two families.** `WorkflowPersistenceSession.effectAdmissions()` creates one
`WorkflowEffectAdmissions(process, http)` containing two distinct capability types. Both use its shared local
`grant(stepName, effectKind)` routine: present `currentRevision`, take the store's conditional write, and refuse when
the write did not take. A later authority fix therefore lands once without the families drifting. The factory returns
null for an ordinary run (no authority is consulted); governed capabilities refuse when the session has no revision.
A governed run without a persistence session receives `refusingHttpAdmission(...)` from `WorkflowRunner`.

**Refusals survive the HTTP error path.** `HttpWorkflowStep`'s retry-loop catch maps errors to `POLICY_REJECTED`,
`TIMEOUT` and `TRANSPORT_FAILED`. `rethrowIfAuthoritativeRefusal()` now runs first, so
`WorkflowAdmissionRefusedException` and `WorkflowCancellationUnsupportedException` are never reported as a failed send.
Everything else in the HTTP path is untouched: redirect refusal, address validation, outbound policy, response limits
and cancellation behaviour are exactly as they were.

**Still outstanding.** §7's per-effect child records remain unimplemented, so an HTTP grant proves authorization and
nothing about dispatch. `admission_seq > 0` continues to mean "this run admitted work", and the store's derived
`CANCELLED_UNCERTAIN` is the correct reading of a run that admitted a request and did not finish.

Proven by `WorkflowHttpAdmissionTest` (11 cases): a consumed permit cannot be reused; an ordinary run sends and asks no
authority; one governed request takes exactly one HTTP grant; cancellation committed before the HTTP admission
prevents the send (zero transport invocations); admission committed before cancellation still sends and leaves the run
`CANCELLED_UNCERTAIN`; a retry takes a fresh grant per attempt (distinct sequences); cancellation between attempts
refuses the next send; a store that cannot admit refuses; a governed run with no durable session never sends; a
connected-address transport is guarded exactly like the default one; and the single-admission topology holds. Ordering
is forced by a store decorator, never by sleeps, and the transport is a counting fake, so "no dispatch" is a zero on a
counter.

Mutants, all applied to the real call sites and all killed: the dispatch-site admission deleted; the grant hoisted to
once per step instead of once per attempt (retry case red); the authoritative-refusal rethrow deleted (refusal
reported as a transport failure); the ordinary-run null removed (an ungoverned run fenced, backward compatibility red);
the runner installing no authority instead of a refusing one (governed run with no session dispatches); `tryConsume()`
made reusable; and a second admission inserted at E1 (double count, and the topology assertion red).

## 11. What approval of this contract authorizes

Approval authorizes implementation of the guards **in this order**, each with its own adversarial tests:

1. `WorkflowStepExecutor` step admission (E11) and the refusal path for a cancelled run.
2. Effect permits at the process boundaries E5–E7, then the HTTP boundaries E1–E2, then MCP server startup E4 and
   MCP tool calls E3, then external executors E8.
3. Approval continuation E9, and recovery re-execution E10.
4. Evidence recording per D7.

Until step 2 lands with its adversarial proof, no runtime enforcement claim may be made — the store-level
authority remains the only proven part, exactly as §9 of the 0.7.7a contract states.
