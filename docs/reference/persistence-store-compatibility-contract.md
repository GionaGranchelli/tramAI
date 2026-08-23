# Persistence Store Compatibility Contract

Epic 8.1 (PR #267, ApprovalStore slice). Shared behavioral TCKs prove every
implementation of a store family satisfies exactly the same externally
observable contract, regardless of storage technology (memory, encrypted
files, JDBC).

## Epic 8.1 matrix

| Store family | In-memory | File | JDBC | Shared TCK |
|---|---|---|---|---|
| Approval | ✅ | ✅ | ✅ | ✅ #267 |
| Approval continuation | ✅ | ✅ | ✅ | ⏳ (Epic 8.1b) |
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
source set for concrete `ApprovalStore` implementations (including body-less
declarations such as `class X : ApprovalStore by delegate`) and requires a
`<Store>TckTest` runner in the same module that actually extends
`ApprovalStoreTck` — a same-named file with an unrelated class does not
count. A future `RedisApprovalStore` cannot merge without
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
