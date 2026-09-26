# Epic 0.7.1 — Control-Plane Authority & Workload Identity

**Branch:** `epic/0.7.1-control-plane-authority`  
**Status:** 🟡 Active — 0.7.1a audit recorded in [TASK-0.7.1a-BASELINE-AUTHORITY-AUDIT.md](TASK-0.7.1a-BASELINE-AUTHORITY-AUDIT.md); 0.7.1b identity contract implemented in [TASK-0.7.1b-WORKLOAD-IDENTITY-CONTRACT.md](TASK-0.7.1b-WORKLOAD-IDENTITY-CONTRACT.md); 0.7.1c authoritative registration/state boundary implemented in [TASK-0.7.1c-AUTHORITATIVE-REGISTRATION-STATE-BOUNDARY.md](TASK-0.7.1c-AUTHORITATIVE-REGISTRATION-STATE-BOUNDARY.md); 0.7.1f safe exposure model implemented in [TASK-0.7.1f-SAFE-EXPOSURE-MODEL.md](TASK-0.7.1f-SAFE-EXPOSURE-MODEL.md)
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

## Exit

0.7.1 is done when later Epics can consume stable identity/authority contracts without inventing parallel representations.
