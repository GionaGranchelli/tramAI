# Time Semantics Contract (Epic 8.3a)

> Status: frozen at the 8.3a exact head. This contract describes the ACTUAL
> frozen types and behaviours; it is the authoritative reference for what
> 8.3a guarantees and what it deliberately leaves at composition boundaries.

## Goal

> No incidental nondeterministic source participates directly in a domain
> decision. Nondeterminism exists only at explicit composition/boundary
> points.

8.3a removes wall-clock (`System.currentTimeMillis()`) participation from
elapsed-time and persisted-timestamp decisions in `tramai-orchestration`.

## Elapsed-time authority (monotonic)

Wall time can jump (NTP, manual correction, VM pause). Elapsed-time decisions
must never be derived from wall time.

- `MonotonicTimeSource` (`MonotonicTime.kt`) — internal seam.
  `markNow(): MonotonicMark`; `MonotonicMark.elapsedMillis()` measures elapsed
  against the SAME source that produced the mark.
- `NanoTime` — production composition: `NanoTimeSource(nanoTime: () -> Long =
  System::nanoTime)`. The raw-nanos supplier is the composition point;
  `System.nanoTime` is bound there, nowhere in domain arithmetic.
- Elapsed arithmetic: `(nanoTime() - startNanos) / 1_000_000`, clamped to
  `>= 0` (hypervisor/TSC jitter). Deterministically testable with controlled
  readings — no sleep, no tolerance, no scheduler assumption.
- `MonotonicDrainBudget(timeoutMillis, timeSource)` — the drain-residual
  authority: `remainingMillis() = (timeoutMillis - elapsed).coerceAtLeast(1L)`.
  Production drain consumes `remainingMillis()` for the residual phase after
  the first `withTimeoutOrNull` expires. The budget arithmetic is exact and
  wall-clock independent.

### Consumers

| Decision | Authority |
|---|---|
| Worker uptime (heartbeat) | `startedAtMark.elapsedMillis()` |
| Drain residual budget | `MonotonicDrainBudget` |
| Anything else | monotonic source only — never `Clock` |

## Persisted-timestamp authority (injected Clock)

Every NEW persisted orchestration timestamp consumes an explicit `Clock` —
never a direct `System.currentTimeMillis()` read. The composition boundary
differs by decision class:

- **Worker-owned execution/checkpoint timestamps** (step-attempt
  `startedAt`/`completedAt`, execution-tracker timestamps, checkpoint saves)
  → the workflow's injected `Clock`, threaded from the worker boundary.
- **Operator recovery-controller timestamps** (retry approval resolution)
  → a controller-owned clock boundary: `Clock.systemUTC()` in production,
  injectable through the internal `forTest` seam for deterministic tests.

Stores preserve timestamps; they never invent business time.

- `Workflow` / `WorkflowBuilder.build(clock: Clock = Clock.systemUTC())` — the
  existing public seam; the workflow's clock feeds step-attempt records
  (`startedAt`/`completedAt`), execution-tracker timestamps, and checkpoint
  saves.
- `WorkflowPersistenceSession` — checkpoint saves stamp
  `savedAtEpochMillis = clock.millis()`.
- `InMemoryWorkflowRecoveryController` — recovery resolution timestamps come
  from a **controller-owned clock boundary**: production defaults to
  `Clock.systemUTC()`, and the internal `forTest` seam injects a deterministic
  clock for tests. The public two-argument constructor and its JVM ABI are
  unchanged.
- Stores (`FileWorkflowCheckpointStore`, in-memory, JDBC) preserve
  timestamps as persisted; no store reads a clock to invent business time.

## Legacy checkpoint contract (0L sentinel)

`WorkflowCheckpoint.savedAtEpochMillis`:

- Framework-owned saves explicitly supply the workflow's injected Clock
  reading.
- When decoding legacy file records that lack the field, `0L` represents
  UNAVAILABLE historical save time — never "1970-01-01" and never a
  read-time synthesis ("unknown historical time ≠ the time we happened to
  read the record").
- Present values are preserved exactly; encode/load round-trips never
  transform `0L` into "now".
- The public `WorkflowCheckpoint(savedAtEpochMillis =
  System.currentTimeMillis())` default is deliberately UNTOUCHED in 8.3a —
  changing public construction behaviour needs its own compatibility
  scrutiny (composition/default boundary).

## Boundary points (nondeterminism allowed here)

1. `NanoTimeSource(System::nanoTime)` — the monotonic composition point.
2. `Clock.systemUTC()` defaults on public constructors — wall-clock
   composition points (worker, workflow, recovery controller).
3. `WorkflowCheckpoint.savedAtEpochMillis` public default.

Everywhere else in the affected paths, domain decisions consume controlled
values: injected clocks in tests, fake monotonic sources, exact arithmetic.

## Mutation evidence (frozen head)

16-mutant campaign across every independently reachable authority:
**15 STRONG / 1 REDUNDANT (M13, honest) / 0 WEAK / 0 INVALID.**

The former fresh-heartbeat-mark mutant (M03) is **structurally eliminated**,
not killed: the heartbeat loop receives a captured `MonotonicMark` instead of
a mark supplier, so a fresh-mark-per-heartbeat behaviour cannot be expressed
through the API at all.

The 14-discriminator suite (`TimeSemanticsDiscriminatorTest`) covers:
P0-A uptime (delta fake + full-worker heartbeat wiring), P0-A deterministic
NanoTime arithmetic, P0-B exact drain residual (500−50=450), P0-B2 exact
clamp (500−5000=1), P0-C ×7 (start/complete/fail/cancel/UNKNOWN-recovery/
consume/failWorkflow), P0-D checkpoint producer ownership, P0-E legacy
UNKNOWN decode.

## Out of scope (later slices)

- 8.3b — identity + randomness (UUID boundaries, retry jitter composition).
- 8.3c — scheduler ownership (`withTimeoutOrNull` placement, executor
  lifecycle).
- Durable equal-`startedAt` chronology for the File/JDBC
  `StepAttemptRecordStore` implementations remains deferred to #318. The
  in-memory implementation already uses creation-order authority after #317.
