# Epic 10.3 — Coverage and Mutation Policy

**Status:** **COMPLETE** (10.3a → 10.3d).

**Slice history:** §8. T0 audit below is the original 10.3a discovery document;
sections 7–9 record the measurement pilot, slice history, and closure evidence.

**Branch (T0):** `epic/10.3a-coverage-mutation-baseline` · **Date:** 2026-08-31 · **Master:** post-10.1d/10.2-closure.

> Guiding question — *not* "what percentage coverage should TramAI have?", but:
> **"Which critical behavior can currently be removed or inverted while all mandatory gates remain green?"**

---

## 1. Coverage authority

| Question | Finding |
|---|---|
| Which tasks/plugins generate coverage? | `MaintainabilityBaselinePlugin` registers `generateCoverageBaseline` (collects via `CoverageCollector`/`CoverageReportParser`) and `verifyCriticalCoverage` (compares against committed baseline via `CoverageBaselineVerifier`, 1.0pp regression tolerance). |
| Which modules participate? | 9 critical modules declared in `config/quality/test-quality.yml`: `:tramai-core`, `:tramai-engine`, `:tramai-security`, `:tramai-sovereign`, `:tramai-standalone`, `:tramai-structured`, `:tramai-orchestration`, `:tramai-persistence-file`, `:tramai-persistence-jdbc`. |
| Which modules are silently absent? | **All of them, in practice.** JaCoCo IS applied programmatically to critical modules (`MaintainabilityBaselinePlugin` `withPlugin("java")` → `apply("jacoco")`, so `jacocoTestReport` exists on each critical module), but `generateCoverageBaseline` has **never been run** — no `build/reports/jacoco/**` XML exists on master, and the committed baseline is `pending`. Non-critical modules (TCK/integration test hosts) emit **no** exec data: the per-module report only sees the module's own test execution, so cross-module credit is impossible under the current wiring. |
| Line/branch coverage per module | **No live measurements exist.** Committed baseline (`config/quality/0.6.0-baseline.json` → `testQuality.coverage`) is `status: "pending"`, `byModule: {}`, `overallLineCoverage: 0.0`, `overallBranchCoverage: 0.0`, note *"Requires JaCoCo plugin configuration"*. |
| Aggregation deterministic + fail-closed? | Collector/parser architecture exists and is deterministic by construction, but is **unexercised** — no run has ever produced a coverage-summary. Fail-closed behavior (missing report → FAIL) is implemented in the verifier but cannot be proven while nothing is measured. |

## 2. Existing mutation authority

| Question | Finding |
|---|---|
| PIT/automated mutation infra? | `generateCriticalMutationBaseline` writes a PITest init-script and runs a **nested Gradle build per target family** (`runNestedGradle`); `verifyCriticalMutationBaseline` compares against committed baseline via `MutationBaselineVerifier`. PITest plugin versions referenced: `gradle-pitest-plugin:1.19.0`, `pitest-junit5-plugin:1.2.1`. |
| Which modules/classes are enrolled? | 7 families in `test-quality.yml`: **policy** (engine/security/sovereign, 8 exact classes incl. `LegacyPermissivePolicyEngine`, `PolicyEnforcementHelper`, `DefaultPolicyEngine`, `SovereignTramai`), **approval** (security/engine wildcards), **routing** (core/engine: `ProviderRegistry`, `ModelProvider`), **retry** (core/engine: `RetryPolicySettings*`, `CircuitBreakerSettings*`, `ProviderCircuitBreaker*`), **tools** (engine: `ToolRegistry`, `CanonicalMessageEncoder`), **evidence** (sovereign/engine/security wildcards), **structuredOutput** (structured, `dev.tramai.structured.*`). |
| What does the existing baseline/config enforce? | **Nothing yet.** Committed `testQuality.mutation` is `status: "pending"`, no `survivingMutants`/`equivalentMutants`/`unclassifiedMutants`. `config/quality/mutation-classifications.yml` exists (schema v1) with `classifications: []`. PITest plugin never applied to enrolled modules. Tasks are registered but **not wired into `verifyPr` or CI** (see §5). |
| Which campaigns are only documented/manual? | All of them. Hand-written mutation campaigns exist across the roadmap (10.1d M01–M05 against static-safety discriminators, 10.1b M01–M10, phase-8 campaigns) — exercised by hand, never automated by PITest, and invisible to CI. |

## 3. Critical-risk surface (architecture-derived, already frozen)

`test-quality.yml` already encodes the architecture-derived set — no re-derivation needed; this is the authoritative registry:

- **9 critical modules**: core, engine, security, sovereign, standalone, structured, orchestration, persistence-file, persistence-jdbc
- **7 configured mutation domains** in the registry: policy, approval, routing, retry, tools, evidence, structuredOutput

The existing registry does **not** cover several other critical behavioral domains — workflow recovery/replay, lifecycle/fencing, worker state machines, checkpoint generations, and persistence concurrency are absent from the mutation families. That is a valuable T0 finding, not a weakness: it defines the 10.3c expansion surface.

Lifecycle/fencing/concurrency **behavior** is already strongly protected by the Phase-8 model-based, TCK, property, race, and mutation-discriminator suites. 10.1d adds complementary static lifecycle/security guards (raw-thread/GlobalScope/body-read/sensitive-log), but is **not** the behavioral authority for those state machines.

## 4. Cost model

| Lane | Composition | Estimated cost | Verdict |
|---|---|---|---|
| Coverage-only | JaCoCo `jacocoTestReport` on 9 critical modules (their test suites already run in CI) | test-run + report overhead; **low** (≈ minutes over existing test time) | **PR-viable** |
| Targeted mutation | PITest on the 7 configured families (~30 declared classes; engines are large — likely 50–200 mutants/family) | **medium–high** (10–40 min on top of PR build, serializable per family) | **PR subset + nightly full** |
| Broad mutation | Repo-wide PITest | very high (hours) | **nightly/release only** |

**These are planning estimates, not measurements** — JaCoCo and PITest have never executed in this repository. They will be measured during 10.3a/10.3c activation and the table then replaced with recorded figures.

## 5. Gap analysis

| Gap class | Finding |
|---|---|
| Missing measurement | **All coverage and mutation measurement is missing.** JaCoCo not applied; PITest not applied; baselines `pending`. |
| Measured but unenforced | None — nothing is measured. |
| Infrastructure exists but dormant | `generateCoverageBaseline`, `verifyCriticalCoverage`, `generateCriticalMutationBaseline`, `verifyCriticalMutationBaseline`, `CoverageBaselineVerifier`, `MutationBaselineVerifier`, `mutation-classifications.yml` schema, `CoverageCollector`/`CoverageReportParser`. **None wired into `verifyPr` or any workflow** (`verifyPr` depends only on maintainability baseline, change policy, module manifest/matrix/doc-contract + build-logic tests; workflows grep for coverage/mutation → 0 hits). |
| Already strongly guarded (manual/documented) | Hand-written mutation campaigns (10.1d M01–M05, 10.1b M01–M10, phase-8); 71-test static-safety suite; contract discriminators. Not automated/CI-attached. |
| Unsuitable for automated mutation | Generated `**/model/**` classes (declared exclusion in `test-quality.yml`); equivalence judgments need a first run before classification policy can bite. |
| Legitimate exclusion | Explicit coverage exclusion exists (`**/model/**`, "Generated model classes") — the only declared one. |

## 6. Proposed slicing (revised per repository facts)

The repository already contains the authority machinery; what is missing is **measurement activation + enforcement wiring**. Revised boundaries:

- **10.3a — measurement authority (this slice → follow-on PR):** apply JaCoCo to the 9 critical modules; run `generateCoverageBaseline` once; commit the first real coverage baseline (line + branch per module); record first-run cost. Mutation stays untouched. → answers "what do we measure, who owns it, what is missing".
- **10.3b — coverage non-regression ratchet (this slice → PR):** base-authoritative policy. `verifyCriticalCoverage` now loads the PR base / master `test-quality.yml` + `coverage-baseline.json` via `-PtramaiCoverageBaseSha` (local: `git merge-base HEAD origin/master`) and judges: (1) current measurement vs BASE baseline with BASE tolerance; (2) candidate policy vs base policy (no critical-module removal, no tolerance widening, no exclusion expansion — exact pattern+reason subset); (3) candidate committed baseline cannot weaken base (no tolerance); (4) new critical modules must be fully enrolled with baseline entry matching fresh measurement; (5) structural integrity of every CoverageData (raw counters recompute the stored percentages). Wired into `verifyPr` + CI. No percentage floor — the ratchet is the policy.
- **10.3c — targeted mutation authority (split into 10.3c1 + 10.3c2):**
  - **10.3c1 — deterministic measurement + population identity (DONE):** repaired the dormant PITest probe (included-build guard, extension configured after Java plugin via direct property access, `pitest-junit5-plugin:1.2.1`); pinned mutation semantics (gradle-pitest-plugin 1.19.0, engine 1.22.1, explicit 11-mutator DEFAULTS expansion, `timeoutConstInMillis=4000`/`timeoutFactor=1.25`); identity schema v2 (methodDescriptor + index, line excluded); status whitelist (KILLED/SURVIVED/NO_COVERAGE/TIMED_OUT + error statuses, unknown = fail); canonical `KILLED|NON_KILLED` ratchet outcome with raw PIT status preserved as diagnosis — exhaustive mapping, `NON_VIABLE`/`MEMORY_ERROR`/`RUN_ERROR`/`REMOVED`/`NOT_STARTED`/unknown fail the measurement (C7: SURVIVED↔TIMED_OUT scheduler races must not churn the ratchet, but tool failures must never silently become approved NON_KILLED); exact population persisted incl. killed mutants; same-family identity collision = hard fail; non-vacuity for all 7 families; `config/quality/mutation-baseline.json` + `mutation-survivors.json` inventory (no classifications); per-family cost table; determinism double-run at identity+family+kill-state level. Discriminator matrix M01–M20 + K1–K6 green.
  - **10.3c2 — base-authoritative survivor ratchet (next):** BASE mutation population + BASE mutation-classifications.yml → fresh candidate population → previously-KILLED → non-killed FAIL; new non-killed identity FAIL; base-approved exact survivor PASS; new KILLED identity PASS; approved survivor becomes KILLED → PASS + stale exemption removed; stale exemption FAIL; wildcard/budget exemption impossible; candidate adds own exemption FAIL; candidate narrows target families FAIL; PITest/identity semantics change invalidates old approvals. Survivor classification is a review decision — no guesswork "equivalent-mutant" labels.
- **10.3d — integration + adversarial closure:** permanent adversarial tests (mutation of the gates themselves), configuration-cache proof, deterministic report identity, CI non-vacuity guard, docs, exact-head certification. 10.5 takes over lane redesign (PR-subset vs nightly/release).

**Boundary discipline:** 10.3a = measurement only, zero enforcement, zero production changes. No percentage thresholds invented before the first real measurement exists.

---

## 7. 10.3c1 — routing-core mutation pilot (experiment report)

**Slice:** 10.3c1 — measurement-only routing-core pilot. **Branch:** `epic/10.3c1-routing-mutation-pilot` · **Master:** `e73512ab` · **Date:** 2026-09-02.

**Question this slice answers:** can TramAI execute a bounded PIT campaign deterministically, identify each mutant durably, and repeat the same measurement?

> **Boundary honored:** this pilot measures. It does not invent enforcement policy, touch `mutation-classifications.yml`, modify any baseline, wire PIT into `check`/PR, or introduce a percentage floor. `ModelRegistryEnforcer` is deliberately excluded — the authoritative routing family (`test-quality.yml`) contains it and is not considered enrolled until the 10.3c2 follow-up expansion.

### C1 — PIT execution

- **Toolchain:** gradle-pitest-plugin 1.19.0 (PIT core 1.22.1, pitest-junit5-plugin 1.2.1), JDK 21 (Temurin 21.0.12), Gradle wrapper 9.0.0, Kotlin 2.3.0, JUnit 5.12.2.
- **Targets:** `:tramai-core` → `dev.tramai.core.provider.ProviderRegistry`, `dev.tramai.core.provider.ModelProvider` (2 classes).
- **Pilot path:** bounded init-script campaign (`pilotMutationProbe`) restricted to the two routing-core classes. The dormant production machinery (`generateCriticalMutationBaseline` → `mutationInitScript`) was found to be **broken at first real execution** and was not used; the pilot reproduced its intended shape in an init script (see C8 findings for the two root causes that must be fixed before the production path is wired).
- **Result:** BUILD SUCCESSFUL; 79 test classes examined, 81 tests discovered, **4 mutations generated** (population non-empty, see C2).

### C2 — non-vacuity

- Target classes resolved: 2/2. Mutants generated: 4 > 0. XML mutation records: 4 > 0.
- Every record's `mutatedClass` ∈ {`dev.tramai.core.provider.ProviderRegistry`, `dev.tramai.core.provider.ModelProvider`}. No unexpected module/class mutated. `failWhenNoMutations` is set (zero population would fail the build).
- Status counts (after the C5 missing-test fix): **KILLED 4 · SURVIVED 0 · NO_COVERAGE 0 · TIMED_OUT 0**.

### C3 — identity stability

- **3/3 identical runs** (same HEAD, same targetClasses, same tests, clean outputs): identity set equal across all runs (`Set<MutationIdentity>` 4/4 identical); SHA-256 over `module+className+method+mutator+description+block` is deterministic.
- **Source-movement probe:** adding harmless comment lines above the mutated methods shifted source lines (28→32, 34→40) with **zero identity or status change** — line numbers are correctly excluded from identity, and PIT's `block` index is stable under pure source movement.

### C4 — status determinism

- 3/3 identical runs: status map (`Map<MutationIdentity, status>`) identical — the same 4 IDs reported KILLED every run. No KILLED↔SURVIVED/NO_COVERAGE flapping. No TIMED_OUT observed; PIT `timeoutConstant=4000`/`timeoutFactor=1.25` defaults in effect.

### C5 — survivor adjudication (every survivor)

Initial population (pre-fix): 4 mutants — 1 KILLED, **3 NO_COVERAGE survivors**, all on `ModelProvider` default interface method bodies:

| Mutant | Method | Why it survived | Adjudication |
|---|---|---|---|
| NegateConditionalsMutator (`?:` → "unknown" path) | `ModelProvider.providerId()` | Every test provider **overrides** `providerId()`, so the default body never executed | **Missing test** — default contract unpinned |
| EmptyObjectReturnValsMutator (return "") | `ModelProvider.providerId()` | Same | **Missing test** |
| BooleanTrueReturnValsMutator (return true) | `ModelProvider.supportsCapability()` | No test calls the default `= false` implementation | **Missing test** — a default-return-true would silently grant VISION/TOOL_CALLING etc. |
| EmptyObjectReturnValsMutator (return emptyList) | `ProviderRegistry.resolveCandidates` | KILLED by `ProviderRegistryTest.resolve candidates returns primary route plus configured fallbacks` | Killed |

None were equivalent mutants, tool limitations, or ambiguous production behavior. **Fix (value of the exercise):** added `ModelProviderDefaultContractTest` (2 tests) pinning the default contract — `providerId()` returns the class simple name; `supportsCapability()` is false for every capability. Re-run: **4/4 KILLED (100%)**, population still 4, `Ran 4 tests (1 test per mutation)`. Classifications file remains empty by design — no survivor needed an exemption.

### C6 — kill-the-mutation-system discriminators

Deferred to the 10.3c3 authority slice (zero-population-fails, unexpected-target-fails, duplicate/identity discriminators, malformed-XML-fails-closed, path rejection, identity movement/semantics-change checks). The parser's existing absolute-path rejection and fail-on-missing/malformed-report behavior were confirmed present by inspection; the pilot exercised `failWhenNoMutations` (would fail on zero). Permanent discriminator tests are out of scope for this measurement slice per the directive.

### C7 — cost (measured, replacing the §4 estimate)

- **Cold execution (clean output dir, `--no-daemon`, no build cache):** ~15 s wall per campaign (2-worker). Includes PIT's coverage pre-scan of 79 test classes + mutation execution of 4 mutants + XML/HTML report generation. Phase split from verbose log: coverage + dependency analysis < 1 s; mutation build < 1 s; test execution < 1 s (4 mutants, 1 test each).
- **Warm/repeat:** ~13 s wall per campaign.
- **Mutation generation:** trivial at this population; the dominant cost is JVM/daemon startup + test-class pre-scan, not mutation itself.
- **Report/aggregation overhead:** negligible (< 1 s) at this scale.

The §4 planning estimate (10–40 min/family serialized) does **not** hold at routing-core scale — the pilot family is PR-viable at ~15 s cold. Per-family cost for the larger families (policy/approval/evidence with engines) must still be measured in 10.3c2; no CI lane decision is made here.

### C8 — findings and decision

1. **The dormant production mutation path is broken at first execution** — `mutationInitScript` configures the pitest extension from a `beforeProject` hook, but gradle-pitest-plugin 1.19.0 registers its extension inside `plugins.withType(JavaPlugin).configureEach(...)`, which fires only after the project's `java` plugin is applied. Result: "Extension with name 'pitest' does not exist" at configuration time. Fix in the follow-up: configure inside a `withType(JavaPlugin)` hook (or `afterEvaluate`). `CanonicalGradleProbe`'s generated init script already applies this pattern (it hooks `plugins.withId('java')`).
2. **`targetTests` must be set explicitly.** When unset, the solidsoft plugin mirrors `targetClasses` into the test filter, so PIT scans for test classes *named like the production classes* → 0 tests found, every mutant NO_COVERAGE. Fix: pin `targetTests` to the module's test package (or the full suite). Without this, even a correctly-applied plugin produces a meaningless all-NO_COVERAGE report.
3. **The pilot's Groovy closure-delegate quirk:** configuring the extension via `extensions.configure('pitest') { ... }` nested inside `withType(JavaPlugin) { }` leaks the JavaPlugin delegate; configure via `extensions.getByName('pitest')` + property assignment instead (the committed reproducer below uses the closure form with an explicit `pitestExt ->` parameter, which binds the delegate correctly — matching `CanonicalGradleProbe`).
4. **Identity scheme is empirically durable** for the routing-core pilot: module+className+method+mutator+description+block → SHA-256 is stable across identical runs and under harmless source movement. PIT's `description` and `block` fields were stable in every probe.

### C9 — reproduction (committed reproducer)

The pilot mechanism is repository-owned so the experiment is reproducible from a clean checkout — the recorded evidence is not an uncommitted-script claim.

**Reproduce the measurement:**
```
./gradlew --init-script config/quality/mutation-routing-core-pilot.init.gradle \
  pilotMutationProbe --rerun-tasks
```
Report XML lands at `tramai-core/build/mutation-pilot/routing-core/tramai-core/mutations.xml` (gitignored under `**/build/`). The init script pins: `:tramai-core`; `ProviderRegistry` + `ModelProvider`; explicit `targetTests` (`dev.tramai.core.provider.*`); PIT 1.19.0 / pitest-junit5-plugin 1.2.1; XML output; `failWhenNoMutations` (non-vacuity); non-timestamped deterministic report dir.

**Turn XML into the identity/status set** (reusing the canonical parser, not a second algorithm): `MutationReportParser.parse("tramai-core", "routing-pilot", mutations.xml)` produces records whose `identity` is `MutationIdentity.stableKey()` (SHA-256 over module+className+method+mutator+description+block, line excluded). A small driver is `MutationReportParserTest`; the verifier path in `MutationBaselineVerifier` consumes exactly this shape. For a shell-only check the XML is directly greppable: `status='KILLED'`/`status='NO_COVERAGE'` per `<mutation>` and `<mutatedClass>`/`<mutator>`/`<description>`/`<block>` are the identity inputs.

**Expected at this head:** 4 mutations, 4 KILLED, 0 survivors. The 3× identity/status stability experiment = run the command 3× from the same HEAD with `rm -rf build/mutation-pilot` between runs and diff the normalized identity/status sets; the recorded result is 4/4 identical identities and 4/4 identical statuses. The line-movement probe = add harmless comment lines above `providerId()`/`supportsCapability()` and confirm identities unchanged.

**Decision:** the pilot answers its question affirmatively — bounded PIT execution is deterministic, identities are durable, statuses are repeatable, and the routing-core population is currently fully killed (4/4) with no sanctioned survivors. Proceed to **10.3c2**: complete routing with `ModelRegistryEnforcer`, then expand family-by-family with the same evidence unit (configured targets → nonzero population → survivor adjudication → missing tests added → exact-ID classifications for genuine residuals). Before any enforcement wiring in 10.3c3, fix the two production-path root causes from C8.1/C8.2 and add the C6 discriminators.

---

## 8. Slice history

| Slice | PR | Summary |
|---|---|---|
| **10.3a** | #345 | Coverage baseline: JaCoCo on 9 critical modules; first real coverage-baseline.json (92.51% line / 76.17% branch); A1–A10 measurement discriminators. |
| **10.3b** | #345 | Coverage ratchet: base-authoritative CoveragePolicyDeltaVerifier; 28 discriminator tests across B01–B22 (including six 'b'-variants: B07b, B12b, B14b, B17b, B20b, B21b) covering M1–M7 gate mutations; `verifyCriticalCoverage` wired into `verifyPr` + CI. |
| **10.3c1** | #362 | Mutation measurement: repaired dormant PITest probe; 2,372 mutants across 7 families; identity schema v2 (SHA-256); determinism proven; M01–M20 measurement discriminators. |
| **10.3c2** | (within #362) | Mutation baseline: exact population + survivor inventory committed; 20 classified survivors (equivalent-mutant + tool-limitation); `mutation-classifications.yml` populated. |
| **10.3c3** | #394 | Mutation ratchet enforcement: `MutationRatchetVerifier` (pure, in-memory, exact-set); M01–M20 ratchet discriminators (38 tests); classification authority rules; three-way PIT renderer semantics check; `verifyMutationRatchet` wired into `verifyPr` + CI. |
| **10.3d** | (this PR) | Integration + adversarial closure: W4 `verifyPr` wiring discriminator + W5 CI workflow wiring discriminators (#29); CC-compatibility correction; docs reconciliation; closure audit; epic marked complete. |

## 9. 10.3d — Integration and Adversarial Closure

**Slice:** 10.3d — integration audit, adversarial closure, and documentation reconciliation.

### 9.1. Closure audit matrix

Each row represents a requirement from the 10.3d specification. Evidence is
cited by file, test name, or CI line number.

| # | Requirement | Status | Evidence |
|---|---|---|---|
| R01 | Coverage baseline measured for 9 critical modules | **COMPLETE** | `config/quality/coverage-baseline.json` — 9 modules, 92.51% line / 76.17% branch. `CoverageMeasurementDiscriminatorTest` (A1–A10): 9 tests. |
| R02 | No coverage regression in critical modules | **COMPLETE** | `CoveragePolicyDeltaVerifier` enforces base-authoritative ratchet. 28 discriminator tests across B01–B22 (including six 'b'-variants: B07b, B12b, B14b, B17b, B20b, B21b) covering 7 gate mutations. CI: `verifyCriticalCoverage` in `ci.yml` lines 204–216. |
| R03 | Coverage exclusions explicit and justified | **COMPLETE** | `test-quality.yml` declares one exclusion: `**/model/**` ("Generated model classes"). B09/B10 discriminators prevent undocumented additions or reason rewrites. |
| R04 | Targeted mutation testing for critical logic | **COMPLETE** | 2,384 mutants across 7 families (policy, approval, routing, retry, tools, evidence, structuredOutput). `config/quality/mutation-baseline.json` — identity schema v2, measured at commit `5856530e`. |
| R05 | Surviving mutants tracked and justified | **COMPLETE** | 20 classified survivors in `mutation-classifications.yml` — all `equivalent-mutant` or `tool-limitation`. M03/M08/M09 discriminators enforce that classifications can only be added on master during ceremonies, never by PRs. |
| R06 | Mutation ratchet enforced on every PR | **COMPLETE** | `verifyMutationRatchet` in `verifyPr` (MaintainabilityBaselinePlugin line 1383) and CI (`ci.yml` lines 218–230). W4 and W5 wiring discriminators (`CoverageWiringTest.kt`) prove presence in the `verifyPr` graph and in the CI workflow with PR base SHA authority. |
| R07 | Base-authoritative enforcement (no self-judging) | **COMPLETE** | `MutationRatchetAuthorityLoader` resolves base SHA from Git; no fallback to candidate authority (M20). `CoverageAuthorityLoader` does the same. 7 authority-loading tests in `MutationRatchetAuthorityTest`. |
| R08 | Discriminator tests for all 20 failure modes | **COMPLETE** | M01–M20 covered by 38 tests in `MutationRatchetDiscriminatorTest` (25) + `MutationRatchetClassificationDiscriminatorTest` (13). M01–M20 measurement pipeline covered by 29 tests in `MutationMeasurementDiscriminatorTest`. |
| R09 | CI non-vacuity guards | **COMPLETE** | `maintainability-baseline.yml` policy-maintainability lane: 302 tests (pinned). scanners-coverage lane: 252 tests (pinned). Python assertions enforce exact counts after every CI run. |
| R10 | Mutation reports available for release review | **COMPLETE** | `maintainability-full.yml` (weekly + manual) runs `verifyFullMaintainabilityBaseline` which generates mutation-summary.json and PIT HTML reports as CI artifacts. |
| R11 | Docs and roadmap reconciled | **COMPLETE** | This document + ROADMAP-0.6.0.md updated. |

### 9.2. Adversarial gate mapping

29 failure modes were evaluated. All 29 are defended by durable discriminators.
The table maps each failure mode to its enforcing test.

| # | Failure mode | Discriminator | Status |
|---|---|---|---|
| 1 | KILLED→NON_KILLED regression | M01 in `MutationRatchetDiscriminatorTest` | ✅ |
| 2 | New NON_KILLED identity | M06 in `MutationRatchetDiscriminatorTest` | ✅ |
| 3 | Forged kill (raw SURVIVED stored as KILLED) | M07 + M13 in `MutationRatchetDiscriminatorTest` | ✅ |
| 4 | Forged identity (SHA-256 mismatch) | M20 in `MutationRatchetDiscriminatorTest` | ✅ |
| 5 | Duplicate identity | M12 in `MutationRatchetDiscriminatorTest` | ✅ |
| 6 | Unknown/non-canonical outcome | M13 in `MutationRatchetDiscriminatorTest` | ✅ |
| 7 | Self-approval of own survivor | M08 in `MutationRatchetClassificationDiscriminatorTest` | ✅ |
| 8 | Fabricated classification | M09 in `MutationRatchetClassificationDiscriminatorTest` | ✅ |
| 9 | Rewrite existing classification | M03 (4 variants) in `MutationRatchetClassificationDiscriminatorTest` | ✅ |
| 10 | Stale classification retained after kill | M05 in `MutationRatchetClassificationDiscriminatorTest` | ✅ |
| 11 | Orphaned classification for disappeared mutant | M10 in `MutationRatchetClassificationDiscriminatorTest` | ✅ |
| 12 | Removed classification while survivor lives | M11 in `MutationRatchetClassificationDiscriminatorTest` | ✅ |
| 13 | Family narrowing (dropping families) | M14 in `MutationRatchetDiscriminatorTest` | ✅ |
| 14 | Module narrowing inside family | M14 in `MutationRatchetDiscriminatorTest` | ✅ |
| 15 | Target-class narrowing | M15 in `MutationRatchetDiscriminatorTest` | ✅ |
| 16 | Target-test narrowing | M15 in `MutationRatchetDiscriminatorTest` | ✅ |
| 17 | PIT plugin/engine version drift | M16 in `MutationRatchetDiscriminatorTest` | ✅ |
| 18 | Mutator set drift | M17 in `MutationRatchetDiscriminatorTest` | ✅ |
| 19 | Timeout drift | M18 in `MutationRatchetDiscriminatorTest` | ✅ |
| 20 | Executable PIT renderer drift | M16 three-way in `MutationRatchetDiscriminatorTest` | ✅ |
| 21 | Identity schema version drift | M19 in `MutationRatchetDiscriminatorTest` | ✅ |
| 22 | Empty authority vacuity | M20 in `MutationRatchetDiscriminatorTest` | ✅ |
| 23 | Blank identity row | M20 in `MutationRatchetDiscriminatorTest` | ✅ |
| 24 | Tampered byFamily summary metrics | M20 in `MutationRatchetDiscriminatorTest` | ✅ |
| 25 | Mutation gate removed from verifyPr | W4 in `CoverageWiringTest` | ✅ (10.3d) |
| 26 | Coverage regression beyond tolerance | B01/B02 in `CoveragePolicyDeltaVerifierTest` | ✅ |
| 27 | Coverage tolerance widening | B07 in `CoveragePolicyDeltaVerifierTest` | ✅ |
| 28 | Coverage exclusion injection | B09/B10 in `CoveragePolicyDeltaVerifierTest` | ✅ |
| 29 | PR CI mutation-ratchet step removed or detached from PR-base authority | W5 (3 tests) in `CoverageWiringTest` | ✅ (10.3d) |

Failure modes #25 (verifyPr task graph wiring) and #29 (CI workflow invocation
with PR-base SHA authority) were addressed by the W4 and W5 wiring
discriminators in `CoverageWiringTest`. All other modes were already covered by
10.3c3 or earlier.

### 9.3. Configuration-cache classification

| Task | CC classification | Rationale |
|---|---|---|
| `verifyMutationRatchet` | `CC_UNSUPPORTED_CURRENTLY` | Accesses `Project` during execution in `doLast` (`project.rootDir`, `project.findProperty`, `verifyTestQualityDiagnostics(project, ...)`), which is unsupported by the Gradle Configuration Cache. Globally disabled in TramAI (`org.gradle.configuration-cache=false`). Typed-input refactoring deferred to a dedicated CC track. |
| `verifyCriticalCoverage` | `CC_UNSUPPORTED_CURRENTLY` | Accesses `Project` during execution in `doLast` (`project.rootDir`, `project.findProperty`, `verifyTestQualityDiagnostics(project, ...)`), which is unsupported by the Gradle Configuration Cache. Globally disabled in TramAI (`org.gradle.configuration-cache=false`). Typed-input refactoring deferred to a dedicated CC track. |
| `generateCriticalMutationBaseline` | `DELIBERATELY_NON_CACHEABLE` | Spawns nested Gradle builds per family via `ProcessBuilder`. Must use `--rerun-tasks` and bracketed provenance. Non-cacheable by construction. |
| `verifyMaintainabilityBaseline` | `CC_REUSABLE` | Pure in-memory file comparison of committed JSON baselines against canonical reference. |
| `verifyChangePolicy` | `CC_SUPPORTED_BUT_NOT_REUSABLE` | Consumes base SHA property and computes diff at execution time. |

### 9.4. Determinism and provenance boundary

The mutation system operates in two distinct trust modes:

1. **Normal PR enforcement** (`verifyMutationRatchet`): static comparison of
   committed `mutation-baseline.json` + `mutation-classifications.yml` between
   the PR branch and the base authority at the PR's merge-base SHA. **No PITest
   campaign runs.** The result is deterministic and fully reproducible: given
   the same base SHA and the same committed files, the verifier produces
   identical diagnostics. This is what runs on every PR in CI.

2. **Measurement ceremony** (`generateCriticalMutationBaseline`): full PITest
   campaign across all 7 families. Runs ~60 minutes on a quiet machine.
   Produces `mutation-baseline.json` with bracketed provenance (clean committed
   tree verified before AND after PIT execution). Determinism at the canonical
   `KILLED|NON_KILLED` level is proven by the paired-run evidence in
   `docs/evidence/10.3c1-mutation-measurement.md`. This is a human-supervised
   operation run during enrollment/audit; it never runs on PR CI.

The boundary is crisp: no PR CI step ever invokes PITest.

