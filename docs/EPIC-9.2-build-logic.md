# EPIC 9.2 — Move build logic into `build-logic`

**Goal:** Make Gradle configuration modular, typed, testable, and mostly declarative.

**Owner:** build-logic conventions
**Status:** in progress (9.2a done)

## Slicing

The epic is deliberately split into small behavior-preserving slices. Each slice
is a separate PR with its own TestKit suite and acceptance checklist. The slices
are ordered so that the riskiest boundary (publishing, which touches every
published module) is extracted first.

| Slice | Scope | Status |
|-------|-------|--------|
| 9.2a | `tramai.publishing` convention plugin (publication, signing, repository policy, POM metadata, sovereign-local hook) | ✅ done — PR #308 |
| 9.2b | `tramai.release-verification` + `tramai.sovereign-verification` typed/cache-aware release tasks | planned |
| 9.2c | `tramai.kotlin-library`, `tramai.java-platform`, `tramai.test-fixtures`, `tramai.integration-test`, `tramai.quality` | planned |
| 9.2d | configuration-cache closure; root build reduced to composition | planned |

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

## 9.2c — quality / language conventions

- `tramai.kotlin-library`, `tramai.java-platform`, `tramai.test-fixtures`,
  `tramai.integration-test`, `tramai.quality`.
- Manifest-derived project descriptions / publication metadata.

## 9.2d — configuration-cache closure

- Normal developer tasks (`test`, `check`) configuration-cache compatible.
- Root build reduced to high-level composition.
