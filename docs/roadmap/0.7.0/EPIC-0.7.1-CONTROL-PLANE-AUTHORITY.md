# Epic 0.7.1 — Control-Plane Authority & Workload Identity

**Status:** ✅ **CONTENT FROZEN** — ready for 0.7.1i integration closure / master promotion. 0.7.1a–0.7.1g implemented and proven; 0.7.1h integration/docs closure recorded in [§0.7.1h](#071h--integration-closure--content-freeze) below. Task records: [0.7.1a](TASK-0.7.1a-BASELINE-AUTHORITY-AUDIT.md), [0.7.1b](TASK-0.7.1b-WORKLOAD-IDENTITY-CONTRACT.md), [0.7.1c](TASK-0.7.1c-AUTHORITATIVE-REGISTRATION-STATE-BOUNDARY.md), [0.7.1d](TASK-0.7.1d-RUN-ATTRIBUTION.md), [0.7.1d1](TASK-0.7.1d1-GOVERNED-APPROVAL-IDENTITY-CARRIAGE.md), [0.7.1e](TASK-0.7.1e-CONTROL-PLANE-AUTHORITY-CONTRACT.md), [0.7.1f](TASK-0.7.1f-SAFE-EXPOSURE-MODEL.md), [0.7.1g1P2](TASK-0.7.1g1P2-AUTHORITY-MODEL-RECONCILIATION.md)
**Branch:** `epic/0.7.1-control-plane-authority`  
**Dependencies:** NONE

## Executive decision

Define the authoritative control-plane boundary and stable workload/configuration/run identity before building policy, projections, controls, or UI around it.

## Outcome

Every independently governed control-plane workload and run can be attributed to one authoritative workload/configuration identity, and read/write authority is explicit enough that a replaceable client cannot become a second source of truth.

## Scope

- authoritative workload identity and configuration/version identity;
- owner, purpose, environment/deployment metadata required by the supported profile;
- run-to-workload/configuration correlation;
- lifecycle identity/state boundary;
- source-of-truth inventory for workloads, approvals, policy/routing decisions, controls, evidence and exposed runtime state;
- projection/query consistency categories;
- stale command precondition/version semantics where needed;
- safe field exposure categories and module dependency direction.

## Non-goals

- rich workflow DSL/annotations;
- policy authoring UX;
- vendor-specific IAM;
- Dashboard implementation;
- broad workload-management product features not required by the P0 loop.

## Core invariants

```text
run -> exactly one authoritative workload/configuration context
independent deployments do not silently share authoritative identity
query/projection state cannot mutate runtime authority
stale privileged mutation cannot silently overwrite newer authoritative state
```

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.1a | Baseline authority audit | Map existing runtime/store/approval/routing/evidence authorities and gaps; no speculative implementation |
| 0.7.1b | Workload identity contract | Define stable workload, configuration/version, environment/deployment and correlation types/validation |
| 0.7.1c | Authoritative registration/state boundary | Establish source-of-truth and lifecycle semantics for control-plane workloads |
| 0.7.1d | Run attribution | Persist/propagate workload+configuration identity through supported runs without ambiguity |
| 0.7.1d1 | Governed approval identity carriage | Get both gateway creation paths onto the existing governed suspension path; carry framework-owned attribution through the approval lifecycle; enforce continuity at reconstruction (#418) |
| 0.7.1e | Control-plane authority contract | Define query vs command boundaries, consistency classes and stale-precondition semantics |
| 0.7.1f | Safe exposure model | Implement the explicit `WorkloadExposure` safe exposure model; protected payloads have no generic query surface |
| 0.7.1g | Evidence/compatibility proof | Typed identity/lifecycle evidence, API/TCK impact, adversarial and mutation proof |
| 0.7.1h | Integration/docs | Update architecture/docs/reference fixtures and prove exact-head Epic acceptance |
| 0.7.1i | Integration closure / master promotion authority | Bind API migrations to the frozen `master → final-0.7.1` hashes, certify the release-sovereign population pin from evidence, and establish a promotion sequence that keeps analyzer/build-logic changes out of the runtime transition — without weakening `analyzer-runtime-separation` ([TASK-0.7.1i](TASK-0.7.1i-INTEGRATION-CLOSURE.md)) |
| 0.7.1g1G1 | Mutation-debt taxonomy | Classify the 195 candidate-only `NON_KILLED` identities from their mutated instructions: 124 glue, 10 weak-assertion (8 closed by #444, 1 closed by #446, 1 deferred), 4 equivalent, 2 compiler guards, 9 closed by #442 — with the 46 `TIMED_OUT` recorded as **undetermined** rather than a settled category ([TASK-0.7.1g1G1](TASK-0.7.1g1G1-MUTATION-DEBT-TAXONOMY.md)) |
| 0.7.1g1G2 | Residual taxonomy and adjudication | Give every residual candidate-only identity exactly one disposition at the post-0.7.1g1G1 frozen head: 177 residual = 12 equivalent + 46 tooling limitation + 1 deferred structural + 118 undetermined, with the re-census showing zero status movement and lost/duplicate/unexplained all zero ([TASK-0.7.1g1G2](TASK-0.7.1g1G2-RESIDUAL-TAXONOMY.md)) |
| 0.7.1g1G3 | Appearing population admission ceremony | Implement M30-M39: a base-authoritative, exact-row, single-use admission mechanism that lets a later exact-population transition admit specifically authorized candidate-only `NON_KILLED` identities while every unauthorized appearance still fails M06 — 0 admissions, 0 preauthorizations ([TASK-0.7.1g1G3](TASK-0.7.1g1G3-POPULATION-ADMISSION-CEREMONY.md)) |
| 0.7.1g1P2 | Authority-model reconciliation | Close the canonical T1–T19 authority model: one shared `AdmissionAuthority` fact threaded to M34/admission/certificate consumers, real-task transport boundaries, and the base-side raw-v1 → authority-v2 migration certificate chain. Step 3c closed as COMPLETE ([TASK-0.7.1g1P2](TASK-0.7.1g1P2-AUTHORITY-MODEL-RECONCILIATION.md) §7/§11) |

## Acceptance criteria

- Supported runs identify workload and configuration/version unambiguously.
- Two distinct deployments cannot collapse to one identity accidentally.
- Dashboard/query replacement cannot change governance semantics.
- Runtime mutation paths are distinct from projection/query paths.
- Stale-state protection is defined and tested for commands that require it.
- Sensitive content is excluded from generic control-plane metadata.

## Adversarial proof

Tests must reject at least:

- missing workload identity on a supported governed run;
- reuse/collision of identity across distinct deployment/config contexts;
- mutation through a read-model/projection path;
- stale precondition accepted where a newer authoritative version exists;
- generic metadata endpoint exposing protected payload content.

## Mutation expectations

Kill mutations that remove identity/version checks, bypass stale preconditions, or reclassify protected fields as default-safe where such checks are implemented in 0.7.1.

## 0.7.1h — Integration closure & content freeze

Reconciled against the merged implementation at the epic tip, not against task-document `Status:`
lines. This task changed documentation only: no production code, no tests, no authority artifacts,
no mutation baselines.

### Epic acceptance criteria — existing evidence

| criterion | evidence | result |
|---|---|---|
| Supported runs identify workload and configuration/version unambiguously | `ServerGovernedRunAttributionTest` ("a registered active deployment starts a governed run with a fresh run id"; "two governed runs of the same deployment get distinct run ids"; "a configured identity differing only by configuration version is rejected") + `WorkloadRegistrationAuthorityTest` (register/idempotency/lifecycle preserve identity) | PASS |
| Two distinct deployments cannot collapse to one identity accidentally | `WorkloadRegistrationAuthorityTest` ("configuration rebinding is rejected from a different deployment scope"; "the same configuration on a different deployment registers cleanly"; "re-registering the same scope with a different configuration is rejected, not upserted") | PASS |
| Dashboard/query replacement cannot change governance semantics | `WorkloadControlPlaneContractTest` ("the read port exposes no operation that can mutate authoritative state"; "a lagging projection observation never authorizes a command"; "a command takes an explicit version, never a read record") + `WorkloadExposureModelTest` ("query and command type graphs are exact and safe") | PASS |
| Runtime mutation paths are distinct from projection/query paths | `WorkloadControlPlaneContractTest` ("authoritative read is classified as such and reports the authoritative version"; "projection read states its consistency class and the version it observed") + `WorkloadExposureModelTest` ("authoritative and projection reads expose identical safe shape") | PASS |
| Stale-state protection is defined and tested for commands that require it | `WorkloadControlPlaneContractTest` ("concurrent commands on the same expected version yield exactly one winner and a truthful stale loser"; "RETIRED is terminal through the command port and stays distinct from a stale expectation") + `WorkloadRegistrationAuthorityTest` ("stale metadata update is rejected without mutation"; "stale lifecycle transition loses and does not mutate"; "two concurrent metadata writers produce one winner and one stale loser") | PASS |
| Sensitive content is excluded from generic control-plane metadata | `WorkloadExposureModelTest` ("exposure has exactly the approved four properties"; "mapper excludes the authority fingerprint"; "metadata has exactly owner and purpose"; "port signatures expose no forbidden extension type"; "classified read exposes safe model and rejects version contradiction") | PASS |

All six criteria are satisfied by tests that already existed. **No implementation gap was found**,
and no test was created or renamed for this task.

### Documentation reconciliation

- `docs/modules/tramai-control-plane.md` already states the responsibility, the safe exposure model
  (a declaration input that is never reflected back), the CAS/stale-writer semantics, the
  RETIRED-terminal lifecycle and the TCK inventory correctly — **left unchanged**.
- This record's `Status:` line was materially stale (it named only 0.7.1a/b/c/f) — corrected, and the
  missing 0.7.1d/d1/e/g1P2 records linked.
- The invariant "candidate transitions cannot mint the authority they consume" is recorded in its own
  task record ([0.7.1g1P2](TASK-0.7.1g1P2-AUTHORITY-MODEL-RECONCILIATION.md) §7/§11) rather than
  duplicated here.

### Content freeze

`epic/0.7.1-control-plane-authority` is **CONTENT FROZEN** for 0.7.1i: no implementation work, no new
tests and no authority-artifact changes land on this branch except 0.7.1i's own promotion authority.

## Exit

0.7.1 is done when later Epics can consume stable identity/authority contracts without inventing parallel representations.
