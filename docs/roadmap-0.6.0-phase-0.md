# Phase 0 — Baseline and Architecture Contract

## Epic 0.1: Establish the 0.6.0 Maintainability Baseline

> **Priority:** P0
> **Nature:** Measurement, characterization and quality infrastructure
> **Runtime behaviour changes allowed:** None
> **Required before:** Engine decomposition, workflow decomposition, module restructuring or public API cleanup

### Goal

Record the exact state of TramAI 0.5.0 before structural changes begin so that:

* Existing runtime behaviour is protected.
* Architectural improvement is measurable.
* Public API compatibility is visible.
* New maintainability debt cannot be introduced silently.
* Every later refactoring PR can explain what changed and why.
* Human reviewers and AI coding agents have a reliable architectural map.

The baseline is not a declaration that the current architecture is acceptable. It is the starting point from which 0.6.0 must improve.

---

## Baseline Principles

### 1. Use an immutable release reference

The baseline must point to the immutable Git commit associated with the published `v0.5.0` tag.

The baseline must record:

* Repository
* Release tag
* Commit SHA
* Commit timestamp
* TramAI version
* Gradle version
* Kotlin version
* JVM target
* CI JDK
* Operating system used for measured reports
* Baseline schema version

The baseline must not use a moving branch such as `master`.

If the `v0.5.0` tag and Central Portal publication do not point to the same source commit, Phase 0 must stop until the discrepancy is resolved.

### 2. Separate deterministic and environmental measurements

The following outputs must be byte-for-byte deterministic for the same source commit and tool versions:

* Module dependency graph
* Module list
* Source line counts
* File and declaration rankings
* Public API dumps
* Resolved dependency graph
* Broad-catch inventory
* Global-state inventory
* Clock/randomness inventory
* Protocol-name inventory
* Baseline JSON structure

The following are measured observations and are not expected to be byte-identical between machines:

* Test duration
* Coverage instrumentation results
* Mutation execution duration
* Memory usage

Measured observations must still use:

* Stable ordering
* Repository-relative paths
* Fixed schemas
* Recorded environment metadata
* Defined tolerances
* No usernames, home directories or absolute workspace paths

Generated timestamps should use the baseline commit timestamp or `SOURCE_DATE_EPOCH`, not the current wall clock.

### 3. Baseline updates must be explicit

The baseline must never update automatically during a normal build.

Updating it requires an explicit command, for example:

```bash
./gradlew updateMaintainabilityBaseline \
  -PmaintainabilityChangeReason="Extract provider execution coordinator" \
  -PmaintainabilityIssue="#204"
```

Every accepted regression must have:

* A reason
* A related issue or PR
* An owner
* A scope
* A removal or reconsideration phase
* The previous and new values

Improvements do not require a waiver, but they must remain visible in the generated diff.

---

## Critical Runtime Boundary

The first baseline must give special treatment to these modules:

* `tramai-core`
* `tramai-engine`
* `tramai-security`
* `tramai-sovereign`
* `tramai-standalone`
* `tramai-structured`
* `tramai-orchestration`
* `tramai-persistence-file`
* `tramai-persistence-jdbc`
* Sovereign Spring Boot starters
* Runtime evidence and approval operations modules

These are the modules where architectural or semantic regressions could affect:

* Policy enforcement
* Approval safety
* Provider selection and fallback
* Tool exposure and execution
* Cancellation
* Retry behaviour
* Persistence and replay
* Audit ordering
* Runtime evidence
* Structured-output contracts

Provider, RAG, platform, server, dashboard and example modules remain part of the repository-wide baseline, but mutation testing and deep characterization initially focus on the critical runtime boundary.

---

## Workstream A — Release Identity and Reproducibility

### Task 0.1.1: Record the exact 0.5.0 release baseline

Create a machine-readable release identity containing:

```json
{
  "repository": "GionaGranchelli/tramAI",
  "releaseTag": "v0.5.0",
  "commitSha": "<full-commit-sha>",
  "commitTimestamp": "<ISO-8601-from-git>",
  "tramaiVersion": "0.5.0",
  "baselineSchemaVersion": "1"
}
```

Verification must confirm:

1. The tag exists.
2. The tag resolves to the recorded commit.
3. `gradle.properties` declares `0.5.0` at that commit.
4. No `-SNAPSHOT` version appears in published module metadata.
5. The release commit can run the canonical 0.5.0 verification suite from a clean checkout.
6. The baseline does not depend on uncommitted files.

### Output

* Baseline identity section in `config/quality/0.6.0-baseline.json`
* Human-readable release reference in the baseline document
* `verifyMaintainabilityBaselineIdentity` task

---

## Workstream B — Structural Architecture Baseline

### Task 0.1.2: Generate the module dependency graph

Generate the graph from actual Gradle project dependencies rather than manually maintained documentation.

The graph must include:

* Every included Gradle project
* Direct project dependencies
* Dependency scope:

  * `api`
  * `implementation`
  * `compileOnly`
  * `runtimeOnly`
  * test-only
* Incoming dependency count
* Outgoing dependency count
* Layer classification
* Publishable/non-publishable classification
* Detected dependency cycles

Required formats:

* JSON for verification
* Graphviz DOT for tooling
* Mermaid or Markdown for human review

The generated documentation should classify modules into architectural layers:

1. Core contracts
2. Runtime execution
3. Governance and security
4. Persistence
5. Provider adapters
6. Framework integrations
7. Operations and observability
8. Higher-level capabilities
9. Applications and examples

The graph must clearly identify violations such as:

* Core depending on Spring
* Runtime depending on examples
* Provider adapters depending on platform or server modules
* Public modules depending on internal application modules
* Cyclic project dependencies

### Outputs

* `docs/architecture/module-dependency-graph.md`
* `build/reports/maintainability/module-dependencies.json`
* `build/reports/maintainability/module-dependencies.dot`
* `verifyModuleDependencyGraph` task

### Initial gate

Phase 0 records existing layering violations. After the baseline is committed:

* No new dependency cycle is permitted.
* No new dependency crossing an explicitly forbidden layer is permitted.
* Existing violations must be assigned to a 0.6.0 cleanup epic.

---

### Task 0.1.3: Record source size by module and source set

Count source code using a documented, stable definition.

At minimum, report:

* Physical lines
* Non-blank lines
* Comment lines
* Code lines
* Number of files

Separate:

* `src/main`
* `src/test`
* `src/testFixtures`
* Integration/E2E source sets
* Generated source
* Build logic
* Examples
* Documentation

Production LOC means executable or declarative source under production source sets. Test LOC must not be mixed with production LOC.

Generated sources must be excluded from maintainability rankings but reported separately.

### Required report

| Module | Production code | Unit test code | Fixture code | Integration test code | Test-to-production ratio |
| ------ | --------------: | -------------: | -----------: | --------------------: | -----------------------: |

The baseline must not use LOC as a release target by itself. LOC is contextual evidence used to find concentration and understand change.

---

### Task 0.1.4: Record structural hotspots

Generate ranked lists for:

* Largest production files
* Largest test files
* Largest classes
* Classes with the most functions
* Longest functions
* Highest cyclomatic complexity
* Highest cognitive complexity
* Constructors with the most parameters
* Functions with the most parameters
* Classes with highest dependency fan-out
* Classes with highest dependency fan-in
* Packages with the highest internal coupling
* Packages with the highest instability

Each record must contain:

```json
{
  "module": "tramai-engine",
  "path": "src/main/kotlin/...",
  "declaration": "TramaiInvocationHandler",
  "metric": "constructorParameterCount",
  "value": 31
}
```

Tool findings must use repository-relative paths and declaration names. Line numbers may be included for navigation but must not be the identity of a finding.

Recommended implementation:

* A pinned Kotlin-aware static-analysis tool for complexity and declaration size
* JVM bytecode analysis for class-level fan-in/fan-out
* A small TramAI-owned aggregator that normalizes outputs into the baseline schema

Do not rely on SonarQube as the only source because the baseline must work in clean local and CI environments.

### Ratchet policy

After Phase 0:

* A new file may not exceed the current agreed file-size ceiling.
* A new constructor may not exceed the agreed parameter ceiling.
* A new function may not exceed the agreed complexity ceiling.
* Existing hotspots may remain temporarily but must not worsen.
* Extracting code without reducing coupling does not count as architectural improvement.

---

## Workstream C — Test and Behaviour Baseline

### Task 0.1.5: Record test duration

Collect timing from structured JUnit reports.

Report:

* Total test duration by module
* Duration by source set
* Slowest test classes
* Slowest individual tests
* Number of tests per module
* Number of skipped tests
* Number of retries, where supported
* Test process failures
* Testcontainers-dependent suites separately

Because timings vary, the official baseline should be generated in a controlled CI environment using:

1. One warm-up run
2. Three measured runs
3. Median duration as the recorded value

Normal PR CI may use one run and compare using a tolerance rather than exact equality.

Suggested alert thresholds:

* Module test duration regression greater than 25%
* Individual critical test regression greater than 50%
* New test class slower than the agreed slow-test threshold
* Any newly skipped test in a critical module

A timing regression should initially warn rather than fail unless it is severe or repeated.

### Outputs

* `build/reports/maintainability/test-performance.json`
* Slow-test section in the human-readable report
* CI artifact containing raw JUnit reports

---

### Task 0.1.6: Capture public binary APIs

Introduce binary API dump generation for all published JVM modules.

The API dump must include:

* Public classes
* Public interfaces
* Public functions and methods
* Public constructors
* Public properties and fields
* Public annotations
* Generic signatures
* Inheritance
* Publicly exposed parameter and return types

Classify modules as:

* Stable API checked
* Preview API checked but allowed to evolve with explicit review
* Internal implementation excluded
* Example/application excluded

The baseline must record the public API of the 0.5.0 release before 0.6.0 changes begin.

Required behaviour:

* `apiDump` deliberately updates API files.
* `apiCheck` fails on unreviewed public API changes.
* API removals and incompatible changes require an explicit compatibility decision.
* Internal refactoring must not change published APIs accidentally.

### Outputs

* API dumps under each publishable module or a central versioned API directory
* API dump hashes in the baseline JSON
* `verifyPublicApiBaseline` task

---

### Task 0.1.7: Record resolved dependencies

Generate two related inventories:

#### Module graph

The relationships between TramAI modules.

#### External dependency graph

The fully resolved external components used by each relevant runtime and test classpath.

For each dependency, record:

* Group
* Artifact
* Selected version
* Requested version
* Direct or transitive
* Configuration
* Selection reason
* Dependency path
* Module consumers

Reuse the existing CycloneDX SBOM where appropriate, but add Gradle resolution information that an SBOM alone does not provide.

Identify:

* Multiple versions of the same dependency
* Dynamic versions
* Version ranges
* Snapshots
* Unexpected provider dependencies pulled into core modules
* Test libraries present on runtime classpaths
* Framework integrations leaking into core artifacts

### Outputs

* `build/reports/maintainability/resolved-dependencies.json`
* Dependency convergence section in the baseline document
* `verifyDependencyConvergence` task

---

### Task 0.1.8: Establish code-coverage baselines

Generate line and branch coverage for critical runtime modules.

Coverage must be broken down by:

* Module
* Package
* Class
* Critical behaviour family

Critical behaviour families include:

* Policy allow, deny and approval decisions
* Tool exposure and execution
* Approval creation and resume
* Provider retry and fallback
* Cancellation propagation
* Evidence ordering and serialization
* Workflow checkpoint and resume
* Structured-output parsing and validation
* Persistence concurrency and optimistic locking

The baseline must not declare one repository-wide number sufficient.

Initial policy:

* Phase 0 records existing coverage.
* Existing coverage must not decrease silently.
* New production code in critical modules must include meaningful tests.
* Coverage exclusions must be documented and narrow.
* Data classes, generated code and trivial no-op implementations may be excluded only through explicit configuration.

Coverage does not prove test quality. It is paired with mutation testing.

### Outputs

* XML and HTML coverage reports
* Normalized coverage JSON
* Module and critical-path coverage summary in the baseline document
* `verifyCriticalCoverage` task

---

### Task 0.1.9: Establish a targeted mutation-testing baseline

Mutation testing should initially target behaviour where passing tests must prove more than execution:

* Policy decision enforcement
* Approval state transitions
* Provider selection
* Retry limits and retry classification
* Fallback ordering
* Tool permission decisions
* Evidence field and ordering guarantees
* Replay and version checks
* Security-sensitive validation
* Structured-output contract validation

Report:

* Generated mutants
* Killed mutants
* Surviving mutants
* Mutants with no coverage
* Timed-out mutants
* Mutation score
* Test-strength score where available
* Surviving mutants grouped by source file and behaviour

Phase 0 must not require a perfect mutation score.

Every surviving mutant must be classified as:

1. Missing test
2. Equivalent mutant
3. Low-risk implementation detail
4. Tool limitation
5. Known design ambiguity

High-risk surviving mutants must become 0.6.0 tasks before the relevant code is refactored.

Run targeted mutation tests:

* On demand locally
* In a scheduled or manually triggered CI job
* Before completion of each critical refactoring epic
* As part of the final 0.6.0 release gate

Do not run the full repository mutation suite on every small documentation PR.

### Outputs

* `build/reports/maintainability/mutation/`
* Mutation summary in baseline JSON
* `verifyCriticalMutationBaseline` task

---

## Workstream D — Runtime Safety Inventories

### Task 0.1.10: Inventory broad catches in suspend-capable code

A plain text search is not sufficient. The inventory should understand Kotlin declarations and identify:

* `catch (Exception)`
* `catch (Throwable)`
* `runCatching`
* Broad exception conversion
* Broad catches inside suspend functions
* Broad catches inside suspend lambdas
* Broad catches around provider, tool, workflow, persistence and observer calls

For each finding, record:

* Module
* File
* Function
* Catch type
* Whether the function is suspend-capable
* Whether cancellation is explicitly rethrown
* Whether the exception is transformed
* Whether retry/fallback may follow
* Risk classification

Risk classifications:

* **Critical:** Cancellation can become retry, fallback or ordinary failure.
* **High:** Cancellation can be wrapped or hidden.
* **Medium:** Broad catch exists but execution is not suspend-capable.
* **Accepted:** Cancellation is explicitly rethrown before transformation.

After Phase 0, no new unapproved critical or high finding may be introduced.

### Outputs

* `build/reports/maintainability/cancellation-safety.json`
* Cancellation-risk section in the baseline document
* Custom static-analysis rule or equivalent architecture test
* `verifyCancellationCatchInventory` task

---

### Task 0.1.11: Inventory process-global mutable state

Identify:

* Mutable top-level variables
* Mutable fields on Kotlin `object` declarations
* Mutable companion-object state
* Static mutable Java fields
* Global registries
* Singleton maps
* Singleton caches
* Singleton stores
* Global worker bindings
* Global coroutine scopes
* Process-wide hooks

Exclude only:

* Immutable constants
* Stateless no-op objects
* Immutable serializers and value objects
* Explicitly documented JVM-wide infrastructure

For every mutable global, record:

* Owner
* Lifecycle
* Thread-safety mechanism
* Clear/reset mechanism
* Test-isolation behaviour
* Tenant/runtime isolation behaviour
* Planned removal or justification

After Phase 0, a new process-global mutable registry is forbidden unless an ADR explicitly permits it.

### Outputs

* `build/reports/maintainability/global-state.json`
* Global-state risk table in the baseline document
* `verifyGlobalStateInventory` task

---

### Task 0.1.12: Inventory direct time, identity and randomness access

Search production runtime code for:

* `System.currentTimeMillis()`
* `System.nanoTime()`
* `Instant.now()`
* `Clock.systemUTC()`
* `Clock.systemDefaultZone()`
* `UUID.randomUUID()`
* `Random.Default`
* `ThreadLocalRandom`
* `Math.random()`
* Direct sleep/delay backoff calculations
* Direct creation of secure randomness

Classify each use as:

* Business/audit time
* Expiry time
* Scheduling time
* Retry/backoff jitter
* Correlation or identity generation
* Performance measurement
* Cryptographic randomness
* Test-only use

The target architecture should eventually inject:

* `Clock`
* `IdGenerator`
* `BackoffStrategy`
* `JitterSource`
* Monotonic-time abstraction where required

Cryptographic randomness may use a dedicated secure source rather than a deterministic abstraction, but ownership and test seams must still be explicit.

### Outputs

* `build/reports/maintainability/nondeterminism.json`
* Direct-time and randomness section in the baseline document
* `verifyNondeterminismInventory` task

---

## Workstream E — Public Protocol Inventory

### Task 0.1.13: Inventory public strings and protocol identifiers

TramAI exposes more than classes and methods. Operational users and tests may depend on:

* Safe reason codes
* Exception messages
* Audit event names
* Evidence event types
* Metric names
* Span names
* Attribute keys
* Health and status names
* Configuration-property names
* JSON schema identifiers
* Manifest versions

Generate a protocol catalogue containing:

```json
{
  "category": "audit-event",
  "name": "tramai.tool.execution.denied",
  "module": "tramai-engine",
  "source": "src/main/kotlin/...",
  "stability": "runtime-contract",
  "containsSensitiveData": false
}
```

Classify protocol items as:

* Stable external contract
* Preview external contract
* Internal diagnostic
* Test-only
* Deprecated
* Unknown and requiring review

The baseline must identify:

* Duplicate names with different meanings
* Equivalent concepts using inconsistent names
* Public strings built dynamically
* Raw exception messages sent to models or callers
* Attribute keys duplicated across modules
* Metrics or events without ownership documentation

The initial inventory may contain unknown items, but 0.6.0 cannot finish with critical runtime protocol identifiers unclassified.

### Outputs

* `config/quality/runtime-protocol-catalog.json`
* Protocol section in the baseline document
* `verifyRuntimeProtocolCatalog` task

---

## Machine-Readable Baseline Model

The baseline JSON should have this approximate structure:

```json
{
  "schemaVersion": "1",
  "baseline": {
    "repository": "GionaGranchelli/tramAI",
    "releaseTag": "v0.5.0",
    "commitSha": "<sha>",
    "commitTimestamp": "<timestamp>",
    "tramaiVersion": "0.5.0"
  },
  "toolchain": {
    "gradle": "<version>",
    "kotlin": "<version>",
    "jvmTarget": "21",
    "ciJdk": "<version>"
  },
  "modules": [],
  "moduleDependencies": {},
  "sourceMetrics": {},
  "structuralHotspots": {},
  "testPerformance": {},
  "publicApis": {},
  "resolvedDependencies": {},
  "coverage": {},
  "mutation": {},
  "cancellationSafety": {},
  "globalState": {},
  "nondeterminism": {},
  "runtimeProtocol": {},
  "ratchets": {},
  "acceptedDeviations": []
}
```

JSON keys, arrays and findings must be sorted deterministically.

---

## Required Gradle Tasks

Quality tooling should live in isolated build logic rather than expanding the root build script.

Suggested structure:

```text
build-logic/
  src/main/kotlin/
    tramai.maintainability-baseline.gradle.kts
    dev/tramai/build/quality/
      BaselineModel.kt
      BaselineGenerator.kt
      ModuleGraphAnalyzer.kt
      SourceMetricsAnalyzer.kt
      ReportNormalizer.kt
```

Required task graph:

```text
generateMaintainabilityBaseline
├── generateModuleDependencyGraph
├── generateSourceMetrics
├── generateStructuralHotspots
├── generatePublicApiBaseline
├── generateResolvedDependencyGraph
├── generateCancellationCatchInventory
├── generateGlobalStateInventory
├── generateNondeterminismInventory
├── generateRuntimeProtocolCatalog
├── generateCoverageBaseline
└── generateTestPerformanceBaseline

generateFullMaintainabilityBaseline
├── generateMaintainabilityBaseline
└── generateCriticalMutationBaseline

verifyMaintainabilityBaseline
├── verifyMaintainabilityBaselineIdentity
├── verifyModuleDependencyGraph
├── verifyPublicApiBaseline
├── verifyDependencyConvergence
├── verifyCancellationCatchInventory
├── verifyGlobalStateInventory
├── verifyNondeterminismInventory
├── verifyRuntimeProtocolCatalog
└── verifyBaselineFreshness
```

`verifyMaintainabilityBaseline` must be practical for regular CI.

Mutation testing and repeated test-performance runs should be placed behind:

```text
verifyFullMaintainabilityBaseline
```

and run on:

* Manual workflow dispatch
* Scheduled quality runs
* Critical refactoring PRs
* 0.6.0 release readiness

---

## Baseline Comparison Rules

Metrics should use three comparison modes.

### Hard failure

* Baseline tag or SHA mismatch
* Missing module
* New dependency cycle
* Missing API dump
* Unapproved binary API break
* Missing critical inventory
* Invalid baseline schema
* Absolute paths or machine-specific data
* New forbidden layer dependency

### Ratcheted failure

* Increased number of critical cancellation findings
* Increased process-global mutable state
* Increased unclassified runtime protocol identifiers
* New direct business-time/randomness access
* Existing hotspot made materially worse
* Coverage regression beyond tolerance
* Mutation-score regression beyond tolerance

### Warning/trend

* Test duration regression
* Non-critical complexity movement
* Documentation size movement
* Test-to-production ratio movement
* Dependency count movement without a forbidden boundary violation

---

## Refactoring Safety Contract

No refactoring PR may move or split critical runtime behaviour unless the old behaviour is characterized first.

Each refactoring PR must include a table:

| Behaviour being moved      | Existing tests      | New characterization tests | Expected semantic change              |
| -------------------------- | ------------------- | -------------------------- | ------------------------------------- |
| Provider fallback ordering | Test names or links | Added tests                | None                                  |
| Audit event ordering       | Test names or links | Added tests                | None                                  |
| Cancellation propagation   | Test names or links | Added tests                | Correctness fix documented separately |

A PR claiming “no behaviour change” must demonstrate:

* Same public API unless explicitly approved
* Same reason codes
* Same audit and evidence ordering
* Same retry and fallback count
* Same approval and replay semantics
* Same persistence compatibility
* Same observer-visible lifecycle
* Same structured-output contract

Correctness fixes must not be hidden inside a mechanical refactor. They should be isolated or explicitly separated in commits and tests.

---

## Baseline Deviation Process

Create:

```text
config/quality/maintainability-deviations.yml
```

Each deviation should contain:

```yaml
- id: MQ-001
  metric: constructor-parameter-count
  scope: tramai-engine:TramaiInvocationHandler
  baseline: 31
  allowed: 31
  reason: Existing 0.5.0 hotspot; decomposition planned
  targetPhase: Phase 3
  issue: "#..."
```

Rules:

* Existing debt receives an identifier.
* A waiver cannot have an empty reason.
* A new waiver must identify a target phase or explicitly state why it is permanent.
* Updating the baseline cannot silently remove an active deviation.
* Completed deviations are retained in history but removed from the active allowance set.
* CI prints active deviations in the quality report.

---

## CI Integration

Add a dedicated `maintainability-baseline` job.

### Pull-request job

Runs:

```bash
./gradlew verifyMaintainabilityBaseline
```

Publishes:

```text
build/reports/maintainability/
```

The job summary should show:

* New regressions
* Improvements
* API changes
* New dependency edges
* New broad catches
* New globals
* New clock/randomness uses
* Coverage movement
* Active waivers

### Full quality job

Runs manually, on schedule and for the 0.6.0 release branch:

```bash
./gradlew verifyFullMaintainabilityBaseline
```

Additionally publishes:

* Mutation report
* Full coverage report
* Test-performance report
* Module graph
* Public API dumps
* Dependency graph
* Baseline diff

The baseline artifact must contain no credentials, secrets, prompts, model outputs, user content or absolute filesystem paths.

---

## Human-Readable Baseline Document

Create:

```text
docs/releases/0.6.0-maintainability-baseline.md
```

Recommended structure:

1. Baseline identity
2. Executive assessment
3. Module architecture
4. Critical runtime boundary
5. Largest structural hotspots
6. Public API surface
7. Test and performance state
8. Coverage state
9. Mutation-testing state
10. Cancellation risks
11. Global-state risks
12. Time/randomness risks
13. Runtime protocol inventory
14. Accepted debt and deviations
15. 0.6.0 target improvements
16. Reproduction commands
17. Claim boundaries

The executive assessment should be honest. It should not present high test volume or documentation volume as evidence that architecture is already maintainable.

---

## Additional Deliverable: Characterization Matrix

Create:

```text
docs/releases/0.6.0-characterization-matrix.md
```

It should track the behaviour that must be protected before refactoring:

| Area             | Behaviour                                   | Current tests  | Missing characterization     | Required before phase       |
| ---------------- | ------------------------------------------- | -------------- | ---------------------------- | --------------------------- |
| Provider routing | Candidate and fallback order                | Existing tests | Cancellation during fallback | Provider extraction         |
| Tools            | Exposure → execution → reinjection ordering | Existing tests | Tool cancellation            | Tool coordinator extraction |
| Approval         | Suspend, approve, deny, expire, resume      | Existing tests | Restart boundary matrix      | Approval extraction         |
| Evidence         | Record ordering and safe fields             | Existing tests | Refactor equivalence fixture | Evidence extraction         |
| Workflow         | Checkpoint and resume                       | Existing tests | Registry isolation           | Workflow decomposition      |

A refactoring epic cannot begin while its row still has unresolved critical characterization gaps.

---

## Proposed PR Sequence

### PR 0A — Maintainability baseline contract

**Purpose:**

* Add this roadmap detail
* Define baseline schema
* Define deterministic-generation rules
* Add deviation format
* Add refactoring-safety template
* Add characterization matrix skeleton

**No runtime changes.**

### PR 0B — Structural baseline tooling

**Purpose:**

* Add isolated quality build logic
* Generate module graph
* Generate source metrics
* Generate hotspot rankings
* Generate safety inventories
* Generate runtime protocol catalogue

**No runtime changes.**

### PR 0C — API, dependency and test-quality baseline

**Purpose:**

* Add binary API dumps
* Add resolved dependency graph
* Add coverage reports
* Add test-performance reports
* Add targeted mutation-testing setup

**No runtime changes.**

### PR 0D — Baseline capture and CI enforcement

**Purpose:**

* Capture the final 0.5.0 baseline
* Commit baseline JSON
* Commit human-readable reports
* Add CI artifact publication
* Add `verifyMaintainabilityBaseline`
* Add full quality workflow
* Establish initial ratchets and waivers

**No runtime changes.**

---

## Deliverables

Required:

* `docs/releases/0.6.0-maintainability-baseline.md`
* `docs/releases/0.6.0-characterization-matrix.md`
* `docs/architecture/module-dependency-graph.md`
* `config/quality/0.6.0-baseline.json`
* `config/quality/runtime-protocol-catalog.json`
* `config/quality/maintainability-deviations.yml`
* Public API dumps
* Machine-readable reports under `build/reports/maintainability/`
* `verifyMaintainabilityBaseline`
* `verifyFullMaintainabilityBaseline`
* Dedicated CI artifact

---

## Acceptance Criteria

### Baseline identity

* [ ] The baseline references the immutable published `v0.5.0` commit.
* [ ] Tag, version and commit identity are verified automatically.
* [ ] Generation does not depend on uncommitted files.

### Determinism

* [ ] Structural reports are byte-for-byte deterministic.
* [ ] Files use UTF-8, LF endings and stable key ordering.
* [ ] Paths are repository-relative.
* [ ] No current timestamps, usernames or machine paths appear in deterministic outputs.
* [ ] Environmental measurements record their execution environment.

### Architecture

* [ ] Every included Gradle module appears in the module graph.
* [ ] Project dependency scopes are represented.
* [ ] Cycles are detected automatically.
* [ ] Architectural layer violations are listed.
* [ ] No new cycle or forbidden edge can be introduced silently.

### APIs and dependencies

* [ ] Every publishable module has a public API dump or explicit exclusion.
* [ ] Unreviewed binary API changes fail CI.
* [ ] External dependency graphs are generated.
* [ ] Dynamic, snapshot and conflicting versions are visible.

### Runtime safety

* [ ] Broad catches in suspend-capable code are inventoried.
* [ ] Process-global mutable state is inventoried.
* [ ] Direct time, identity and randomness access is inventoried.
* [ ] Critical findings have owner and target phase.
* [ ] New critical findings fail the ratchet.

### Test quality

* [ ] Critical modules have line and branch coverage baselines.
* [ ] Policy, approval, routing, evidence and retry code have mutation baselines.
* [ ] Surviving high-risk mutants are converted into tasks.
* [ ] Test duration is reported by module and slowest test.
* [ ] Skipped critical tests are visible.

### Runtime protocol

* [ ] Public reason codes, events, metrics and attribute keys are catalogued.
* [ ] Critical protocol names have a stability classification.
* [ ] Duplicate or inconsistent identifiers are reported.
* [ ] Raw sensitive exception text is identified.

### Refactoring process

* [ ] No critical refactoring begins without characterization coverage.
* [ ] Every refactoring PR includes a behaviour-preservation table.
* [ ] Correctness changes are not hidden inside mechanical refactors.
* [ ] Baseline changes show a machine-generated diff.
* [ ] Regressions require an explicit, reviewable deviation entry.

### CI

* [ ] Regular CI runs `verifyMaintainabilityBaseline`.
* [ ] Full quality CI runs `verifyFullMaintainabilityBaseline`.
* [ ] Reports are published as CI artifacts.
* [ ] The artifact contains no secrets or machine-specific paths.
* [ ] Baseline generation and verification work from a clean checkout.

---

## Exit Condition

Phase 0 is complete only when TramAI has:

1. An immutable 0.5.0 reference.
2. A reproducible architecture and quality snapshot.
3. A complete critical-behaviour characterization matrix.
4. Machine-enforced ratchets against new maintainability debt.
5. Public API and dependency visibility.
6. A documented process for accepting or rejecting baseline changes.
7. CI artifacts that allow a reviewer to understand the codebase without manually scanning every module.

Only then may structural 0.6.0 refactoring begin.
