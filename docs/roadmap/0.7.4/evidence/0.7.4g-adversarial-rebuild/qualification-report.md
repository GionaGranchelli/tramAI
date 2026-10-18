# 0.7.4g — Adversarial rebuild and mutation proof: qualification report

Epic 0.7.4. Base SHA `33a1830f4edd9604ac40f6ac4e5676d20b01c616` (the 0.7.4f merge, zero divergence from
the task's stated dependency). Final SHA `704783333f748106c38d9b670fa47a259960a266`, certified by exact-head
CI at 19/19 checks success (recorded in §Canonical gate results below).

Everything below is a measurement from a run performed in this campaign, or is labelled otherwise. Three
delegated agents drafted test files and timed out during their own verification; every count quoted here
comes from a re-run performed here, not from an agent's report.

## Invariants

| ID | Invariant | Verdict | Evidence |
|---|---|---|---|
| I1 | No authority amplification | PASS | Q2/E2 + M10 killed; `PROVIDER_ROUTE` cannot be built with a stronger authority (asserted at the DTO) |
| I2 | Deterministic reconstruction | PASS | R1 (existing) + M02, M03 killed; contradictory history no longer elects by arrival order |
| I3 | Corruption is not repairable by rebuild | PASS | `GovernanceCorruptionPreservationTest` 8 tests: provenance/identity/digest disruption refused **and** not repaired by a newer publish; M05, M06, M07 killed |
| I4 | No inferred completeness or freshness | PASS | Q4 (existing) + M08 killed after strengthening, M09 killed; a derived read with no watermark is `STALE_OR_UNKNOWN`, never current |
| I5 | Observation is not enforcement | PASS | E2/E4; XR1 boundary evidence exports as `POLICY_AUTHORIZATION_RECORD` and never as an enforcement claim |
| I6 | No protected-payload exposure | PASS | E1 canary suite (6 tests) over accessor, `toString()` and rendered OTEL surfaces; M11, M13 killed |
| I7 | Queries and rebuilds cannot execute governance operations | PASS | Q5 database-level zero-write proof (write-rejecting DataSource + before/after row contents); M12 killed 7-of-8; Q3 shows a failed source publishes nothing |

## Real database scenarios exercised

All of the following run against a real PostgreSQL (`postgres:17-alpine`, Testcontainers) through the
production store and query service, not against fakes:

- deterministic rebuild and idempotent re-materialisation (existing `GovernanceProjectionMaterializerTest`,
  `MaterializedReadModelQualificationTest`, re-run in this campaign's gate slices)
- publication races: stale-after-newer, equal-ordering conflict, concurrent publishers, publish during read
  (existing `JdbcGovernanceProjectionStoreTest`)
- corrupt stored identity, digest, decision rows, family population, format version (existing)
- **corrupt stored provenance** — `audit_head_position`, `audit_head_hash`, `registration_version` tampered
  directly in the row: refused on load, and a subsequent newer valid publication does not replace it
  (`GovernanceCorruptionPreservationTest`)
- **read-only across all four result states** — `Found`, `NotMaterialized`, `UnsupportedFormat`,
  `InvalidStoredProjection`, with a DataSource that refuses write verbs and byte-comparison of all three
  tables before and after
- **interrupted rebuild** — a real `SIGKILL` (child exit 137, `Process.destroyForcibly()`) inside the open
  publish transaction, and after commit before acknowledgement, via
  `scripts/qualify-termination-recovery.sh` + `GovernanceProjectionTerminationQualificationTest`

## Rebuild determinism and idempotence

VERIFIED. Independently rebuilt snapshots carry the same semantic digest, and re-materialising identical
source facts reports `ALREADY_CURRENT` rather than writing a second version (`GovernanceProjectionMaterializerTest`
84, 106; `MaterializedReadModelQualificationTest` 232). A retry of a committed-but-unacknowledged publish also
reports `ALREADY_CURRENT` — proven in the termination harness.

## Corruption refusal and non-repair

VERIFIED, and this is the mission's highest-priority claim. The store refuses a row whose content contradicts
its digest, whose identity columns disagree with its key, or whose format this build does not write; each
refusal is a distinct typed outcome (`ProjectionCorruptionException` vs `UnsupportedProjectionFormatException`),
and the ordering/format/identity comparison lives inside a single SQL statement, so a racing newer publication
cannot replace a corrupt or unsupported row. The new test proves the *non-repair* half explicitly: after
tampering, a newer valid candidate is refused and the tampered values remain.

## Query side-effect counters

VERIFIED. Zero writes reach the database on any query path, measured rather than inferred: a DataSource wrapper
rejects `INSERT/UPDATE/DELETE/TRUNCATE/ALTER/DROP/CREATE` and counts statements, and all three tables are
byte-compared before and after each result state. When the read path was mutated to write (M12), seven of the
eight tests failed — the counter is not decorative.

## OTEL privacy and authority

VERIFIED. A canary test seeds the fixture's free text, currency note, boundary id and strength, must-not-infer
text, audit head hash, classification revision, workload/environment identifiers and format version into real
query DTOs and asserts each sentinel is absent from all three exposure surfaces (String accessors, `toString()`,
rendered records). This replaces two 0.7.4f assertions that could never fail because the value was never seeded.
Every (family, authority) pair the contract permits is exported as itself and never upgraded.

## Mutation population and verdicts

13 mutants, hand-authored (PIT cannot express them — the pinned PIT configuration is the executable mutation
authority and carries only syntactic mutators). **13 of 13 KILLED**, each with a stated detection mechanism and a
byte-identical restoration. Full transcript with per-mutant sha256 and mechanism:
`mutation-campaign.txt` in this directory. One survivor (M08) was converted by strengthening an inadequate
discriminator, not by reasoning it away; M13 replaces the stale 0.7.4f M6 verdict.

## Known limitations preserved (not solved here)

- **Event-origin authenticity (S19)** and **workload authenticity (S18)** remain unresolved: a registered
  boundary's strength never proves an event passed through it.
- **Identity authentication (T1)** is tracked, not implemented.
- **Caller-attested exposure**: `GovernanceQueryResult` and its DTOs are publicly constructible, and the
  exposure types are `sealed` — a Kotlin compile-time rule, not a JVM security boundary. Telemetry is a faithful
  representation of what a caller supplied, never authentication of it.
- **Infrastructure failure propagates** as a typed exception rather than being mapped to a result state. That is
  asserted (the query failure is explicitly *not* a `GovernanceQueryResult`), and it is the honest shape: a
  failure cannot be mistaken for evidence.
- The audit side of the composition tests is an in-memory store; `JdbcAuditStore` is not exercised from
  `:tramai-security`.
- Metrics and export/trace correlation remain unimplemented by design (0.7.4f).

## Base defects found and fixed during Phase 0

1. `docs/reference/module-matrix.md` was stale (`verifyModuleMatrixDrift` failed at the base) — XR1's example
   module was missing. Regenerated.
2. `examples:spring-ai-authority-boundary` had no module card and the `docs/modules` coverage table and list were
   stale (`verifyModuleDocContract` failed at the base). Card written, counts corrected.
3. **Neither gate, nor the aggregate `verifyPr`, runs in any workflow** — which is why a 19/19 green pipeline
   never exercised them. Adding a CI lane is a `ci-workflow` change and is deliberately left out of this PR.
4. The 0.7.4f M6 mutation verdict was stale, and the 0.7.4f completion report's "impossible, not argued" wording
   was wrong in kind. Corrected by M13's verdict (detected by two tests, not made impossible by the types).

## Canonical gate results

Run under JDK 21. `verifyPr` is the canonical local aggregate, but it does not complete in one invocation in this
environment — it is terminated with SIGTERM (exit 143) by the process reaper — so its constituent gates were run in
slices with the base-policy property `-PchangePolicyBase=33a1830f4edd9604ac40f6ac4e5676d20b01c616` and, for the
mutation ratchet, `-PtramaiMutationBaseSha=33a1830f…`. A slice result is reported as a slice, never as `verifyPr`.

| Gate | Result |
|---|---|
| `spotlessCheck` | PASS |
| `verifyStaticAnalysis` | PASS — detekt baseline 4792 → 4792, **0 added**; 16 new findings in the delegated test files were remediated structurally with no suppression |
| `verify060Architecture` | PASS |
| `verifyChangePolicy` | PASSED — 15 changed files, `runtime-behaviour`, no violations |
| `verifyModuleDocContract` | PASS (was red at the base — see §Base defects) |
| `verifyModuleMatrixDrift` | PASS (was red at the base) |
| `verifyMutationRatchet` | PASSED — base `33a1830f`, no PIT campaign executed |
| `verifyCompilerWarnings` | not re-run in this branch's final state; unchanged by this diff and green on the base slice |
| exact-head CI | **19/19 checks success**, zero failures, head `7047833` |

Invocation note worth recording: `verifyMutationRatchet` fails when it is run without `-PtramaiMutationBaseSha`,
because it then falls back to `git merge-base HEAD origin/master` and compares the population admissions against a
different authority base (M35). That red is an invocation artifact, not repo state; the same gate passes with the
PR base.

## Deferred to 0.7.4h or later

- CI lane for `verifyModuleMatrixDrift` / the docs contract gates (separate `ci-workflow` change).
- Publication regression guard for test-fixture artifacts covering all eleven fixture-bearing modules
  (separate `build-logic` change, agreed in the 0.7.4f review).
- S18/S19/T1 remain open as stated above.
