# State-Machine and Property-Based Testing Contract

Epic 8.2 replaces "specific scenario → expected result" with
"model state → generated action → predicted transition → execute real
store → compare → assert all invariants → repeat" for the lifecycle-heavy
components of TramAI.

## Epic 8.2 matrix

| Target | Status |
|---|---|
| Approval lifecycle | ✅ #278 |
| Continuation lifecycle | ✅ #279 |
| Worker lifecycle | ✅ #280 |
| Lease lifecycle | ✅ #290 |
| Outbox lifecycle | ✅ #291 |
| Workflow checkpoint/resume lifecycle | ✅ #295 |
| Circuit breaker states | ✅ #302 |
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

### Properties (20)

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
20. **Cleanup finishing before shutdown cannot release lifecycle
    ownership** (review round 5, M32) — the reverse ordering: B's cleanup
    runs to completion while the shutdown owner is still blocked early in
    its drain (parked synchronously at `onShutdownStarted`). STOPPED must
    NOT become visible until BOTH the graceful drain and the same-
    generation late-registration cleanup have finished. A SHUTTING_DOWN-
    origin cleanup therefore restores the captured SHUTTING_DOWN state and
    signals completion; the shutdown owner waits for that signal and CASes
    SHUTTING_DOWN→STOPPED only after its own drain. Property 20 pins the
    ordering Property 19 does not cover: cleanup-first, drain-second.

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

### Round-5 production changes (review round 5, M32)

- **Cleanup does not own the final STOPPED release (M32).** The round-4
  reservation fixed *cleanup-begins-while-shutdown-drains* but not
  *cleanup-finishes-before-shutdown-drains*: `reconcileRegistration` still
  released `RECONCILING → STOPPED` unconditionally in its `finally`, so a
  SHUTTING_DOWN-origin cleanup that completed quickly could expose STOPPED
  while the graceful shutdown owner was still executing observer
  callbacks / job cancels / unregister — and a new generation C could
  claim STOPPED and install its hook, jobs, and registry row while B's old
  drain was still running (which could then remove C's hook, cancel C's
  jobs, unregister C's row under a lifecycle claiming RUNNING(C)).
  `Reconciling` now carries `returnTo` (the captured SHUTTING_DOWN state
  for SHUTTING_DOWN-origin cleanups, STOPPED otherwise) and a
  `completion` signal. The cleanup restores `returnTo` and completes the
  signal; the shutdown's release loop waits for a same-generation
  `completion` before CASing the restored SHUTTING_DOWN → STOPPED.
  STOPPED is therefore visible only after BOTH obligations finish.
  Property 20 discriminates; M32 (release directly to STOPPED) is the
  mutation that reverts this exact ordering.

### Mutation evidence (32 mutations, each restored; 0 weak)

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
review-round 3 M28), stale startup reset runs even when shutdown already
claimed (stale-reset, review-round 4 M29), onWorkerStarted executes user
code before the activation resources exist (reentrant-activation,
review-round 4 M30), old cleanup unregisters naked under
STARTING/SHUTTING_DOWN (cleanup-in-flight, review-round 4 M31),
SHUTTING_DOWN reconciliation releases directly to STOPPED (cleanup-before-
shutdown, review-round 5 M32). A CAS-loser provisional-supervisor leak
mutation is intentionally NOT added: the orphan `SupervisorJob` is not
observable from the harness without a production test hook, so such a
mutation would be weak — the fix itself is asserted structurally by
property 9's ownership assertions.

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

## Lease lifecycle — #290 (Epic 8.2d)

The second lifecycle slice targets the durable lease ownership state machine
— NOT the worker renewal loop (delay/renew/conflict/retry is a separate
machine, explicitly out of scope). The slice proves the central lease
invariant:

> Exactly one lease generation is authoritative; once a token loses
> authority it can never regain it — even when the same owner returns, the
> old snapshot carries the same workflow identity, or the caller supplies
> newer-looking metadata.

### Model states

`WorkflowLeaseLifecycleModel(now, generation, current, predecessors, hasOlderSnapshot)` —
a pure oracle independent of store-generated UUIDs. Tokens are symbolic
(T1/T2/T3...); the harness binds each symbolic token to the real `leaseId`
returned by the store and asserts the actual IDs differ across generations.
`generation` counts successful claims; a takeover (claim over an expired
lease) increments it exactly once; renewal never increments it.
`ModeledLease(generation, symbolicToken, ownerId, checkpointRevision,
acquiredAtEpochMillis, expiresAtEpochMillis)`.

### Snapshot vs generation (explicit distinction)

An older metadata snapshot of the CURRENT token (same `leaseId`, stale
revision/expiry/acquiredAt) is still a legal capability — the store checks
only `leaseId` + `ownerId`. A predecessor token (different generation) is
permanently fenced. The model distinguishes these: renew/release via an old
snapshot of the current token succeeds; via any predecessor/wrong/forked
token it conflicts.

### Expiry boundary (pinned)

`expiresAt > now` → active; `expiresAt <= now` → expired (lazily removed).
Time is monotonic (no rewinding — unlike the approval slice, expiry
legitimately normalizes durable state).

### Generated corpus

32 seeds × 32 actions, deterministic (`Random(seed)`), state-aware picking
with a forced 22-step discriminator spine guaranteeing: fresh claim, active
competing claim, same-owner competing claim, renew current, multiple
renewals, release current, claim after release, before-expiry active,
exact-expiry transition, past-expiry transition, different-owner takeover,
same-owner takeover, stale predecessor renew, stale predecessor release, old
same-token snapshot renew, old same-token snapshot release, wrong owner
renew/release, forged token renew/release, checkpointRevision null→non-null
and non-null→null, and 3+ generations in one history. The coverage guard
(`WorkflowLeaseLifecycleActionGeneratorTest`) asserts the 23 categories are
semantically reached (not just present as enum constants) and that the same
seed yields the same trace.

### Properties (6 new shared cases, 51 → 57 × 3 implementations)

1. **Generated histories match the independent model** — every seed/step:
   outcome category (success/no-op/conflict), durable `currentLease` vs the
   model, fresh-token-per-generation assertion (actual `leaseId` not in the
   set of all prior bound IDs), conflict non-mutation, per-step invariants.
2. **Every predecessor stays fenced after multiple generations** — T1→T2→T3→T4;
   renew/release of T1/T2/T3 all conflict; T4 unchanged.
3. **Same-owner reincarnation is a new generation** — A/T1 → expiry → A/T2;
   `leaseId` differs; renew(T1)/release(T1) conflict; T2 current.
4. **Renewal does not create a new generation** — leaseId/owner/acquiredAt
   preserved across renew×3; only checkpointRevision and expiresAt change.
5. **Concurrent renew vs release has one legal serialization** (×20, start
   barrier): release succeeds, renew ∈ {success, conflict}, final ABSENT —
   never a post-release resurrection.
6. **Exact-expiry takeover vs old release cannot destroy the successor**
   (×20): at `now == expiresAt`, claim(new) vs release(old); both legal
   serializations end with current = T2.

### Fence-lineage properties (2 new shared cases, 14 → 16 × 3 implementations; 6 + 2 = 8 Epic 8.2d properties total)

7. **Fence authority follows token lineage** — T1→T2→T3; fence(T3) succeeds;
   fence(T1)/fence(T2) stale; failed predecessor fences leave the checkpoint
   unchanged.
8. **Exact-expiry takeover atomically invalidates the predecessor fence** —
   old expires at exact boundary, successor claims; old token can neither
   save nor delete a checkpoint (STALE, checkpoint unchanged); the successor
   is the only active fencing capability.

### Mutation evidence (21 mutations, each restored; 0 weak)

M1 active claim overwrites the current lease, M2 exact expiry treated as
still active, M3 takeover reuses the predecessor leaseId (covers both owner
kinds — the same-owner and different-owner paths share one code line in
InMemory), M5 renew generates a new token, M6 renew changes acquiredAt, M7
renew expiry based on old expiry instead of now, M8 renew trusts
caller-provided expiresAt, M9 renew trusts caller checkpointRevision as
durable source, M10 wrong-owner renew accepted, M11 wrong-token renew
accepted, M12 stale predecessor renew changes the successor, M13 wrong-owner
release accepted, M14 wrong-token release accepted, M15 stale predecessor
release deletes the successor, M16 release requires the latest metadata
snapshot rather than token capability, M17 renew-vs-release race resurrects
the lease (missing-row guard removed), M18 expired release removes a
concurrently installed successor (caller expiry trusted), M19 fence accepts
a predecessor token, M20 fence extends the lease expiry, M21 fence mutates
the durable lease revision, M22 JDBC claim reports conflict when the expired
predecessor row vanished concurrently (0-row insert revert). M4 is folded
into M3 (identical InMemory code line); every mutation made at least one NEW
Epic 8.2d property red and the property suite went green again after each
restore.

### Production change discovered

The generated-history property and the P6 concurrency property exposed a
genuine JDBC linearizability defect: `JdbcWorkflowLeaseStore.claim` could
lose a legitimate exact-expiry takeover. `claim` loaded the expired
predecessor row, then a concurrent no-op `release` of that already-expired
lease deleted the row before `replaceExpiredLease`'s conditional UPDATE —
the UPDATE matched 0 rows and claim reported CONFLICT even though the key
was now free. That outcome has no legal serialization (both serializations
end with the new lease installed). Fix: when the UPDATE affects 0 rows and
re-reading shows the key is gone, the claim legally wins by inserting the
new lease on the same connection (a concurrent claim racing the insert is
still detected via the active-lease re-check + SQLException path). The
InMemory and File stores never exhibited the race (both serialize the
check-then-act); the property runs against all three implementations and
only JDBC went red pre-fix.
## Outbox lifecycle — #291 (Epic 8.2e)

### Thesis

`EMITTING` is a state. A dispatch attempt is an authority generation. They are not the same thing.
The target invariant: **one durable outbox state, one authoritative dispatch generation — a
successful claim increments the generation, and once an attempt is superseded it can never resolve
a later attempt** (including same-worker reincarnation; `claimedBy` cannot fence, `attemptCount`
can).

### The defect the slice exposed

The terminal mutation API took only `expectedStatus`, so a stale dispatcher from attempt-1 could
complete attempt-2: both generations are `EMITTING`, and the store could not distinguish the
caller's authority. P0 discriminator (stale attempt-1 `markEmitted`/`markFailed` against
attempt-2) was RED on all three implementations (56 tests, 1 failed — the existing 55 stayed
green).

### Production changes (3, deliberate)

1. **Attempt-count fencing (public SPI change).** `markEmitted`/`markFailed` gained a required
   `expectedAttemptCount: Int` — the optimistic dispatch-generation fence, monotonic and durable
   across all implementations. `PREPARED → FAILED_PERMANENT` uses generation 0. A mismatch throws
   `tramai-sovereign-ops-outbox-concurrent-update`. `api/` dump regenerated.
2. **JDBC claim linearization.** `claimPending` keeps `SKIP LOCKED` as the non-blocking fast path;
   when it selects zero rows, a non-locking candidate probe proves whether any claimable row exists,
   and if one does, the claim blocks on that candidate by primary key (`SELECT … FOR UPDATE`, no
   SKIP LOCKED) and re-reads it under the acquired lock. The blocking wait is only ever entered
   while this transaction holds zero outbox row locks, so concurrent claimants cannot deadlock (a
   blocking multi-row `FOR UPDATE` recheck variant deadlocked the pool-claim race and was rejected;
   the earlier bounded `SKIP LOCKED` re-polling was rejected because observation-count heuristics
   cannot establish linearizability — a hidden eligible row must be serialized against or proven
   ineligible, never polled away). Covered by a deterministic gated-codec regression: a `markFailed`
   parked between row lock and commit must not make a concurrent reclaim of the expired row report
   empty.
3. **Dispatcher companion.** The dispatcher passes `expectedAttemptCount = record.attemptCount`
   (the generation it owns) on both `markEmitted` and `markFailed`, so a stale completion can no
   longer be converted into a retryable failure against the newer attempt; it propagates as claim
   loss.

### Model

`SovereignOpsAuditOutboxLifecycleModel` — pure oracle (no stores, no coroutines, deterministic):
`current: ModeledOutbox?` + `predecessorClaims: List<ModeledClaim>`; tracks status, attemptCount,
lastErrorCode, claimedBy/claimedAt/claimExpiresAt, emittedAt; static audit fields held once and
asserted immutable. Expiry boundary deliberately OPPOSITE to the workflow-lease contract:
`claimExpiresAt < now` → reclaimable, `== now` → NOT reclaimable.

### Action alphabet

`AppendPrepared`, `MarkReady`, `MarkPreparedPermanentFailure`, `ClaimWorkerA/B`,
`MarkEmittedCurrent`, `MarkRetryableFailureCurrent`, `MarkPermanentFailureCurrent`,
`MarkEmittedStaleAttempt`, `MarkRetryableFailureStaleAttempt`, `MarkPermanentFailureStaleAttempt`,
`AdvanceBeforeClaimExpiry`, `AdvanceToExactClaimExpiry`, `AdvancePastClaimExpiry`, `ObserveCurrent`.
No separate RetryClaim/ExpiredReclaim — both are ordinary `Claim(worker)` whose legality depends on
model state (PENDING/FAILED_RETRYABLE/expired EMITTING claimable; fresh EMITTING/terminal not).

### Generated corpus

32 seeds × 32 actions × 3 implementations = 3,072 model-checked lifecycle actions. 8 seed lanes
force every discriminator (fresh PREPARED, PREPARED→PENDING, PREPARED→FAILED_PERMANENT, PENDING
first claim, EMITTING→EMITTED/FAILED_RETRYABLE/FAILED_PERMANENT, FAILED_RETRYABLE re-claim,
before/exact/past-expiry reclaim, different-worker + same-worker reclaim, attempts 1/2/3+,
retry clears lastErrorCode, stale predecessor emit/retryable/permanent, terminal emit/failure/
claim rejection, EMITTED + FAILED_PERMANENT absorbing) then state-aware random picking.
Coverage guard: 25 semantic pre-state categories + determinism.

### Properties (9 new shared cases, 55 → 64 × 3 implementations)

P0. **Stale attempt-1 completion cannot mutate attempt-2** — the P0 discriminator, now GREEN.
P1. **Generated lifecycle histories match the independent model** — 32×32×3; after every action
    compare outcome class, durable status, attemptCount, claim fields, failure/emission metadata;
    immutable audit fields; failed mutations leave the record value-identical.
P2. **Attempt generation advances only on successful claim** — PENDING→claim=1→retryable
    failure→claim=2→expiry→reclaim=3; attemptCount == successful claims, never on failure/
    emission/readiness/rejected claim/observations.
P3. **Same-worker reclaim is still a new authority generation** — worker-A/1 → expiry →
    worker-A/2; attempt 1 stale (outbox analogue of the same-owner lease reincarnation from #290).
P4. **Every predecessor attempt stays fenced** — 1→2→3; attempts 1/2 completion or failure never
    affects 3.
P5. **Exact claim-expiry boundary stays authoritative** — `now < expiry` no reclaim,
    `== expiry` no reclaim, `> expiry` reclaim (opposite of lease exact-expiry semantics).
P6. **Concurrent current-attempt completion vs failure linearizes** (×12, retryable +
    permanent): exactly one winner; final EMITTED or FAILED_RETRYABLE/FAILED_PERMANENT with the
    winner's metadata only.
P7. **Expired reclaim vs old completion cannot destroy the successor** (×20): old completion
    first → EMITTED → reclaim sees terminal → no claim; reclaim first → EMITTING attempt-2 →
    old completion rejected stale. Illegal: attempt-2 installed then marked EMITTED by attempt-1.
P8. **Expired reclaim vs old failure cannot demote the successor** (×20): final EMITTING
    attempt-2 in both serializations; never FAILED_RETRYABLE carrying attempt-2 metadata.
Plus the dispatcher discriminator: `dispatcher stale completion cannot demote the successor
attempt` — a stale completion propagates as claim loss; the successor record is untouched.

### Per-step invariants

attemptCount monotonic, +1 exactly per successful claim; PREPARED/PENDING attemptCount 0;
EMITTING attemptCount ≥ 1 with non-null claimedBy/claimedAt/claimExpiresAt; claimExpiresAt >
claimedAt; successful claim sets claimedAt = now, claimExpiresAt = now + duration, clears
lastErrorCode; retryable/permanent failure sets code + target status; EMITTED carries exact
emittedAt, null before; terminal never reopens; current generation never decreases; same worker
can own different generations; stale generation never authoritative again, cannot complete or fail
successor; failed mutation leaves durable state value-identical; audit identity/payload immutable.

### Mutation evidence (19 executed mutations, each restored; 0 weak)

Candidate IDs M15/M16/M20/M21 were folded into equivalent production-line mutations
(M13/M14 — identical InMemory code lines) rather than run as independent candidates.

M1 terminal status becomes claimable, M2 PREPARED becomes claimable, M3 FAILED_RETRYABLE cannot
re-claim, M4 fresh EMITTING can re-claim, M5 exact expiry becomes reclaimable, M6 past expiry
remains non-reclaimable, M7 attemptCount does not increment, M8 reclaim resets attemptCount to 1,
M9 claim keeps old claimedBy, M10 claim keeps old claimedAt, M11 claim expiry based on previous
expiry, M12 claim keeps lastErrorCode, M13 stale attempt may markEmitted (covers M20
reclaim-vs-old-completion — identical InMemory code line), M14 stale attempt may mark failure
(covers M15/M16/M21 — identical line: same-worker stale and reclaim-vs-old-failure demotion),
M17 EMITTED can be modified, M18 FAILED_PERMANENT can be modified, M19 completion-vs-failure
allows two winners (CAS enforcement removed), M22 dispatcher converts stale completion into a
successor failure (expectedAttemptCount + 1), M23 JDBC blocking serialization fallback removed
(first-pass only — the gated-codec regression must go red).
Every mutation made at least one NEW Epic 8.2e property red; suite green again after each restore.

### Files

- `tramai-testing/src/testFixtures/.../persistence/outbox/SovereignOpsAuditOutboxLifecycleModel.kt`
- `tramai-testing/src/testFixtures/.../persistence/outbox/SovereignOpsAuditOutboxLifecycleActionGenerator.kt`
- `tramai-testing/src/test/.../persistence/outbox/SovereignOpsAuditOutboxLifecycleActionGeneratorTest.kt`
- `SovereignOpsAuditOutboxStoreTck` (P0–P8; 55 → 64 shared cases)
- `SovereignOpsAuditOutboxStore` + `InMemory`/`File`/`Jdbc` stores + `SovereignOpsAuditOutboxDispatcher`
  + `DefaultSovereignOpsAuditOutboxOperations` + `api/` dump

## Workflow checkpoint/resume lifecycle — #295 (Epic 8.2f)

**One checkpoint capability = one incarnation + one revision. Deletion permanently kills the incarnation; revision reuse after recreation can never revive stale authority. Recovery-required checkpoints are non-runnable through every execution entry point, including direct resume.**

### Proven defects (P0 discriminators, RED ×4 stores before the fix)

- **P0-A (ABA).** Delete incarnation G1 @ r1, recreate successor @ r1. A stale G1 capability (loaded before the delete) saved/deleted/requireRecovered/cleared against the successor. Pre-fix, revision-only fencing could not distinguish G1/r1 from G2/r1 — the stale save **mutated the recreated successor**.
- **P0-B (Required bypass).** `workflow.resume()` on a checkpoint in `WorkflowRecoveryState.Required` executed it directly, skipping operator resolution.

### Production changes (3)

1. **Store-owned incarnation token.** `WorkflowCheckpoint.checkpointGeneration: String?` is minted by the store (`newCheckpointGeneration()`), never accepted from callers. Authority becomes identity + generation + revision: `save` carries the generation inside the checkpoint; `delete`/`requireRecovery`/`clearRecovery` gained `expectedGeneration`. Legacy pre-8.2f records (generation absent/null) remain **readable**; the first legitimate fenced mutation installs the token (migration). JDBC adds `checkpoint_generation TEXT NULL` + `checkpointGenerationMigrationSql()` ALTER; File/Markdown persist a `checkpointGeneration` key.
2. **Resume fails closed on Required.** `WorkflowRunner.resume()` throws `WorkflowRecoveryStateException` before decode/execution for a Required checkpoint. The session carries + updates the generation from persisted state; the completion-delete is generation-fenced.
3. **Recovery-controller operator actions are generation-fenced.** `WorkflowRecoveryController.retryStep` (both overloads) and `failWorkflow` now take `expectedGeneration` alongside `expectedRevision`; `loadRequiredCheckpoint` validates both **before** any approval-evidence write, clear, or delete. A stale operator command authorized against G1/r2 must never act on a recreated G2/r2 — the controller does not adopt the current checkpoint's generation on behalf of a caller that only possesses an old revision. Discriminated by `stale failWorkflow authorized against G1 cannot delete recreated same-revision G2` and `stale retryStep authorized against G1 cannot approve or clear recreated same-revision G2` (both assert G2 remains value-identical; the retry variant additionally asserts no approval evidence was written).

### Evidence categories

| Category | Count |
|---|---|
| Store compatibility | **51 shared cases × 4 implementations** (InMemory/File/Markdown/JDBC) |
| Lifecycle properties | **P0–P6** (7 properties in TCK section E) |
| Resume lifecycle | **R1–R6** (6 tests, `WorkflowCheckpointResumeDiscriminatorTest`) |
| Legacy migration | **5 cases × 3 persistent implementations** (`WorkflowCheckpointLegacyMigrationContractTest`) |
| Generated histories | **32 seeds × 32 actions × 4 stores = 4,096 model-checked actions** |
| Mutation evidence | **25/25 STRONG, 0 WEAK** |

**TCK accounting (42 → 51):** 42 existing shared cases + 7 lifecycle properties (P0–P6, section E) + 2 generation/migration contract cases (`caller-supplied generation on create is ignored and the store token is authoritative`, `every recreate mints a genuinely distinct generation`) = **51**.

### Discriminator-suite lesson (M21)

M21 (`lease-fence drops generation propagation` in `WorkflowLease`) was initially classified WEAK because the discriminator set contained only the checkpoint TCK. It was **not** weak — the base checkpoint TCK asks "does the store obey the store contract?", not "does the lease/checkpoint adapter propagate the capability?". A mutation in a composition/fencing adapter is not adequately classified by the base store TCK alone.

**Rule for future campaigns:** the discriminator set must include every contract runner that owns the affected semantic boundary:
- checkpoint store mutation → checkpoint TCK
- lease/checkpoint composition mutation → checkpoint TCK + lease-fence TCK
- resume/session mutation → resume lifecycle suite

With `WorkflowLeaseCheckpointFenceTck` runners added, M21 went STRONG (7 red cases in the File fence runner).

### Mutation highlights

- **M05/M06** (drop generation fence in File save/delete) → all four stores' ABA path red.
- **M13** (JDBC update drops generation predicate) → 23 TCK red cases; the generation predicate is load-bearing for cross-store authority.
- **M21** (lease-fence generation propagation) → fence TCK red; exposed the discriminator-suite gap above.
- **M25** (`WorkflowRecoveryController.loadRequiredCheckpoint` drops the generation predicate) → both stale-operator ABA discriminators red (`Expecting code to raise a throwable` — the stale command succeeded and acted on the recreated successor). Proves the operator-action boundary cannot adopt the current generation on a caller's behalf.

### Files

- `tramai-testing/src/testFixtures/.../persistence/checkpoint/WorkflowCheckpointStoreTck.kt` (section E; 42 → 51 shared cases)
- `tramai-testing/src/testFixtures/.../persistence/checkpoint/WorkflowCheckpointLifecycleModel.kt` + `WorkflowCheckpointLifecycleActionGenerator.kt` + `WorkflowCheckpointLifecycleActionGeneratorTest.kt`
- `tramai-orchestration/src/test/.../WorkflowCheckpointResumeDiscriminatorTest.kt` (R1–R6)
- `tramai-orchestration/src/test/.../WorkflowCheckpointLegacyMigrationContractTest.kt` (5 cases × 3 stores)
- `tramai-orchestration/src/test/.../WorkflowRecoveryContractTest.kt` (stale-operator G1-vs-G2 ABA discriminators for `failWorkflow` + `retryStep`)
- Production: `WorkflowPersistence.kt`, `WorkflowRunner.kt`, `WorkflowPersistenceSession.kt`, `WorkflowLease.kt`, `FileWorkflowCheckpointStore.kt`, `MarkdownWorkflowCheckpointStore.kt`, `JdbcWorkflowCheckpointStore.kt`, `WorkflowRecoveryController.kt` + `api/` dump

---

## Circuit breaker lifecycle — #302 (Epic 8.2g)

**Status: ✅ complete.** The provider circuit breaker previously tracked state but
not the ownership of an admitted attempt: `beforeCall` returned only a deadline,
`onSuccess(providerId)`/`onFailure(providerId, error)` reconstructed breaker
identity from the provider name, and OPEN expiry cleared the state so every
competing caller was admitted. Five P0 discriminators proved the resulting
defects RED, then the production lifecycle was redesigned around admission
ownership + epoch-safe completion.

### Architecture invariant

> A provider attempt is allowed to mutate circuit-breaker state only while the
> admission permit that authorized it remains valid for the authoritative
> breaker generation.

```text
CLOSED(generation=N, failures=f)
  │ beforeCall → Allowed(permit N)
  │ qualifying failure, f+1 ≥ threshold
  ▼
OPEN(generation=N, blockedUntil=T)
  │ exact expiry, exactly ONE atomic caller
  ▼
HALF_OPEN(generation=N, probe permit)
  ├── probe success ─────────────► CLOSED(N, 0)
  ├── probe qualifying failure ───► OPEN(N+1, now+openDuration)   (fresh deadline, no threshold)
  └── probe neutral/abandoned ────► OPEN(N+1, now+openDuration)   (fresh deadline, no event)
```

- `beforeCall(providerId): CircuitBreakerAdmission` — `Allowed(permit)` /
  `Rejected(blockedUntil)`; permit = `(providerId, generation)`.
- Completion APIs consume the **admission permit**, not merely the providerId;
  a stale generation is rejected **before** state-specific handling.
- OPEN **cannot own a completion permit** — no permit is ever minted for an OPEN
  epoch, so a valid execution can never reach `completion(Open, matchingPermit)`.
- HALF_OPEN is real state (not "OPEN removed and one lucky caller runs"):
  exactly one probe permit exists; competing callers are rejected until the
  probe resolves. **Every terminal outcome of the probe is a breaker
  transition** — success closes; qualifying failure reopens; neutral
  abandonment reopens with a fresh deadline and an advanced generation.
- **Neutral/abandoned probe outcomes** (caller cancellation, DLP/sanitizer
  failure, policy/model-registry rejection, non-retryable provider error,
  token-budget exhaustion) never count as breaker failures and emit no
  `CIRCUIT_OPENED` event. Re-entering OPEN after an abandoned probe is a
  **recovery-state transition, not a breaker-trip event** — OPEN is used as
  the recovery-delay state after an inconclusive probe. The generation
  advances so the abandoned permit can never regain authority; terminal
  cleanup is idempotent with respect to an already-consumed/stale permit
  (a second completion from the same permit is a generation-mismatch no-op).
- Generation advances on every entry into OPEN (CLOSED→OPEN and HALF_OPEN→OPEN).
- Qualifying failures: `TimeoutException` + retryable `ProviderException` only.
- Disabled breaker is transparent: every `beforeCall` Allowed, completions no-op.
- **Sync/streaming parity:** both execution paths thread the admission permit
  from `beforeCall` into `onSuccess`/`onFailure`; a success path never returns
  before breaker completion is recorded, and streaming does not perform a
  second admission at stream completion.

### P0 discriminators (5/5 RED → 5/5 GREEN)

| # | Defect (baseline symptom) | RED evidence |
|---|---|---|
| P0-A | Sync success bypasses `onSuccess` (breaker mis-tripped) | `CircuitBreakerOpenException` on call 4 |
| P0-B | OPEN expiry deletes state, admitting everyone (stampede) | `expected: 1 but was: 8` |
| P0-C | Old success can close a newer OPEN | `Expecting actual not to be null` |
| P0-D | Old failure can extend a newer OPEN | `expected: 100L but was: 110L` |
| P0-E | Expiry loses history instead of controlled probe | `Expecting actual not to be null` |

### Property suite (P1–P13)

Pure `ProviderCircuitBreakerModel` oracle (Closed/Open/HalfOpen + generation +
live permits) mirroring the contract above; 32 seeds × 32 actions with forced
archetypes (threshold OPEN, expiry→HALF_OPEN, probe success/failure,
abandoned probe, stale completion after recovery, concurrent-expiry clusters,
mixed qualifying). Every action is applied to model and real breaker and
compared after each step.

- P1 model/reality equivalence across the corpus
- P2 live permits never carry a newer generation; stale completions are no-ops
- P3 OPEN expiry admits at most one HALF_OPEN probe per instant
- P4 stale completions never mutate breaker state
- P5 qualifying failures open exactly at threshold; `true` exactly once
- P6 non-qualifying failures never count, never open, never return true
- P7 probe success closes (generation preserved); probe failure reopens (fresh deadline)
- P8 rejected callers receive blockedUntil ≥ admission time (OPEN / probe in flight)
- P9 `openUntilMillis` is expiry-aware (null when expired)
- P10 stale success/failure after recovery cannot disturb CLOSED
- P11 generation strictly increases on every OPEN entry
- P12 disabled breaker transparency
- P13 every HALF_OPEN probe reaches a terminal breaker transition; an
  abandoned probe reopens with an ADVANCED generation and the abandoned
  permit is fenced (stale success/failure after replacement recovery is a no-op)

### Concurrency discriminators (C1–C4) + secondary regressions (H1–H17, incl. H1b)

- C1 atomic expiry: exactly one probe under 16 concurrent callers
- C2 concurrent stale completions cannot mutate the open deadline/state
- C3 concurrent probe + competitors reopen exactly once on probe failure
- C4 generation strictly increases across rapid cycles; stale permits ignored
- H1 HALF_OPEN concurrent success admits one probe and closes
- H1b stale pre-OPEN success cannot close an in-flight HALF_OPEN probe
- H2 stale success after recovery cannot disturb CLOSED
- H3 stale failure after recovery cannot reopen (threshold 1)
- H4 sync coordinator and breaker lifecycle agree on OPEN then CLOSE
- H5 sync coordinator HALF_OPEN probe failure reopens with fresh deadline (qualifying trip emits exactly one CIRCUIT_OPENED; abandonment emits none)
- H6 streaming success must reach `onSuccess` (next call admitted)
- H7 streaming HALF_OPEN probe failure reopens; next expiry admits again (event-count discriminates trip vs abandonment)
- H8 neutral HALF_OPEN failure cannot strand the circuit
- H9 abandoned HALF_OPEN probe is released; a replacement probe is eventually admitted
- H10 abandoned probe is fenced after replacement recovery begins (stale permit cannot close/reopen/reset)
- H11 streaming neutral probe outcome cannot strand recovery
- H12 sync coordinator DLP-neutral HALF_OPEN probe cannot strand recovery
- H13 streaming token-budget exhaustion on the probe cannot strand recovery
- H14 sync pre-route policy failure cannot strand the HALF_OPEN probe (structural scope guard)
- H15 sync pre-try interceptor escape cannot strand the HALF_OPEN probe (structural scope guard)
- H16 streaming pre-try observer escape cannot strand the HALF_OPEN probe (structural scope guard)
- H17 scope-abandon fenced permit cannot disturb the replacement epoch

### Structural permit relinquishment (round-2 P1, sync + streaming)

Permit ownership is enforced at the admission boundary, not at individual
throw sites: both coordinators wrap the entire admitted route in
`finally { circuitBreaker.onAbandoned(permit) }`. Admission creates an
obligation; scope exit always discharges it. The guard is idempotent by
construction — success leaves CLOSED (same generation → no-op), qualifying
and neutral failures advance the generation (stale permit → no-op), and only
an unrecorded neutral escape (pre-route policy/cancellation, pre-try
observer/interceptor failure, cancellation during the retry delay) releases
the probe. M30/M31 remove the sync/streaming guard and are killed by
H14/H15/H17 and H16 respectively.

### Mutation evidence (31 candidates, reachable set re-run in full on the structural-guard head)

| Classification | Count |
|---|---|
| Total candidate mutations | 31 |
| **Reachable, non-redundant, compile-valid** | **26** |
| STRONG (killed by an 8.2g test) | **26 / 26** |
| Unreachable by contract | 1 |
| Invalid (compile-breaking) | 1 |
| Redundant (corroborating) | 3 |
| Reachable WEAK | **0** |

Breakdown: the original campaign produced 24 candidates (21 STRONG + M03
unreachable + M04 invalid + M15 redundant); the P1 round added five
abandonment candidates M25–M29; the structural scope-guard round added
M30 (remove the sync admitted-scope guard) and M31 (remove the streaming
admitted-scope guard) → 31 total. The 26 reachable, non-redundant,
compile-valid mutations were re-executed in full against the
structural-guard implementation — all 26 killed, 0 weak. The re-run itself
found two masked mutations: M17 (sync) and M24 (streaming) fresh-permit
adoption survived because the `onAbandoned` fallback still released the
probe — the remaining observable is the CIRCUIT_OPENED event, and H5/H7 were
strengthened with event-count assertions so a qualifying trip and a neutral
abandonment are distinguishable. M30 kills H14/H15/H17, M31 kills H16.
M28/M29 (removing the inner sync DLP / streaming budget abandonment calls)
are REDUNDANT after the structural guard: the admitted-scope `finally`
subsumes them, so the mutation changes no observable behavior. Each STRONG
result carries strict XML evidence (failures=1, XML present — a
compile-error candidate is classified INVALID, never STRONG).

Every reachable, non-redundant, compile-valid mutation was killed by an 8.2g
discriminator/property/regression test. **Zero reachable weak mutations.**

Seven mutations survived the post-GREEN suite and exposed real oracle gaps;
each was closed by a new discriminator rather than rationalized:

| Mutation | Gap exposed | Fix |
|---|---|---|
| M01 (drop onSuccess generation check) | P0-C absorbed stale completions at OPEN; HALF_OPEN authority unpinned | H1b |
| M17 (sync adopts fresh permit) | sync probe-FAILURE path unpinned (H4 covered success only) | H5 (+ event count) |
| M23 (drop streaming onSuccess) | streaming recovery unpinned (harness only tested open-skip) | H6 |
| M24 (streaming adopts fresh permit) | streaming probe-failure path unpinned | H7 (+ event count) |
| M25 (drop neutral HALF_OPEN release in onFailure) | neutral probe could strand forever | H8 |
| M28 (drop sync DLP abandonment plumbing) | sync neutral path bypasses onFailure | H12 |
| M29 (drop streaming budget abandonment plumbing) | streaming neutral path bypasses onFailure | H13 |

The M03/M04 Open-branch mutations are unreachable by contract (no permit is
ever minted for an OPEN epoch and generation validation precedes
state-specific handling; the abandonment vocabulary consumes permits, never
mints them), so they are excluded from the strength denominator — adding test
seams to execute an impossible state would weaken the architecture.

### Files

- `tramai-engine/src/main/kotlin/dev/tramai/engine/ProviderCircuitBreaker.kt` (new)
- `tramai-engine/src/main/kotlin/dev/tramai/engine/TramaiEngine.kt` (inline breaker removed)
- `tramai-engine/src/main/kotlin/dev/tramai/engine/provider/ProviderAttemptExecutor.kt` (permit-threaded sync)
- `tramai-engine/src/main/kotlin/dev/tramai/engine/provider/ProviderExecutionCoordinator.kt` (admission-based)
- `tramai-engine/src/main/kotlin/dev/tramai/engine/streaming/StreamingExecutionCoordinator.kt` (permit-threaded streaming)
- `tramai-engine/src/test/.../provider/ProviderCircuitBreakerLifecycleDiscriminatorTest.kt` (P0, RED commit `cc1fc065`)
- `tramai-engine/src/test/.../provider/ProviderCircuitBreakerModel.kt` + `ProviderCircuitBreakerActionGenerator.kt`
- `tramai-engine/src/test/.../provider/ProviderCircuitBreakerLifecyclePropertyTest.kt` (P1–P13)
- `tramai-engine/src/test/.../provider/ProviderCircuitBreakerSecondaryRegressionTest.kt` (H1–H17 incl. H1b, C1–C4)
- `tramai-engine/src/test/.../streaming/StreamingExecutionCoordinatorTest.kt` (H6, H7, H11, H13, H16)

## Epic 8.2h — Provider retry/fallback lifecycle (PR pending)

**Central invariant.** Every provider attempt has exactly one authoritative disposition. Retry remains within the currently admitted route; fallback can occur only after that route exhausts its retry authority; a breaker permit is owned by the route, not by each physical retry attempt; and once streaming output becomes externally visible, retry and fallback authority are permanently lost.

**Three ownership levels:**

```
Invocation
  └─ Route
       └─ Attempt

ATTEMPT      → may request same-route retry
ROUTE        → owns one circuit-breaker permit
             → terminal route outcome completes that permit
             → may advance through fallback
INVOCATION   → owns the final result
```

One breaker admission → route (physical attempt, retry, retry, …) → one semantic breaker completion. **"Each retry is a circuit-breaker attempt" is false under this architecture.** Sync and streaming share retry semantics but are not mechanically identical: streaming legitimately adds the irreversible `OUTPUT_VISIBLE` state.

### P0-A: the production defect (RED → GREEN)

Streaming ignored `@Operation.providerRetries`. Before: a primary retryable failure fell back immediately. After: it retries the same provider within budget, and falls back only after exhaustion. RED baseline commit: `6edfc9cc`; fixed by `349f12b6`.

Remaining discriminators, grouped:

- **Retry authority** — P0-A/B/C/N: budget = `N + 1` physical attempts; retry stays on the same route; retry delay contract (`retryAfterMillis` honored, backoff for timeouts, cap applied, suspension real).
- **Streaming irreversibility** — P0-D/M: after the first token, retryable failure is terminal; `STREAMING_STARTUP_RETRY` is a recovery-eligible marker only.
- **Cancellation/policy** — P0-E/G: cancellation is absolute control flow (no retry/fallback classification); fallback-gate denial is fail-closed with the deny error authoritative.
- **Routing** — P0-F/H/I/J/L: circuit-open consumes zero attempts, advances exactly once; explicit provider resolves to route cardinality one; effective-fallback model is resolution-owned; global attempt numbering stays continuous; last executed provider failure beats circuit-open in terminal error precedence.
- **Circuit-breaker composition** — P0-K: intermediate retries never trip the breaker; only the terminal route outcome completes breaker authority; retry → permanent is a NEUTRAL completion.

### Model vocabulary

Pure `ProviderRetryFallbackModel` (test-only oracle) with frozen vocabulary:

- `AttemptOutcome × OutputVisibility` — two independent algebras. The failure identity is the same before and after a token; disposition changes solely because output became visible. `OutputVisibility`: `NONE → VISIBLE`, irreversible.
- `RouteAdmission` — circuit-open is route **admission** (zero attempts, no permit), not a provider-attempt failure.
- `RouteDisposition` — `RetrySameRoute(nextRetryIndex) / Fallback(nextRouteIndex) / Succeeded(routeIndex) / Failed(failure)`: the single authoritative per-attempt decision.
- `TerminalOutcome` — `Success / Cancelled / Failure(kind) / FallbackDenied(kind)`. `FallbackDenied` is distinct from attempt-time `PolicyRejection`.
- `BreakerDisposition` — `SUCCESS / QUALIFYING_FAILURE / NEUTRAL`. Describes the semantic completion of one admitted route, **not** the raw number of `onSuccess`/`onFailure`/`onAbandoned` calls (idempotent belt-and-suspenders cleanup can double-invoke).

### Property evidence

P1–P14 independent model-vs-reality oracle. Reality corpus: 32 seeds × retry budgets {0, 1, 2} = **96 coordinator executions**. Semantic coverage guard: 32 seeds × 3 budgets × route counts {1, 2, 3} = **288 model scripts**.

The generated script is authoritative. Model and production independently consume the same declared route/admission/outcome sequence; model output is never used to configure production inputs. (Review correction: the harness originally derived reality's response queues from the model's routing trace, coupling the oracle to the implementation it judges; the script now declares every route explicitly and `runModel` `require()`s each action against the model's own decisions.)

### Mutation evidence

22 mutation candidates: **19 STRONG, 3 REDUNDANT, 0 UNREACHABLE, 0 WEAK, 0 INVALID**. Exactly-one-replacement; probe = property suite + streaming coordinator suite.

- **M15/M16** — redundant because the structural admitted-route `finally { onAbandoned(permit) }` subsumes the elided inner cleanup.
- **M21** — redundant under the mutation probe configuration because `jitterRatio = 0.0`; jitter is not claimed behaviorally irrelevant in production.
- **Taxonomy correction.** M14/M22 were initially misclassified as unreachable because the probe did not observe retry delay semantics; they were actually reachable WEAK mutations. P0-N added coordinator-level delay/source/suspension observability (`RETRY_SCHEDULED` attributes + a lower-bound suspension measurement), after which both became STRONG.

### Event semantics

- `tramai.retry.scheduled` — the actual same-route retry decision.
- `tramai.streaming.startup_retry` — once-per-route marker that a retryable pre-token streaming failure entered an available recovery path. **Observational**, not the authority that decides retry. P0-M pins the boundary: `providerRetries = 0` + no fallback → no event; recovery actually possible → exactly one event for the route.
