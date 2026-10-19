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

## Real isolated repository

Metadata and POM inspection is a claim about generated description files. The stronger claim is about what a
consumer would actually download, so the full publishable set was published into a clean, isolated Maven
repository and the repository itself was audited (`scripts/verify-publication-boundary.sh`):

| Check | Result |
|---|---|
| modules published | **46** |
| `*-test-fixtures.jar` anywhere in the repository | **0** |
| published `.module` files with a `testFixtures*` variant | **0** (of 46) |
| published `.pom` files exporting a fixture dependency | **0** (of 46) |
| modules publishing main + sources + javadoc | 45 of 45 jar modules |
| `tramai-bom` | POM only — a `java-platform` module, no jar by design |
| jars in the repository | 135 |
| repository size | 13 MB |

Re-runnable: `scripts/verify-publication-boundary.sh [repo-dir]` exits non-zero if any check fails.

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

### Second control, at the repository level

The same mutation was applied again and measured through the published repository rather than the generated
metadata, because the two are different claims:

| Step | Result |
|---|---|
| mutation applied (1 anchor, sha256 changed) | `f55bc3ff…` → `8e375209…` |
| `scripts/verify-publication-boundary.sh` with the mutation | **exit 1** — `fixture vars/jars/poms: 8/20/0` |
| diagnostics | `tramai-control-plane`, `tramai-engine`, `tramai-security`, `tramai-testing`: fixture jar published; and each published `.module` naming `testFixturesApiElements` / `testFixturesRuntimeElements` |
| restore | byte-identical (`f55bc3ff…`) |
| script after restore | **exit 0**, `0/0/0`, `RESULT: PASS` |

The 20 fixture files are the two jars plus their published checksums per module — a consumer-facing artifact
count, not a metadata count.

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
| `:build-logic:test --tests PublishedFixtureLeakVerifierTest` | **13 tests, 0 failures** — 5 publication semantics, 2 POM, 6 fail-closed structure (VERIFIED) |
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

## Review round 1 (owner REQUEST TARGETED CHANGES) — what changed

| Finding | Resolution |
|---|---|
| P1: no automated TestKit regression for the exclusion | `TramaiTestFixturesPublicationTest` — 3 TestKit tests. A real project with a real `testFixtures` source set is published; the control proves the fixture variant **is** published without the convention, and two tests prove it is removed when `maven-publish` is applied **before** and **after** the convention, while `apiElements` and `sourcesElements` survive |
| P1: `readVariants()` returned an empty list for metadata without a valid variants array | Structure is now refused, not read as clean: metadata that is not JSON, has no `variants` array, has an empty one, or carries a variant without a name each produce a finding. Four tests pin it, including `{"formatVersion": "1.1"}` |
| P1: repository audit did not require metadata/POM per expected module | The gate writes `build/publication-boundary/publishable-modules.txt`; the script requires that set to equal the published set and requires `.module` **and** `.pom` for every module in it. Missing metadata is a failure, not an absence of evidence |
| P2: `<none>` diagnostics were confusing | `variant` is nullable; a finding with no variant name renders as `module 'x' publishes <reason> (artifact 'module.json')` with no fabricated variant |
| P2: stale cross-reference in `tramai-security` | The comment pointed at "the exclusion at the end of this file", which no longer exists; it now names the convention |
| P2: report said five verifier tests where the class has more | Counts above are the measured ones: 11 verifier tests, 3 TestKit tests |

### The maven-publish-without-java guard: adjusted, not tested

The previous implementation threw when `maven-publish` was applied without a `java` component. That branch is
unreachable: the callback runs inside `withPlugin("java-library")`, which is what creates both the component
and the fixture variants, and a module that publishes without `java-library` never constructs fixture variants
at all. Rather than write a test that manufactures an impossible state, the guarantee is **withdrawn from the
code** and replaced with an honest one: the branch is gone, and the KDoc states that the protection is the
convention (for every fixture-producing module) plus `TramaiTestFixturesPublicationTest` and the release gate —
not a runtime refusal. The unreachable `throw` was load-bearing only in prose, so removing it is the fix.

## Review round 2 (owner) — two fail-closed edge cases

Both were real. Neither was an architecture problem.

### P1a: a reported fixture file did not decide the exit code

`fixture_files` was collected, printed, and then omitted from the success predicate, so an orphan fixture
artifact — one no variant names, and in a location the per-module checks do not enumerate — was reported
and still exited 0. The predicate now includes it.

Control, on a real published repository:

| Step | Result |
|---|---|
| baseline (46 modules published) | exit **0** — `expected 46; published 46; fixture variants/files/poms 0/0/0` |
| plant `tramai-engine-0.7.0-SNAPSHOT-test-fixtures.jar` under `0.7.0-SNAPSHOT/extra/` — no variant names it, so only the repository-wide scan can see it | exit **1** — `0/1/0`, naming the exact path |
| remove it | exit **0** — `0/0/0` |

The control drove two fixes to the script itself: `--audit-only` now parses as a flag rather than as the repo
path, and the `rm -rf "$REPO"` reset moved inside the publishing branch — an audit-only run was deleting the
repository it was supposed to inspect, which made the first two control attempts fail for the wrong reason
(reported as `published: 0`).

### P1b: mixed valid and malformed variants were partially accepted

`mapNotNull` discarded non-object entries, so `[{"name": "apiElements"}, 42]` produced one valid variant and
no finding. Every entry must now be an object; the count of non-object entries is reported. Blank variant
names are also refused, since `"   "` is not a name. Two regressions pin both.

Verifier tests are therefore **13**, TestKit **3**, total **16**.
