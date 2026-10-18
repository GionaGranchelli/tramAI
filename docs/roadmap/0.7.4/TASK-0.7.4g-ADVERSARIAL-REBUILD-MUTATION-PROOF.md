# TASK 0.7.4g — Adversarial Rebuild and Mutation Proof

Epic 0.7.4. Status: Phase 0 complete (inventory and frozen acceptance matrix). Qualification phases in progress.

This milestone adds no governance functionality. It establishes that the pipeline built by 0.7.4a–f cannot make a
governance claim stronger than the authority of its sources, under hostile, inconsistent, stale and corrupted
conditions. The core invariant:

> A rebuild may reproduce established governance evidence, but it must never repair away corruption, invent
> missing facts, or strengthen authority.

## 1. Baseline

| Item | Value |
|---|---|
| Integration branch | `release/0.7.0` |
| Verified base SHA | `33a1830f4edd9604ac40f6ac4e5676d20b01c616` |
| Divergence from the mission's stated dependency | none — this is exactly the 0.7.4f merge (`Merge pull request #537`) |
| Working branch | `task/0.7.4g-adversarial-rebuild-mutation-proof` |
| Worktree | isolated from the base revision, clean tree at start |
| Gradle | 9.0.0, all work under JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`); CI pins JDK 25 |
| Database | real PostgreSQL via Testcontainers `postgres:17-alpine`; Docker 29.6.1 available |
| Mutation tooling | PIT is *not* applied by any build script; see §6 |

## 2. Production entry points (inventoried, not assumed)

Read paths and where the invariants actually live:

| Boundary | File | What it owns |
|---|---|---|
| Source read (registration) | `tramai-security/.../governance/GovernanceProjectionReader.kt` | reads the authoritative registration and decision sources |
| Source read (audit) | `.../AuditBackedGovernanceDecisionFactSource.kt` | paginated audit read, hash-chain verification, range attestation |
| Folding | `.../GovernanceDecisionFold.kt` | dedupe by identity, elect by `sourceSequence`, absence classification, completeness carried from the read |
| Snapshot | `.../GovernanceProjectionSnapshot.kt` | canonical form and semantic digest |
| Publication | `.../JdbcGovernanceProjectionStore.kt` | transactional publish, monotonic ordering, corruption refusal |
| Materialization | `.../GovernanceProjectionMaterializer.kt` | read → fold → validate → store; a rebuild is the same operation |
| Query | `.../StoreBackedGovernanceQueryService.kt`, `query/GovernanceQueryContract.kt` | read-only query port, result states, public reason-code policy |
| Safe exposure | `tramai-observability/.../governance/GovernanceExposure.kt` | allowlist of seven fields; no payload channel exists |
| OTEL export | `tramai-observability/.../governance/GovernanceTelemetryExporter.kt` | events built through `RuntimeEvent.of`, so the catalogue validates them |

Ordering, format and identity are enforced **inside one SQL statement** (`JdbcGovernanceProjectionStore.kt:592-626`):
`ON CONFLICT ... WHERE format_version = EXCLUDED.format_version AND <five identity columns> AND (audit_head_position,
registration_version) > (stored)`. A stale candidate cannot replace a newer row under a race, and an unsupported
stored format is never replaced by a newer candidate. This is why M04 and M07 are expected to be detectable at the
storage boundary rather than by a check-then-act guard.

## 3. Frozen acceptance matrix

Scenario → authoritative source → production boundary → existing positive proof → existing negative discriminator →
gap. "Existing proof" names the test that actually discriminates, not the closest test by name.

### Invariants

| ID | Invariant | Preserving boundary | Status at base |
|---|---|---|---|
| I1 | No authority amplification | `GovernanceFactClassification`, snapshot constructor guards, exposure mapper | partially proven (§4 Q2/E2) |
| I2 | Deterministic reconstruction | materializer (no incremental path), semantic digest, store ordering | proven (R1) |
| I3 | Corruption is not repairable by rebuild | `requireStoredIdentity`, digest self-check, conditional upsert | proven for identity/digest/format; gap for provenance |
| I4 | No inferred completeness or freshness | `GovernanceDecisionFold`, `MaterializedReadModelQualificationTest` currency | proven (Q3/Q4) |
| I5 | Observation is not enforcement | `RuntimeBoundaryProvenance`, `BOUNDARY_ORIGIN_LIMIT`, XR1 tests | proven (E4) |
| I6 | No protected-payload exposure | allowlist in `GovernanceExposure`, closed 10-key catalogue | proven structurally; gaps in canary coverage |
| I7 | Queries and rebuilds cannot execute governance operations | query port surface, read-only load | gap: no database-level zero-write proof |

### Scenario status

| Scenario | Existing status | Gap | Action |
|---|---|---|---|
| R1 deterministic rebuild + idempotence | ADEQUATE — `GovernanceProjectionMaterializerTest.kt:84,106`, `MaterializedReadModelQualificationTest.kt:232`, real PostgreSQL | none | no new test |
| R2 duplicate / reordered evidence | ADEQUATE at fold level (`GovernanceProjectionTest.kt:231,245`) | conflicting content at the **same** source position unproven | add discriminator |
| R3 source tampering | ADEQUATE — broken chain, foreign-run, wrong stream, head moved mid-pagination (`AuditBackedGovernanceDecisionFactSourceTest.kt:115,152,206,244,265,396`) | in-memory audit store only, no `JdbcAuditStore` | record as a stated limit |
| R4 publication races | ADEQUATE — stale-after-newer, equal-ordering conflict, concurrent publishers, publish during read (`JdbcGovernanceProjectionStoreTest.kt:176,190,234,494`), real PostgreSQL | none | no new test |
| R5 interrupted rebuild | PARTIAL — failure during publication rolls back (`:270`) | **failure after commit before ack**, and **abrupt process termination**, unproven | add discriminator |
| Q1 corrupt stored identity | ADEQUATE — `GovernanceProjectionIdentityIntegrityTest.kt:92,109,126`, `GovernanceQueryServicePostgresTest.kt:221` | none | no new test |
| Q2 corrupt stored content | PARTIAL — digest, decision rows, family population, format all proven (`JdbcGovernanceProjectionStoreTest.kt:292,303,324,348,559`) | **provenance tampering** (`audit_head_position`, `audit_head_hash`, `registration_version`) only inferred from the digest | add discriminator |
| Q3 missing sources | PARTIAL — currency UNKNOWN, no-fact absence authority (`GovernanceQueryServicePostgresTest.kt:377,538`) | **registration unavailable, audit unavailable, infrastructure failure** — no source is ever made to throw | add discriminator |
| Q4 coverage independence | ADEQUATE — `GovernanceProjectionMaterializerTest.kt:159`, `JdbcGovernanceProjectionStoreTest.kt:110`, restart qualification | none | no new test |
| Q5 read-only | PARTIAL — surface-only assertions (`GovernanceQueryServicePostgresTest.kt:674`) | no before/after row counts or statement spy; no provider/tool/approval/workflow assertion on the query path | add discriminator |
| E1 protected-data canaries | PARTIAL — real canary only on `decision`; reason-code canary real end-to-end but not secret-shaped | `currency.note`, identifiers, hashes, `classificationRevision`, `mustNotInfer` never asserted absent; two existing `doesNotContain` assertions are **vacuous** | add canary suite, remove vacuity |
| E2 weaker classifications | PARTIAL — only `NOT_OBSERVED` proven un-upgraded | `TELEMETRY`, `PRODUCER_DECLARED`, `PROVIDER_ROUTE`, `IMPORTED` never exported in a test | add discriminator |
| E3 unavailable states | PARTIAL — `NOT_MATERIALIZED` only; `GovernanceExposureMapperTest.kt:99-101` is a tautology over a local enum list | `UNSUPPORTED_FORMAT` / `INVALID_STORED_PROJECTION` state identity and render; **infrastructure failure stays a failure** | add discriminator |
| E4 XR1 origin limit | ADEQUATE — mapper + rendered span with real seeded boundary values | none | no new test |
| E5 caller-attested | PARTIAL — the dedicated test's absence assertions cannot fail for the reason named | prove the limit through the closed catalogue instead | strengthen |

## 4. Pre-existing findings recorded during Phase 0

These are defects in the base, not in this branch. Each is fixed or tracked here rather than silently absorbed.

1. **`docs/reference/module-matrix.md` was stale at the base.** `verifyModuleMatrixDrift` fails at
   `33a1830f`: `examples:spring-ai-authority-boundary` (added by `2645a7d6`, the XR1 example) is in
   `settings.gradle.kts` but missing from the generated matrix. Regenerated in this branch (`+1 -0`).
2. **No CI lane runs that gate.** Neither `verifyModuleMatrixDrift` nor the aggregate `verifyPr` appears in any
   workflow, so a 19/19 green pipeline never exercised it. Adding the lane is a `ci-workflow` change and belongs
   in a separately reviewed change, not here.
3. **The 0.7.4f M6 mutation verdict is stale.** `docs/roadmap/0.7.4/evidence/0.7.4f-.../mutation-campaign.txt`
   records `M6 arbitrary metadata merged into the event — SURVIVED - benign`, with a control of 10 tests. That
   campaign ran **before** the review-round-1 rework that routed every event through `RuntimeEvent.of`. The 0.7.4f
   completion report then described M6 as "impossible, not argued", which no recorded run supports. Re-running M6
   against this head is part of §6; the earlier claim is treated as **withdrawn pending that run**.
4. **Two E1 assertions are vacuous** (`GovernanceExposureMapperTest.kt:66-67`, `GovernanceTelemetryExporterTest.kt:92`):
   the credential-shaped literal is asserted absent but never placed in the fixture. Replaced by real seeding.
5. **`GovernanceExposureMapperTest.kt:99-101` is a tautology** — it asserts inequality over a locally declared enum
   list and discards the result-state class it closed over, so the mapping is never checked.

## 5. Qualification plan (what will be proven, and how)

Real PostgreSQL is used for every storage, corruption, race and read-only claim; in-memory only for pure fold and
mapping semantics, and each such test is named as in-memory in the evidence.

| Phase | Proof |
|---|---|
| R2 | conflicting records at one source position: fold must refuse to elect, or mark unresolved, and must not pick by arrival order |
| R5 | publish succeeded but the caller was never acknowledged; and abrupt termination mid-publish against the real database — the committed projection or the previous one, never a partial |
| Q2 | direct tamper of `audit_head_position`, `audit_head_hash`, `registration_version` — refusal, row preserved, and a newer publication cannot repair it |
| Q3 | registration authority throws, audit source throws, publication infra throws — each surfaces as its documented state; none becomes CURRENT/COMPLETE/ALLOW |
| Q5 | statement-level spy plus before/after row counts across every query result state; zero writes reachable |
| E1 | secret-shaped canaries seeded into every forbidden location (`reasonCode`, `currency.note`, `established`, `mustNotInfer`, identifiers, `auditHeadHash`, `classificationRevision`, boundary fields), asserted absent from query view, exposure, rendered OTEL and `toString()` |
| E2 | every weaker authority/freshness class the vocabulary supports, exported as itself, never upgraded |
| E3 | all three unavailable states keep their identity and emit no facts, no completeness, no authority; infrastructure failure stays a failure |
| E5 | the limit is proven through the closed catalogue (10 keys, none an authentication claim), replacing the vacuous assertions |

## 6. Mutation campaign design

PIT cannot express these mutants: the pinned PIT configuration (`MutationProbeInitScript.kt`) supplies 11 syntactic
mutators and is the executable mutation authority, so a security mutant such as "ignore the hash mismatch" cannot
be represented without editing that authority — forbidden by this task. The campaign therefore uses hand-authored
single-anchor patches with a JUnit discriminator, following `AGENTS.md` Engineering Evidence Protocol item 5.

Per mutant: clean tree → control run green → apply by exact anchor → prove application (`git diff` + sha256) → run
the discriminator → read the test XML for failures/errors → verdict KILLED / SURVIVED / INVALID / UNDETERMINED with
its mechanism → restore → prove byte-identical restoration → post-restore control.

Non-negotiable: `--rerun-tasks --no-build-cache` (the build cache will otherwise serve a previously-cached pass and
execute nothing), JDK 21, and **no** invocation of `generateCriticalMutationBaseline`, `verifyReleaseMutation`, any
`-PtramaiMutationEvolution` mode, or any edit to `config/quality/mutation-*`, `test-quality.yml`,
`0.6.0-baseline.json` or `maintainability-deviations.yml`.

Targets M01–M12 as specified by the mission, plus M13 = re-run of the stale 0.7.4f M6 (arbitrary metadata merged
into the event) against this head, to replace an unevidenced claim with a verdict.

Evidence: `docs/roadmap/0.7.4/evidence/0.7.4g-adversarial-rebuild/`.

## 7. Known architectural non-guarantees, preserved

These are not solved here and must remain visible in any 0.7.4h closure:

- **Event-origin authenticity** (S19) and **workload authenticity** (S18) are unresolved: a registered boundary's
  strength never proves an event passed through it, and nothing authenticates the producer of an event.
- **Identity authentication** (T1) is tracked, not implemented.
- **Caller-attested exposure**: `GovernanceQueryResult` and its DTOs are publicly constructible and the exposure
  types are `sealed` (a Kotlin compile-time rule, not a JVM security boundary), so telemetry is a faithful
  representation of what a caller supplied, never authentication of it.
- The audit side of the composition tests is an in-memory store; `JdbcAuditStore` is not exercised from this module.
- Metrics and export/trace correlation remain unimplemented by design (0.7.4f).

## 8. Gate invocation record

`verifyPr` is the canonical local aggregate, but in this environment it does not complete in one invocation: the
run is terminated with SIGTERM (exit 143) by the process reaper. Its constituent gates are therefore run in slices,
with the base-policy property `-PchangePolicyBase=33a1830f4edd9604ac40f6ac4e5676d20b01c616`. A slice result is
reported as a slice, never as `verifyPr`. CI remains the authority for the aggregate.

`verifyMutationRatchet` was verified to pass at the base in isolation (11s, no PIT campaign executed).
