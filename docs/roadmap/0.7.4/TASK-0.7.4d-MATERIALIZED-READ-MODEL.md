# TASK-0.7.4d — Durable, rebuildable governance read model

Status: **PHASE 0 COMPLETE — baseline and storage-boundary decision recorded.** Implementation not started.

- Base commit: `052cc27fb9979a4b5fb467d06c6eed3ba87176d3` (`release/0.7.0` tip, the 0.7.4c3 squash of PR #534)
- Task branch: `task/0.7.4d-materialized-governance-read-model`, worktree `/home/gionag/tramai-d1`
- Change class: `runtime-behaviour`
- Predecessor: PR #534 — 0.7.4c3, **QUALIFIED — VERIFIED COVERAGE SEMANTICS**

## 1. Baseline, measured at the base commit

| Check | Command | Result |
| --- | --- | --- |
| Release containment | `git merge-base --is-ancestor 052cc27f origin/release/0.7.0` | VERIFIED: the tip *is* `052cc27f`, so PR #534 is the head of the release line |
| Governance projection suite | `:tramai-security:test --tests 'dev.tramai.security.governance.*'` | VERIFIED: **207 tests, 0 failures** (14 result files) |
| Durable audit store, real backend | `:tramai-persistence-jdbc:test --tests '*JdbcAuditStoreTckTest*'` | VERIFIED: **43 tests, 0 failures**, 31s wall, real PostgreSQL through Testcontainers |

That second row is the environment probe for this task and not a formality: it establishes that a real
JDBC backend is available here, so "durable" is testable rather than assertable. Docker is present
(`docker ps` returns), and `tramai-memory-store` already declares Testcontainers in test scope — a
non-persistence module running real JDBC tests is established practice in this repository.

## 2. Existing projection semantics (what may be materialized)

All of the following are `internal` to `:tramai-security`, package `dev.tramai.security.governance`:

- `GovernanceProjection` — `registration: ProjectedFact<WorkloadExposure>`,
  `declaredRunIdentity: ProjectedFact<DeclaredRunIdentity>`, `decisions: List<DecisionProjection>`,
  `telemetry: ProjectedFact<List<TelemetryObservation>>`. Its constructor refuses a duplicated decision
  family, a `provider.route` record promoted above source-declared, and an authorization record
  promoted to authoritative runtime state.
- `DecisionProjection` — one family: `latest: ProjectedFact<RecordedDecision>`, `completeness`,
  `verifiedStreamRange`, `duplicatesIgnored`, `staleIgnored`. `COMPLETE` requires the range.
- `ProjectedFact<T>` — `value`, `authority`, `freshness`, `established`, `mustNotInfer`,
  `observedVersion: WorkloadStateVersion?`, `sourceVersion: Long?`, `watermark: Long?`. Its constructor
  is the authority model: absence only as no-producer/unobserved/owning-authority; currency only at
  the owning authority's version; a lag claim requires a watermark; a declared or recorded fact must
  state what may not be inferred.
- `StreamCoverage` — `verifiedRange`, `accountedPositions`, `unrecordedDecisionPositions`,
  `unclassifiedPositions`. The raw-stream claim.
- `RecordedDecision` — deliberately narrow: run id, kind, decision, safe reason code, declared
  boundary, source position. **No prompt, provider, model, credential, tool argument or result field
  exists**, so none can reach a persisted or queried surface.
- `Completeness`, `FactAuthority`, `FactFreshness`, `DecisionKind`.

Two gaps against the materialization requirement, established by reading the types rather than assumed:

1. **`observedHeadHash` is not in the contract.** The adapter anchors the range to the observed head's
   position *and* hash, then discards the hash. A persisted snapshot that must detect "different hash
   at the same purported source position" needs it carried out of the read.
2. **The raw coverage buckets do not reach the projection.** `DecisionProjection` exposes
   `verifiedStreamRange` (raw) and `completeness` (decision) but not `accountedPositions`,
   `unrecordedDecisionPositions` or `unclassifiedPositions`, and there is no
   classification-contract revision anywhere.

Both are additive refinements of internal types, and Phase 1 must make them before anything is
persisted — a snapshot cannot preserve what the read never carried.

## 3. Source and storage ownership

- **Registration authority**: `WorkloadRegistrationAuthority` + `WorkloadControlPlaneQueries`
  (`:tramai-control-plane`), whose durable form is `JdbcWorkloadRegistrationStore` (migration
  `V8__control_plane_workload_registration.sql`). Facts read from it are
  `AUTHORITATIVE_PERSISTED` and carry `WorkloadStateVersion`.
- **Audit evidence**: `AuditStore` (SPI in `:tramai-security`, public) with
  `InMemoryAuditStore`, file-backed and `JdbcAuditStore` implementations, hash-chained, positions from
  1, `latestEvent` for the head. Migrations `V1`, `V3` under
  `tramai-persistence-jdbc/src/main/resources/tramai/persistence/jdbc/postgres/`.
- **The projection** (`GovernanceProjectionReader` + `GovernanceDecisionFold` +
  `AuditBackedGovernanceDecisionFactSource`) reads both and is read-only: it can state what a source
  established and nothing more.
- **Migration mechanism**: Flyway, with versioned `V<n>__*.sql`. Two precedents exist for *where* the
  SQL lives: the persistence modules' own resource dirs, and `tramai-platform`'s
  `src/main/resources/db/migration/V1__platform.sql` — a non-persistence module shipping its own
  migration directory.

## 4. Chosen storage boundary, and why the alternatives are illegal

**Chosen: the durable read model lives in `:tramai-security`, internal, JDBC through the JDK
(`java.sql` / `javax.sql.DataSource` supplied by the caller), with its own migration resource and its
own real-PostgreSQL tests.**

Dependency legality, from the catalog and the boundary rules rather than from intuition:

| Candidate | Verdict | The rule that decides it |
| --- | --- | --- |
| Durable store in `:tramai-persistence-jdbc` | **ILLEGAL as designed** | It cannot see the projection: the types are `internal` to `:tramai-security`. Making them public is a published-module API change — the task forbids making internals public to solve dependency access, and `tramai-security/api/tramai-security.api` would change, which the stop-and-report list calls an unapproved ABI change. |
| `:tramai-security` depends on `:tramai-persistence-jdbc` | **ILLEGAL** | Cycle. `tramai-persistence-jdbc/build.gradle.kts:13` already declares `api(project(":tramai-security"))`; `module-boundaries.yml` states cycles are detected programmatically by the verifier. |
| New module in the persistence layer | **ILLEGAL for the same reason as row 1** | It would have to name `internal` projection types. |
| Coverage semantics redefined in `:tramai-control-plane` so persistence can see them | **REJECTED** | `core-contracts → governance-security` is a forbidden edge, and 0.7.4c3 already settled this: a second model of the same governance decision is the thing the epic forbids. |
| Store in `:tramai-security` speaking JDBC | **LEGAL** | No new module edge (the JDK types it needs are not module dependencies), no cycle, no public API change, and `adding-a-store.md` allows implementations in "the module that will own the new backend". |

Precedent for the chosen boundary, verified in the tree: `tramai-memory-store` ships
`JdbcChatMemoryStore.kt` in main sources implementing an SPI owned elsewhere;
`tramai-scheduler` ships `JdbcWorkflowSchedulerStore.kt` and `JdbcGovernedScheduleBindingStore.kt`;
`tramai-platform` ships `JdbcRepositories.kt` plus its own `db/migration/`. All are outside the
persistence layer, and none required a public API change to do it.

**Consequence, stated plainly:** the materialized read model is not reachable by
`:tramai-persistence-jdbc` consumers. It is an internal, composition-wired capability of the
governance module, which is what 0.7.4e's headless query API will surface. This is the narrow boundary
the task asks for, not a compromise.

## 5. Transaction and concurrency guarantees actually available

- **Atomic publish**: a single `INSERT ... ON CONFLICT ... DO UPDATE` statement. One statement is
  atomic without an explicit transaction, so an interrupted write leaves the previous snapshot intact
  rather than a half-written one. This is the crash-safety mechanism; no separate recovery log is
  needed.
- **Monotonicity**: the update is conditional on the candidate's watermark being strictly greater than
  the stored one (`WHERE excluded.watermark > t.watermark`). A stale candidate that finishes later
  therefore cannot overwrite a newer snapshot — the rejection is in the storage contract, not in the
  caller's ordering.
- **Position/hash conflict**: a candidate at the same source position with a different head hash is a
  conflict, refused explicitly rather than resolved by arrival order. Divergent evidence at one
  position is not something a read model may paper over.
- **Concurrent writers**: safe by the same conditional update; the loser observes zero rows affected
  and re-reads to report the winner. No lock, no advisory lock, no background refresher.
- **Isolation**: PostgreSQL's default READ COMMITTED is sufficient for a single-statement conditional
  upsert. Nothing here needs SERIALIZABLE, and claiming a stronger guarantee than the statement
  provides would be exactly the kind of unsupported authority claim this epic exists to prevent.
- **Not available, and therefore not claimed**: an atomic cross-store snapshot of registration *and*
  audit together. The sources are read independently, so the snapshot records their versions
  separately and never asserts a globally consistent point.

## 6. Missing prerequisites, and how each is resolved

| Prerequisite | State | Resolution |
| --- | --- | --- |
| `observedHeadHash` in the projection | missing | Phase 1 adds it to the internal coverage type |
| Raw coverage buckets on `DecisionProjection` | missing | Phase 1 exposes the coverage alongside completeness |
| Classification-contract revision | missing | Phase 1 introduces a revision constant carried in the snapshot; a mismatch invalidates the decision-coverage claim only |
| Durable-backend tests in `:tramai-security` | none today | test-scope Testcontainers + PostgreSQL driver, as `tramai-memory-store` already does |
| A JDBC environment | available | verified by the 43/0 `JdbcAuditStore` TCK run above |

## 7. Phases

- [x] Phase 0 — baseline and storage-boundary decision (this section)
- [ ] Phase 1 — materialization contract
- [ ] Phase 2 — durable storage
- [ ] Phase 3 — deterministic materialization and rebuild
- [ ] Phase 4 — real durable-backend qualification
- [ ] Phase 5 — adversarial battery
- [ ] Phase 6 — verification and evidence

## 8. Non-claims carried from earlier slices

Unchanged and still unresolved: T1 workload-origin authentication; event-origin authenticity (S19);
live `provider.route` production (`TASK-0.7.4b7`); bundle-backed decision reads; non-decision
recognition by producer vocabulary rather than an authenticated family marker; `JdbcAuditStore`
end-to-end coverage in the governance path. No baseline regeneration, suppression, deviation increase,
API workaround or architecture-rule weakening is part of this task.
