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

`ApprovalStoreTck` (tramai-testing testFixtures) runs **33 shared behavioral
cases** against every `ApprovalStore` implementation:

- **Creation/read (10):** PENDING round-trip, missing-ID null, duplicate
  conflict, non-zero version rejected, non-PENDING rejected, pre-populated
  decision/consumption fields rejected, blank ID rejected, future
  requestedAt rejected, invalid expiry rejected.
- **Transitions (10):** APPROVED/DENIED with decided fields + version 1,
  expired→TIMED_OUT, timeout-before-expiry rejected, approve/deny-after-
  expiry rejected, terminal-state re-transition rejected, stale version
  conflict, missing-approval not-found, version increments exactly once.
- **Consumption/replay (10):** fresh consumption (replayed=false, consumed
  fields, version 2), wrong token rejected, wrong consumedBy on replay
  rejected, stale version rejected, PENDING/DENIED/TIMED_OUT not consumable,
  expired unconsumed not consumable, exact replay returns the same durable
  record with no write, replay valid after expiry, rejected consumption is
  non-mutating.
- **Concurrency (3):** concurrent transition race — exactly one wins, one
  conflict, version 1; concurrent identical consumption — one fresh + one
  replay receipt referencing the same durable record, version 2, identical
  consumedAt.

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
datasources, schema), so technology never contaminates the contract.

### Enrollment guard

`ApprovalStoreTckEnrollmentArchitectureTest` scans every module's main
source set for concrete `ApprovalStore` implementations and requires a
`<Store>TckTest` runner in the same module. A future `RedisApprovalStore`
cannot merge without `RedisApprovalStoreTckTest` — the phrase "future stores
must pass the TCK" is architecture, not documentation.

### Mutation evidence (all restored after each run)

| Mutation | TCK outcome |
|---|---|
| Remove expectedVersion check in transition | RED — stale-version + concurrent-race cases fail |
| Allow terminal APPROVED to transition again | RED — terminal re-transition case fails |
| Increment version during exact replay | RED — replay-identity + concurrent-consumption cases fail |
| Replace consumedAt during exact replay | RED — replay-after-expiry case fails |
| Allow expired fresh consumption | RED — expired-unconsumed case fails |

### Scope

The TCK owns SPI semantics; implementation-specific suites continue owning
encryption, permissions, corruption, record format (file), SQL schema, JSON
mapping, connection cleanup (JDBC). No existing tests were deleted. Zero
public API change; no persisted format or schema changes.
