# Epic 10.4 — Unified 0.6.0 Architecture Gate (Track B2)

**Branch:** `build/0.6.0-architecture-gate`
**Base:** `master @ 8705c5bd` (merged B1 / PR #298)
**PR title:** `build(quality): add unified 0.6.0 architecture gate`

## 1. Objective

Create one authoritative release-facing command:

```bash
./gradlew verify060Architecture
```

that aggregates **existing** architectural contracts (never reimplements them)
and emits:

```text
build/reports/tramai/architecture/architecture-report.json
```

It answers: *"Does the current repository satisfy the architectural
invariants required for TramAI 0.6.0?"*

## 2. Critical design rule

The façade owns **orchestration, normalization, aggregation, reporting,
final PASS/FAIL** — nothing else. It must NOT become another source of
architectural truth.

- Where an existing verifier returns typed diagnostics, **consume that
  directly**.
- Where an existing Gradle task is the only boundary, use a **minimal
  adapter** (read its typed artifact; never parse console output).
- Do NOT copy logic from `BaselineVerifier`, cancellation checks, TCKs, etc.

## 3. Check registry (stable IDs → evidence source)

| id | Evidence source | Typed codes consumed |
|----|-----------------|----------------------|
| `module-manifest` | `ModuleManifestVerifier.verify(...)` (B1, direct call) + baseline report catalog codes | `MODULE_CATALOG_*` (except drift codes below) |
| `publishing-topology` | `ModuleManifestVerifier.verify(...)` (B1) | `MODULE_CATALOG_BOM_DRIFT`, `MODULE_CATALOG_PUBLISHING_DRIFT` |
| `dependency-boundaries` | `BaselineVerifier.verify()` → `verification-report.json` | `FORBIDDEN_LAYER_EDGE`, `SELF_DEPENDENCY`, `MODULE_CATALOG_DISAGREEMENT` |
| `dependency-cycles` | same | `NEW_DEPENDENCY_CYCLE` |
| `global-state` | same | `NEW_GLOBAL_STATE_FINDING` |
| `api-architecture` | same | `API_*` |
| `protocol-catalog` | same | `STABLE_PROTOCOL_CONTRACT_REMOVED` |
| `cancellation-safety` | same | `NEW_CANCELLATION_FINDING`, `CANCELLATION_RISK_WORSENED` |
| `provider-contracts` | thin Test task running `ProviderTckEnrollmentArchitectureTest` (existing), JUnit XML adapter | n/a (test outcome) |
| `store-contracts` | thin Test task running the 9 existing `*StoreTckEnrollmentArchitectureTest` classes, JUnit XML adapter | n/a (test outcome) |

The `dependency-boundaries` check additionally reflects the B1 dependency
policy verification (already inside `BaselineVerifier.verify()` via
`verifyDependencyPolicies`, which emits `FORBIDDEN_LAYER_EDGE`).

## 4. Internal model (build-logic, not published API)

```kotlin
enum class ArchitectureCheckStatus { PASS, FAIL }

data class ArchitectureCheckResult(
    val id: String,
    val status: ArchitectureCheckStatus,
    val diagnostics: List<VerificationDiagnostic>,
)

data class ArchitectureVerificationSummary(
    val checks: Int,
    val passed: Int,
    val failed: Int,
)

data class ArchitectureVerificationReport(
    val schemaVersion: String,      // "1"
    val status: ArchitectureCheckStatus,
    val checks: List<ArchitectureCheckResult>,
    val summary: ArchitectureVerificationSummary,
)
```

Put this in a new build-logic file `ArchitectureReport.kt` together with the
**pure aggregation function**:

```kotlin
object ArchitectureReportAggregator {
    fun aggregate(checkDiagnostics: Map<String, List<VerificationDiagnostic>>): ArchitectureVerificationReport
}
```

- status = FAIL iff any check has a FAILURE-severity diagnostic (or the
  check's source reported failure).
- summary = derived counts; deterministic ordering (checks sorted by id).

## 5. Report contract (`architecture-report.json`)

```json
{
  "schemaVersion": "1",
  "status": "PASS",
  "checks": [
    { "id": "module-manifest", "status": "PASS", "diagnostics": [] },
    { "id": "dependency-boundaries", "status": "PASS", "diagnostics": [] }
  ],
  "summary": { "checks": 10, "passed": 10, "failed": 0 }
}
```

Requirements:
- deterministic — no timestamps, no absolute paths, no `Instant.now()`; keys
  sorted (use the same deterministic JSON writer style as
  `ReportNormalizer.writeJson` / `ApiBaselineVerifier.deterministicJson`)
- machine-readable
- stable check IDs (the table in §3)
- typed diagnostic codes where available (`DiagnosticCode.name` + severity)
- no console-output parsing anywhere
- **the report is still written when the gate fails** — the task writes the
  JSON first, then throws `GradleException` if status == FAIL

Diagnostic JSON shape (mirror `BaselineVerifier.writeVerificationReport`):
`code`, `severity`, `message`, `modulePath`, `findingId`, `deviationId` (omit nulls).

## 6. Task wiring

Register in `MaintainabilityBaselinePlugin.kt`:

1. **`verify060Architecture`** (group `verification`, description as PR title):
   - `dependsOn("generateResolvedDependencyBaseline")` — the baseline verifier
     requires `build/reports/maintainability/resolved-dependencies.json`; the
     gate must run standalone on a clean workspace, not only after
     `verifyPr`/`verifyMaintainabilityBaseline` have created the artifact.
   - `dependsOn` the enrollment Test task below.
   - Orchestrates **fail-closed** evidence collection so the report is ALWAYS
     written before any terminal exception:
     1. Run `BaselineVerifier(...).verify()` (same construction as
        `verifyMaintainabilityBaseline`), catch exceptions → convert to a
        failure diagnostic for the affected checks.
     2. Read the typed diagnostics from the `verification-report.json` the
        verifier wrote (`build/reports/maintainability/verification-report.json`).
     3. Call `ModuleManifestVerifier.verify(...)` directly (parse catalog +
        project model + published extra + BOM constraints) → typed diagnostics;
        catch exceptions → failure diagnostics for module-manifest +
        publishing-topology.
     4. Read the JUnit XML produced by the enrollment Test task (see 2) →
        provider-contracts / store-contracts outcomes; catch exceptions →
        failure diagnostics for both enrollment checks.
     5. Partition by check id (§3), call `ArchitectureReportAggregator.aggregate`.
     6. Write `build/reports/tramai/architecture/architecture-report.json` —
        BEFORE the terminal exception, so a failing run leaves evidence.
     7. If `status == FAIL`, throw `GradleException` listing failed checks and
        the report path.
   - Do NOT wire into `verifyPr`/`check` in this PR (CI lane redesign is a
     non-goal, Epic 10.5); keep it standalone. Running it in the PR's
     verification is the evidence.
2. **`architectureContractEnrollmentTest`** — thin Test task (root project):
   - `testClassesDirs`/`classpath` from the `:tramai-testing` test source set
     (⚠️ custom Test tasks need explicit wiring or they run zero tests)
   - `useJUnitPlatform { includeTestsMatching("dev.tramai.testing.*EnrollmentArchitectureTest") }`
   - `ignoreFailures = true` (the façade reads the XML and decides; the test
     task must not throw before the report is written)
   - results land in `tramai-testing/build/test-results/architectureContractEnrollmentTest/`

   Do NOT create provider/store separate tasks — one task, the façade splits
   XML by test class name (`ProviderTckEnrollmentArchitectureTest` →
   provider-contracts; any other enrollment test → store-contracts).

   **Enrollment guard identities are pinned** (review round 1, P1): the gate
   holds the exact set of the 10 expected enrollment architecture test classes
   (`enrollmentArchitectureTestClasses`). The façade compares discovered XML
   classes against the pinned set — deleting or renaming ANY guard class fails
   `store-contracts`/`provider-contracts` even when the other guards still run.
   Discovery is by identity, not by count.

## 7. Discriminator / mutation proof (A1–A11)

New build-logic test file `ArchitectureReportAggregatorTest.kt`. Each test
feeds the aggregator a real failure diagnostic (constructed with the EXACT
`DiagnosticCode` the underlying verifier emits) and asserts the report is FAIL
with the failing check id; A8 asserts PASS. Where cheap, produce the
diagnostic via the real verifier on a fixture instead of constructing it:

- **A1 forbidden edge**: `ModuleBoundaries.checkEdge(":tramai-core", ":tramai-openai", catalog)` on the committed fixture → `FORBIDDEN_LAYER_EDGE` → `dependency-boundaries` FAIL
- **A2 dependency cycle**: `ModuleGraphAnalyzer.findCycles(A→B→A)` → `dependency-cycles` FAIL (aggregator maps `NEW_DEPENDENCY_CYCLE`; assert via constructing that code if the analyzer returns raw cycles — the aggregator consumes typed diagnostics, so construct `VerificationDiagnostic.failure(NEW_DEPENDENCY_CYCLE, ...)` and assert the bucket)
- **A3 manifest/settings drift**: `ModuleManifestVerifier.verify` with ghost/missing project path → `MODULE_CATALOG_UNKNOWN_ENTRY`/`MODULE_CATALOG_MISSING_ENTRY` → `module-manifest` FAIL
- **A4 BOM drift**: `ModuleManifestVerifier.verify` with stale bomPaths → `MODULE_CATALOG_BOM_DRIFT` → `publishing-topology` FAIL
- **A5 API classification**: `VerificationDiagnostic.failure(API_COMPATIBILITY_FAILED, ...)` → `api-architecture` FAIL
- **A6 global mutable state**: `VerificationDiagnostic.failure(NEW_GLOBAL_STATE_FINDING, ...)` → `global-state` FAIL
- **A7 cancellation**: `VerificationDiagnostic.failure(NEW_CANCELLATION_FINDING, ...)` → `cancellation-safety` FAIL
- **A8 all valid**: empty diagnostics for every check → PASS, zero failed, summary counts correct
- **A9 evidence-source exception (review round 1, P1)**: `collectEvidence` with a throwing evidence lambda → affected checks FAIL with `EMPTY_SECTION`, and `ArchitectureReportJson.write` still produces a file with `status: FAIL` — proves the report survives evidence-source failures
- **A10 enrollment identity pinning (review round 1, P1)**: deleting one pinned store class from the discovered set → `store-contracts` FAIL naming that class; renaming → FAIL in both directions (missing + unexpected); deleting the provider class → `provider-contracts` FAIL
- **A11 aggregator rejects unexpected id set (review round 1, P2)**: `aggregate` with a map missing one of the 10 stable ids throws `IllegalArgumentException` — no 9/11-check reports

The essential property: **an underlying verifier failing means
`verify060Architecture` must fail and record that exact failure** — the
aggregator maps every code the real verifiers emit to a FAIL bucket, and the
exhaustive `when (code)` classification in `baselineCheckFor` has NO else
branch, so a new `DiagnosticCode` forces an explicit in-gate/out-of-gate
decision at compile time.

## 8. Files

### Create
- `build-logic/src/main/kotlin/dev/tramai/build/quality/ArchitectureReport.kt` — model + `ArchitectureReportAggregator` (pure) + `collectEvidence` (fail-closed) + pinned `enrollmentArchitectureTestClasses` + `enrollmentGuardDiagnostics`
- `build-logic/src/test/kotlin/dev/tramai/build/quality/ArchitectureReportAggregatorTest.kt` — A1–A11
- `docs/EPIC-10.4-architecture-gate.md` — this spec (committed first)

### Modify
- `build-logic/src/main/kotlin/dev/tramai/build/quality/MaintainabilityBaselinePlugin.kt` — register `verify060Architecture` + `architectureContractEnrollmentTest`

### Do NOT touch
- Any `tramai-*/src/main/kotlin/**` file (zero runtime production changes)
- `config/quality/0.6.0-baseline.json`, `maintainability-deviations.yml`, `mutation-classifications.yml`, `test-quality.yml`
- `tramai-testing/src/**` (the enrollment tests already exist; no new TCK semantics)
- `BaselineVerifier.kt`, `ModuleCatalog.kt`, `ModuleManifest.kt`, `ModuleManifestVerifier.kt` (B1 code is consumed, not modified — only add new files + plugin registration)
- `build.gradle.kts` monolith unless the Test task wiring requires it (prefer plugin registration; the Test task needs `:tramai-testing`'s source set — if the plugin can't reach it cleanly, register the Test task in the root build.gradle.kts with the exact wiring and note why)
- `.github/workflows/**` (CI lane redesign is Epic 10.5, non-goal)

## 9. Verification (all must pass)

```bash
cd ~/Development/aurora-b2
export PATH="$HOME/.sdkman/candidates/gradle/8.5/bin:$PATH"   # CanonicalProbeFunctionalTest needs literal gradle
git status --short                                            # no .hermes/, no stray files
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :build-logic:test --no-build-cache
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew verify060Architecture
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew verify060Architecture   # 2nd run: byte-identical report
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew verifyPr --no-build-cache
```

- `:build-logic:test` — 250+ existing + A1–A8 suite PASS
- `verify060Architecture` — PASS, `architecture-report.json` written with 10
  checks, status PASS
- second `verify060Architecture` — report byte-identical (determinism proof)
- `verifyPr` — green (existing gates unaffected)

## 10. Acceptance criteria (checklist)

- [ ] `./gradlew verify060Architecture` exists
- [ ] One machine-readable `architecture-report.json` always produced (also on failure)
- [ ] Existing verifiers reused, not duplicated (only aggregation code is new)
- [ ] Stable IDs for every architectural check (§3)
- [ ] Typed diagnostics preserved (`DiagnosticCode.name` + severity in JSON)
- [ ] Failure of any mandatory check makes aggregate FAIL
- [ ] Manifest/settings topology included
- [ ] Publishing/BOM topology included
- [ ] Dependency direction included
- [ ] Dependency cycles included
- [ ] Core/examples boundary rules included
- [ ] Global-state architecture included
- [ ] API architecture included (existing baseline infrastructure)
- [ ] Protocol/event catalogue checks included
- [ ] Cancellation safety included
- [ ] Provider TCK aggregate included (enrollment tests, not full TCK execution)
- [ ] Store TCK aggregate included (enrollment tests)
- [ ] Aggregate has mutation/discriminator proof (A1–A8)
- [ ] Existing `verifyPr` remains green
- [ ] No runtime production behaviour changes
- [ ] No baseline/deviation edits

## 11. Non-goals (this PR)

- No new Detekt/formatting policy (Epic 10.1)
- No new API compatibility semantics (Track B3 / Epic 10.2)
- No coverage/mutation infrastructure (Epic 10.3)
- No CI lane redesign (Epic 10.5) — `verify060Architecture` stays standalone
- No moving Gradle code into additional convention plugins (Epic 9.2)
- No runtime/provider fixes (Track A)
- No new TCK semantics — the enrollment tests are the existing boundary; full
  provider/store TCK execution stays in the normal test tasks

If B2 discovers a missing architecture invariant belonging to another epic,
report the gap in the PR body rather than absorbing the epic.
