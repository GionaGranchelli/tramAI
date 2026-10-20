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

## Repo-scale positive control (and the gap it found)

The fixture tests were not sufficient. Applying the intended two-line enrollment to the **real** `ci.yml` and
running the task showed the verifier rejecting all three gates — because real CI does not name them in the step:

```yaml
- name: Run quality gates (${{ matrix.name }})
  run: |
    GRADLE_ARGS=(${{ matrix.gates }} --no-daemon)
    ...
    ./gradlew "${GRADLE_ARGS[@]}"
```

A search over step text therefore reports a correctly enrolled gate as absent. Both indirections are now
resolved, and only where execution is real:

- `${{ matrix.FIELD }}` resolves to that field's values across the job's matrix include entries, because every
  entry runs the same step with its own value;
- a shell assignment in the same step (`NAME=(a b)`, `NAME="a b"`, `NAME=a`) resolves into the command that
  references it;
- the match is restricted to logical `gradlew` command lines, with backslash continuations joined first, so a
  gate assigned to a variable that no command uses still counts as **not** executed.

| Step | Result |
|---|---|
| `ci.yml` byte hash before | `d8476d8e3aaf…` |
| enrollment applied (2 lines, matrix entry `governance-integration` with all four gates) | patched hash `c9963cb3fa1f…` |
| `./gradlew verifyCiGateEnrollment` with the enrollment | **exit 0** — "3 required gate(s) audited across 7 workflow file(s)…" |
| bytes restored | `d8476d8e3aaf…` — **byte-identical** |
| `./gradlew verifyCiGateEnrollment` after restore | **exit 1**, naming all three gates |

Contract tests are **16** (14 mechanism controls + 2 indirection controls).

## Review round 2 — four cases closed

| Case | Resolution |
|---|---|
| A gate named in `echo`, a comment or another program is not execution | the run block is split into command segments on `&&`, `||`, `;`, `|`, and a segment counts only when its **command word is Gradle itself**. `echo "verifyModuleMatrixDrift"`, `# ./gradlew verifyModuleMatrixDrift` and `env ./gradlew-echo verifyModuleMatrixDrift` are rejected; the real `./gradlew "${GRADLE_ARGS[@]}"` still resolves |
| The failure-artifact upload must belong to the job that runs the gate | it is now checked on the selected job, not on any job in the workflow |
| A real Gradle-task integration test | `VerifyCiGateEnrollmentTaskTest` drives the task through `ProjectBuilder` so files are read from disk: compliant workflow accepted; no workflow files fail closed; unparseable workflow fails closed naming the gates; a dropped gate fails with "1 required gate(s)"; a gate split across two files still enforced |
| `./gradlew ... \|\| exit 0` | added as a masking pattern. A bare `; exit 0` is **not** restricted: GitHub runs steps under `bash -e`, which aborts at the failing `gradlew` before that line executes, so treating it as masking would be a false positive. A test asserts the non-restriction explicitly |

Tests: **22 verifier + 5 task = 27, 0 failures**. The repo-scale control was re-run after these changes and
still holds: `ci.yml` with the two-line enrollment → exit 0; restore byte-identical (`d8476d8e3aaf…`); without
it → exit 1.

## Review round 3 — conditional execution

`disablingReason()` rejected only a literal `if: false`. Any other expression fell through `yamlBoolean()` as
`null` and the step counted as unconditional enforcement, so `if: ${{ github.event_name == 'push' }}` was
"enforceable" while provably never running on a pull request.

A condition now counts only when its satisfaction on the required pull-request event is **provable**:

| Condition | Verdict |
|---|---|
| absent / blank | accepted |
| `always()`, `success()`, `true` | accepted |
| `github.event_name == 'pull_request'` | accepted — it proves PR applicability, and the real workflows use that form |
| `${{ github.event_name == 'push' }}` | **rejected**, naming the condition |
| `inputs.generate-baseline` | **rejected**, naming the condition |

"Unprovable" is treated as no enforcement: a gate nobody can prove runs is a gate nobody can rely on.
Five controls cover it — four verifier tests plus a real-task test proving the task fails closed with
"3 required gate(s)" for the push-only workflow. Tests now **32** (26 verifier + 6 task), 0 failures.

The real `ci.yml` control still holds unchanged: exit 0 with the intended enrollment, byte-identical restore
(`d8476d8e3aaf…`), exit 1 without it — the quality job and its gate step carry no `if:`.
