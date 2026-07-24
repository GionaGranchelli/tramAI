# PR #206 — Complete Spec

Branch: build/0.6.0-test-quality-baseline
Base: master (after PR #205)
Title: build(0.6.0): capture coverage, mutation, and test-performance baselines

## Context

PR #205 established the API and dependency baseline infrastructure. PR #206 completes Phase 0 with:
- JaCoCo coverage measurement for critical modules
- Targeted PITest mutation testing for critical behaviours
- Controlled test-performance timing (warm-up + 3 measured runs)
- TestKit functional tests proving CanonicalGradleProbe works end-to-end
- Canonical v0.5.0 measurement capturing all three baselines

Repository at /home/gionag/Development/aurora, branch build/0.6.0-test-quality-baseline.
Gradle 9.0.0, Kotlin 2.3.0, JVM 21.
Files under build-logic/src/ are gitignored by **/build/ — always use `git add -f` to stage.

## Files to read for context (survey these first)

Read all of these before writing code:

1. build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineGenerator.kt
2. build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineModel.kt
3. build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineVerifier.kt
4. build-logic/src/main/kotlin/dev/tramai/build/quality/MaintainabilityBaselinePlugin.kt
5. build-logic/src/main/kotlin/dev/tramai/build/quality/CanonicalGradleProbe.kt
6. build-logic/src/main/kotlin/dev/tramai/build/quality/VerificationDiagnostic.kt
7. build-logic/src/main/kotlin/dev/tramai/build/quality/ApiBaselineVerifier.kt
8. build-logic/src/main/kotlin/dev/tramai/build/quality/DependencyBaselineVerifier.kt
9. build-logic/src/main/kotlin/dev/tramai/build/quality/DependencyEdgeNormalizer.kt
10. build-logic/src/main/kotlin/dev/tramai/build/quality/MeasurementContext.kt
11. build-logic/src/main/kotlin/dev/tramai/build/quality/ReportNormalizer.kt
12. build-logic/build.gradle.kts
13. config/quality/0.6.0-baseline.json (first 100 lines only)
14. .github/workflows/maintainability-full.yml
15. .github/workflows/maintainability-baseline.yml
16. docs/roadmap-0.6.0-phase-0-api-dependency.md
17. gradle/libs.versions.toml (version catalogue)

## What the codebase currently looks like at the start of PR #206

- BaselineModel.kt has ApiBaseline, ResolvedDependency, BaselineDocument etc.
- CoverageData and MutationData in BaselineModel default to status="pending"
- Test performance parsing exists inside BaselineGenerator.generateTestPerformanceBaseline() as inline code
- MaintainabilityBaselinePlugin registers stub tasks (verifyFullMaintainabilityBaseline prints a message)
- CanonicalGradleProbe probes API and dependency only — no coverage/mutation/timing
- No JaCoCo or PITest configuration in build-logic
- No TestKit functional tests
- verifyFullMaintainabilityBaseline is a stub that prints "Full maintainability baseline verification complete."

## Workstream A: Test-quality configuration

Create `config/quality/test-quality.yml`:
```yaml
schemaVersion: "1"
criticalModules:
  - ":tramai-core"
  - ":tramai-engine"
  - ":tramai-security"
  - ":tramai-sovereign"
  - ":tramai-standalone"
  - ":tramai-structured"
  - ":tramai-orchestration"
  - ":tramai-persistence-file"
  - ":tramai-persistence-jdbc"
coverage:
  regressionTolerancePercentagePoints: 1.0
  exclusions:
    - pattern: "**/model/**"
      reason: "Generated model classes"
mutation:
  regressionTolerancePercentagePoints: 1.0
  targetFamilies:
    policy:
      modules: [":tramai-security", ":tramai-sovereign"]
    approval:
      modules: [":tramai-sovereign"]
    routing:
      modules: [":tramai-engine"]
    retry:
      modules: [":tramai-engine"]
    tools:
      modules: [":tramai-engine", ":tramai-security"]
    evidence:
      modules: [":tramai-sovereign"]
    structuredOutput:
      modules: [":tramai-structured"]
```

Create `TestQualityConfiguration.kt` that parses this YAML.
Must reject: unknown modules, duplicate family names, empty targets, absolute paths, invalid percentages, coverage exclusions without a reason.

## Workstream B: TestKit functional tests for CanonicalGradleProbe

Create a committed multi-project test fixture at:
`build-logic/src/test/resources/fixtures/canonical-probe/`

With:
- settings.gradle.kts (2-3 modules: app, library)
- build.gradle.kts (project deps, external deps)
- app/src/main/kotlin/ (simple Kotlin sources with public API)
- library/src/main/kotlin/ (simple Kotlin sources)
- local-maven-repository/ (pre-built artifacts to avoid network)

Create `CanonicalGradleProbeFunctionalTest.kt` with TestKit tests for:
1. API probe produces non-empty records with valid SHA-256 hashes
2. Dependency probe preserves consumer/config/immediate-parent identity
3. Explicit output directory is respected
4. Two executions produce equivalent normalized output
5. Probe output does not modify tracked files
6. Measured checkout remains clean (git status --porcelain)
7. Nested Gradle failure is propagated
8. Missing reports fail rather than returning empty
9. Output does not contain temp dirs, home dirs, cache paths, usernames
10. Local Maven repo sufficient — no network required

Do NOT commit the fixture's build/ or .gradle/ directories.

## Workstream C: JaCoCo coverage baseline

Add JaCoCo plugin to build-logic/build.gradle.kts (for `:build-logic:test` only).

Create:
- CoverageReportParser.kt — parse JaCoCo XML, normalize counters
- CoverageBaselineVerifier.kt — verify against committed baseline
- CoverageCollector.kt — collect JaCoCo XML reports from configured modules

Update BaselineModel.kt: CoverageData currently has status/lineCovered/lineTotal only.
Add branch covered/missed counters. Change default status from "pending" to "not_configured".

Update BaselineGenerator.kt generateCoverageBaseline() to actually collect coverage from JaCoCo XML reports.

Hard failures:
- Critical module produces no coverage report → fail
- Non-empty critical module reports zero executable lines → fail
- XML is malformed → fail
- Report contains unknown module → fail
- Coverage leaks absolute paths → fail
- Required branch counters absent → fail
- Coverage status remains pending → fail

Ratcheted failures:
- Critical-module line or branch coverage drops beyond tolerance → fail
- Critical behaviour family loses all coverage → fail
- New undocumented exclusion → fail

## Workstream D: PITest mutation baseline

Add PITest plugin to build-logic/build.gradle.kts.

Create:
- MutationReportParser.kt — parse PITest XML/HTML reports
- MutationIdentity.kt — stable identity (module+class+method+mutator+description, NOT line numbers)
- MutationBaselineVerifier.kt — verify against committed baseline

Update BaselineModel.kt MutationData to include:
- totalMutants, killedMutants, survivedMutants
- mutationScore (percentage)
- per-family breakdown

Hard failures:
- No mutation report for configured target → fail
- Zero mutants for non-empty configured target → fail
- Malformed report → fail
- Unclassified surviving mutant → fail
- Missing-test survivor without issue/target phase → fail
- Mutation status remains pending → fail

Create `config/quality/mutation-classifications.yml` with an empty classifications list for start.

## Workstream E: Test-performance baseline

Extract test-performance parsing from BaselineGenerator into dedicated:
- TestPerformanceCollector.kt
- TestPerformanceAggregator.kt
- TestPerformanceVerifier.kt

The execution model must:
1. One warm-up run
2. Three measured runs
3. Aggregate using median duration
4. Store individual observations before aggregation

Record: module duration, class duration, individual test duration, test count, skipped count, failure count, source set, test task name, JDK version, Gradle version

Comparison policy:
- >25% module regression → warning
- >50% critical-test regression → warning
- Newly skipped critical test → failure
- Missing expected test report → failure

## Workstream F: Canonical probe extension

Extend CanonicalGradleProbe with:
```kotlin
fun probeTestQualityBaseline(configuration: TestQualityConfiguration): TestQualityProbeResult
```

This should inject measurement configuration into the detached v0.5.0 worktree without editing tracked files.
Requirements:
- Use v0.5.0 Gradle wrapper
- Use temporary GRADLE_USER_HOME
- Write output outside measured source tree
- Leave git status --porcelain empty
- Fail if any expected module report is missing
- Record analyzer version and measured commit
- Never replace unavailable measurement with "pending"

## Workstream G: Gradle task graph

Update MaintainabilityBaselinePlugin.kt to register:
- generateTestPerformanceBaseline
- generateCoverageBaseline
- generateCriticalMutationBaseline
- generateFullMaintainabilityBaseline

- verifyTestPerformanceBaseline
- verifyCriticalCoverage
- verifyCriticalMutationBaseline
- verifyFullMaintainabilityBaseline

Relationships:
```
generateFullMaintainabilityBaseline
├── generateMaintainabilityBaseline (existing)
├── generateTestPerformanceBaseline
├── generateCoverageBaseline
└── generateCriticalMutationBaseline

verifyFullMaintainabilityBaseline
├── verifyMaintainabilityBaseline (existing)
├── verifyTestPerformanceBaseline
└── verifyCriticalMutationBaseline
```

verifyFullMaintainabilityBaseline must execute real checks, not print a message.

## Workstream H: CI changes

Update `.github/workflows/maintainability-full.yml` to:
1. Create canonical v0.5.0 worktree
2. Run warm-up test execution
3. Run three measured test runs
4. Generate critical JaCoCo coverage
5. Execute targeted PITest mutation
6. Upload raw XML/HTML artifacts
7. Update job summary with: critical line/branch coverage per module, total/killed/surviving mutants, mutation score by behaviour, median test duration, slowest tests, skipped test count

## Files to create

build-logic/src/main/kotlin/dev/tramai/build/quality/
  CoverageBaselineVerifier.kt
  CoverageReportParser.kt
  CoverageCollector.kt
  MutationBaselineVerifier.kt
  MutationIdentity.kt
  MutationReportParser.kt
  TestPerformanceAggregator.kt
  TestPerformanceCollector.kt
  TestPerformanceVerifier.kt
  TestQualityConfiguration.kt

build-logic/src/test/kotlin/dev/tramai/build/quality/
  CanonicalGradleProbeFunctionalTest.kt
  CoverageBaselineVerifierTest.kt
  CoverageReportParserTest.kt
  MutationBaselineVerifierTest.kt
  MutationReportParserTest.kt
  TestPerformanceAggregatorTest.kt
  TestPerformanceVerifierTest.kt
  TestQualityConfigurationTest.kt

build-logic/src/test/resources/fixtures/canonical-probe/
  settings.gradle.kts
  build.gradle.kts
  app/
  library/
  local-maven-repository/

config/quality/
  test-quality.yml
  mutation-classifications.yml

## Files to modify

build-logic/src/main/kotlin/dev/tramai/build/quality/
  BaselineGenerator.kt (extract inline parsing, add coverage/mutation generation)
  BaselineModel.kt (extend CoverageData, MutationData)
  BaselineVerifier.kt (wire new verifiers)
  CanonicalGradleProbe.kt (add probeTestQualityBaseline)
  MaintainabilityBaselinePlugin.kt (register new tasks, fix verifyFullMaintainabilityBaseline)
  VerificationDiagnostic.kt (add new diagnostic codes if needed)

build-logic/build.gradle.kts (add JaCoCo, PITest plugins/deps)

build-logic/src/test/kotlin/dev/tramai/build/quality/
  (new test files as listed above)

.github/workflows/
  maintainability-full.yml (real tasks, job summary)
  maintainability-baseline.yml (add coverage aggregation)

config/quality/
  0.6.0-baseline.json (coverage, mutation, timing sections populated)

docs/
  roadmap-0.6.0-phase-0-test-quality.md (new spec doc)

## Non-goals

- Do NOT modify any TramAI runtime source code (anything outside build-logic/src/)
- Do NOT add JaCoCo or PITest to any consumer module - only build-logic infrastructure
- Do NOT enforce arbitrary coverage percentages - ratchet against observed v0.5.0
- Do NOT introduce timing as a hard CI gate
- Do NOT mutate the entire repository - only critical behaviour families
- Do NOT change existing maintainability-baseline.yml structure (only add to full workflow)

## Verification commands (run after implementation)

```bash
./gradlew :build-logic:compileKotlin :build-logic:compileTestKotlin
./gradlew :build-logic:test
./gradlew generateCoverageBaseline || echo "coverage may need modules built first"
./gradlew verifyFullMaintainabilityBaseline || echo "stub may remain in this run"
```
