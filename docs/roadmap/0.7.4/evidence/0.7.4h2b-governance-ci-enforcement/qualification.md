# 0.7.4h2b — Governance CI enforcement (workflow-only)

Branch: `task/0.7.4h2b-governance-ci-enforcement`
Base: `release/0.7.0` = `5c287cd5d1084a992013cabc0992a54b45e0e981` (0.7.4h2a merged, PR #541)
Primary change class: `ci-workflow`

## What changed

Four added lines in `.github/workflows/ci.yml`, nothing else:

```yaml
          - name: governance-integration
            gates: verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures verifyCiGateEnrollment
```

This is a new leg of the **existing `quality` job**, not a new job. The job already runs every PR
(`on: pull_request`, no branch filter), already sets up JDK 25, already fetches the base SHA, and already
uploads diagnostics under `if: always()`. Nothing about the existing matrix legs or check identities changes:
`quality (compiler-warnings)`, `quality (static)`, `quality (hygiene)` and `quality (junit-test-signatures)`
keep their exact names.

The published check identity is `quality (governance-integration)`.

## Phase A coverage, before and after

| Gate | Before 0.7.4h2b | After |
|---|---|---|
| `verifyModuleMatrixDrift` | reachable only from `verify060MaintainabilityRelease`, which no workflow runs | executed on every PR by `quality (governance-integration)` |
| `verifyModuleDocContract` | **unreachable**: the conditional edge in `MaintainabilityBaselinePlugin` never binds because of plugin apply order | executed on every PR |
| `verifyNoPublishedTestFixtures` | reachable from no aggregator and no workflow | executed on every PR |
| `verifyCiGateEnrollment` | registered, aggregated into nothing (0.7.4h2a rollout) | executed on every PR — it audits this very enrollment |

## Verification (local, JDK 21, at this head)

| Command | Result |
|---|---|
| `./gradlew verifyCiGateEnrollment` | **exit 0** — "3 required gate(s) audited across 7 workflow file(s); each is executed unconditionally by a job that runs for pull requests targeting 'release/0.7.0'" |
| `./gradlew verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures` | **exit 0**, 297 tasks, BUILD SUCCESSFUL in 26s |
| `./gradlew verifyNoPublishedTestFixtures --dry-run` | generation precedes the gate: last `generateMetadataFileForMavenPublication` at plan line 516, last `generatePomFileForMavenPublication` at 517, `:verifyNoPublishedTestFixtures` at 518 — the prerequisite edge is a declared `dependsOn`, not a workflow ordering assumption |
| `./gradlew verifyChangePolicy -PchangeClass=ci-workflow` | **PASSED** — 1 changed file |
| `git diff --stat` | `.github/workflows/ci.yml | 4 ++++` — workflow file only, no production, API, runtime or analyzer change |

## Phase D4 — bypass controls against the real workflow

Each mutation applied to the real `.github/workflows/ci.yml` in this checkout, one at a time, then restored.

| Control | `verifyCiGateEnrollment` | Diagnostic |
|---|---|---|
| remove `verifyModuleMatrixDrift` from the matrix leg | **exit 1** | `gate 'verifyModuleMatrixDrift' ... no workflow step executes it` |
| remove `verifyModuleDocContract` | **exit 1** | `gate 'verifyModuleDocContract' ... no workflow step executes it` |
| remove `verifyNoPublishedTestFixtures` | **exit 1** | `gate 'verifyNoPublishedTestFixtures' ... no workflow step executes it` |
| disable the gate step (`if: false`) | **exit 1** | all three gates, `not proven to run for pull requests` |
| mask a failing Gradle exit status (`|| true`) | **exit 1** | all three gates, `masks its exit status` |
| restore | **exit 0** | `sha_restored` byte-identical to `sha_before` (`88bbc444ca95…`) |

### Known limit, stated rather than hidden

Removing **the verifier's own invocation** from the matrix exits 0. A gate cannot observe its own removal
from inside itself: if nothing runs `verifyCiGateEnrollment`, nothing reports that fact. Detecting that
requires the check to be *required* at the repository level, which is a branch-protection setting and not a
workflow property. `release/0.7.0` currently has no branch protection (the protection API returns `404`), so
no check on this branch — including the four pre-existing `quality` legs — is merge-required server-side.
0.7.4h2b makes the gates execute and fail correctly; it does not and cannot make GitHub require them.

## Review note

The 0.7.4h2a contract that this enrollment is validated by was merged in PR #541 and its own controls prove
each rejection names the specific missing or bypassed gate. Nothing in this PR weakens, replaces or bypasses
an existing check.

## Phase D1/D2/D3 — the real gates fail for real violations

Run at this head against the real gates, each violation applied to the working tree and restored.

| Control | Clean | Violated | Diagnostic | Restored |
|---|---|---|---|---|
| **D1** matrix drift — one phantom row appended to `docs/reference/module-matrix.md` | exit 0 | **exit 1** | `[GENERATED_DOCUMENT_DRIFT] Module matrix drift: run ./gradlew generateModuleMatrix` | byte-identical → exit 0 |
| **D2** documentation drift — `docs/modules/tramai-engine.md` removed | exit 0 | **exit 1** | `[MODULE_CARD_MISSING] Manifest module 'tramai-engine' has no card in docs/modules` | byte-identical → exit 0 |
| **D3** fixture publication — reused from 0.7.4h1 | — | exit 1 | 8 findings naming module/variant/artifact (metadata level); `8 variants / 20 files` (repository level); orphan artifact `0/1/0` | byte-identical → exit 0 |

D3 reuse is sound rather than assumed: `git diff eed45f6f..HEAD` over
`PublishedFixtureLeakVerifier.kt`, `TramaiTestFixturesPlugin.kt` and `VerifyNoPublishedTestFixturesTask.kt` is
**empty**, so the gate and the convention that produce 0.7.4h1's negative controls are the same code that runs
in this enrollment.

Each failure names the specific cause and the specific fix, which is the property the mission asks for: CI
fails for the intended reason, not merely with a non-zero exit.
