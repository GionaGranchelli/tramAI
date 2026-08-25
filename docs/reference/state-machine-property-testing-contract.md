# State-Machine and Property-Based Testing Contract

Epic 8.2 replaces "specific scenario → expected result" with
"model state → generated action → predicted transition → execute real
store → compare → assert all invariants → repeat" for the lifecycle-heavy
components of TramAI.

## Epic 8.2 matrix

| Target | Status |
|---|---|
| Approval lifecycle | ✅ #278 |
| Continuation lifecycle | ⏳ |
| Worker lifecycle | ⏳ |
| Lease lifecycle | ⏳ |
| Outbox lifecycle | ⏳ |
| Workflow checkpoint/resume lifecycle | ⏳ |
| Circuit breaker states | ⏳ |
| Provider retry/fallback | ⏳ |

## Method

Every 8.2 slice follows the same shape:

1. **Independent pure model.** The oracle is a standalone state machine
   encoded from the DOCUMENTED contract (KDoc + prior example TCKs) — never
   derived from production helpers such as `resolveNextStatus(...)` or
   `consumeFreshApproval(...)`. Otherwise the suite only proves production
   agrees with itself.
2. **State-aware action alphabet.** Actions carry everything needed to
   execute against the real component (actors, workers, versions, tokens);
   the model decides legality for the current state. Illegal actions are
   first-class corpus members — rejection is part of the contract.
3. **Deterministic corpus.** Explicit seeds, no property-testing framework
   yet (no Kotest/jqwik/QuickCheck). `32 seeds × 32 actions` per
   implementation. Same seed → same trace, pinned by a coverage guard.
4. **Exact predicted outcome per action.** Success → exactly the
   model-predicted state (whole-record comparison); rejection → exactly the
   model-predicted failure category AND zero durable mutation.
5. **Invariants after EVERY action.** Not only the field touched by the
   latest action — status monotonicity, version discipline, field
   pairing/immutability, and full-record equality with the model.
6. **Concurrency properties.** Real parallel workers (start barrier, one-way
   release), 20 iterations: the result must correspond to one legal
   serialization of the concurrent operations.
7. **Coverage guard.** A pure test proves the fixed corpus reaches every
   important category, so future generator edits cannot silently delete
   behavioral coverage.
8. **Mutation evidence.** Every mutation must turn a NEW 8.2 property RED on
   its own — it does not count if only an older example test detects it.

## Failure diagnostics

Every generated-sequence property failure prints:

```
seed=<seed>
step=<step>
action=<action>
prefix:
  0 <action>
  1 <action>
  ...
modelBefore=<model state>
expected=<predicted outcome>
actual=<actual outcome>
```

Because invariants are checked after every action, the failing prefix is
already a useful minimal reproduction.

---

## Approval lifecycle — #278 (Epic 8.2a)

`ApprovalStoreTck` (tramai-testing testFixtures) gained **5 model-based
properties** on top of its 37 example-based cases — 42 shared cases × 3
implementations (InMemory, File, JDBC). No new runner family: the existing
harness (fresh store + deterministic `MutableClock` per case) already
provides everything.

### Model states

```
PENDING@v0
   │
   ├── approve ──────> APPROVED/UNCONSUMED@v1
   │                          │
   │                          └── consume ──> APPROVED/CONSUMED@v2
   │                                             │
   │                                             └── exact replay → unchanged @v2
   │
   ├── deny ─────────> DENIED@v1
   │
   └── timeout ──────> TIMED_OUT@v1
```

`ApprovalLifecycleModel` carries status, version, now, decidedBy/decidedAt/
decisionComment, consumedBy/consumedAt. The immutable request fields
(approvalId, binding, requestedBy, requestedAt, expiresAt) are pinned
separately; after every action the expected `ApprovalRequest` is
reconstructed and compared to `store.get(id)` **exactly**.

### Action alphabet

```
AdvanceToBeforeExpiry / AdvanceToExactExpiry / AdvancePastExpiry
ApproveCurrentVersion / ApproveWrongVersion
DenyCurrentVersion / DenyWrongVersion
TimeoutCurrentVersion / TimeoutWrongVersion
ConsumeValid(worker) / ConsumeWrongVersion / ConsumeWrongToken
```

`ConsumeValid(workerB)` legality depends on state: unconsumed → fresh;
consumed by workerA → exact replay; consumed by workerA but caller workerB →
rejected.

### Expiry boundary (pinned)

`now < expiresAt` → decisions legal, fresh consume legal.
`now >= expiresAt` → timeout legal (while PENDING), decisions illegal, fresh
consume illegal, exact replay of an already-consumed approval remains legal.
This matches the implementation's `now >= expiresAt` boundary in all three
stores.

### Per-step invariants

Status monotonicity (PENDING leaves once, terminal never changes); version
never decreases or jumps (max lifecycle version v2); binding/identity
immutable; PENDING carries no decision/consumption fields; DENIED/TIMED_OUT
never acquire consumption fields; APPROVED consumption fields both-null or
both-set; decision and consumption metadata immutable after being set;
rejected action → durable record value-identical before/after (externally
observable domain equality — `store.get(id)` equals the pre-action record;
not a claim about persisted bytes).

### Generated corpus

32 seeds × 32 actions = 1,024 lifecycle actions per implementation × 3 =
3,072 model-checked operations. Every seed opens with a GUARANTEED
wrong-version decision evaluated against the PENDING pre-state (the only
state where the transition version guard is the discriminator) — the corpus
must never depend on luck for a primary optimistic-concurrency invariant.
`ApprovalLifecycleActionGeneratorTest` pins: same seed → same trace, all 18
semantic categories present (valid approve/deny/timeout, early-timeout
rejection, approve/deny-after-expiry rejection, exact-expiry boundary,
wrong-version conflict, wrong-version-while-pending, post-terminal
rejection, fresh consume, expired fresh-consume rejection, fresh-consume
wrong-version rejection, wrong-token rejection, exact replay, replay after
expiry, wrong-actor/wrong-version replay rejections), and all four statuses
reached. Categories are recorded from the MODEL WALK (reachable pre-state
semantics), not from action-enum presence: e.g. `exact-expiry-boundary`
requires a PENDING pre-state at exactly `now == expiresAt` (not just
`now > expiresAt`), and `fresh-consume-wrong-version-rejection` requires
APPROVED + unconsumed — a future generator edit that silently loses these
discriminators fails the guard.

Note on time actions: the `Advance*` actions SET the clock absolutely and
may move it backwards (e.g. past-expiry then before-expiry). Clock reversal
is part of the tested model — deterministic time control for boundary
pinning, not wall-clock monotonicity. Epic 8.3 owns time abstractions.

### Wrong-version decision matrix (deterministic, not a race)

For every Approve/Deny/Timeout with `expectedVersion = durable + 1`, at
t0 / exact expiry / after expiry → `ApprovalStoreConflictException` and the
durable record exactly unchanged. Pins optimistic versioning on DECISION
transitions including failure precedence: version is checked before the
expiry window, so a stale-version decision at/after expiry is a CONFLICT,
not an IllegalApprovalTransition.

### Concurrency properties (×20 iterations, start barrier)

- **Duplicate decisions:** 8 contenders (Approve/Deny alternating,
  expectedVersion 0) → exactly 1 success, 7 conflicts, durable v1 with the
  winner's status/decidedBy; no second decision can overwrite the winner.
- **Identical consumption:** 8 identical consumers (same version, token,
  worker) → 1 fresh receipt + 7 exact replays, all returning the SAME
  durable v2 record with identical consumedAt.
- **Competing consumers:** 8 unique actors → exactly one fresh winner,
  durable v2 with the winner's consumedBy; no second actor ever obtains a
  successful receipt. Every loser deterministically surfaces
  `ApprovalStoreNotConsumableException`: whole-consume atomicity (InMemory
  CAS, File per-record lock, JDBC `FOR UPDATE` row-lock) serializes each
  loser AFTER the winner, so the loser reads the consumed record, takes the
  replay path with the valid predecessor version (1) and matching token,
  and fails only on the actor check. Conflict or TokenRejected are NOT
  legal serialized loser outcomes — a loser leaking Conflict would mean an
  implementation that lost a race without re-reading the winning state
  (the exact false confidence this suite exists to remove).

### Contract precedence discovered

The model originally checked the expiry window before the token on the
wrong-token path; all three stores uniformly check the token FIRST (an
expired-but-unconsumed approval with a wrong token is
`ApprovalStoreTokenRejectedException`, not `NotConsumable`). The SPI KDoc
does not pin check order. This PR makes an explicit decision: **token-first
is promoted to an intentional cross-store contract**, pinned by the model
and verified on all three implementations. If a future implementation ever
wants a different precedence, it must first change the SPI KDoc — the
shared TCK will reject the divergence.

### Mutation evidence (18 mutations, each restored)

Each mutation makes a NEW 8.2 property RED (generated-sequence, identical-
consumption and/or competing-consumers); none rely on an old #267 example
test:

| Mutation | Discriminator |
|---|---|
| terminal decision guard removed | generated sequence |
| expiry boundary `>=` → `>` | generated sequence |
| early timeout allowed | generated sequence |
| approve after expiry allowed | generated sequence |
| deny after expiry allowed | generated sequence |
| transition version check removed | generated sequence + wrong-version matrix |
| decision version increment removed | generated sequence |
| decidedAt not updated | generated sequence |
| decidedBy dropped | generated sequence |
| fresh consume version check removed | generated sequence |
| fresh consume expiry check removed | generated sequence |
| fresh consume version increment removed | generated sequence |
| replay changes the durable record | generated sequence + identical consumption |
| replay accepts a different consumer | generated sequence |
| token check removed | generated sequence |
| exact replay disabled | generated sequence + identical consumption |
| replay accepts expectedVersion == durableVersion | generated sequence |
| replay loser leaks Conflict instead of NotConsumable | competing consumers + generated sequence |

### Production changes

None. Zero production code, zero public API, zero schema, zero persisted
format. The model exposed one genuine cross-store behavior (token-first
precedence) which the SPI KDoc leaves unpinned; the model was aligned, not
the stores.

---

## Epic 8.2b — Approval continuation lifecycle state-machine properties (PR #279)

The continuation lifecycle is richer than the approval lifecycle in one
decisive way: **a failed operation can legitimately mutate durable state**.
A late claim/cancel normalizes an elapsed PENDING row to EXPIRED before
reporting its typed failure, so the model outcome carries the post-failure
state (`Failure(kind, next)`), not merely a failure kind.

### Model

`ApprovalContinuationLifecycleModel` (pure oracle, not derived from
production helpers) tracks status / version / now / claim / completion /
recovery metadata / argumentsAvailable over the six statuses
(PENDING@0 → CLAIMED@1 → COMPLETED@2 | CANCELLED_UNCERTAIN@2;
PENDING@0 → CANCELLED@1 | EXPIRED@1). Immutable identity fields are pinned
separately by the executor and reconstructed for whole-record comparison.
Lazy expiry (`now >= expiresAt` on PENDING) mirrors the stores'
`expireIfElapsed`; `normalizedAt(observationTime)` handles the crucial
detail that the TCK's verification `get()` runs at the pre-action clock
instant and can itself transition a still-PENDING record — the model
accounts for observation-time normalization before comparing.

### Generated corpus

32 seeds × 32 actions = 1,024 model-checked operations per implementation
× 3 = 3,072, plus 16-seed multi-record sweep and stale-claim models. Every
seed opens with a fixed non-mutating phase pinning the version guards
against the PENDING non-expired pre-state (wrong-version claim, early
explicit expire, wrong-version cancel/expire, complete on non-claimed,
force-cancel on non-claimed), then `seed % 4` selects a forced lifecycle
archetype: claim→complete / claim→cancelled-uncertain / claim→wrong-actor+
wrong-version recovery exercises; exact-expiry boundary (advance to exactly
`expiresAt` then explicit expire); advance past expiry then lazy get / late
claim / late cancel; valid cancel / advance past expiry + explicit expire
(with a wrong-version expire evaluated AFTER the deadline — the version
guard is the only thing standing between the record and a spurious EXPIRED
transition).

**Deliberate scope decision:** wrong-version actions are never emitted on
terminal/EXPIRED states. The combined-invalid precedence (already-EXPIRED +
wrong expected version) currently differs across stores (InMemory checks
status first; File/JDBC fall through to the version check on an
already-normalized row) and is an implementation detail — the corpus pins
version-first only on the discriminating pre-states (PENDING before expiry,
CLAIMED). The `ApprovalContinuationLifecycleActionGeneratorTest` guard
asserts 25 semantic categories incl. `exact-expiry-boundary`,
`late-claim-persists-expired`, `late-cancel-persists-expired`,
`get-lazy-expiry`, `expire-wrong-version-after-expiry`, the four
terminal-stable categories, and exactly-once argument release per seed.

### Properties (10 new shared cases, 50 → 60 × 3 implementations)

1. **Generated lifecycle sequences** — every action's typed return/failure
   and durable state agrees with the model, including failure paths that
   perform lazy-expiry normalization; whole-record equality after every
   step; released raw arguments equal the original exactly once.
2. **Failed late claim / late cancel persist EXPIRED before reporting** —
   a late claim (NotClaimable) and late cancel (Conflict) must themselves
   persist EXPIRED@v1: the clock is rewound to t0 before the read so the
   assertion's own `get()` cannot lazily expire a still-PENDING record and
   mask a missing normalization (the operation, not the read, must have
   transitioned the row).
3. **Wrong-version matrix** — PENDING-before-expiry (claim/cancel/expire
   with expectedVersion 1) and CLAIMED (complete/forceCancel/cancel with
   expectedVersion 0) → typed Conflict + value-identical record.
4. **Eight concurrent claims** (×20) — exactly 1 fresh winner releasing the
   raw arguments, 7 Conflict losers (whole-consume atomicity serializes
   each loser after the winner), durable CLAIMED@1 with the winner's
   identity; a follow-up claim → NotClaimable.
5. **Mixed claim/cancel race** (×20, 4+4) — exactly one legal transition to
   CLAIMED (one release) or CANCELLED (zero releases); 7 Conflict losers.
6. **Claimed resolution race** (×20, 4 complete + 4 forceCancel) — exactly
   one winner to COMPLETED (completedAt set, recovery null) or
   CANCELLED_UNCERTAIN (completedAt null, recovery fields = winner); never
   COMPLETED+recovery, never version > 2.
7. **Concurrent lazy expiry** (×20, 8 observers at exactly `expiresAt`) —
   every observation EXPIRED@1, durable EXPIRED@1, never v2+.
8. **Generated sweep model** (16 seeds × 27 records) — only elapsed PENDING
   rows transition (each exactly once, v0 → v1 EXPIRED); live PENDING,
   CLAIMED and terminal rows are compared pre/post-sweep and must remain
   value-identical; second sweep zero.
9. **Generated stale-claim query model** (16 seeds, accumulated records) —
   boundary-inclusive filter (`claimedAt <= claimedBefore`, incl. a record
   claimed exactly at the boundary), claimedAt ASC then approvalId ASC
   ordering, limit — compared against a pure collection model.

### Production changes (1, deliberate)

Epic 8.2b exposed one genuine File-store defect: `findStaleClaimed`
truncated to `limit` in content-hash file order BEFORE sorting, so with
more stale rows than the limit it returned the wrong subset (the #269
3-record ordering test could not catch it because its limit exceeded the
record count). Fixed to filter → sort → take, matching the documented
contract and the InMemory/JDBC implementations. No other production,
public-API, schema, or persisted-format changes.

## Epic 8.2c — Worker lifecycle state-machine properties (PR #280)

Unlike the approval slices, the worker lifecycle has a single
implementation (`TramaiWorker` → `WorkerLifecycleController` +
`WorkerShutdownCoordinator`) — no backend matrix, so the oracle lives in
`tramai-orchestration` test sources, not `tramai-testing` testFixtures.
The question: *after arbitrary start/shutdown/crash/restart histories and
adversarial interleavings, can the worker have two lifecycle owners,
resurrect an old generation, continue accepting work after shutdown, leak
registration, or emit lifecycle events for a generation that has already
died?*

### Model

`WorkerLifecycleModel` (pure oracle) tracks phase (STOPPED / STARTING /
RUNNING / SHUTTING_DOWN / CRASHED), generation, registered /
acceptingWork / rootOwned flags, and exactly-once event counters
(workerStarted, shutdownStarted, shutdownComplete, workerStopped,
registrations, unregistrations). `WorkerLifecycleOutcome` again carries
`Failure(kind, next)` — a failed startup rolls the model back to STOPPED
(retryable), never leaves a half-owned STARTING generation. State graph:
`STOPPED → STARTING → RUNNING → SHUTTING_DOWN → STOPPED`;
`RUNNING → CRASHED` (crash is NOT graceful: registry record retained so it
can go stale); `CRASHED --start/crash--> no-op`; `CRASHED --shutdown-->
STOPPED`. The enum is test-only; production keeps its own simpler ownership
primitive.

### Generated corpus

32 seeds × 32 actions = 1,024 synchronous lifecycle operations, every seed
opening with a forced archetype (seed % 6: clean start/shutdown cycles,
shutdown-before-start + duplicate starts, crash → shutdown → restart,
multiple clean generations, duplicate starts + duplicate shutdowns,
close-equivalence) then a state-aware random free-run. The generator guard
asserts 23 semantic categories computed from model pre-state + action +
predicted outcome (never enum presence): the five phase transitions plus
crashed-start no-op, close from running/stopped, restart-after-shutdown,
multi-generation, exactly-once per-generation registration / started /
shutdownStarted / shutdownComplete / workerStopped / unregister, registry
present-while-running, registry absent-after-graceful-stop,
registry-retained-after-crash, generation monotonic.

### Properties (16)

1. **Generated worker lifecycle sequences** — after every action: event
   counters, registry presence, registration/unregistration counters,
   phase, generation, and per-step invariants agree with the model
   (idempotent start, no-op shutdown before start, crash semantics, no
   generation after its shutdown).
2. **Failed registration rolls startup back** — registerWorker throws →
   no started event, empty registry, and a subsequent start() retries
   normally (STOPPED, not a stuck half-owned generation).
3. **Shutdown during registration cannot resurrect the startup** — the
   suspended start of generation B must abort when B is shut down while
   registration is in flight: no workerStarted after shutdownComplete, no
   zombie registry row, no new heartbeats, and a fresh generation C starts
   cleanly. The event order `shutdownComplete(B) → workerStarted(B)` is
   rejected.
4. **Eight concurrent starts create one generation** (×20, start barrier)
   — exactly 1 registration, 1 workerStarted, 1 active identity; graceful
   shutdown afterwards.
5. **Eight concurrent shutdowns have one owner** (×20) — exactly 1
   shutdownStarted / shutdownComplete / workerStopped / unregister / owner
   release, 7 no-op observers, fresh start afterwards.
6. **Start while shutdown is in progress cannot transfer ownership** —
   shutdown A held in drain; 8 start() calls while draining are no-ops (no
   generation B); after drain release a single new start → generation B.
7. **Crash is not graceful shutdown** — crash cancels the root execution
   scope (observed via a blocking heartbeat fake) but emits NO shutdown
   events and retains the registry record (stale/takeover semantics); a
   later explicit shutdown performs the real cleanup.
8. **Shutdown stops heartbeat/poll ownership completely** — blocking
   fakes (registry heartbeat and checkpoint catalog that signal entry then
   suspend forever); shutdown() must cancelAndJoin both loops BEFORE the
   onWorkerStopped event fires (deterministic order observation via a
   synchronous observer hook — root cancellation alone would kill the
   loops after the stopped event and is exactly what the property rejects).
9. **Stale start contender has zero authority** (review round, P1) —
   deterministic idle-worker gate: starter A observes STOPPED and pauses
   at the ownership-claim boundary (test seam), starter B wins ownership
   and reaches RUNNING, B's shutdown begins and is held at the unregister
   step; A resumes and loses the ownership CAS. A must NOT reset the
   winning generation's shutdown state (a second shutdown caller must
   remain a loser and return immediately), must NOT become an owner, and
   must NOT register. Exactly one shutdown sequence completes; a fresh
   generation starts afterwards. No workflow / lease / recovery machinery
   is exercised — the discriminator lives entirely in the lifecycle state
   machine.
10. **Aborted start cannot release ownership while shutdown still owns
    the generation** (review round, P2a; round 3 re-shape) — generation
    B's startup is suspended in registration; shutdown B starts and is
    held at the unregister step (shutdown owner won, ownership NOT yet
    released); B's registration resumes. The aborted startup must
    reconcile its registration side effect but must NOT clear root
    ownership: a new start() while the shutdown is still in progress
    remains a no-op, the shutdown completes exactly once, and only after
    completion can a fresh generation start. Round 3: the RECONCILING
    reservation masks a plain release (reconcile re-reserves before
    unregistering), so the discriminator is release WITH the
    reconciliation skipped — a fresh start must not claim/register while
    the previous shutdown is still draining.
11. **Cancelled registration rolls the startup back** (review round, P2b)
    — the start coroutine is cancelled while registerWorker is suspended;
    the SAME CancellationException instance escapes (the property asserts
    `caught?.cause isSameAs cause` — the framework may surface a wrapping
    cancellation whose cause is the supplied instance; production rethrows
    the exception it itself caught, so the escaped instance's cause is the
    supplied one), ownership is rolled back (no started event, no zombie
    row), and a subsequent start() succeeds. The cancellation path must
    not skip mandatory ownership rollback just because cancellation is
    preserved.
12. **Shutdown in the claim→prepare gap is accepted, never lost**
    (review round 2, M24) — generation A completes a full lifecycle; B
    wins the ownership claim and pauses (test seam) BEFORE the
    shutdown-state reset. A shutdown of B in that gap must be accepted and
    complete (shutdownStarted/shutdownComplete/workerStopped fire), and B
    must not reach RUNNING afterwards. This rejects a lifecycle-global
    shutdown-idempotency boolean whose stale value from generation A
    rejects generation B's shutdown.
13. **Startup cannot commit RUNNING after a completed shutdown**
    (review round 2, M25) — B's registration completes, every revalidation
    passes, then B pauses (test seam) immediately before the RUNNING
    commit; a shutdown completes fully in that window. Resuming must NOT
    emit workerStarted, install a hook, or accept work — the
    STARTING→RUNNING transition must be atomic, so the shutdown owner's
    decision can never be overridden by a startup commit.
14. **Failed-startup cleanup cannot delete a newer generation's row**
    (review round 2, M26; round 3 re-shape) — A's registration fails; A's
    rollback cleanup pauses (registry hook) before the delegate
    unregister. B starts. A's cleanup must never delete B's registry row
    (workerId-keyed registry cannot tell generations apart), so cleanup
    is ordered BEFORE ownership release: while A still owns STARTING, B's
    start is a guard-level no-op and cannot register in the cleanup
    window. Round 3: the RECONCILING reservation alone masks a naive
    release-first reorder (reconcile re-reserves before unregistering),
    so the discriminator is the combined defect — release ownership
    first AND unregister naked, skipping the reservation entirely.
15. **Shutdown cannot bisect the activation epilogue** (review round 3,
    M27) — B's RUNNING commit SUCCEEDS and B enters the activation
    critical section, then B blocks synchronously inside the seam
    (immediately before workerStarted). A concurrent shutdown begins
    while B is blocked: it must NOT emit shutdownStarted/shutdownComplete
    — claiming RUNNING→SHUTTING_DOWN requires the same lock the epilogue
    holds. Release the block: B finishes hook/job handoff, THEN the
    shutdown proceeds. The `shutdownComplete(B) → workerStarted(B)` order
    stays impossible even after the commit.
16. **Old-generation STOPPED cleanup cannot delete a newer generation's
    row** (review round 3, M28) — shutdown completes → STOPPED; the old
    registration lands after shutdown's unregister (zombie row); the old
    cleanup reserves reconciliation (STOPPED → RECONCILING) and pauses at
    the unregister hook. A new start races while the reservation is held:
    it MUST NOT register yet. The old cleanup finishes → STOPPED; the new
    start retries → registers and remains registered.
17. **Stale startup reset cannot clobber an in-flight shutdown** (review
    round 4, M29) — B claims STARTING and pauses at the ownership seam; B's
    shutdown claims SHUTTING_DOWN and begins draining (held at the
    unregister hook). B resumes: the verification+reset is ONE atomic
    operation under the activation lock, so the failed verify must prevent
    `prepareLifecycleStart()` — the shutdown's graceful state
    (shuttingDownGracefully/shutdownRoot) stays owned by the shutdown.
18. **Observer reentrant shutdown during activation leaves a clean
    stopped worker** (review round 4, M30) — an observer whose
    `onWorkerStarted` synchronously calls `worker.shutdown()` (same thread,
    reentrant monitor) must leave a fully stopped worker: lifecycle STOPPED,
    no JVM hook, acceptingWork false, registry absent, lazy heartbeat/poll
    never started. The reentrant callback is the FINAL activation step, so
    every shutdown-owned resource already exists and the background jobs
    start only if still RUNNING.
19. **Old-generation cleanup under SHUTTING_DOWN cannot delete a newer
    row** (review round 4, M31) — B's cleanup begins while state is
    SHUTTING_DOWN(B) (still ours) and pauses the suspendable unregister;
    the shutdown owner releases SHUTTING_DOWN→STOPPED; a new C claims and
    tries to register. The reservation must span the suspendable unregister
    from ANY still-ours state, so C's claim fails while the cleanup holds
    RECONCILING — never STOPPED — and C's row survives B's unregister.

### Production changes (2, deliberate)

The suite exposed three genuine defects, all in `WorkerLifecycleController`:

- **Registration failure retained root ownership.** `workerJob` was
  assigned before the suspendable `registerWorker()`; on failure start()
  threw but the root stayed owned, so the next start() silently no-oped.
- **Shutdown during suspended registration resurrected the startup.** The
  existing example test proved shutdown was *accepted* during registration
  but never asserted the suspended start couldn't continue: it emitted
  onWorkerStarted after shutdownComplete, installed a JVM hook for a dead
  generation, set acceptingWork, and launched heartbeat/poll loops on the
  cancelled root — and a registration committed after the shutdown's
  unregister left a zombie registry row.
- A third defect was exposed by the concurrent-start property: the plain
  null-guard + mutable assignment was not atomic, so eight racing starts
  could create two generations.

Fix: `workerJob: Job?` → `AtomicReference<Job?>` lifecycle-ownership
primitive. The review round (first review of #280) closed three further
holes around the same generation boundary:

- **Stale-contender reset (P1).** The per-generation shutdown-state reset
  (`prepareLifecycleStart`) now runs only AFTER winning the ownership CAS,
  so a contender that loses the race has zero authority over the winner's
  shutdown state (previously it could reset `shutdownStarted` +
  `shuttingDownGracefully` mid-shutdown, letting a second shutdown caller
  win the shutdown CAS and run the sequence concurrently).
- **Aborted startup keeps ownership during a drain (P2a).** The
  post-registration revalidation is split: if ownership was lost while
  suspended → reconcile a zombie row (only when nothing newer owns) and
  cancel the root; if a shutdown currently owns and drains this generation
  → reconcile the row but NEVER release ownership (the shutdown owner
  releases it on completion), so a new generation cannot start mid-drain.
- **Cancellation rollback (P2b).** A dedicated `catch (CancellationException)`
  performs the mandatory ownership rollback (release only when this
  generation still owns and no shutdown owns it) in `NonCancellable`,
  then rethrows the SAME cancellation instance — cancellation is never
  classified as a failure, but it also never leaves a stuck half-owned
  lifecycle. A CAS-losing start additionally cancels its provisional
  `SupervisorJob` (P3).

No lock spans `registerWorker` / drain / `unregisterWorker` — the
activation lock covers only the non-suspending lifecycle handoff, so
shutdown during registration is still accepted (round 3, Property 15).

The second review round (properties 12–14) showed that adding more
revalidation checks around a `lifecycleOwner: AtomicReference<Job?>`
plus separate coordinator booleans kept moving the race window. The
fix replaces that split state with ONE generation-aware atomic state
machine (review round 2, P1a/P1b/P2 — the three new races):

- **A single `WorkerLifecycleState` atomic.** `Stopped / Starting(gen,
  root) / Running(gen, root) / ShuttingDown(gen, root) / Crashed(gen,
  root)` in one `AtomicReference`; every transition is a CAS on that one
  reference and carries the generation identity. A stale shutdown state
  from a previous generation can no longer reject (or accept) a decision
  for the current generation — the shutdown claim is the state
  transition itself, not a lifecycle-global boolean.
- **Claim-to-prepare gap closed (P1a).** `prepareLifecycleStart` still
  runs only after the claim, but the coordinator's shutdown idempotency
  is now per-root (`shutdownRoot: AtomicReference<Job?>`) instead of a
  global boolean. A shutdown arriving between the claim and the reset is
  accepted — it wins `STARTING → SHUTTING_DOWN` regardless of what the
  previous completed lifecycle left behind. Property 12.
- **Atomic RUNNING commit (P1b).** The final revalidation and the commit
  are the SAME CAS (`STARTING → RUNNING`). A shutdown that transitioned
  to `SHUTTING_DOWN`, or a rollback that released to `STOPPED`, makes the
  commit fail — startup can never resurrect after a completed shutdown.
  Property 13.
- **Cleanup ordered before release (P2).** `rollbackStart` cancels the
  root, reconciles the registry row in `NonCancellable`, and only THEN
  releases ownership. While the failed generation still owns `STARTING`,
  no newer generation can claim — so the workerId-keyed unregister can
  never delete a newer generation's row. Property 14. A stale
  registration that lands after the shutdown owner released to `STOPPED`
  is still cleaned: it is no longer a naked-STOPPED unregister — the
  cleanup first reserves `STOPPED → RECONCILING(g)` so a fresh claim
  cannot slip into the check-to-unregister window at all (round 3, M28,
  Property 16). No registry-schema generation fencing is needed; the
  state machine reserves the cleanup itself.
- **Shutdown / crash as transitions.** `shutdown()` CAS-loops
  `STARTING/RUNNING/CRASHED → SHUTTING_DOWN` and releases to `STOPPED`
  only after the drain completes; `crash()` CASes `RUNNING → CRASHED`
  (root cancelled, registry retained — not a graceful departure). The
  shutdown idempotency guard is per-root (`compareAndSet`), so two
  concurrent direct coordinator calls cannot run the drain twice for the
  same root.

The third review round showed the state machine is atomic but the
startup activation side effects were not: `check → side effect` around
the commit was still a two-step protocol. The fix linearizes the
non-suspending lifecycle handoff and reserves STOPPED cleanup:

- **Activation critical section (M27).** The RUNNING commit and the ENTIRE
  activation epilogue (workerStarted, JVM hook install + handoff, scope
  attach, acceptingWork, heartbeat + poll launch + handoff) run inside a
  non-suspending critical section (`activationLock`) shared with the
  shutdown and crash lifecycle claims. A concurrent shutdown can never
  observe — let alone bisect — a half-activated worker: claiming
  `RUNNING → SHUTTING_DOWN` requires the same lock, so it either sees
  STARTING (its claim wins, the startup verifies and aborts) or RUNNING
  with the full epilogue already emitted. No check after the commit
  exists and none is needed — the lock IS the linearization. Property 15
  proves it by blocking synchronously inside the critical section and
  asserting shutdown events cannot fire until it is released.
- **RECONCILING reservation (M28).** When a zombie cleanup observes
  STOPPED it no longer unregisters while naked STOPPED. It first CASes
  `STOPPED → RECONCILING(generation, root)`, unregisters, and releases
  back to STOPPED in a `finally`. While the reservation is held, a newer
  generation's claim fails and it retries — so the old cleanup can never
  delete a newer generation's row. Property 16 proves the full sequence:
  shutdown completed → old registration lands → old cleanup reserves →
  new start MUST NOT register yet → cleanup finishes → new start retries
  and remains registered.
- **Identity-CAS discipline.** `AtomicReference.compareAndSet` compares
  by identity, not `equals()`. The RECONCILING reservation therefore
  captures ONE `Reconciling(g, root)` instance and reuses it at both the
  install and the release CAS — constructing a second, merely-equal
  instance for the release would never match and would strand the
  lifecycle permanently in RECONCILING (found by the round-3 review
  subagent as the cause of the fresh-start-after-abort regression).

### Round-4 production changes (review round 4, M29/M30/M31)

The round-4 review found three more ownership escapes — every one in the
"the protected operation can call user code or suspend" family, which the
Socratic rule now states as: *never call user code or cross a suspension
point based solely on a prior ownership observation unless the protocol
explicitly reserves that ownership for the full side effect.*

- **Atomic verification + shutdown-state reset (M29).** The STARTING
  re-check and `prepareLifecycleStart()` were two separate operations
  outside the lock: a shutdown could claim STARTING→SHUTTING_DOWN between
  them, and the stale starter's reset would clear the shutdown's
  graceful state. Now the verify and the reset are ONE
  `synchronized(activationLock)` block — a failed verify never reaches
  the reset. Property 17 discriminates.
- **Reentrancy-safe activation epilogue (M30).** `synchronized` is a
  reentrant monitor, and the observer runs synchronously — so an
  `onWorkerStarted` that calls `worker.shutdown()` re-enters the lock,
  completes the shutdown, and then the old epilogue resumed installing a
  zombie JVM hook / re-enabling acceptingWork / launching jobs on a
  STOPPED worker. The epilogue now creates and hands off EVERY
  shutdown-owned resource (hook, scope, acceptingWork, lazy heartbeat +
  poll jobs) BEFORE the final `onWorkerStarted` callback, and starts the
  lazy jobs only if the lifecycle is still RUNNING afterwards. Property
  18 proves the reentrant shutdown leaves a clean STOPPED worker.
- **Reservation spans ANY still-ours state (M31).** The round-3
  reservation only engaged when the cleanup observed STOPPED. A cleanup
  that observed SHUTTING_DOWN(B) unregistered naked — if that
  suspendable unregister outlived the shutdown's release, a new
  generation could register and be deleted by the old unregister.
  `reconcileRegistration` now reserves `RECONCILING` from any still-ours
  state (Starting/ShuttingDown/Stopped), and the shutdown's release CAS
  treats a same-generation RECONCILING as "the cleanup will release".
  Property 19 discriminates.

### Mutation evidence (31 mutations, each restored; 0 weak)

Baseline GREEN → exactly-one-replacement → NEW property RED → restore →
same property GREEN for every mutation: start claims ownership
unconditionally (GEN / concurrent-start), start never commits RUNNING
(GEN), shutdown never releases lifecycle ownership after the drain (GEN),
`prepareLifecycleStart` removed (GEN / claim-gap), rollback cleanup runs
in a cancellable context (cancelled-start), registration failure retains
root ownership (startup-failure/retry), registration twice per generation
(GEN), onWorkerStarted dropped (GEN), aborted startup keeps the zombie
registry row (registration-race), shutdown transition not atomic
(concurrent-shutdown, controller + coordinator guards removed together),
unregister skipped (GEN), onShutdownComplete / onWorkerStopped /
onShutdownStarted dropped (GEN), crash does not cancel the root (crash),
crash emits graceful events (crash), crash clears ownership (GEN), poll /
heartbeat cancellation skipped (background-loop ownership), started event
fires before the activation verify (registration-race / commit-boundary,
review-round M20), stale contender proceeds past a lost claim
(stale-contender, review-round M21), aborted startup releases ownership
while the shutdown still owns/drains it (abort-mid-drain, review-round
M22), cancelled registration skips rollback (cancelled-start, review-round
M23), shutdown idempotency reverts to a lifecycle-global boolean
(claim-gap, review-round M24), RUNNING commit skips the in-lock STARTING
verification (registration-race / commit-boundary resurrection,
review-round M25), cleanup releases ownership before reconciling the
registry row (cleanup-race, review-round M26), activation critical section
is not serialized against shutdown/crash — epilogue can be bisected
(epilogue, review-round 3 M27), STOPPED cleanup skips the RECONCILING
reservation — old cleanup can delete a newer row (STOPPED-cleanup race,
review-round 3 M28). A CAS-loser provisional-supervisor leak mutation is
intentionally NOT added: the orphan `SupervisorJob` is not observable from
the harness without a production test hook, so such a mutation would be
weak — the fix itself is asserted structurally by property 9's ownership
assertions.

### Known anomaly (NOT fixed in #280 — tracked for follow-up)

Observed while developing #280: the original property-9 draft exercised the
real execution engine (poll → lease claim → resume), and the worker
repeatedly acquired and released the same workflow lease without ever
starting the step. Observed: repeated `onLeaseAcquired`, no
StepAttempt(STARTED), `latestFailure == null`, no poll failure, checkpoint
remained available, cycle repeated every lease duration. The symptom
reproduces only in a lifecycle-heavy integration harness; root cause NOT
isolated. The initial `LeaseFencedCheckpointStore.load` revision-fence
hypothesis is DISPROVED — `load()` is not fenced on master (the fence
applies to save/delete/requireRecovery only). Property 9 was rewritten to
the idle-worker harness (no workflow execution), so #280 no longer depends
on the engine. Follow-up: build a minimal reproducer (one worker, one
checkpoint, one trivial step, worker.start()) and bisect the contributing
factor; if it reproduces outside the lifecycle harness it is a material
orchestration liveness defect and should be fixed in a dedicated PR before
Epic 8.2d.
