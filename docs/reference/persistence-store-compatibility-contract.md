# Persistence Store Compatibility Contract

Epic 8.1 (PR #267 ApprovalStore slice, PR #269 ApprovalContinuationStore
slice). Shared behavioral TCKs prove every implementation of a store family
satisfies exactly the same externally observable contract, regardless of
storage technology (memory, encrypted files, JDBC).

## Epic 8.1 matrix

| Store family | In-memory | File | JDBC | Shared TCK |
|---|---|---|---|---|
| Approval | ✅ | ✅ | ✅ | ✅ #267 |
| Approval continuation | ✅ | ✅ | ✅ | ✅ #269 |
| Suspended invocation | … | … | … | ⏳ |
| Audit | … | … | … | ⏳ |
| Audit outbox | … | … | … | ⏳ |
| Workflow checkpoint | … | … | … | ⏳ |
| Workflow lease | … | … | … | ⏳ |
| Step attempt | ✅ | ✅ | ✅ | ✅ #218 |
| Memory | … | … | … | ⏳ |

## ApprovalStore TCK (PR #267)

`ApprovalStoreTck` (tramai-testing testFixtures) runs **37 shared behavioral
cases** against every `ApprovalStore` implementation:

- **Creation/read (10):** PENDING round-trip, missing-ID null, duplicate
  conflict, non-zero version rejected, non-PENDING rejected, pre-populated
  decision/consumption fields rejected, blank ID rejected, future
  requestedAt rejected, invalid expiry rejected.
- **Transitions (10):** APPROVED/DENIED with decided fields + version 1,
  expired→TIMED_OUT, timeout-before-expiry rejected, approve/deny-after-
  expiry rejected, terminal-state re-transition rejected for every terminal
  status (APPROVED, DENIED, TIMED_OUT), stale version conflict,
  missing-approval not-found, version increments exactly once.
- **Consumption/replay (15):** fresh consumption (replayed=false, consumed
  fields, version 2), wrong token rejected, wrong consumedBy on replay
  rejected, stale version rejected, PENDING/DENIED/TIMED_OUT not consumable,
  expired unconsumed not consumable, exact replay returns the same durable
  record with no write, replay valid after expiry, replay with stale
  expected version rejected (Conflict), wrong token rejected on the replay
  path, consume on missing approval not-found, fresh consumption records the
  advanced clock instant, rejected consumption is non-mutating.
- **Concurrency (2):** concurrent transition race — exactly one wins, one
  conflict, version 1; concurrent identical consumption — one fresh + one
  replay receipt referencing the same durable record, version 2, identical
  consumedAt. Both cases run on parallel workers (`Dispatchers.Default`) with
  a start barrier, so the operations genuinely overlap instead of
  serializing on the test event loop; a non-atomic check-then-act store
  fails them.

### Typed failure taxonomy (pinned, not just "some RuntimeException")

| Failure | Exception |
|---|---|
| duplicate / stale version | `ApprovalStoreConflictException` |
| missing approval | `ApprovalStoreNotFoundException` |
| wrong token | `ApprovalStoreTokenRejectedException` |
| not consumable (status/expiry/already consumed) | `ApprovalStoreNotConsumableException` |
| illegal transition | `IllegalApprovalTransitionException` |
| invalid input | `IllegalArgumentException` |

### Deterministic time

The TCK owns a `MutableClock` fixed at T0; expiry is T0 + 10 min; tests
advance time via `clock.advance(...)`. No `Thread.sleep`, no `Instant.now()`.

### Harness

`ApprovalStoreTckHarness.createStore(clock)` returns a fresh store wired to
the given clock. Runners own storage technology (temp dirs, encryption keys,
datasources, schema), so technology never contaminates the contract — and
each case runs against isolated storage: the file runner provisions a unique
child directory per case, the JDBC runner resets the table per case.

### Enrollment guard

`ApprovalStoreTckEnrollmentArchitectureTest` scans every module's main
source set for concrete `ApprovalStore` implementations and requires a
`<Store>TckTest` runner in the same module that actually extends
`ApprovalStoreTck` — a same-named file with an unrelated class does not
count. The scanner recognizes class/object declarations with or without
bodies, single-line or multiline, and with or without visibility/`open`
modifiers (including delegated body-less declarations such as
`class X : ApprovalStore by delegate`); it is a source-shape scanner, not a
type resolver. A future `RedisApprovalStore` cannot merge without
`RedisApprovalStoreTckTest` extending the TCK — the phrase "future stores
must pass the TCK" is architecture, not documentation.

### Mutation evidence (all restored after each run)

| Mutation | TCK outcome |
|---|---|
| Remove expectedVersion check in transition | RED — stale-version cases fail |
| Check-then-act consume without atomicity (read version, yield, write) | RED — concurrent-race cases detect two winners / a conflict instead of one fresh + one replay |
| Allow terminal APPROVED to transition again | RED — terminal re-transition case fails |
| Increment version during exact replay | RED — replay-identity + concurrent-consumption cases fail |
| Replace consumedAt during exact replay | RED — replay-after-expiry case fails |
| Allow expired fresh consumption | RED — expired-unconsumed case fails |
| Skip version check on the replay branch | RED — replay-with-stale-version case fails |
| Skip token check on the replay branch | RED — wrong-token-on-replay case fails |

### Scope

The TCK owns SPI semantics; implementation-specific suites continue owning
encryption, permissions, corruption, record format (file), SQL schema, JSON
mapping, connection cleanup (JDBC). No existing tests were deleted. Zero
public API change; no persisted format or schema changes.

---

## ApprovalContinuationStore TCK (PR #269)

`ApprovalContinuationStoreTck` (tramai-testing testFixtures) runs **50 shared
behavioral cases** against every `ApprovalContinuationStore` implementation.
It pins the state machine (PENDING → CLAIMED → COMPLETED, → EXPIRED,
→ CANCELLED, CLAIMED → CANCELLED_UNCERTAIN), strict optimistic concurrency,
and the exactly-once release of raw sensitive arguments — the only API path
that exposes them.

- **Creation/read (13):** PENDING round-trip, missing-ID null, duplicate
  conflict, non-zero version / non-PENDING / pre-populated claimed /
  completion / recovery fields rejected, blank ID rejected, future createdAt
  rejected, non-future expiry rejected, TTL bound enforced, arguments digest
  mismatch rejected.
- **Claim (5):** PENDING → CLAIMED with version +1, claimedBy/claimedAt from
  the injected clock, exact raw arguments released, missing → NotFound,
  stale version → Conflict, non-claimable terminal statuses → NotClaimable.
- **Exactly-once release (2):** a second claim can never retrieve arguments
  (typed rejection on both stale and current versions); arguments are
  cleared from storage on claim.
- **Expiry (8):** explicit expire only after the deadline, early expire →
  Conflict, late claim persists EXPIRED and fails NotClaimable, **late
  cancel persists EXPIRED and fails Conflict**, lazy get() expires exactly
  once, CLAIMED never lazy-expires, expire on non-PENDING and on
  already-EXPIRED → Conflict.
- **Cancellation (4):** PENDING → CANCELLED with version +1 and arguments
  gone, cancel on CLAIMED / COMPLETED / CANCELLED_UNCERTAIN → Conflict,
  stale version → Conflict, missing → NotFound.
- **Completion (5):** CLAIMED → COMPLETED by the claimant with completedAt
  from the injected clock, wrong actor → NotCompletable, stale version →
  Conflict, non-completable statuses → NotCompletable, missing → NotFound.
- **Recovery (8):** findStaleClaimed includes the claimedAt boundary and
  excludes fresh/terminal rows, deterministic ordering by claimedAt then
  approvalId (same-instant claims exercise the secondary key), limit
  enforced, invalid limit → IAE, forceCancelClaimed → CANCELLED_UNCERTAIN
  with recovery actor/time/reason pinned, non-CLAIMED → NotClaimable,
  invalid reason codes → IAE, stale version → Conflict.
- **Sweep (3):** sweeps only elapsed PENDING rows with exact count, second
  sweep returns 0, CLAIMED and terminal rows never touched.
- **Concurrency (3, real parallel races with a start barrier, 5 iterations
  each):** concurrent claims — exactly one winner releases the raw
  arguments and the loser gets a typed failure; claim vs cancel — exactly
  one legal transition, version stays 1; competing same-version cancels —
  exactly one wins, one Conflict, version 1.

### Typed failure taxonomy (pinned, not just "some RuntimeException")

| Failure | Exception |
|---|---|
| duplicate / stale version | `ApprovalContinuationConflictException` |
| missing continuation | `ApprovalContinuationNotFoundException` |
| not claimable (status / expired) | `ApprovalContinuationNotClaimableException` |
| not completable (status / actor) | `ApprovalContinuationNotCompletableException` |
| invalid input (fields, TTL, digest, reason code, limit) | `IllegalArgumentException` |

### Cross-store discrepancies the TCK exposed (all fixed in #269)

The TCK found three real JDBC divergences from the in-memory/file contract:

1. **Late cancel** produced `CANCELLED` instead of persisting `EXPIRED` and
   failing with `Conflict` — JDBC `cancel()` lacked the PENDING-only
   lazy-expiry normalization its `get()`/`claimForExecution()` paths already
   had.
2. **`create()` accepted a mismatched `argumentsDigest`** — the in-memory and
   file stores validate the digest against the released payload; JDBC did
   not.
3. **`claimForExecution()` checked status before version** — a stale-version
   claim on a CLAIMED row returned `NotClaimable` instead of `Conflict`,
   diverging from the version-first precedence of the other two stores.

### Mutation evidence (all restored after each run; InMemory store)

| Mutation | TCK outcome |
|---|---|
| Claim leaves arguments stored | RED — completion guard fails (claim keeps arguments, complete on CLAIMED → NotCompletable) |
| Claim does not increment version | RED — claim-transition + completion cases fail |
| Claim drops version + status guards (non-atomic check-then-act claim) | RED — concurrent-claim race detects two winners releasing arguments; second claim succeeds |
| CLAIMED lazy-expires / sweep touches CLAIMED | RED — claimed-never-expires + sweep-cases fail |
| Late cancel produces CANCELLED | RED — late-cancel case fails (the exact JDBC bug the TCK caught) |
| Complete ignores claimedBy | RED — wrong-actor case fails |
| forceCancelClaimed accepts PENDING | RED — non-claimed recovery case fails |
| findStaleClaimed drops secondary ordering | RED — deterministic-ordering case fails |

### Scope

The TCK owns SPI semantics; implementation-specific suites continue owning
encryption codecs, BYTEA representation, SQL schema/migrations, database
integrity, connection cleanup (JDBC) and encryption envelopes, permissions,
corruption, record format (file). No existing tests were deleted. Zero
public API change; no persisted format or schema changes.
