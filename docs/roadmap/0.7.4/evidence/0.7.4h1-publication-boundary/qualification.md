# 0.7.4h1 — Publication boundary for test fixtures

Epic 0.7.4, phase H1. Base `7e628de74d6753be3659f5781b7ca7d98ec7b193` (`release/0.7.0` head at the time of
work; zero local divergence from it).

## What was wrong

Test fixtures are an internal testing surface. `java-test-fixtures` attaches `testFixturesApiElements` and
`testFixturesRuntimeElements` to the `java` component, so every module that owns fixtures publishes
`<module>-<version>-test-fixtures.jar` and two extra metadata variants to every consumer — test-only classes,
their dependencies and their metadata shipped as part of the library's contract.

0.7.4f excluded the variants for `:tramai-security` only, in that module's own build script. The other
producer modules kept publishing. **Measured, not inferred:** by deleting the generated metadata and letting
the gate's own dependency graph regenerate it, four modules were found publishing eight variants:

```
tramai-control-plane   testFixturesApiElements / testFixturesRuntimeElements
tramai-engine          testFixturesApiElements / testFixturesRuntimeElements
tramai-security        testFixturesApiElements / testFixturesRuntimeElements
tramai-testing         testFixturesApiElements / testFixturesRuntimeElements
```

## What changed

1. **`TramaiTestFixturesPlugin`** now excludes both fixture variants from publication for every module that
   owns fixtures — one place, every producer, instead of a per-module copy that only covered the module that
   happened to be audited. It fails closed if a module applies `maven-publish` without a `java` component,
   because then the exclusion would be silently skipped.
2. **`tramai-security/build.gradle.kts`** loses its local copy of the exclusion. The module-level block is
   gone; the behaviour is unchanged because the convention now applies it.
3. **`verifyNoPublishedTestFixtures`** (build-logic) audits the generated publication metadata of every
   publishable module.

## The gate inspects artifacts, not configuration

The authority is the generated `module.json` — the metadata a consumer's dependency resolution actually
reads — not a flag in a build script and not the presence of an exclusion. `PublishedFixtureLeakVerifier`
reports a finding for:

- a variant whose name marks it as fixtures (`testFixtures*`);
- **a fixture jar served under a non-fixture variant** (the harder case: the variant looks legitimate, so
  only the artifact identifies it);
- **a publishable module with no generated metadata at all** — fail closed, because a gate that silently
  skips unaudited modules cannot claim the boundary holds.

The task throws when the publishable module set is empty, so a corrupt or missing catalog cannot degrade into
an audit of nothing. Diagnostics name the module, the variant and the leaked artifact file.

## Positive result

```
verifyNoPublishedTestFixtures: 46 publishable module(s) audited against their generated publication
metadata; no test-fixtures variant or artifact published.
```

All 46 generated metadata files carry four variants (api, runtime, javadoc, sources) and no fixture jar.

## Negative control (required, and it discriminates)

The mutation is the real defect, restored deliberately: both `skip()` calls removed from the convention
plugin, and every `module.json` deleted first so Gradle cannot report a stale clean state.

| Step | Result |
|---|---|
| mutation applied (2 anchors, sha256 changed) | `2ea94829…` → `a2e6b757…` |
| gate with the mutation | **exit 1**, `8 unexpected test-fixtures publication(s)`, each naming module + variant + artifact |
| engine metadata during the control | 6 variants including `testFixturesApiElements`, `tramai-engine-0.7.0-SNAPSHOT-test-fixtures.jar` |
| restore | byte-identical (`2ea94829…`) |
| gate after restore | **exit 0**, 46 modules audited; engine back to 4 variants |

This is the guard failing for the intended reason, against a genuinely regenerated publication artifact.

## Internal fixture usage still works

`testFixtures(project(":x"))` resolves the project's `testFixtures*Elements` **configuration**, which exists
whether or not the variants are published. Proven by compiling the consumers:

```
./gradlew :tramai-observability:compileTestKotlin :tramai-security:compileTestKotlin \
          :tramai-engine:compileTestKotlin   → BUILD SUCCESSFUL
```

## Gate results

| Gate | Result |
|---|---|
| `:build-logic:test --tests PublishedFixtureLeakVerifierTest` | **5 tests, 0 failures** (VERIFIED) |
| `spotlessCheck` + `verifyStaticAnalysis` | PASS, detekt baseline 4792 → 4792, **0 added** (VERIFIED) |
| `verifyChangePolicy -PchangePolicyBase=7e628de7…` | PASSED — 7 files, `runtime-behaviour`, no violations (VERIFIED) |
| `verifyNoPublishedTestFixtures` | PASS — 46 modules audited (VERIFIED) |
| `verify060Architecture` | **UNRESOLVED locally** — the run was SIGTERM'd (exit 143) by the process reaper before it reported |
| `:build-logic:test --tests ReleaseVerificationPluginTest` | **7 of 24 fail at the head AND at the pristine base** — see below (OBSERVED) |
| `verifyPr` | not run as an aggregate; its constituents were run in slices and are reported as slices |

## Pre-existing red on the target branch

`release/0.7.0` at `7e628de7` is **not green**: `api-architecture (verify060Architecture)`,
`build-logic (release-sovereign)`, `Verify Maintainability Baseline` and `tests` all fail at that head
(`success=22/27`, plus one neutral GitGuardian check).

The `ReleaseVerificationPluginTest` failures I hit locally are the same family: 7 failures — T1b, T2, T5, T7,
T7c, T9, T15 — with an identical identity set in a clean detached worktree at the pristine base (dirty=0) and
at this head. The reported cause is `Could not create task ':verifySovereignRuntimeSignedBundle'`, which this
change does not touch. **Both runs are recorded; the head did not introduce them.**

This matters for the rest of 0.7.4h: H2 requires exact-head CI to prove the integration gates were selected,
executed and passed, and that claim is impossible to make truthfully while the target branch is red at its
own head. Fixing that red is a separate task, not part of H1, and it must not be done by weakening a gate.
