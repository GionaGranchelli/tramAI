# Change Guide: Adding an Approval State

## Start here

There are **two independent state machines**. Decide first which one the new state belongs to:

- `ApprovalStatus` — the decision outcome: `PENDING → {APPROVED, DENIED, TIMED_OUT}`; everything except `PENDING` is terminal. `tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalStatus.kt:9-14`
- `ApprovalContinuationStatus` — the resume lifecycle: `PENDING, CLAIMED, COMPLETED, EXPIRED, CANCELLED_UNCERTAIN, CANCELLED`. `tramai-core/.../approval/ApprovalContinuationStatus.kt:3-10`

Adding a decision outcome (`ApprovalStatus`) is a different, much wider change than adding a continuation-lifecycle state (`ApprovalContinuationStatus`). Most real changes are the latter.

Start from [`ARCHITECTURE.md`](../../../ARCHITECTURE.md) (governance-security layer → approval), read [`docs/architecture/human-approval-workflow-ergonomics.md`](../human-approval-workflow-ergonomics.md) and the module card [`tramai-security.md`](../../modules/tramai-security.md), then follow this guide.

## Owning module

- Contracts: `tramai-core/approval` (enums, `ApprovalTransition`, store SPIs, `ApprovalLifecycleAuditEmitter` SPI).
- Store implementations: `tramai-security` (in-memory + gate), `tramai-persistence-file`, `tramai-persistence-jdbc`.
- Coordinators: `tramai-engine/approval`.
- Oracles + TCKs: `tramai-testing`.

## Authoritative contracts

- Transitions: `ApprovalTransition` (sealed `Approve/Deny/Timeout`, each with `targetStatus()`) — `tramai-core/.../approval/ApprovalTransition.kt:9-48`.
- Persistence: `ApprovalStore` (`tramai-core/.../approval/ApprovalStore.kt:13-98`; `create` must be PENDING v0 L18/35-43, `transition(approvalId, expectedVersion, transition)` optimistic-concurrency L45-49, `consumeApprovedOrReplay` strict fresh vs exact-replay L51-92) and `ApprovalContinuationStore` (`tramai-core/.../approval/ApprovalContinuationStore.kt:5-48`; `claimForExecution` is the only path exposing raw args).
- Lifecycle oracles (deliberately independent of production code): `tramai-testing/.../persistence/approval/ApprovalLifecycleModel.kt:30-231`, `.../continuation/ApprovalContinuationLifecycleModel.kt:19-275`.
- Audit SPI: `tramai-core/.../approval/ApprovalLifecycleAuditEmitter.kt:9-126` (never emit raw args/tokens).

## Files normally changed

| Layer | File |
|---|---|
| Enum | `tramai-core/.../approval/ApprovalContinuationStatus.kt` (or `ApprovalStatus.kt`) |
| DTO serialization | `tramai-persistence-file/.../PersistedDtos.kt` |
| Store impls | `InMemoryApprovalContinuationStore.kt` (tramai-security), `FileApprovalContinuationStore.kt` (file), `JdbcApprovalContinuationStore.kt` (jdbc) |
| Coordinators | `ApprovalResumeCoordinator.kt:39-61`, `ContinuationClaimService.kt:16` (`validateBindings` requires `status == PENDING`), `ApprovalSuspensionCoordinator.kt:146` (creates hardcoded `PENDING`) |
| Gate | `DefaultApprovalGateCoordinator.kt:172-180,307-345` (authorization requires `consumed.status == APPROVED`) |
| Gateway mapping | `DefaultApprovalGateway.kt:110-153` (`toGatewayResult` — new statuses silently fall into `Suspended`) |
| Audit emitter | `tramai-security/.../audit/AuditEngineApprovalLifecycleAuditEmitter.kt` (fixed enforcementPoint/decision strings per callback) |
| Lifecycle oracles | `ApprovalLifecycleModel.kt`, `ApprovalContinuationLifecycleModel.kt` |
| TCK invariant tables | `ApprovalStoreTck.kt`, `ApprovalContinuationStoreTck.kt` |
| Action generators | `ApprovalLifecycleActionGenerator.kt:26-27`, `ApprovalContinuationLifecycleActionGenerator.kt:38-39` |

## NOT changed

- **TCKs are oracles** — do not weaken them; they fail loudly on drift by design.
- **Expiry/invariant semantics** — `now < expiresAt` decision-legal vs `now >= expiresAt` timeout-legal while PENDING (`ApprovalLifecycleModel.kt:22-27`, `InMemoryApprovalStore.kt:246-269`) stays as-is unless the new state explicitly needs new expiry rules (that is a contract change).
- **Replay envelope security** — `ReplayEnvelopeFactory`/`ReplayEnvelopeValidator` stay untouched by a state addition.
- **Analyzer/baseline** — same-PR edits forbidden; adding an enum constant may shift scanner cardinality — if `verifyChangePolicy` flags it, stop and report.

## Required tests / TCK

- `ApprovalStoreTck` — `tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/persistence/approval/ApprovalStoreTck.kt:37-866` (transition matrix L133-255, consumption/exact-replay L257-450, model-based property 32×32 L532-574, wrong-version matrix L577-608, 8-way race L611-646).
- `ApprovalContinuationStoreTck` — `.../continuation/ApprovalContinuationStoreTck.kt:39-1382` (claim L222-279, exactly-once L283-315, expiry L319-414, cancel L418-468, complete L472-527, recovery L531-634, sweep L638-677, races L701-779, model property L781-857 + L951+).
- Enrollment: `InMemoryApprovalStoreTckTest`, `JdbcApprovalStoreTckTest`, `FileApprovalStoreTckTest` + continuation equivalents, enforced by `ApprovalStoreTckEnrollmentArchitectureTest.kt` / `ApprovalContinuationStoreTckEnrollmentArchitectureTest.kt`.
- Generators + their unit tests in `tramai-testing/src/test/.../`.

## Compatibility

- **Version ceiling ≤ 2** per continuation with field-shape per status: `ApprovalContinuationStoreTck.kt:803-857` (L810 `version ≤ 2`). A new state that increments version breaks existing stored records.
- **Exactly-once argument release** — raw args are exposed once via claim: `ApprovalContinuationStoreTck.kt:283-315`.
- **Outcome mapping `when`s** — `DefaultApprovalGateway.toGatewayResult` L110-153 and the gate coordinator's APPROVED checks silently treat unknown statuses as `Suspended`/denied; new states need explicit mapping or authorization fails.
- **Audit event strings** are consumed by external tooling — the fixed enforcementPoint/decision strings in `AuditEngineApprovalLifecycleAuditEmitter.kt` are a compatibility surface.

## Failure / cancellation / lifecycle

- Store methods rethrow `CancellationException` (`ApprovalStore.kt:9-11`).
- Rejected actions must not mutate durable state (`ApprovalStoreTck.kt:567-571`); late claim/cancel DO mutate (PENDING→EXPIRED) before typed failure (`ApprovalContinuationLifecycleModel.kt:12-17`, TCK L341-366).
- Lifecycle: `ApprovalSuspensionCoordinator` creates continuations hardcoded `PENDING` (L146); new pre-claim states break `ContinuationClaimService.validateBindings` (L16 requires `PENDING`) until it is relaxed deliberately.

## Verification

```bash
./gradlew verifyPr
./gradlew verifyChangePolicy -PchangeClass=runtime-behaviour
./gradlew :tramai-security:test :tramai-persistence-jdbc:test :tramai-persistence-file:test :tramai-testing:test :tramai-engine:test --tests '*Tck*'
```

## Common mistakes

- Adding a state to one store impl and not the other three — all four impl layers + DTOs must change together.
- Breaking the version ceiling — stored records fail `version ≤ 2` assertions.
- Letting a new status fall through `toGatewayResult` — it silently maps to `Suspended`.
- Editing the lifecycle oracles to match the code — the oracles are the independent spec; code must match them.

## Related ADRs / specs

- [ADR-018](../../adr/adr-018.md) — separate security enforcement from SaaS platform concerns
- [`docs/architecture/human-approval-workflow-ergonomics.md`](../human-approval-workflow-ergonomics.md) — golden-path guide (documents `ApprovalStatus`-shaped outcomes; not continuation transitions)
- Module card [`tramai-security.md`](../../modules/tramai-security.md)
