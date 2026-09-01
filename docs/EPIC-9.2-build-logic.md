# EPIC 9.2 — Move build logic into `build-logic`

**Goal:** Make Gradle configuration modular, typed, testable, and mostly declarative.

**Owner:** build-logic conventions
**Status:** ✅ complete (9.2a, 9.2b, 9.2c, 9.2d)

## Slicing

The epic is deliberately split into small behavior-preserving slices. Each slice
is a separate PR with its own TestKit suite and acceptance checklist. The slices
are ordered so that the riskiest boundary (publishing, which touches every
published module) is extracted first.

| Slice | Scope | Status |
|-------|-------|--------|
| 9.2a | `tramai.publishing` convention plugin (publication, signing, repository policy, POM metadata, sovereign-local hook) | ✅ done — PR #309 |
| 9.2b | `tramai.release-verification` + `tramai.sovereign-verification` typed/cache-aware release tasks | ✅ done — PR #313 |
| 9.2c | language, test-fixture, and testing conventions | in progress |
| 9.2c-a | `tramai.kotlin-library` / `tramai.java-platform` / `tramai.test-fixtures` | ✅ done — PR #319 |
| 9.2c-b | `tramai.testing` convention — common test-dependency baseline | ✅ done — PR #322 |
| 9.2c-c | manifest-derived publication descriptions (module-catalog.yml schema v3 `description` + analyzer/parser + independent publication verifier input) | ✅ done — PR #325 |
| 9.2d | configuration-cache closure; root build reduced to composition | ✅ complete — PR #353 (b1), PR #357 (b2), PR #358 (b3) |

The `tramai.integration-test` convention is **deferred** until a dedicated
integration-test source set exists in at least one production module — a
convention plugin with zero consumers adds abstraction surface without removing
any duplication. It will be documented and built when a real consumer appears.

## 9.2a — `tramai.publishing` (this PR)

### Objective

Move ordinary Maven publication/signing configuration out of the root
`build.gradle.kts` into a tested convention plugin `tramai.publishing`,
producing exactly the same publication surface as today.

**Before:** root `build.gradle.kts` owns maven-publish application, signing,
java/javaPlatform publication setup, POM metadata, remote repository selection,
credentials, the sovereign local verification repository, and the
`projectDescription()` mapping.

**After:** root `build.gradle.kts` is composition only:

```kotlin
subprojects {
    group = tramaiGroup.get()
    version = tramaiVersion.get()
    repositories { mavenCentral() }
    apply(plugin = "tramai.publishing")
}
```

### Behavior contract (must be preserved exactly)

1. **Java-library** modules (`java-library` applied): maven-publish, signing,
   `withJavadocJar()`, `Javadoc.failOnError = false`, publication component
   `java`, `artifactId = project.name`.
2. **Java-platform / BOM** modules: publication component `javaPlatform`,
   `artifactId = project.name`, POM packaging `pom`, dependency-management
   output unchanged.
3. **Remote repository selection**: `version.endsWith("-SNAPSHOT")` → snapshot
   URL → release URL fallback; release version → release URL → snapshot URL
   fallback. Properties: `tramaiPublishReleaseUrl`, `tramaiPublishSnapshotUrl`,
   `tramaiPublishUsername`, `tramaiPublishPassword`.
4. **Security contract**: `file:` repositories never receive credentials;
   non-file repositories may receive credentials from Gradle properties.
5. **Signing**: only when both `signingKey` AND `signingPassword` are present →
   in-memory PGP + sign Maven publications. Otherwise no signing
   configuration requiring credentials.
6. **Sovereign local publication repository** `sovereignBundleLocal`: always
   `file://` (build-local by default), no remote credentials, never
   interchangeable with `tramaiRemote`. Membership is the existing
   `publishable - sovereignBundleExcluded` set — membership is NOT redesigned
   in this slice (a later `tramai.sovereign-verification` slice owns that).
7. **Description policy**: the existing hand-written `projectDescription()`
   mapping moves into build-logic as compatibility behavior. Values are NOT
   changed in this slice; manifest-derived descriptions land in 9.2c.

### Non-goals (explicitly out of scope)

- No `tramai-*/src/main/**` changes.
- No Maven coordinates / artifactId / scopes / publishability / signing
  requirements / repository precedence / POM contents / BOM membership /
  API dumps / migration registry / architecture report changes.
- Do NOT extract: `verifyPublicationMetadata`, `verifyPublishedLocalArtifacts`,
  `verifySovereignRuntimePublication`, `verifySovereignRuntimeSignedBundle`,
  release evidence-index generation — those are 9.2b typed tasks.

### Architecture

```
build-logic/src/main/kotlin/dev/tramai/build/publishing/
    TramaiPublishingPlugin.kt        # plugin entry; reacts to java-library / java-platform
    TramaiPublicationMetadata.kt     # metadata resolution + projectDescription() compatibility policy
    TramaiPublishingRepositories.kt  # repository selection, credentials guard, sovereignBundleLocal hook
```

The plugin registers `withPlugin("java-library")` / `withPlugin("java-platform")`
callbacks — no `afterEvaluate`, no eager lookup that depends on plugin ordering.

### Discriminator suite (TestKit)

`build-logic/src/test/kotlin/dev/tramai/build/publishing/TramaiPublishingPluginTest.kt`

- P1 — java-library publication: publication exists, component `java`,
  `artifactId == project.name`, javadocJar present.
- P2 — java-platform publication: publication exists, component
  `javaPlatform`, POM packaging `pom`.
- P3 — release repository selection (`0.6.0` + both URLs → release URL), plus
  both fallback directions.
- P4 — snapshot repository selection (`0.6.1-SNAPSHOT` + both URLs → snapshot
  URL), plus both fallback directions.
- P5 — no repository configured when both URLs absent (developer machines).
- P6 — `file:` repository never receives credentials even when username /
  password are provided.
- P7 — signing optional: no keys → configuration succeeds; keys present →
  signing tasks exist. No real credentials in test data.
- P8 — sovereignBundleLocal exists (file://, no credentials) on selected
  projects only.
- P9 — POM metadata parity: group, artifactId, version, name, description,
  project URL, license, developer, SCM, packaging unchanged. End-to-end oracle:
  the repository's existing `verifyPublicationMetadata`.
- P10 — configuration cache: minimal build passes twice with cache reuse on the
  second run.
- S1 — structural guard: root `build.gradle.kts` must not contain
  `configureTramaiPublishing`, `MavenPublication`, `SigningExtension`,
  `PublishingExtension`, or `configureSovereignBundleLocalRepo`.

### Acceptance checklist

- [x] `tramai.publishing` registered as a real convention plugin
- [x] Java-library publishing behavior identical
- [x] Java-platform/BOM behavior identical
- [x] POM metadata identical
- [x] Repository URL precedence identical
- [x] File repositories never receive credentials
- [x] Signing behavior identical
- [x] `sovereignBundleLocal` remains local-only
- [x] Publishing implementation removed from root `build.gradle.kts`
- [x] TestKit P1–P10 + S1 green
- [x] `:build-logic:test` green
- [x] `verifyPublicationMetadata` green
- [x] `verifyPublishedLocalArtifacts` green
- [x] `verify060Architecture` green
- [x] `verifyPr` green
- [x] zero `tramai-*/src/main` diffs

Because this is a build-logic-only PR, `verifyChangePolicy` classifies it
cleanly as `build-logic` with no baseline-migration exemption.

## 9.2b — release-verification typed tasks

- Extract `verifyPublicationMetadata`, `verifyPublishedLocalArtifacts`,
  `verifySovereignRuntimePublication`, `verifySovereignRuntimeSignedBundle`,
  evidence-index generation into typed task classes with declared
  inputs/outputs and fail-closed evidence semantics.
- Configuration-cache-aware where feasible; document the unavoidable
  non-cacheable remainder.

## 9.2c — language, test-fixture, and testing conventions

- `tramai.kotlin-library`, `tramai.java-platform`, `tramai.test-fixtures`
  (9.2c-a, PR #319): behavior-preserving extraction of the repeated JVM/Kotlin
  baseline, the BOM wiring, and the test-fixtures plugin application. The
  kotlin-library convention reacts to BOTH `java-library` and Kotlin JVM and
  configures only the `test` task; behavioral TestKit proofs cover plugin-order
  independence, JVM-21 bytecode, sourcesJar content, real JUnit-5 execution,
  and testFixturesJar content.
- `tramai.testing` convention (9.2c-b, PR #322): owns the common test-dependency
  baseline — JUnit BOM (platform), AssertJ, Kotlin test/JUnit5 — added to
  `testImplementation` only, reading coordinates from the version catalog.
  Migrates the 36 exact trio consumers; 11 partial and 4 none stay untouched.
- `tramai.quality` **not created**: the B7 survey found no coherent repeated
  module-level quality policy — Sonar, BCV, maintainability, compiler policy,
  and test logging are root-owned or module-specific. Recording this in the
  roadmap instead of preserving a speculative plugin name.
- Manifest-derived project descriptions / publication metadata (9.2c-c, PR #325):
  module-catalog.yml moves to schema v3 and gains a `description` field for every
  published module (exact legacy parity with the removed `projectDescription()`
  policy — no copy editing). The publishing plugin reads the catalog description;
  the typed `verifyPublicationMetadata` task receives an independent
  `expectedDescriptions` map input so publisher and verifier cannot share a
  broken lookup. Published modules fail closed when the description is missing
  (`MODULE_CATALOG_MISSING_DESCRIPTION`); internal/excluded modules may omit it.
- `tramai.integration-test`: **deferred** — no production module currently
  defines a dedicated integration-test source set; integration tests live in the
  normal `test` source set as `*IntegrationTest` classes.

## 9.2d — configuration-cache closure

✅ **COMPLETE** — closure lineage:

- **a-series** — typed/config-cache conversions of verification tasks;
  C1 `help`, C2 `test`, C6 `verifyPublicationMetadata` are
  configuration-cache reusable.
- **b1 — PR #353** — module-catalog.yml as the single publishability
  authority; all four consumers fail closed on a missing/corrupt catalog.
- **b2 — PR #357** — root responsibility extraction: SBOM →
  `tramai.supply-chain`, sovereign-lab verification → `tramai.sovereign-lab-
  verification`, `verify050ReleaseReadiness` → `tramai.release-verification`;
  root `build.gradle.kts` reduced to composition.
- **b3 — PR #358** — developer-lifecycle CC closure:
  - Release-only `verify050ReleaseReadiness` detached from `check` (the
    normal developer `test`/`check` lifecycle no longer traverses the
    deliberate CC-incompatible release boundary).
  - The task itself is unchanged: same name, dependencies, diagnostics,
    fail-closed document inspection, `notCompatibleWithConfigurationCache`
    (C3 = 1 deliberate), explicit release invocation, and publish workflow
    invocation with `--no-configuration-cache`.
  - Final offender matrix: **C4 = 0** (no execution-time `Task.project`),
    **C5 = 0** (no remaining typed-task execution-model offender),
    **C3 = 1 deliberate** (release-orchestration exclusion).
  - CC closure proof: `test` cold → stored → warm reused; `check` cold →
    stored → warm reused (both with `--configuration-cache-problems=fail`).
  - Kill discriminators: T18 (root no longer wires release readiness into
    `check`; publish workflow still invokes it with
    `--no-configuration-cache`), T19 (release dependencies retained when
    invoked explicitly).
