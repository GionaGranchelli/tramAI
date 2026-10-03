# Known Promotion Blockers — release/0.7.0 → master

**Status:** recorded, not scheduled. This is a ledger, not a task list.

**Purpose:** so these are not rediscovered months later. Nothing here is being
fixed as part of this record.

## The rule these exist under

```text
epic/task PRs      must be green before merge
release/0.7.0      must remain internally healthy
release -> master  must become fully green only when 0.7 is ready to promote
```

`release/0.7.0` is deliberately a release canary, not a continuously-green
development branch. A red check on it is not by itself an emergency; a red check
on it becomes a blocker at promotion time. The entries below are the checks that
will be red *at promotion*, measured rather than assumed.

## Measurement provenance

Measured at `6bb4830749…` (the released head immediately after the 0.7.1
promotion). The only later change to the released tree is one line in
`.github/workflows/maintainability-baseline.yml` (#484), which cannot affect any
test count below.

Commands, run against the released tree:

```bash
./gradlew :build-logic:test --rerun-tasks <lane filter set from maintainability-baseline.yml>
./gradlew verifyMaintainabilityBaseline --no-daemon
./gradlew verifyChangePolicy -PchangePolicyBase=41ac91512f9c58607b89646315d0ce2eb8dc3dae
```

## B1 — `release-sovereign` lane pin is stale: 179 → 181

| | |
|---|---|
| pinned | `expected: 179` |
| measured | **181**, in two independent environments (clone and worktree) |
| lane failures | 14 (the lane does not pass locally at this head) |
| effect at promotion | the `build-logic-tests` job asserts `total == expected` and fails |

The count was stable at 181 across environments while the failure count differed
(14 in a clone, 49 in a worktree, where `.git` is a file rather than a directory),
so the failures do not perturb the count.

**Why it is not fixed here:** the lane does not pass locally, and a count read
from a lane that does not pass is not evidence. This workflow only runs for
master-targeted PRs, so a release-targeted PR cannot certify the number either.
The authoritative number is the one CI reports in its own failure text
(`expected exactly 179 release-sovereign build-logic tests, discovered N`) — the
fix is to re-pin to that, at the promotion head, in a workflow-only change.

## B2 — `verifyChangePolicy` refuses the promotion delta: `analyzer-runtime-separation`

| | |
|---|---|
| command | `./gradlew verifyChangePolicy -PchangePolicyBase=<master>` |
| result | **BUILD FAILED** |
| policy | `POLICY [analyzer-runtime-separation]: Analyzer/tooling code and runtime production modules must not change together` |
| changed runtime cited | `tramai-control-plane/src/main/kotlin/dev/tramai/controlplane/ConfigurationFingerprint.kt`, `InMemoryWorkloadRegistrationStore.kt`, … |

Against a master base, the promotion delta is the whole 0.7.1 line: build-logic
analyzers and control-plane runtime production code in the same change set. The
policy rejects that combination by design.

**This needs a decision, not a repair.** The accepted change classes are
`runtime-behaviour`, `build-logic`, `baseline-migration`; none of them admits
analyzer + runtime together, and the only label-driven override in `ci.yml` is
`baseline-migration` (analyzer + baseline). So the release→master promotion
cannot pass this policy as currently configured. Either a promotion-scoped
change class is authorized, or the gate is changed for this transition — and a
gate or workflow change is an owner decision, not an agent edit.

## B3 — `quality-static-safety` has one failing test locally

| | |
|---|---|
| lane count | 71 measured vs 71 pinned — **matches**, so this is not pin drift |
| lane failures | 1 — `CancellationWiringTest` |
| status | unconfirmed by CI |

Recorded rather than diagnosed: the count is correct, so the pin is not at
fault, and the failing test is the known daemon-contention red. It needs CI's
verdict to separate "environment-only" from a real red at promotion.

## Recorded as resolved

- **`policy-maintainability`: 364 → 448** (#484, merged into `release/0.7.0`).
  Caused by the 0.7.1 line adding tests to the packages the lane globs. Merged
  separately, workflow-only, as its own attributable change.
- **`analyzer-runtime-separation` inside `verifyMaintainabilityBaseline`**:
  passes (`rc=0`). It is the *change policy* on the promotion delta (B2) that
  fires, not this task.
- Lanes measured at this head that **match** their pins: `quality-formatting`
  8/8, `quality-compiler-deps` 75/75, `quality-static-analysis` 25/25,
  `policy-maintainability` 448/448, `scanners-coverage` 297/297.
