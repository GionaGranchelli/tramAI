# Change Guide: Adding an Approval State

**Applies to:** approval lifecycle in `tramai-core`, `tramai-security`, `tramai-engine`, `tramai-persistence-file`, `tramai-persistence-jdbc`, `tramai-testing`.

## TL;DR

There are **two independent state machines**:

- `ApprovalStatus` — the decision outcome: `PENDING → {APPROVED, DENIED, TIMED_OUT}`; everything except `PENDING` is terminal. `tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalStatus.kt:9-14`
- `ApprovalContinuationStatus` — the resume lifecycle: `PENDING, CLAIMED, COMPLETED, EXPIRED, CANCELLED_UNCERTAIN, CANCELLED`. `tramai-core/.../approval/ApprovalContinuationStatus.kt:3-10`

Transitions are declared by `ApprovalTransition` (sealed `Approve/Deny/Timeout`, each with `targetStatus()`) at `tramai-core/.../approval/ApprovalTransition.kt:9-48`.

**Decision first:** decide which machine the new state belongs to. Adding a decision outcome (`ApprovalStatus`) is a different, much wider change than adding a continuation-lifecycle state (`ApprovalContinuationStatus`). Most real changes are the latter.

## What a new continuation state touches (blast radius)

| Layer | File | What changes |
|---|---|---|
| Enum | `tramai-core/.../approval/ApprovalContinuationStatus.kt` | new constant |
| DTO serialization | `tramai-persistence-file/.../PersistedDtos.kt` | status encoding |
| Store impls | `InMemoryApprovalContinuationStore.kt` (tramai-security), `FileApprovalContinuationStore.kt` (tramai-persistence-file), `JdbcApprovalContinuationStore.kt` (tramai-persistence-jdbc) | transition logic in `claim`, `complete`, `expire`, `cancel`, `forceCancelClaimed` |
| Coordinators | `ApprovalResumeCoordinator.kt:39-61` (status-sensitive branch), `ContinuationClaimService.kt:16` (`validateBindings` requires `status == PENDING`), `ApprovalSuspensionCoordinator.kt:146` (creates hardcoded `PENDING`) | explicit handling of the new state |
| Gate | `DefaultApprovalGateCoordinator.kt:172-180,307-345` (requires `consumed.status == APPROVED`) | authorization path |
| Gateway mapping | `DefaultApprovalGateway.kt:110-153` (`toGatewayResult` — new statuses silently fall into `Suspended`) | explicit mapping |
| Audit emitter | `tramai-security/.../audit/AuditEngineApprovalLifecycleAuditEmitter.kt` | enforcementPoint/decision strings per callback (never emit raw args/tokens — SPI contract `tramai-core/.../approval/ApprovalLifecycleAuditEmitter.kt:9-126`) |
| Lifecycle oracles | `tramai-testing/.../approval/ApprovalLifecycleModel.kt:30-231`, `.../continuation/ApprovalContinuationLifecycleModel.kt:19-275` | transition application + invariant audit — deliberately independent of production code |

## Invariants that must not break

- **Version ceiling ≤ 2** per continuation, field-shape per status: `ApprovalContinuationStoreTck.kt:803-857` (L810).
- **Exactly-once argument release** — raw args are exposed only via `claimForExecution`, once: `ApprovalContinuationStoreTck.kt:283-315`.
- **Expiry boundary:** `now < expiresAt` → decisions legal while PENDING; `now >= expiresAt` → timeout legal, decisions illegal. `ApprovalLifecycleModel.kt:22-27`, `InMemoryApprovalStore.kt:246-269`.
- **Rejected actions must not mutate durable state:** `ApprovalStoreTck.kt:567-571`.
- **Late claim/cancel DO mutate** (PENDING → EXPIRED) before the typed failure: `ApprovalContinuationLifecycleModel.kt:12-17`, TCK L341-366.
- Store methods must rethrow `CancellationException` (`ApprovalStore.kt:9-11`).

## Mandatory contract tests (run before push)

- `ApprovalStoreTck` — `tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/persistence/approval/ApprovalStoreTck.kt` (transition matrix L133-255, consumption/exact-replay L257-450, model-based property 32×32 L532-574, wrong-version matrix L577-608).
- `ApprovalContinuationStoreTck` — `.../continuation/ApprovalContinuationStoreTck.kt` (creation validation L107-218, claim L222-279, exactly-once L283-315, expiry L319-414, cancel L418-468, complete L472-527, recovery L531-634, sweep L638-677, races L701-779, model property L781-857 + L951+).
- Enroll every new store implementation: `InMemoryApprovalStoreTckTest`, `JdbcApprovalStoreTckTest`, `FileApprovalStoreTckTest` + continuation equivalents — enforced by `ApprovalStoreTckEnrollmentArchitectureTest.kt` / `ApprovalContinuationStoreTckEnrollmentArchitectureTest.kt`.
- Generators: extend `ApprovalLifecycleActionGenerator.kt:26-27` / `ApprovalContinuationLifecycleActionGenerator.kt:38-39` (SEED_COUNT=32, ACTIONS_PER_SEQUENCE=32) + unit tests in `tramai-testing/src/test/`.

## Verification commands

```bash
./gradlew verifyPr
./gradlew verifyChangePolicy -PchangeClass=runtime-behaviour
./gradlew :tramai-security:test :tramai-persistence-jdbc:test :tramai-persistence-file:test :tramai-testing:test :tramai-engine:test --tests '*Tck*'
```

**Guardrail:** never edit the analyzer/baseline (`config/quality/0.6.0-baseline.json`) in the same PR; adding an enum constant may shift scanner cardinality — if `verifyChangePolicy` flags it, stop and report rather than weakening the gate.

## Why the TCKs are the safety net

The lifecycle models (`ApprovalLifecycleModel`, `ApprovalContinuationLifecycleModel`) are deliberately independent oracles. Any drift between the new state and production code fails the model-based property tests loudly — the TCKs are the contract, not a documentation exercise.
