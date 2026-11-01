# TASK 0.7.4h2 — CI enforcement: the real CI contract (Phase A)

Integration branch `release/0.7.0`. Starting head **`9ddd54025775f15da43f93569cd63e81f4781a08`** — fetched and
matched the brief; no advance to reconcile.

This is the source-backed inventory and wiring plan. Everything below was measured at that head; the method is
named for each row, and nothing is inherited from the 0.7.4h task document.

## 1. The three gates: where they live

| Gate | Registered by | Task class | Authoritative input | Detects |
|---|---|---|---|---|
| `verifyModuleMatrixDrift` | `MaintainabilityBaselinePlugin:1253` | `ModuleMatrixDriftVerifierTask` | the generated module matrix document versus the module catalog | a module matrix that no longer matches the set of modules and their declared dependencies |
| `verifyModuleDocContract` | `TramaiDocsGuardsPlugin:215` | `DocsContractVerifierTask` (`contractId = verifyModuleDocContract`) | the module documentation cards under `docs/modules/` and their contract fields | a missing or malformed required module card, and contract fields that disagree with the module |
| `verifyNoPublishedTestFixtures` | `TramaiReleaseVerificationPlugin:171` | `VerifyNoPublishedTestFixturesTask` | each publishable module's **generated** `module.json` and `pom-default.xml`, plus `build/publication-boundary/publishable-modules.txt` | a published `*-test-fixtures.jar`, a `testFixtures*` metadata variant, a fixture dependency exported through the POM, or a module whose metadata is missing/unparseable |

None of the three trusts a configuration flag: the matrix and documentation gates read documents, and the
fixture gate reads generated publication metadata, with an empty module set an error rather than a pass.

## 2. What CI actually executes today

Measured with `./gradlew <task> --dry-run` at `9ddd5402` and by reading every `./gradlew` invocation in
`.github/workflows/`.

| Gate | Direct enrollment | Transitive enrollment | Verdict |
|---|---|---|---|
| `verifyModuleMatrixDrift` | none | only from `verify060MaintainabilityRelease`, which **no workflow runs** | **not executed in CI** |
| `verifyModuleDocContract` | none | **none** — see §3 | **not executed in CI** |
| `verifyNoPublishedTestFixtures` | none | registered but reachable from no aggregator at all | **not executed in CI** |

`verify060Architecture --dry-run` resolves a plan of **348 tasks** and contains none of the three. That is the
discriminating measurement: an absent check and a check executed indirectly look identical in the workflow
YAML, and they are only distinguishable from the task graph.

`./gradlew verifyPr` — the aggregate the PR template asks authors to report — is invoked by **no workflow**.
`ci.yml` runs selected constituent tasks through a matrix (`verifyCompilerWarnings`, `spotlessCheck`
`verifyStaticAnalysis` `verifyStaticSafetyGuards`, `verifyDependencyHygiene`, `verifyJUnitTestSignatures`),
plus `:build-logic:test`, `verifyCancellationSafety`, `verifyCriticalCoverage`, `verifyMutationRatchet`, and
the sovereign-runtime chain. The matrix, documentation and publication gates are in none of those sets.

## 3. The doc-contract edge that was supposed to exist, and does not

`MaintainabilityBaselinePlugin:1423` wires the edge conditionally:

```kotlin
if (project.tasks.findByName("verifyModuleDocContract") != null) {
    project.tasks.named("verify060Architecture") { dependsOn("verifyModuleDocContract") }
}
```

The comment above it claims the gate is "wired into the 0.6.0 architecture gate". At this head the edge is
**absent**, and the plugin order in the root build script explains why: `tramai.maintainability-baseline` is
applied on line 5, `tramai.docs-guards` on line 14, so when the maintainability plugin evaluates the
conditional the docs task does not exist yet and the guard silently takes the false branch.

Labelled: the dry-run absence is **VERIFIED**; the ordering as its cause is **INFERRED** from the applied order
and the guarded `findByName`, and Phase B pins the required end state regardless of which mechanism wins,
by enrolling the task explicitly rather than relying on a conditional.

This is the exact failure mode the phase exists to catch: a comment asserting enforcement, no enforcement, and
no test that would notice.

## 4. Environment and invocation facts

- `ci.yml` quality jobs run **Java 25** (temurin). `:build-logic:test` runs in its own job.
- Gradle 9.0.0. Local work for this task uses JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`).
- Canonical local invocations, each runnable today:
  - `./gradlew verifyModuleMatrixDrift --no-daemon`
  - `./gradlew verifyModuleDocContract --no-daemon`
  - `./gradlew verifyNoPublishedTestFixtures --no-daemon` (declares its own `generateMetadataFileForMavenPublication`
    and `generatePomFileForMavenPublication` prerequisites, so metadata cannot be stale by construction)
  - publication boundary end to end: `scripts/verify-publication-boundary.sh`
- `verify060MaintainabilityRelease` **cannot be configured at all** in this environment:
  `Could not determine the dependencies of task ':verifySovereignRuntimeVerificationRepoClosure' > Could not
  create task ':verifySovereignRuntimeSignedBundle' > verifySovereignRuntimeSignedBundle only supports file://
  repositories for local verification. Got: https://ossrh-staging-api.central.sonatype.com`. This is the same
  root cause the owner diagnosed for the seven `ReleaseVerificationPluginTest` failures. It makes that
  aggregator unusable as an enforcement vehicle without a property, so it is not the plan.

## 5. Required-check policy: there is none, server-side

`GET /repos/.../branches/release/0.7.0/protection` returns **404 Branch not protected**. There is no
server-side required-check list on `release/0.7.0`. Enrolling a gate therefore does not make it merge-blocking
by itself; the merge gate is `AGENTS.md` plus the owner. Recorded so no later claim implies otherwise.

## 6. Can a gate pass with nothing to inspect?

| Gate | Missing input | No modules | Verdict |
|---|---|---|---|
| `verifyNoPublishedTestFixtures` | finding (metadata missing, unparseable, no `variants`, empty `variants`, unnamed variant, missing POM) | empty catalog is an error | fails closed — hardened in 0.7.4h1 review rounds |
| `verifyModuleMatrixDrift` | to be confirmed in Phase B against the real task | to be confirmed | **UNRESOLVED** — not asserted here |
| `verifyModuleDocContract` | to be confirmed in Phase B | to be confirmed | **UNRESOLVED** |

## 7. Wiring plan (Exit A)

**Phase B (this PR, `build-logic`).** No workflow edits.

1. A pure verifier that reads workflow YAML *semantics*: the event triggers (must include `pull_request` with
   the integration branch in scope), the selected job, and the exact step that executes each required gate —
   then refuses each of: gate command removed, step disabled (`if: false`), job disabled, `continue-on-error:
   true`, shell-level failure masking (`|| true`, `|| exit 0`, `set +e`), and a missing/unconditional
   failure-artifact upload.
2. Tests against **controlled workflow fixtures** — one accepted positive and one per negative control — so
   this PR is green on its own unchanged base. The diagnostic must name the specific gate.
3. The real-repository enforcement task is **registered but not aggregated**, so nothing runs it in this PR.
   It is activated by the workflow-only PR invoking it, which is exactly when the gates it asserts are
   actually enrolled. That keeps the preparatory PR green without weakening the eventual enforcement.

A bare `; exit 0` after the Gradle command is **not** treated as masking: GitHub runs steps under `bash -e`,
which aborts at the failing `gradlew` before that line executes. Refusing it would be a false positive, and the
non-restriction is pinned by a test rather than left implicit.

**Phase C (separate `ci-workflow` PR).** Enroll `verifyModuleMatrixDrift`, `verifyModuleDocContract` and
`verifyNoPublishedTestFixtures` in an existing quality job (matrix entry or step, JDK 25), keep failure
propagation intact, keep `if: always()` artifact uploads, preserve base-ref properties for PR-delta
verification, and run the Phase B verifier itself.

**Phase D.** Adversarial proof against the real gates and against controlled workflow fixtures, per the
required controls (remove each gate, disable the step, mask the exit status), each with before/after,
diagnostic and restore.

## 8. Exclusions

No production, API, analyzer-semantic or governance-behaviour changes; no baseline regeneration; no
deviation-ceiling increase; no enrolment of `verifyPr` as a substitute; no 0.7.0 promotion.
