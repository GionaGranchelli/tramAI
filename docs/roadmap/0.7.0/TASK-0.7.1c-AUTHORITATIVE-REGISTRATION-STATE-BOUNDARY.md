# Task 0.7.1c — Authoritative Registration / State Boundary

**Parent:** [Epic 0.7.1 — Control-Plane Authority & Workload Identity](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md)

**Branch:** `task/0.7.1c-authoritative-registration` → `epic/0.7.1-control-plane-authority`

**Baseline:** epic head `4434b2fa` (0.7.1b identity vocabulary merged)

**Change class:** `public-api` (additive), plus new-module registration (`settings.gradle.kts`, `module-catalog.yml`)

**Status:** Implemented; pending exact-head certification.

## Decision

Introduce exactly one authoritative workload-registration boundary that binds
the immutable 0.7.1b identity to safe metadata, the governed-configuration
revision, lifecycle state and a monotonic mutable-state version, with durable
atomic storage semantics — and no runtime propagation or REST API yet.

New published module `tramai-control-plane` (depends only on `tramai-core`)
owns the contract and the rules. The JDBC durable implementation lives in the
existing `tramai-persistence-jdbc` (persistence adapters depend TOWARD the
authority contract, never the reverse). `tramai-server`, `tramai-platform`,
Spring, Jackson, the dashboard and JDBC are all absent from the new module.

## Contract

- `RegisteredWorkload` — immutable portion: `WorkloadDeploymentIdentity` +
  `ConfigurationFingerprint`; mutable authoritative portion: `WorkloadMetadata`,
  `WorkloadLifecycleState`, `WorkloadStateVersion`. State version is never part
  of any identity type: a state update cannot change the identity of
  historical runs.
- `ConfigurationFingerprint` — opaque, deterministic witness over the complete
  governed configuration; opaque to the store. Enforces the invariant 0.7.1b
  deferred: `(configurationId, version)` can never be rebound to a different
  fingerprint, from ANY deployment scope (database-level authority via the
  `tramai_configuration_revision` primary key).
- `WorkloadLifecycleState` — ACTIVE / SUSPENDED / RETIRED only; RETIRED is
  terminal; suspending never cancels running workflows (registration lifecycle
  and run lifecycle are separate authorities).
- `WorkloadStateVersion` — starts at 1; every successful authoritative mutation
  advances with overflow-safe `addExact`; reads, rejected mutations, stale CAS
  and idempotent re-registration leave it unchanged.
- `WorkloadRegistrationAuthority` owns every rule — registration is NOT an
  upsert, configuration revisions cannot be rebound, one deployment scope has
  one registration, metadata changes preserve identity, stale writers lose
  (version-guarded compare-and-set).
- Store boundary `WorkloadRegistrationStore` (`find` / atomic `create` /
  atomic `compareAndSet`) implemented by `InMemoryWorkloadRegistrationStore`
  (reference) and `JdbcWorkloadRegistrationStore` (durable, Postgres
  migrations `V8__control_plane_workload_registration.sql`).

## Shared contract test

The same store contract runs against both implementations via
`WorkloadRegistrationStoreTck` (testFixtures of `tramai-control-plane`):
17 contract cases per store, including the same-environment/different-deployment
invariant, global configuration-rebinding rejection, stale-CAS rejection and a
real concurrency race (5 iterations, ready/release handshake on
`Dispatchers.Default`). JDBC runners execute the full migration chain
V1→V8 against `postgres:17-alpine`; a second suite proves durability across
store instances (restart), cross-instance rebinding rejection and DB-level
atomic create races.

## Non-goals

0.7.1c does NOT: propagate identity into `WorkflowContext`/engine/approval/
evidence/scheduler (0.7.1d); add REST query/mutation APIs, If-Match/ETag or
projection consistency classes (0.7.1e); define safe exposure categories
(0.7.1f); replace `WorkflowRegistry` executable-definition lookup; cancel runs;
or add arbitrary metadata maps.

## Verification

- `./gradlew :tramai-control-plane:test` — passed (26 authority-rule tests,
  17 InMemory TCK cases).
- `./gradlew :tramai-persistence-jdbc:test --tests "*WorkloadRegistration*"` —
  passed (17 JDBC TCK cases + 5 JDBC-specific tests).
- `./gradlew :tramai-control-plane:apiDump :tramai-persistence-jdbc:apiDump` —
  additive.
- `./gradlew spotlessApply` / `spotlessCheck` — passed.
- `./gradlew verifyPr -PchangeClass=public-api` — see completion report.

## Mutation expectations

- remove fingerprint/whitespace/length validation → `ConfigurationFingerprint`
  constructor tests;
- ignore deployment scope or configuration in idempotency/conflict
  classification → TCK + authority tests;
- allow register to silently overwrite an existing registration → `re-register
  ... is rejected, not upserted` + JDBC `registration is never silently
  replaced`;
- allow a `(configurationId, version)` pair to rebind to another fingerprint →
  global rebinding tests (both stores);
- permit RETIRED resurrection → lifecycle terminal tests;
- drop the state-version guard → stale CAS + concurrent-writer tests.

Full Epic adversarial/mutation certification remains assigned to 0.7.1g.

## Evidence index

- `tramai-control-plane/src/main/kotlin/dev/tramai/controlplane/` — authority
  contract and InMemory store (see module doc).
- `tramai-control-plane/src/testFixtures/kotlin/dev/tramai/controlplane/testing/`
  — shared `WorkloadRegistrationStoreTck` + fixtures + harness.
- `tramai-persistence-jdbc/src/main/kotlin/dev/tramai/persistence/jdbc/JdbcWorkloadRegistrationStore.kt`
- `tramai-persistence-jdbc/src/main/resources/.../postgres/V8__control_plane_workload_registration.sql`

## Candidate definition of done

0.7.1c is complete when TramAI can durably answer "what workload deployment is
authoritatively registered, against which governed configuration revision,
with what lifecycle/metadata and state version?" — and two concurrent or
conflicting writers cannot silently change that answer.
