# 0.7.4h2a — CI wiring regression contract (evidence)

Base: `release/0.7.0` = `9ddd54025775f15da43f93569cd63e81f4781a08`. Branch
`task/0.7.4h2a-ci-enforcement-contract`. Primary change class `build-logic`. **No workflow file is touched.**

## What was added

| File | Role |
|---|---|
| `build-logic/src/main/kotlin/dev/tramai/build/ci/CiGateEnrollmentVerifier.kt` | pure verifier over workflow semantics |
| `build-logic/src/main/kotlin/dev/tramai/build/ci/VerifyCiGateEnrollmentTask.kt` | `verifyCiGateEnrollment`, a real-repository task |
| `build-logic/src/main/kotlin/dev/tramai/build/release/TramaiReleaseVerificationPlugin.kt` | registers the task; **aggregated into nothing** |
| `build-logic/src/test/kotlin/dev/tramai/build/ci/CiGateEnrollmentVerifierTest.kt` | 14 contract tests over controlled fixtures |

## What the verifier refuses

It reads the workflow's event triggers, jobs and steps, and rejects a required gate when:

- no step executes it (absent),
- the step is conditional (`if: false`, `if: ${{ false }}`),
- its job is conditional,
- either carries `continue-on-error: true`,
- the step masks its exit status (`|| true`, `|| :`, `set +e`),
- the workflow does not run for `pull_request` events targeting the integration branch,
- the workflow has no `always()` failure-artifact upload — an `if: success()` upload does not count.

Substring matches do not satisfy it: `verifyModuleMatrixDrift2` is not `verifyModuleMatrixDrift`. A gate enrolled
in a *second* workflow still counts, because the contract is about enforcement rather than placement.

## Rollout: registered now, activated by the workflow PR

`verifyCiGateEnrollment` is registered and reachable from nothing — not `check`, not `verifyPr`, not the release
aggregators. 0.7.4h2b invokes it in the same change that enrolls the gates, so this preparatory PR is green on
its own base and the enforcement becomes real exactly when it can be satisfied. Wiring it into `check` here
would have made this PR red for a condition only 0.7.4h2b can create.

## Verification at this head

| Check | Result |
|---|---|
| `:build-logic:test --tests CiGateEnrollmentVerifierTest` | **14 tests, 0 failures** (VERIFIED) |
| `spotlessCheck` + `verifyStaticAnalysis` | PASS — detekt baseline `4792 → 4792`, **0 added** (VERIFIED) |
| `verifyChangePolicy -PchangeClass=build-logic` | PASSED (VERIFIED) |
| `:build-logic:test --tests ReleaseVerificationPluginTest` | 24 tests, **7 failures** — T7c, T15, T1b, T2, T5, T9, T7, the same identities recorded at the pristine base, unchanged by this registration (VERIFIED, both runs recorded) |
| `./gradlew verifyCiGateEnrollment` on the real workflows | **exit 1**, naming all three gates (VERIFIED — this is the expected pre-H2b state) |

The last row is the point of the task: at this base the real CI genuinely does not enforce the three gates, and
the task says so by name instead of passing quietly.

## Known limits

- The verifier proves *selection and fatal semantics*, not that a gate's own logic is correct; that is what the
  gates' own tests and Phase D's adversarial fixtures establish.
- `release/0.7.0` has **no branch protection** (`404 Branch not protected`), so enrolling a check does not make
  it merge-required server-side. The merge gate remains `AGENTS.md` plus the owner.
- `verify060MaintainabilityRelease` cannot be configured in this environment
  (`verifySovereignRuntimeSignedBundle only supports file:// repositories`), so it is not an enforcement vehicle.
