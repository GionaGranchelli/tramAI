# Change Guide: Adding a Store

## Start here

A store implementation (approval, continuation, credential, audit, suspended-invocation, checkpoint, lease) implements a **core SPI** and proves conformance by enrolling in the matching **shared TCK** in `tramai-testing` — enrollment is architecture-enforced, so a store without its TCK runner fails the build. The TCK is the contract, not a documentation exercise.

Start from [`ARCHITECTURE.md`](../../../ARCHITECTURE.md) (persistence layer), read the relevant module card ([`tramai-persistence-file.md`](../../modules/tramai-persistence-file.md), [`tramai-persistence-jdbc.md`](../../modules/tramai-persistence-jdbc.md), [`tramai-security.md`](../../modules/tramai-security.md)), then follow this guide.

## Owning module

- Store SPI: `tramai-core` (approval/continuation/credential), `tramai-security` (audit), `tramai-engine` (suspended-invocation), `tramai-orchestration` (checkpoint/lease).
- Store implementations: new code lands in the owning persistence module (`tramai-persistence-file`, `tramai-persistence-jdbc`) or the module that will own the new backend; in-memory defaults stay in the module owning the SPI.
- Shared TCK fixtures: `tramai-testing`.

## Authoritative contracts

| Store | SPI file | Required methods |
|---|---|---|
| ApprovalStore | `tramai-core/.../approval/ApprovalStore.kt` | `create` L22 · `get` L30 · `transition(approvalId, expectedVersion, transition)` L45 · `consumeApprovedOrReplay` L87 (thread-safe, atomic RMW; rethrow `CancellationException`, L6-11) |
| ApprovalContinuationStore | `tramai-core/.../approval/ApprovalContinuationStore.kt` | `create(continuation, arguments)` L6 · `get` L11 · `claimForExecution` L13 (only path exposing raw args) · `complete` L19 · `expire` L25 · `cancel` L30 · `findStaleClaimed` L35 · `forceCancelClaimed` L40 · `sweepExpired` L47 |
| ApprovalResumeCredentialStore | `tramai-core/.../approval/gateway/ApprovalResumeCredentialStore.kt` | `create` L20 (dup → IllegalStateException) · `get` L26 · `delete` L33 — MUST encrypt SealedResumeToken at rest (L10-12) |
| AuditStore | `tramai-security/.../audit/AuditStore.kt` | `appendNext` L4 · `readStream` L9 · `readStreamPage` L23 · `latestEvent` L41 |
| SuspendedInvocationStore | `tramai-engine/.../SuspendedInvocationStore.kt` | `create(metadata, replayEnvelope)` L155 · `get` L167 · `revealReplayEnvelope` L175 (only after claim) · `remove` L181 — envelope NOT exposed via `get` (L139-142) |
| WorkflowCheckpointStore | `tramai-orchestration/.../WorkflowPersistence.kt` | `load` L73 · `save(checkpoint, expectedRevision)` L77 · `delete` L81 · `requireRecovery` L96 · `clearRecovery` L139 + `WorkflowStateCodec<S>` L63 |
| WorkflowLeaseStore | `tramai-orchestration/.../WorkflowLease.kt` | `currentLease` L34 · `claim` L38 · `renew` L45 · `release` L50 (+ optional `WorkflowLeaseCheckpointFence` L56-72) |

## Files normally changed

- New store implementation class + its TCK runner `<Store>TckTest.kt` in the owning module's `src/test/kotlin`.
- Codecs (JDBC): `JdbcReplayEnvelopeCodec.kt:10-24`, `JdbcAuditPayloadCodec.kt:10-24`, `JdbcContinuationArgumentsCodec.kt` (JdbcApprovalContinuationStore.kt L992-1006), `JdbcOpsAuditOutboxPayloadCodec.kt` (spring starter).
- Spring wiring (if auto-configurable): `@ConditionalOnMissingBean` store bean in `SovereignJdbcPersistenceAutoConfiguration.kt` (store beans L171-285) or `SovereignFilePersistenceAutoConfiguration.kt` (bundle `@Bean(destroyMethod="close")` L82-110), plus the `.imports` registration.
- Ops stores under `tramai-spring-boot-starter-sovereign-ops/.../{lease,outbox}/` (no engine SPI — module tests only).

## NOT changed

- **TCKs themselves** — `tramai-testing` TCKs are independent oracles; never weaken them to make a store pass.
- **SPI contracts** — `ApprovalStore`, `ApprovalContinuationStore`, etc. change only for a `public-api`-classified change, never when adding an implementation.
- **`ReplayEnvelopeValidator` / `ReplayEnvelopeDigestHelper`** — the replay-security invariants are shared; stores re-validate, they do not re-define.
- **Analyzer/baseline** — `config/quality/0.6.0-baseline.json` never changes in the same PR.
- Do not copy behavior from a sibling store; the TCK is authoritative.

## Required tests / TCK

All TCKs in `tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/persistence/`:

| Store | TCK | Enrollment shape |
|---|---|---|
| ApprovalStore | `approval/ApprovalStoreTck.kt` | harness `approval/ApprovalStoreTckHarness.kt:13-18` (`createStore(clock: MutableClock)`, `closeStore`) |
| ApprovalContinuationStore | `approval/continuation/ApprovalContinuationStoreTck.kt` | harness L15-20 |
| AuditStore | `audit/AuditStoreTck.kt` | direct `createStore()` L38 |
| SuspendedInvocationStore | `engine/SuspendedInvocationStoreTck.kt` | direct |
| WorkflowCheckpointStore | `checkpoint/WorkflowCheckpointStoreTck.kt` | direct `createStore()` L32-35 |
| WorkflowLeaseStore | `lease/WorkflowLeaseStoreTck.kt` | direct `createStore(clock: MutableMillisClock)` |
| ChatMemoryStore | `memory/ChatMemoryStoreTck.kt` | harness |

Runner pattern: harness-style (`tramai-persistence-file/src/test/.../FileApprovalStoreTckTest.kt:22,40-60`), direct-style (`tramai-persistence-jdbc/src/test/.../JdbcSuspendedInvocationStoreTckTest.kt:30`), Markdown (`tramai-orchestration/src/test/.../MarkdownWorkflowCheckpointStoreTckTest.kt:18`).

**Enforcement:** `tramai-testing/src/test/kotlin/dev/tramai/testing/StoreEnrollmentScanner.kt:13-98` + per-family `*EnrollmentArchitectureTest.kt` (e.g. `ApprovalStoreTckEnrollmentArchitectureTest.kt:31-71`: pinned runner allowlist + every concrete impl must have a valid runner).

## Compatibility

- **Replay-envelope security:** `SensitiveReplayEnvelope` (`tramai-engine/.../SensitiveReplayEnvelope.kt:21-51`) is opaque (`toString` = `[REDACTED]`), only `revealForResume()` (L29). Store must verify `metadata.replayEnvelopeDigest` at create (`InMemorySuspendedInvocationStore.kt:69-70`, `JdbcSuspendedInvocationStore.kt:140-144`) and re-validate invariants before persist (`JdbcSuspendedInvocationStore.kt:279-290`). Breaking these breaks replay/continuation trust for existing consumers.
- **Codec encrypt-at-rest** is the implementer's seam: key management, algorithm, nonce are owned by the store (JDBC codecs above).
- **Spring override semantics:** `@ConditionalOnMissingBean` means user-provided beans win — a new default store must not override an explicit user bean.
- JDBC stores imply PostgreSQL durability contracts; TCK runners require Docker (Testcontainers).

## Failure / cancellation / lifecycle

- Store methods must **rethrow `CancellationException` unchanged** (`ApprovalStore.kt:9-11`).
- Atomic RMW with optimistic concurrency (`transition(..., expectedVersion, ...)`) — rejected actions must not mutate durable state (`ApprovalStoreTck.kt:567-571`).
- Lifecycle: file stores use exclusive `.tramai.lock` + 0700 dirs + `manifest.json` v1 + verify-on-open (`FileBackedSovereignStores.kt:45-101`); JDBC stores own connection/transaction management; in-memory stores are process-local defaults.

## Verification

```bash
./gradlew :tramai-testing:test                          # runs all *EnrollmentArchitectureTest gates
./gradlew :<module>:test --tests '*TckTest'            # your store's TCK runner
./gradlew verifyPr
./gradlew verifyChangePolicy -PchangeClass=runtime-behaviour
```

## Common mistakes

- Writing a runner that extends nothing / extends a copy of the TCK — enrollment scan fails.
- Exposing the raw replay envelope via `get()` — only `revealReplayEnvelope` after claim.
- Skipping digest verification at create — replay trust breaks.
- Editing a TCK to fit the store — the TCK is the oracle, not the store.
- Adding a Spring bean without `@ConditionalOnMissingBean` — silently overrides user beans.

## Related ADRs / specs

- [ADR-018](../../adr/adr-018.md) — separate security enforcement from SaaS platform concerns
- [spec-007-testing-support.md](../../specs/spec-007-testing-support.md) — testing-support + TCK philosophy
- Module cards: [`tramai-persistence-file.md`](../../modules/tramai-persistence-file.md), [`tramai-persistence-jdbc.md`](../../modules/tramai-persistence-jdbc.md), [`tramai-security.md`](../../modules/tramai-security.md)
