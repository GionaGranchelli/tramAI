# Epic 10.3 — Coverage and Mutation Policy: T0 audit

**Slice:** 10.3a — measurement/baseline authority (facts first, zero enforcement).

**Branch:** `epic/10.3a-coverage-mutation-baseline` · **Date:** 2026-08-31 · **Master:** post-10.1d/10.2-closure.

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
- **10.3c — targeted mutation authority:** apply PITest to the configured 7 families; run once; classify survivors into kill/survive/equivalent/unclassified; commit a ratcheted survivor baseline with the 10.1d exemption model (stable identity, scope, rationale; stale survivors fail; population growth cannot inherit an exemption; no wildcard budget). Wire `verifyCriticalMutationBaseline` where runtime permits.
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

1. **The dormant production mutation path is broken at first execution** — `mutationInitScript` configures the pitest extension from a `beforeProject` hook, but gradle-pitest-plugin 1.19.0 registers its extension inside `plugins.withType(JavaPlugin).configureEach(...)`, which fires only after the project's `java` plugin is applied. Result: "Extension with name 'pitest' does not exist" at configuration time. Fix in the follow-up: configure inside a `withType(JavaPlugin)` hook (or `afterEvaluate`).
2. **`targetTests` must be set explicitly.** When unset, the solidsoft plugin mirrors `targetClasses` into the test filter, so PIT scans for test classes *named like the production classes* → 0 tests found, every mutant NO_COVERAGE. Fix: pin `targetTests` to the module's test package (or the full suite). Without this, even a correctly-applied plugin produces a meaningless all-NO_COVERAGE report.
3. **The pilot's Groovy closure-delegate quirk:** configuring the extension via `extensions.configure('pitest') { ... }` nested inside `withType(JavaPlugin) { }` leaks the JavaPlugin delegate; configure via `extensions.getByName('pitest')` + property assignment instead.
4. **Identity scheme is empirically durable** for the routing-core pilot: module+className+method+mutator+description+block → SHA-256 is stable across identical runs and under harmless source movement. PIT's `description` and `block` fields were stable in every probe.

**Decision:** the pilot answers its question affirmatively — bounded PIT execution is deterministic, identities are durable, statuses are repeatable, and the routing-core population is currently fully killed (4/4) with no sanctioned survivors. Proceed to **10.3c2**: complete routing with `ModelRegistryEnforcer`, then expand family-by-family with the same evidence unit (configured targets → nonzero population → survivor adjudication → missing tests added → exact-ID classifications for genuine residuals). Before any enforcement wiring in 10.3c3, fix the two production-path root causes from C8.1/C8.2 and add the C6 discriminators.
