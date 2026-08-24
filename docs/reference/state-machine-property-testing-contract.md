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
