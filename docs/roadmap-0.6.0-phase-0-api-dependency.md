# PR #205 — Canonical API and Dependency Baseline

**Branch:** `build/0.6.0-api-dependency-baseline`
**Base:** `origin/master` (after PR #204 merge)

## Objective

Replace the empty API and dependency sections in the v0.5.0 canonical baseline with deterministic, Gradle-backed measurements generated from the exact v0.5.0 source tree.

### Current state (summary)

| Metric | Before | After |
|--------|--------|-------|
| Public API dump hashes | 0 captured | ≥ N captured |
| Resolved dependency records | 0 captured | ≥ 1 per module |

### Current weaknesses being replaced

| Issue | Replace with |
|-------|-------------|
| API collection: filesystem-only hash of existing .api files | Gradle model probe: which modules, what apiStability, correct dump ownership |
| Dependency collection: Gradle cache path parsing | Gradle resolution model: `configuration.incoming.resolutionResult` |
| Global deduplication of dependencies | Per-consumer, per-configuration records with path |
| Silent failure on resolution errors | Hard failure with typed diagnostics |

---

## Workstream A — CanonicalGradleProbe

A component that executes controlled Gradle measurements against a detached worktree.

Requirements:
- Use the target worktree's Gradle wrapper
- Verify the target worktree is clean before measurement
- Write probe output outside the target source tree
- Never depend on build-cache filesystem layout
- Never include absolute paths, usernames, Gradle cache paths, or temporary directories
- Fail if nested Gradle invocation fails
- Capture stdout/stderr in a sanitized diagnostic
- Use deterministic ordering
- Support an explicit output directory
- Do not silently return empty measurements

The existing `generateCanonicalMaintainabilityBaseline` in `MaintainabilityBaselinePlugin.kt` already verifies analyzer checkout is clean; preserve this.

---

## Workstream B — Public API Baseline

### Data model

```
{
  "module": ":tramai-core",
  "stability": "stable",
  "applicable": true,
  "dumpPath": "tramai-core/api/tramai-core.api",
  "sha256": "<64-char-hash>"
}
```

Distinctions preserved:
- Module identity
- API stability classification (stable/preview/internal/excluded from module-catalog.yml)
- Whether API validation applies (not applicable for excluded modules)
- Dump location
- Dump hash
- Explicit reason when excluded

### Verification rules (verifyPublicApiBaseline)

Must FAIL when:
- The canonical API baseline is empty
- An applicable published module has no API dump
- Two modules claim the same dump
- A dump path escapes the repository
- A dump contains nondeterministic workspace data (absolute paths, timestamps)
- The normal binary compatibility task (`apiCheck`) fails
- A module classified as stable silently drops API validation

Must NOT fail merely because aggregate API hash changed (compatible API addition legitimately changes hash).

### New diagnostic codes

`API_BASELINE_EMPTY`, `API_DUMP_MISSING`, `API_DUMP_DUPLICATE`, `API_MODULE_UNCLASSIFIED`, `API_VALIDATION_NOT_CONFIGURED`, `API_COMPATIBILITY_FAILED`

---

## Workstream C — Resolved External Dependency Graph

### Data model

```
{
  "consumer": ":tramai-engine",
  "configuration": "runtimeClasspath",
  "group": "org.jetbrains.kotlinx",
  "artifact": "kotlinx-coroutines-core-jvm",
  "requestedVersion": "1.10.2",
  "selectedVersion": "1.10.2",
  "direct": true,
  "selectionReasons": ["requested"],
  "dependencyPath": [":tramai-engine", "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"]
}
```

Use `Configuration.incoming.resolutionResult` to traverse `ResolvedComponentResult` and `ResolvedDependencyResult`.

Capture `compileClasspath` and `runtimeClasspath` configurations only — no test-only dependencies.

### Normalization rules

Sort deterministically by: consumer → configuration → group → artifact → selected version → dependency path.

Do NOT globally deduplicate by group:artifact:version — same component through different consumers/configurations/paths is meaningful.

Project dependencies remain in the module dependency graph; do not duplicate as external dependencies.

### Verification rules (verifyResolvedDependencyBaseline)

Must FAIL when:
- Committed dependency baseline is empty
- Resolution fails for a required configuration
- A dynamic selector (`1.+`, `latest.release`) is introduced
- A production dependency resolves to `-SNAPSHOT`
- Same group:artifact resolves to multiple selected versions in production configs without explicit accepted exception
- A dependency record lacks consumer or configuration information
- An absolute filesystem path leaks into output

Warnings for: added dependencies, removed dependencies, version changes.

### New diagnostic codes

`DEPENDENCY_BASELINE_EMPTY`, `DEPENDENCY_RESOLUTION_FAILED`, `DYNAMIC_DEPENDENCY_VERSION`, `SNAPSHOT_DEPENDENCY`, `DEPENDENCY_CONVERGENCE_FAILURE`, `DEPENDENCY_ADDED`, `DEPENDENCY_REMOVED`, `DEPENDENCY_VERSION_CHANGED`

---

## Workstream D — Generated Artifacts

PR #205 produces:

| File | Purpose |
|------|---------|
| `build/reports/maintainability/public-api-dumps.json` | API probe output (per-module entries) |
| `build/reports/maintainability/resolved-dependencies.json` | Resolved dependency graph |
| `build/reports/maintainability/dependency-changes.json` | Dependency diff report |

And updates:

| File | Change |
|------|--------|
| `config/quality/0.6.0-baseline.json` | Replace empty `api` and `dependencies` sections |
| `docs/releases/0.6.0-maintainability-baseline.md` | Replace "0 captured" with actual counts |
| `docs/architecture/module-dependency-graph.md` | Remove stale "deferred to PR #204" note |
| `docs/roadmap-0.6.0-phase-0.md` | Update status of API/dependency baseline |
| `CHANGELOG.md` | Add PR #205 entry |

---

## Workstream E — Gradle Tasks

| Task | Action |
|------|--------|
| `generatePublicApiBaseline` | Generate per-module API dump records |
| `generateResolvedDependencyBaseline` | Generate resolved external dependency graph |
| `verifyPublicApiBaseline` | Verify committed API baseline against current |
| `verifyResolvedDependencyBaseline` | Verify committed dependency baseline against current |

Task relationships:
```
generateCanonicalMaintainabilityBaseline
├── canonical API probe (via generatePublicApiBaseline)
└── canonical dependency probe (via generateResolvedDependencyBaseline)

verifyMaintainabilityBaseline
├── verifyPublicApiBaseline
└── verifyResolvedDependencyBaseline

verifyFullMaintainabilityBaseline
└── verifyMaintainabilityBaseline
```

---

## Workstream F — Tests

### Unit tests (in build-logic project)

1. API records are sorted deterministically
2. Applicable module without a dump fails
3. Non-applicable BOM/platform module is explicitly excluded
4. Duplicate API dump ownership fails
5. Dependency traversal distinguishes direct and transitive edges
6. Requested and selected versions are both preserved
7. Multiple consumers are not collapsed
8. Multiple dependency paths are not silently discarded
9. Dynamic selectors are rejected
10. Snapshot dependencies are rejected
11. Convergence conflicts are detected
12. Resolution exceptions produce failures rather than empty output
13. Absolute paths are rejected
14. Two equivalent inputs produce byte-identical JSON

### ~~Gradle TestKit functional tests~~ Deferred to PR #206

The TestKit functional tests that prove CanonicalGradleProbe works end-to-end,
that probe output doesn't dirty the measured checkout, and that API/dependency
reports are populated are deferred to **PR #206** (coverage, mutation testing,
and controlled timing). They require a multi-project Gradle test fixture with
a local Maven repository that is better built alongside the PR #206 workstream.

---

## Explicitly Out of Scope

- JaCoCo configuration, coverage thresholds, PITest, mutation classification
- Three-run test timing, TestKit functional tests
- Runtime refactoring
- Dependency upgrades
- Module restructuring
- Public API redesign
- Changes to stable runtime behaviour

Those remain for PR #206.

---

## Acceptance Criteria

1. v0.5.0 canonical baseline contains at least one valid API dump
2. v0.5.0 canonical baseline contains a non-empty resolved dependency graph
3. Running canonical generation twice produces byte-identical API and dependency sections
4. The measured v0.5.0 worktree remains clean after generation
5. No absolute environment-specific paths appear in outputs
6. Dependency resolution failures cannot be swallowed
7. apiCheck remains the semantic API compatibility gate
8. API/dependency completeness enforced by typed diagnostics
9. TestKit functional tests deferred to PR #206 (coverage, mutation, controlled timing workstream)
10. Documentation contains actual counts and no stale deferrals

### Required verification

```
./gradlew :build-logic:test
./gradlew verifyMaintainabilityBaseline
./gradlew verifyFullMaintainabilityBaseline
./gradlew apiCheck
```

### Canonical reproduction

```
git worktree add --detach ../tramai-0.5.0 v0.5.0
./gradlew generateCanonicalMaintainabilityBaseline \
  -Pmaintainability.sourceRoot=../tramai-0.5.0
git -C ../tramai-0.5.0 status --porcelain
git status --porcelain
```

Both status commands must be empty after generation (except for planned baseline/doc changes).

---

## Key Trap

The main trap is treating this as "fill the two empty JSON arrays." That would preserve cache-path parsing weakness and silent failure.

The correct abstraction is an isolated Gradle model probe, not more filesystem scanning. This also creates the foundation PR #206 can reuse for controlled test, coverage, and mutation observations.
