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
| Which modules are silently absent? | **All of them.** The JaCoCo Gradle plugin is NOT applied to any module in `build.gradle.kts`, `settings.gradle.kts`, convention plugins, or module build files. `jacocoTestReport` therefore does not exist → `generateCoverageBaseline` cannot run today. JaCoCo appears only inside `CanonicalGradleProbe` (the v0.5.0 canonical-baseline probe), not live measurement. |
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
- **10.3b — coverage non-regression ratchet:** wire `verifyCriticalCoverage` into `verifyPr` + CI; enforce: critical line/branch coverage cannot regress (1.0pp tolerance), missing/empty report = FAIL, module falling measured→unmeasured = FAIL. No repo-wide percentage floor (anti-vanity; the ratchet is the policy).
- **10.3c — targeted mutation authority:** apply PITest to the configured 7 families; run once; classify survivors into kill/survive/equivalent/unclassified; commit a ratcheted survivor baseline with the 10.1d exemption model (stable identity, scope, rationale; stale survivors fail; population growth cannot inherit an exemption; no wildcard budget). Wire `verifyCriticalMutationBaseline` where runtime permits.
- **10.3d — integration + adversarial closure:** permanent adversarial tests (mutation of the gates themselves), configuration-cache proof, deterministic report identity, CI non-vacuity guard, docs, exact-head certification. 10.5 takes over lane redesign (PR-subset vs nightly/release).

**Boundary discipline:** 10.3a = measurement only, zero enforcement, zero production changes. No percentage thresholds invented before the first real measurement exists.
