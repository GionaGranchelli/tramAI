# Persistence Store Compatibility Contract

Epic 8.1 (PR #267 ApprovalStore slice, PR #269 ApprovalContinuationStore
slice, PR #270 SuspendedInvocationStore slice, PR #271 AuditStore slice,
PR #272 SovereignOpsAuditOutboxStore slice, PR #273 WorkflowCheckpointStore
slice). Shared behavioral TCKs prove every implementation of a store family
satisfies exactly the same externally observable contract, regardless of
storage technology (memory, encrypted files, Markdown, JDBC).

## Epic 8.1 matrix

| Store family | In-memory | File | Markdown | JDBC | Shared TCK |
|---|---|---|---|---|---|
| Approval | ✅ | ✅ | — | ✅ | ✅ #267 |
| Approval continuation | ✅ | ✅ | — | ✅ | ✅ #269 |
| Suspended invocation | ✅ | ✅ | — | ✅ | ✅ #270 |
| Audit | ✅ | ✅ | — | ✅ | ✅ #271 |
| Audit outbox | ✅ | ✅ | — | ✅ | ✅ #272 |
| Workflow checkpoint | ✅ | ✅ | ✅ | ✅ | ✅ #273 |
| Workflow lease | … | … | — | … | ⏳ |
| Step attempt | ✅ | ✅ | — | ✅ | ✅ #218 |
| Memory | … | … | — | … | ⏳ |

Markdown cells: the Markdown checkpoint store implements the
`WorkflowCheckpointStore` SPI (enrolled in #273) but not the optional
`WorkflowCheckpointCatalog`/step-attempt families; the remaining families
have no Markdown implementation.

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
  conflict, non-zero version / non-PENDING rejected, **each pre-populated
  claimed / completion / recovery field rejected independently** (one
  forbidden field at a time, so a store that drops a single validation goes
  RED), blank ID rejected, future createdAt rejected, non-future expiry
  rejected, TTL bound enforced, arguments digest mismatch rejected.
- **Claim (5):** PENDING → CLAIMED with version +1, claimedBy/claimedAt from
  the injected clock, exact raw arguments released, missing → NotFound,
  stale version → Conflict, non-claimable terminal statuses → NotClaimable.
- **Exactly-once release (2):** a second claim can never retrieve arguments
  (typed rejection on both stale and current versions); a second claim can
  never expose released arguments — physical scrubbing of the encrypted
  payload is owned by the implementation-specific suites (JDBC asserts
  `encrypted_arguments` becomes NULL directly).
- **Expiry (8):** explicit expire only after the deadline, early expire →
  Conflict, late claim persists EXPIRED and fails NotClaimable, **late
  cancel persists EXPIRED and fails Conflict**, lazy get() expires exactly
  once, CLAIMED never lazy-expires, expire on non-PENDING and on
  already-EXPIRED → Conflict.
- **Cancellation (4):** PENDING → CANCELLED with version +1 and arguments
  gone, cancel on CLAIMED / COMPLETED / CANCELLED / CANCELLED_UNCERTAIN /
  EXPIRED → Conflict, stale version → Conflict, missing → NotFound.
- **Completion (5):** CLAIMED → COMPLETED by the claimant with completedAt
  from the injected clock, wrong actor → NotCompletable, stale version →
  Conflict, non-completable statuses (PENDING / CANCELLED / COMPLETED /
  CANCELLED_UNCERTAIN / EXPIRED) → NotCompletable, missing → NotFound.
- **Recovery (8):** findStaleClaimed includes the claimedAt boundary and
  excludes fresh/terminal rows, deterministic ordering by claimedAt then
  approvalId (same-instant claims exercise the secondary key), limit
  enforced, invalid limit → IAE, forceCancelClaimed → CANCELLED_UNCERTAIN
  with recovery actor/time/reason pinned, non-CLAIMED statuses (PENDING /
  COMPLETED / CANCELLED / CANCELLED_UNCERTAIN / EXPIRED) → NotClaimable,
  invalid reason codes → IAE, stale version → Conflict.
- **Sweep (3):** sweeps only elapsed PENDING rows with exact count, second
  sweep returns 0, CLAIMED and terminal rows never touched.
- **Concurrency (3, real parallel races with a start barrier, 5 iterations
  each):** concurrent claims — exactly one winner releases the raw
  arguments and the loser gets **Conflict**; claim vs cancel — exactly one
  legal transition, the loser gets **Conflict** regardless of which
  operation wins; competing same-version cancels — exactly one wins, one
  Conflict, version 1. The loser type is pinned to Conflict (stale
  snapshot), not a broad store exception — version-before-status precedence
  on every path, including the JDBC CAS-loss branch (a deterministic
  interleaving regression with a gated arguments codec pins that branch:
  claim blocks at decrypt, cancel wins, released claim must throw
  Conflict).

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

The follow-up review found a fourth in the same family: the **claim CAS-loss
re-read** still mapped a lost claim/cancel race to `NotClaimable` (status
seen after the re-read) while memory and file reported `Conflict` (stale
version). Fixed with the same version-before-status precedence on the
CAS-loss branch, pinned by a deterministic interleaving regression (gated
codec: claim blocks at decrypt, cancel wins, released claim must throw
Conflict) and by tightening the shared race assertions from "a typed
failure" to exactly `Conflict`.

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

---

## SuspendedInvocationStore TCK (PR #270)

`SuspendedInvocationStoreTck` (tramai-testing testFixtures) runs **39 shared
behavioral cases** against every `SuspendedInvocationStore` implementation:
the engine's default in-memory store (`InMemorySuspendedInvocationStore`,
`tramai-engine`), the encrypted file store (`tramai-persistence-file`), and
the JDBC store (`tramai-persistence-jdbc`).

The contract covers safe metadata CRUD, the sensitive replay-envelope release
path, the digest + envelope-binding invariants, ID validation, and real
parallel races. It deliberately does NOT pin implementation-specific
concerns: restart durability, encryption format, file permissions, corruption
handling, schema-version decoding, SQL locking/resource behavior, or the
JDBC-only unique replay-envelope-digest constraint (see below).

- **Creation/read (13):** full-metadata round-trip (incl. token budget and
  tool security), missing → null, duplicate approvalId → IAE, blank /
  control-character / whitespace-padded / oversized approvalId → IAE, blank
  toolCallId / toolName / correlationId / conversationId → IAE, blank
  approvalId on get/reveal/remove → IAE.
- **Sensitive release (6):** reveal returns an envelope whose messages equal
  the originals, missing → null, get() never exposes the messages (safe
  metadata shape only), envelope `toString` is `[REDACTED]`, reveal does not
  consume (record survives until remove), repeated reveals return equal
  content.
- **Digest + binding (6):** create rejects a canonical-digest mismatch
  (wrong digest and tampered envelope both), an envelope without an assistant
  tool-call message, a toolCallId absent from the envelope, a toolCallIndex
  out of bounds, and a toolName that does not match the envelope.
- **Redaction invariants (6):** the replay envelope must already be redacted
  — a correctly-digested envelope whose selected tool call carries RAW
  arguments is rejected (the digest is recomputed over the invalid envelope,
  so only the redaction check can fire); a duplicate selected toolCallId
  across the envelope is rejected; an extra or misplaced redaction sentinel
  is rejected; a selected call outside the latest assistant tool-call batch
  is rejected; negative historySize and an envelope smaller than its
  historySize are rejected.
- **Remove (5):** remove returns the created metadata, remove then get →
  null, remove then reveal → null, missing → null, create after remove
  succeeds with the same approvalId.
- **Concurrency (3, real parallel races with a start barrier, 20 iterations
  each):** concurrent create — exactly one winner, seven `IllegalArgumentException`
  losers; concurrent remove — exactly one winner returns metadata; concurrent
  reveal while remove — no crashes and exactly one remove winner (reveals may
  observe the entry or the empty state, both valid).

### Deliberate cross-store decision: replay-digest uniqueness

`JdbcSuspendedInvocationStore` keeps a unique index on
`replay_envelope_digest` and maps a collision to
`suspended-invocation-replay-envelope-digest-already-exists`, so the same
replay payload can never be suspended under two different approval IDs.
The in-memory and file stores do NOT enforce this, and the TCK deliberately
does not pin it:

- The engine's suspension flow (`ApprovalSuspensionCoordinator`) creates the
  `ApprovalContinuationStore` entry first, and that store already rejects a
  duplicate approvalId — the engine never suspends the same invocation twice.
- Digest uniqueness across different approval IDs is therefore defensive
  hardening specific to the JDBC store, not a shared contract invariant.
- It is documented here so a future implementation knows the divergence is
  deliberate; if double-suspension protection ever becomes a shared
  requirement, it belongs in the TCK and all three stores must conform.

### Production changes the enrollment required

- **`InMemorySuspendedInvocationStore` gained the shared validations** it
  lacked, delegated to a shared `ReplayEnvelopeValidator` (tramai-engine,
  internal): ID-field validation on every operation, envelope binding, the
  canonical replay-envelope digest check, and the **redaction invariants** —
  history-size consistency, globally unique selected toolCallId, selected
  slot in the latest assistant batch, and exactly one redaction sentinel at
  the selected slot. It previously accepted records the file store rejected.
- **`JdbcSuspendedInvocationStore` now enforces the same redaction
  invariants** (mirrored inline — cross-module copy of the engine validator,
  pinned by the TCK so the copies cannot drift). It previously accepted and
  encrypted a correctly-digested unredacted envelope, which violated the
  architecture invariant that raw tool arguments live only behind the
  ApprovalContinuationStore boundary (SPEC-016).
- **SPI KDoc fixed**: the interface claimed stores "do not persist beyond
  the JVM lifecycle"; durability is implementation-specific (in-memory is
  process-local, file/JDBC may survive restart).
- Engine test doubles that built invalid envelopes now build valid ones
  (redacted sentinel + canonical digest): the testFixtures
  `TestApprovalGatewayPersistenceRequestBuilder`, `DefaultApprovalGatewayTest`,
  and the JDBC implementation-specific fixtures. The JDBC
  `raw replay envelope is not visible in the database` test was rewritten:
  raw-args envelopes are now rejected at the API boundary, and the valid
  (redacted) envelope's sensitive message content is still encrypted at rest.

### Mutation evidence (all restored after each run; InMemory store + validator)

| Mutation | TCK outcome |
|---|---|
| Duplicate create overwrites existing | RED — duplicate-create case fails |
| Remove becomes get-without-delete | RED — remove-then-get / remove-then-reveal / create-after-remove / concurrent-remove cases fail |
| Reveal returns null despite existing entry | RED — reveal round-trip + non-consuming reveal cases fail |
| Remove leaves replay envelope reachable | RED — remove-then-reveal case fails |
| Digest mismatch accepted | RED — digest-mismatch + tampered-envelope cases fail |
| toolCallId mismatch accepted | RED — toolCallId-binding case fails (the duplicate-id case passes via `single()`'s incidental IAE — documented) |
| toolName mismatch accepted | RED — toolName-binding case fails |
| toolCallIndex checks dropped | RED — toolCallIndex-binding case fails |
| Concurrent remove check-then-act (two winners) | RED — concurrent-remove race fails |
| Redaction sentinel not required | RED — not-redacted + extra-sentinel cases fail |
| historySize consistency not validated | RED — negative-historySize + too-small-envelope cases fail |

### Scope

The TCK owns SPI semantics; implementation-specific suites continue owning
restart durability (`FileSuspendedInvocationStoreRestartTest`), encryption
envelopes, permissions, corruption handling, record format (file), SQL
schema/JSON mapping/connection cleanup (JDBC). No existing tests were
deleted. The `tramai-testing` testFixtures gained a dependency on
`tramai-engine` (the SPI's home module) so the shared suite can be written
once and run against all three implementations.

## AuditStore TCK (PR #271)

`AuditStoreTck` (tramai-testing testFixtures) runs **43 shared behavioral
cases** against every `AuditStore` implementation. Audit is not ordinary
CRUD: the store is the chain authority — it serializes access to the
authoritative latest event, hands it to the factory, validates the returned
event's chain position, and appends atomically. The TCK therefore tests the
factory-callback semantics themselves, not just resulting rows.

- **Append / chain semantics (19):** first append's factory receives
  `latest == null`; first event has sequence 1 and `previousEventHash ==
  null`; the second append's factory receives the exact latest event;
  second event has sequence 2 and `previousEventHash == first.eventHash`;
  every metadata field round-trips; wrong auditStreamId rejected
  (`audit-stream-id-mismatch`); wrong sequence rejected (`audit-sequence-gap`);
  wrong previous hash rejected (`audit-hash-chain-broken`); invalid
  self-hash rejected (`audit-event-hash-mismatch`); unsupported schema
  version rejected (`audit-schema-version-unsupported`); duplicate event ID
  in the same stream rejected (`audit-duplicate-event-id`); blank audit
  stream ID rejected (`audit-store-invalid-stream-id`); blank event ID
  rejected (`audit-store-invalid-event-id`); a rejected append leaves the
  stream unchanged; the event factory is invoked exactly once per append;
  a factory exception propagates unchanged and appends nothing;
  `CancellationException` propagates as the same instance and appends
  nothing. Malformed fixtures compute their digest over the malformed event,
  so exactly one invariant is violated per case.
- **Read / latest semantics (8):** missing stream → `emptyList()`/`null`;
  ascending sequence order; exact field equality; `latestEvent()` equals the
  final event; independent streams never bleed; metadata is defensively
  isolated — mutating the source map after append cannot mutate stored
  evidence, and mutating returned metadata cannot modify the persisted
  event (storage isolation is the observable rule, not whether every
  returned map throws `UnsupportedOperationException`).
- **Pagination (12):** the SPI cursor is exclusive and the limit is a
  maximum — `null`/`0` cursor starts at sequence 1; cursor N returns events
  starting at N+1; limit respected; partial page at the end; cursor at the
  final event → empty; missing stream → empty; zero/negative limit rejected;
  negative cursor rejected; ascending order preserved; page contents equal
  the corresponding slice of `readStream`. The contract expects the
  requested limit when enough events are available within the
  implementation's supported page cap, but does not require a request
  larger than an implementation-specific maximum such as JDBC's
  `maxPageSize` to return the entire requested count.
- **Hash-chain integrity (1):** for a valid multi-event stream —
  `events[i].sequenceNumber == events[i-1].sequenceNumber + 1`,
  `events[i].previousEventHash == events[i-1].eventHash`,
  `events[i].eventHash == calculateHash()` — and `AuditChainVerifier.verify`
  reports valid.
- **Concurrency (3 real parallel races, start barrier, 20 iterations
  each):** 8 concurrent same-stream appends — all succeed, sequences
  exactly 1..8, unique event IDs, valid uninterrupted chain; concurrent
  duplicate event ID — exactly one winner, losers rejected with
  `audit-duplicate-event-id`, stored once, chain valid; concurrent
  independent streams — both start at sequence 1 independently, both chains
  valid.

### Decisions (deliberate, per review)

- **Event-ID uniqueness is per-stream shared contract; cross-stream reuse is
  NOT.** Cross-stream event-ID reuse is intentionally outside the shared
  AuditStore contract — a direct SPI caller can reuse an ID across streams
  and InMemory/File accept it. JDBC additionally enforces global event-ID
  uniqueness as implementation-specific hardening, retained because normal
  AuditEngine generation uses UUID event IDs and there is no architecture
  requirement to reuse an ID across streams (same rationale as the
  replay-digest uniqueness in #270). Documented so future implementations
  know the divergence is deliberate.
- **`appendNext` is a chain-authority API, not a plain append:** the TCK
  pins the factory-callback contract (invoked exactly once, receives the
  authoritative latest, exception/cancellation propagate unchanged with
  nothing appended).
- **Validation errors are stable safe reason codes**, not interpolated
  messages: File and JDBC already used the fixed codes; `InMemoryAuditStore`
  was normalized to them so storage technology never changes the error
  surface.

### Production changes the enrollment required

- **`InMemoryAuditStore`** gained the shared input validation it lacked
  (blank audit stream ID on every operation, blank event ID on append) and
  replaced its interpolated human-readable validation messages with the
  fixed safe reason codes used by File/JDBC. Its Mutex-per-stream atomic
  append, snapshot-based defensive metadata copies, and chain validation
  were already conformant.
- **`FileAuditStore`** gained the same shared blank stream/event ID
  validation before any filesystem work (it previously hashed whatever
  string it received). No append-chain change: File is the strictest
  implementation (full-chain validation on every read).
- **`JdbcAuditStore`** gained the shared blank event-ID validation, and
  `appendNext()`'s transaction cleanup now follows the #267
  primary-exception-precedence pattern: operation / cancellation **>**
  rollback failure **>** autoCommit-restore failure, with later cleanup
  failures attached as `suppressed` to the primary — a rollback or restore
  failure can no longer mask the primary exception or cancellation. No
  database schema change.
- Deterministic JDBC regressions added: primary `CancellationException` +
  rollback failure → same cancellation escapes, rollback failure suppressed;
  primary `CancellationException` + autoCommit-restore failure → same
  cancellation escapes, restore failure suppressed.
- Existing `AuditEngineTest` assertions on the old interpolated InMemory
  messages were updated to the fixed reason codes (6 tests).

### Mutation evidence (all restored after each run; InMemory store)

| Mutation | TCK outcome |
|---|---|
| append skips stream-ID check | RED — wrong-stream case fails |
| append accepts wrong sequence | RED — wrong-sequence case fails |
| append ignores previous hash | RED — chain-link case fails |
| append ignores event hash self-hash | RED — self-hash case fails |
| append accepts unsupported schema | RED — schema-version case fails |
| duplicate eventId accepted | RED — duplicate + concurrent-duplicate cases fail |
| blank stream-ID check removed | RED — blank-stream case fails |
| blank event-ID check removed | RED — blank-event case fails |
| page cursor becomes inclusive (`>=`) | RED — exclusive-cursor + final-cursor cases fail |
| page ignores limit | RED — limit case fails |
| latestEvent returns first instead of last | RED — latest-equals-final case fails |
| same-stream append lock removed (non-atomic) | RED — both same-stream races fail |
| shared metadata reference retained | RED — source-mutation isolation case fails |
| rejected factory result still stored (add before validation) | RED — rejected-append-leaves-stream-unchanged (and every other append case) fails |

### Scope

The TCK owns SPI semantics; implementation-specific suites continue owning
restart durability, encryption format, file permissions, corruption
injection, SQL schema internals, indexes/query strategy, and `maxPageSize`.
No existing tests were deleted. The `tramai-testing` testFixtures gained a
dependency on `tramai-security` (the SPI's home module) so the shared suite
can be written in one place.

## SovereignOpsAuditOutboxStore TCK (PR #272)

`SovereignOpsAuditOutboxStoreTck` (tramai-testing testFixtures) runs **55
shared behavioral cases** against every `SovereignOpsAuditOutboxStore`
implementation. The outbox is a delivery state machine, not generic
persistence — the contract pins one legal lifecycle (the JDBC guards from
PR #85's review are the authoritative semantics):

```
PREPARED ──markReadyForDispatch──→ PENDING ──claimPending──→ EMITTING
PREPARED ──markFailed(retryable=false)──→ FAILED_PERMANENT
EMITTING ──markEmitted──→ EMITTED
EMITTING ──markFailed(retryable=true)──→ FAILED_RETRYABLE ──claimPending──→ EMITTING
EMITTING ──markFailed(retryable=false)──→ FAILED_PERMANENT
EMITTING (claimExpiresAt < now) ──claimPending──→ EMITTING (attempt + 1)
```

- **Append / lookup (13):** valid PREPARED round-trips exactly; append
  returns the stored record; missing `get`/`findByEventKey` → null; exact
  event-key lookup; duplicate outboxId rejected; duplicate eventKey rejected
  (and leaves no orphan second record, with the original event-key mapping
  intact); blank outboxId/eventKey rejected; every non-PREPARED initial
  status rejected; reason codes are exact fixed strings, never interpolated
  (`tramai-sovereign-ops-outbox-invalid-id`,
  `-invalid-event-key`, `-invalid-status`, `-duplicate-id`,
  `-duplicate-event-key`, `-not-found`, `-status-mismatch`).
- **markReadyForDispatch (5):** PREPARED → PENDING preserving every
  non-status field; persisted PENDING; missing → `IllegalStateException` +
  not-found; wrong current status rejected; **the supplied expectedStatus
  itself must be PREPARED** (markReady(expectedStatus = PENDING) on a
  PREPARED record rejects — catches the InMemory divergence).
- **markEmitted (7):** EMITTING → EMITTED with the exact supplied
  `emittedAt`; attempt/claim fields preserved; missing → not-found;
  PREPARED/PENDING/EMITTED/FAILED_RETRYABLE/FAILED_PERMANENT all rejected —
  the expected status must be EMITTING.
- **markFailed (9):** legal matrix — PREPARED + permanent → FAILED_PERMANENT;
  EMITTING + retryable → FAILED_RETRYABLE; EMITTING + permanent →
  FAILED_PERMANENT, each pinning `lastErrorCode == errorCode`; everything
  else rejected (PREPARED + retryable, PENDING + either, FAILED_RETRYABLE,
  EMITTED, FAILED_PERMANENT).
- **Claim / retry / lease (10):** PENDING claim → EMITTING with attempt +1,
  claimant, claim timestamp, expiry = now + 5 minutes; FAILED_RETRYABLE
  claim → EMITTING with new claimant and **`lastErrorCode = null`** (its own
  dedicated case — removing that one line turns the TCK red); expired
  EMITTING reclaim replaces claimant/attempt/expiry; the lease boundary is
  strictly `claimExpiresAt < now` (exact equality is NOT reclaimable, one
  second past IS); PREPARED, EMITTED, FAILED_PERMANENT and fresh EMITTING
  are never claimable.
- **Diagnostic listing (7):** membership only, never ordering (JDBC orders
  by `created_at`; InMemory/File do not) — listPending → PENDING only,
  listByStatus → exact status only, listExpiredEmitting → EMITTING with
  expiry < now only; limits cap the result size without pinning which
  records win; zero/negative limits → empty on all four paths; the expired
  boundary excludes exact equality; `findByEventKey` reflects the current
  transitioned version, not the original PREPARED snapshot.
- **Concurrency (5 real parallel races, start barrier, 20 iterations
  each):** duplicate outboxId — exactly one append winner; duplicate
  eventKey — exactly one winner AND every loser's record is absent (proves
  the InMemory event-key index rollback is real); one PENDING record, 8
  claimers with limit 1 — exactly one claim total, attempt 1, one claimant;
  pool claim — 8 PENDING records claimed by 8 workers, every record exactly
  once, every persisted attemptCount 1; concurrent completion — exactly one
  `markEmitted` winner, final EMITTED.

### Decisions (deliberate, per review)

- **The JDBC lifecycle guards are the authoritative semantics** (they were
  explicitly added in PR #85's review): InMemory and File were aligned to
  them rather than weakening JDBC.
- **Clearing `lastErrorCode` on a fresh claim is shared contract** — a retry
  actively claimed must not carry the previous failure forward.
- **Non-positive diagnostic limits return `[]`** on every path (the public
  ops layer separately validates user-facing limits as positive and bounded).
- **Error messages are stable safe reason codes**, never interpolated IDs or
  event keys (policy continued from #271).

### Production changes the enrollment required

- **`InMemorySovereignOpsAuditOutboxStore`** gained: PREPARED-only readiness
  guard, EMITTING-only emission guard, the legal markFailed matrix, clearing
  `lastErrorCode` on claim, `[]` for non-positive list limits, and fixed
  (non-interpolated) duplicate/invalid-status reason codes.
- **`FileSovereignOpsAuditOutboxStore`** gained blank outboxId/eventKey
  validation, the EMITTING-only emission guard, the legal markFailed matrix,
  and clearing `lastErrorCode` on claim. Its write staging was also fixed:
  temp files are now created **outside** the scanned outbox directory, so a
  concurrent `committedEntries()` scan never observes an in-flight write as
  an unexpected entry (the TCK's pool-claim race exposed this).
- **`JdbcSovereignOpsAuditOutboxStore`** normalized its duplicate errors to
  fixed reason codes and collapsed all five mutating transaction paths onto
  one non-suspend `inOutboxTransaction` helper with the #267 precedence:
  operation / cancellation **>** rollback failure **>** autoCommit-restore
  failure, later cleanup failures suppressed. Deterministic regressions
  prove a primary `CancellationException` escapes unchanged when rollback
  fails, and when autoCommit-restore fails. No database schema change.
- `SovereignOpsAuditOutboxRecord` KDoc lifecycle diagram corrected to the
  shared matrix (retryable failures re-claim directly to EMITTING; no
  PENDING → FAILED_PERMANENT path).

### Mutation evidence (all restored after each run; InMemory store)

| Mutation | TCK outcome |
|---|---|
| duplicate outbox ID overwrites existing | RED — duplicate-id + concurrent-duplicate races |
| duplicate eventKey accepted | RED — event-key + concurrent event-key race |
| loser record not removed after eventKey race | RED — concurrent event-key race |
| blank outboxId validation removed | RED — blank-id case |
| markReady allows expected PENDING | RED — readiness lifecycle cases |
| markEmitted accepts PREPARED | RED — emission lifecycle cases |
| retryable markFailed accepts PREPARED | RED — failure-matrix cases |
| permanent markFailed accepts PENDING | RED — failure-matrix case |
| claim allows PREPARED | RED — claim-eligibility case |
| fresh EMITTING reclaimed (expiry ignored) | RED — fresh-EMITTING + lease-boundary + list-boundary cases |
| lease boundary becomes inclusive (`<=`) | RED — exact-boundary case |
| attemptCount not incremented | RED — claim/reclaim cases |
| retry claim retains lastErrorCode | RED — dedicated clear-error case |
| listPending includes FAILED_RETRYABLE | RED — listing case |
| non-atomic claim (CAS replaced) | RED — claim + pool-claim races |
| claim ignores limit | RED — limit-capped case |

### Scope

The TCK owns SPI semantics; implementation-specific suites continue owning
durability/restart guarantees, encryption/ciphertext, filesystem
permissions, corruption detection, JDBC queryable-column tamper detection,
SQL indexes/schema, `FOR UPDATE SKIP LOCKED`, file record versions, JDBC
`maxClaimLimit`, and `isDurable()` (the SPI documents it as
implementation-dependent). No existing tests were deleted. The
`tramai-testing` testFixtures gained a dependency on
`tramai-spring-boot-starter-sovereign-ops` (test-fixture-only; no Spring
enters any production runtime module) so the shared suite can be written in
one place.

## WorkflowCheckpointStore TCK (PR #273)

`WorkflowCheckpointStoreTck` (tramai-testing testFixtures, which gained a
test-fixture-only dependency on `tramai-orchestration`, the SPI's home
module) runs **42 shared behavioral cases** against every
`WorkflowCheckpointStore` implementation — the orchestration module's
in-memory store, the properties-file store, the Markdown store, and JDBC
(real H2, not a fake backend; the checkpoint SPI is standard SQL). A
checkpoint is a **versioned logical record identified by (workflowName,
workflowId)**, never "a file containing state": filesystem-safe naming,
Markdown formatting and JDBC primary keys are implementation mechanics and
cannot change what constitutes a unique checkpoint.

- **Creation / read / identity (14):** full round-trip; save returns the
  persisted value; missing load → null; first revision always 1; the
  caller-supplied `revision` field is never authoritative; nullable
  `lastCompletedStepName` preserved; multiline/Unicode/punctuation state
  payload preserved; metadata exact; `Required` recovery state round-trips;
  same workflowName/different IDs and same workflowId/different names
  independent; duplicate create → `WorkflowCheckpointConflictException`
  with exactly `"Workflow checkpoint conflict"` and null cause; rejected
  duplicate leaves the original unchanged; **distinct keys whose legacy
  sanitized paths collide remain distinct** (`"a/b"` vs `"a?b"` — the
  discriminator that exposed the File/Markdown identity-domain divergence).
- **Revision / optimistic concurrency (9):** exact expected revision
  succeeds; update persists new values; stale lower revision conflicts;
  impossible higher revision conflicts; expected revision on missing record
  conflicts; expected null on existing record conflicts; failed update is
  non-mutating; supplied revision 999 never becomes 999/1000; repeated
  updates advance exactly 1 → 2 → 3.
- **Delete / idempotency (7):** exact revision deletes; stale revision
  conflicts; stale delete non-mutating; missing + expected revision
  conflicts; missing + null expected is a no-op; existing + null expected
  deletes; recreate after delete starts fresh at revision 1 (deleting a
  checkpoint deletes its revision history).
- **Recovery state (8):** `requireRecovery` transitions Normal@1 →
  Required@2 with the exact `WorkflowRecoveryRecord` (reason, stepName,
  attemptId, priorWorkerId, detectedAt, nullable idempotencyKey,
  instructions); unrelated checkpoint fields preserved; stale expected
  revision conflicts; missing checkpoint conflicts; failed operation
  non-mutating. `clearRecovery` transitions Required@2 → Normal@3; stale
  revision conflicts; failed operation non-mutating. The store-level
  recovery contract deliberately does NOT declare
  `requireRecovery(Required)`/`clearRecovery(Normal)` illegal — those
  higher-level lifecycle restrictions belong to `WorkflowRecoveryController`.
- **Concurrency (4 real parallel races, start barrier, 20 iterations
  each):** concurrent create — exactly one winner, final revision 1;
  **competing same-revision updates** — exactly one winner, final revision
  2 with the winner's payload, all others conflict; update versus delete —
  exactly one legal winner; **competing requireRecovery** — exactly one
  winner with the winner's exact record (proves the SPI's default
  load-then-save implementation is sufficiently atomic through the
  underlying save CAS — no store override was rewritten).

### Decisions (deliberate, per review)

- **File/Markdown logical-key collision repair (the principal production
  fix).** The lossy `sanitizePathSegment` mapping collapsed distinct keys
  onto one file (`"order/a"` and `"order?a"` → `"order_a"`), while
  InMemory/JDBC distinguished them. The checkpoint stores now default to the
  new collision-free `CollisionFreeWorkflowCheckpointPathStrategy`
  (URL-safe Base64 segments, injective and reversible) and read pre-upgrade
  checkpoints via the legacy sanitized path **with identity verification**
  (the decoded record must identify the requested key — a legacy path may
  hold a colliding key's record, and it is never overwritten). The first
  legitimate update migrates the record to the canonical path and deletes
  the legacy file — never two authoritative copies.
  `DefaultWorkflowCheckpointPathStrategy` is unchanged (the lease store and
  explicit-injection callers depend on it). New public class is additive;
  `api/` dump regenerated.
- **`WorkflowCheckpointCatalog` is outside the shared #273 TCK**: it is a
  distinct optional SPI and Markdown intentionally does not implement it.
- **The JDBC runner uses real H2** — stronger contract evidence than the
  proxy backend; no Testcontainers needed for standard-SQL semantics.
- **Conflict taxonomy pinned:** `WorkflowCheckpointConflictException` with
  message `"Workflow checkpoint conflict"` and null cause. Raw
  SQL/filesystem exceptions and corruption behavior stay out of the shared
  contract.
- The enrollment scanner now skips `private` declarations (the supervisor's
  private lease-fencing decorator is an implementation detail, not a store
  family member).

### Mutation evidence (13 mutations, each restored; InMemory store + strategy)

| Mutation | TCK outcome |
|---|---|
| duplicate create overwrites existing | RED — duplicate-create + create race |
| initial revision trusts supplied revision | RED — first-revision + input-revision cases |
| initial revision becomes 0 | RED — first-revision case |
| update ignores expected revision | RED — stale/higher conflicts + update & recovery races |
| update does not increment revision | RED — exact-revision + 1→2→3 cases |
| missing + expected becomes create | RED — missing-with-expected conflict |
| delete ignores expected revision | RED — stale-delete cases |
| missing + expected delete silently succeeds | RED — missing-with-expected delete conflict |
| missing unconditional delete throws | RED — missing no-op idempotency |
| save becomes check-then-write (synchronized removed) | RED — update race + create race |
| delete becomes check-then-delete (synchronized removed) | RED — update-vs-delete race |
| save always writes Normal recovery state | RED — Required round-trip case |
| file path mapping reverts to lossy sanitize | RED — collision discriminator (File + Markdown) |

### Scope

The TCK owns SPI semantics; implementation-specific suites continue owning
persistence formats (properties/Markdown/JSON), restart durability,
permissions, corruption injection, JDBC schema internals, and the
`WorkflowCheckpointCatalog` listing SPI. Existing suites
(`WorkflowCheckpointStoreTest`, `WorkflowRecoveryContractTest`,
`DurableWorkflowRecoveryFileTest`/`JdbcTest`,
`FileWorkflowPersistenceCancellationContractTest`,
`JdbcWorkflowPersistenceCancellationContractTest`,
`PersistenceSafeFailureBoundaryTest`) are untouched and keep their
higher-level coverage. No existing tests were deleted. `tramai-testing`
testFixtures gained a dependency on `tramai-orchestration` (test-fixture-only)
so the shared suite can be written in one place. Legacy path behavior is
documented in `docs/guides/orchestration-persistence.md`.
