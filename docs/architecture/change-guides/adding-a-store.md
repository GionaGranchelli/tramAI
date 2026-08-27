# Change Guide: Adding a Store

**Applies to:** persistence store implementations for approval, continuation, audit, suspended-invocation, workflow checkpoint/lease, chat-memory, and ops outbox stores.

## TL;DR

Every durable store implements a **core SPI** and proves conformance by enrolling in the matching **shared TCK** in `tramai-testing` — enrollment is architecture-enforced, so a store without its TCK runner fails the build. The TCK is the contract, not a documentation exercise.

## 1. Pick the SPI (interface + required methods)

| Store | SPI file | Required methods |
|---|---|---|
| ApprovalStore | `tramai-core/.../approval/ApprovalStore.kt` | `create` L22 · `get` L30 · `transition(approvalId, expectedVersion, transition)` L45 · `consumeApprovedOrReplay` L87 (thread-safe, atomic RMW; rethrow `CancellationException`, L6-11) |
| ApprovalContinuationStore | `tramai-core/.../approval/ApprovalContinuationStore.kt` | `create(continuation, arguments)` L6 · `get` L11 · `claimForExecution` L13 (only path exposing raw args) · `complete` L19 · `expire` L25 · `cancel` L30 · `findStaleClaimed` L35 · `forceCancelClaimed` L40 · `sweepExpired` L47 |
| ApprovalResumeCredentialStore | `tramai-core/.../approval/gateway/ApprovalResumeCredentialStore.kt` | `create` L20 (dup → IllegalStateException) · `get` L26 · `delete` L33 — MUST encrypt SealedResumeToken at rest (L10-12); **no shared TCK** (module tests only) |
| AuditStore | `tramai-security/.../audit/AuditStore.kt` | `appendNext` L4 · `readStream` L9 · `readStreamPage` L23 · `latestEvent` L41 |
| SuspendedInvocationStore | `tramai-engine/.../SuspendedInvocationStore.kt` | `create(metadata, replayEnvelope)` L155 · `get` L167 · `revealReplayEnvelope` L175 (only after claim) · `remove` L181 — envelope NOT exposed via `get` (L139-142) |
| WorkflowCheckpointStore | `tramai-orchestration/.../WorkflowPersistence.kt` | `load` L73 · `save(checkpoint, expectedRevision)` L77 · `delete` L81 · `requireRecovery` L96 · `clearRecovery` L139 + `WorkflowStateCodec<S>` L63 |
| WorkflowLeaseStore | `tramai-orchestration/.../WorkflowLease.kt` | `currentLease` L34 · `claim` L38 · `renew` L45 · `release` L50 (+ optional `WorkflowLeaseCheckpointFence` L56-72) |

Ops-level stores (sovereign starter, no engine SPI): `SovereignOpsWorkerLeaseStore`, `SovereignOpsAuditOutboxStore`, `SovereignOpsApprovalMutationStore`, `SovereignOpsApprovalRequestMutationStore`, `ApprovedContinuationResumeQueueStatusStore`, `ApprovedContinuationResumeWorkerStatusStore` — under `tramai-spring-boot-starter-sovereign-ops/.../{lease,outbox}/`.

## 2. Enroll in the shared TCK (mandatory, architecture-enforced)

All TCKs live in `tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/persistence/`:

| Store | TCK | Enrollment shape |
|---|---|---|
| ApprovalStore | `approval/ApprovalStoreTck.kt` | harness `approval/ApprovalStoreTckHarness.kt:13-18` (`createStore(clock: MutableClock)`, `closeStore`) |
| ApprovalContinuationStore | `approval/continuation/ApprovalContinuationStoreTck.kt` | harness L15-20 |
| AuditStore | `audit/AuditStoreTck.kt` | direct `createStore()` L38 |
| SuspendedInvocationStore | `engine/SuspendedInvocationStoreTck.kt` | direct |
| WorkflowCheckpointStore | `checkpoint/WorkflowCheckpointStoreTck.kt` | direct `createStore()` L32-35 |
| WorkflowLeaseStore | `lease/WorkflowLeaseStoreTck.kt` | direct `createStore(clock: MutableMillisClock)` |
| ChatMemoryStore | `memory/ChatMemoryStoreTck.kt` | harness |

Runner pattern: `<Store>TckTest.kt` in the **same module's** `src/test/kotlin` extending the shared TCK — harness-style: `tramai-persistence-file/src/test/.../FileApprovalStoreTckTest.kt:22,40-60`; direct-style: `tramai-persistence-jdbc/src/test/.../JdbcSuspendedInvocationStoreTckTest.kt:30`; `tramai-orchestration/src/test/.../MarkdownWorkflowCheckpointStoreTckTest.kt:18`.

**Enforcement:** `tramai-testing/src/test/kotlin/dev/tramai/testing/StoreEnrollmentScanner.kt:13-98` + per-family `*EnrollmentArchitectureTest.kt` (e.g. `ApprovalStoreTckEnrollmentArchitectureTest.kt:31-71`: pinned runner allowlist + every concrete impl must have a valid runner). Families: ApprovalStore, ApprovalContinuationStore, AuditStore, SuspendedInvocationStore, WorkflowCheckpointStore, WorkflowLeaseStore, ChatMemoryStore, SovereignOpsAuditOutboxStore.

## 3. Copy the right existing implementation as a template

- **In-memory** (process-local default): `tramai-security/.../approval/InMemoryApprovalStore.kt`, `InMemoryAuditStore.kt`; `tramai-engine/.../InMemorySuspendedInvocationStore.kt`; `tramai-orchestration/.../InMemoryWorkflowCheckpointStore.kt` (WorkflowPersistence.kt L304), `InMemoryWorkflowLeaseStore.kt` (WorkflowLease.kt L126).
- **Encrypted file** (restart durability): `tramai-persistence-file/.../FileApprovalStore.kt` etc., bundled in `FileBackedSovereignStores.kt:45-101` (exclusive `.tramai.lock`, 0700 dirs, `manifest.json` v1, per-record locks, verify-on-open).
- **JDBC** (PostgreSQL durability): `tramai-persistence-jdbc/.../JdbcApprovalStore.kt`, `JdbcWorkflowCheckpointStore.kt` (orchestration), `JdbcStepAttemptRecordStore.kt` (orchestration), `JdbcWorkflowSchedulerStore.kt` (scheduler); spring-starter JDBC variants (`JdbcSovereignOps*`, `JdbcApprovalResumeCredentialStore`). JDBC TCK runners use Testcontainers PostgreSQL.
- **Markdown** (format ≠ contract): `MarkdownWorkflowCheckpointStore.kt` — deliberately omits `WorkflowCheckpointCatalog` (`WorkflowCheckpointStoreTck.kt:28-30`).

## 4. Replay envelope / codec obligations (suspended-invocation stores)

- `SensitiveReplayEnvelope` (`tramai-engine/.../SensitiveReplayEnvelope.kt:21-51`): opaque, deep-copied on `of()`, `toString` = `[REDACTED]`, only `revealForResume()` (L29). Raw suspended tool args replaced by sentinel `__redacted_approval_continuation_args__` (`ReplayEnvelopeFactory.kt:13`).
- `ReplayEnvelopeFactory.prepareForSuspension` (L41-61): fail-closed validation (unique slot, index/name match, exactly one sentinel), digest from exact redacted snapshot.
- `ReplayEnvelopeDigestHelper.compute` (L17-36): canonical SHA-256 via `CanonicalMessageEncoder` — store must verify `metadata.replayEnvelopeDigest` at create (`InMemorySuspendedInvocationStore.kt:69-70`, `JdbcSuspendedInvocationStore.kt:140-144`) and re-validate invariants before persist (`JdbcSuspendedInvocationStore.kt:279-290`).
- JDBC codec seams (encrypt-at-rest, you own key mgmt/algorithm/nonce): `JdbcReplayEnvelopeCodec.kt:10-24`, `JdbcAuditPayloadCodec.kt:10-24`, `JdbcContinuationArgumentsCodec.kt` (JdbcApprovalContinuationStore.kt L992-1006), `JdbcOpsAuditOutboxPayloadCodec.kt` (spring starter).

## 5. Spring wiring (if the store must be auto-configurable)

- Persistence starters register **before** the base starter so in-memory defaults back off: `@ConditionalOnMissingBean` in `tramai-spring-sovereign/.../SovereignTramaiAutoConfiguration.kt` L60, store defaults L118-139.
- JDBC pattern: `SovereignJdbcPersistenceAutoConfiguration.kt` — `@AutoConfiguration(before = SovereignTramaiAutoConfiguration)` L85, gated `tramai.sovereign.persistence.type=jdbc` L87-91, fail-fast without DataSource L104-110, AES key bean L120-127, codec beans L134-164, store beans L171-285 (all `@ConditionalOnMissingBean`, key injection `@Qualifier("sovereignJdbcEncryptionKey")`). Register via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- File pattern: `SovereignFilePersistenceAutoConfiguration.kt` (`type=file` L66-70, `FileBackedSovereignStores` bundle `@Bean(destroyMethod="close")` L82-110).

## 6. Mandatory verification

```bash
./gradlew :tramai-testing:test                          # runs all *EnrollmentArchitectureTest gates
./gradlew :<module>:test --tests '*TckTest'            # your store's TCK runner
./gradlew verifyPr
./gradlew verifyChangePolicy -PchangeClass=runtime-behaviour
```

JDBC TCK runners require Docker (Testcontainers PostgreSQL).

**Guardrails:** the TCKs are independent oracles — do not weaken them to make a store pass; do not copy behavior from a sibling store, the TCK is authoritative; never edit analyzer/baseline in the same PR. Stores without a shared TCK (`ApprovalResumeCredentialStore`, `SovereignOpsWorkerLeaseStore`) must still be covered by module-level contract tests.
