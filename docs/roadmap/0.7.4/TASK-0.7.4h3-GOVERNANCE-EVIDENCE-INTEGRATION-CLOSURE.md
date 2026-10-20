# 0.7.4h3 — Governance evidence integration and Epic 0.7.4 closure

**Branch:** `task/0.7.4h3-epic-closure`
**Base:** `release/0.7.0` = `5222375eb0cff7b4d55c9ea627dfafb529333809`
**Primary change class:** `documentation`
**Date:** 2026-10-20

## Purpose

Consolidate the evidence produced by 0.7.4a–0.7.4h2b, state what is proven and what is not, and close
Epic 0.7.4. This record performs **no implementation work**: no production, API, runtime-governance, analyzer
or workflow change. Its only job is to make the Epic's state auditable from one place.

## Delivery ledger

Every row is a merged change on `release/0.7.0`, with the merge commit that carries it.

| Task | Result | PR | Merge commit | Primary evidence |
|---|---|---|---|---|
| 0.7.4a | Evidence baseline audit: inventory, XR1 discrepancy, threat model | #516 | `6e1d4a1e` | `0.7.4a-GOVERNANCE-EVIDENCE-AUDIT.md` |
| 0.7.4b1 / G1a | Governed identity carried to the authoritative evaluator (Option A) | #517 | `584cf8cb` | `0.7.4b1-*`, `evidence/0.7.4b1-option-a/` |
| 0.7.4b2 / G1b | Governed identity bound into `tool.permission` evidence and its digest | #521 | `7c823d05` | `0.7.4b2-*`, `evidence/0.7.4b2-*` |
| 0.7.4b3 / G2 | Registered enforcement-boundary provenance | #523 | `2c7e3206` | `0.7.4b3-*`, `evidence/0.7.4b3-*` |
| 0.7.4b4 / G3 | Authorization distinguished from completion | #525 | `eee841c5` | `0.7.4b4-*`, `evidence/0.7.4b4-*` |
| 0.7.4b5 | Merge-gate RC applicability | #526 | `c8b24e19` | `0.7.4b5-*`, `evidence/0.7.4b-merge-gate/` |
| 0.7.4b6 / G4 | `provider.route` explicit non-production declaration | #527 | `47b230b1` | `TASK-0.7.4b7-PROVIDER-ROUTE-LIVE-PRODUCER.md` |
| 0.7.4b | Governance evidence schema freeze | #529 | `24caf4bb` | `CHECKPOINT-0.7.4b-*`, `evidence/0.7.4b-schema-freeze/` |
| 0.7.4c1 | Authority-aware projection contract and first read boundary | #530, #531 | `072f5e63`, `ce503dda` | `0.7.4c1-*`, `evidence/0.7.4c1-*` |
| 0.7.4c2 | Audit-backed governance decision fact source (Option C) | #533 | `b918b765` | `0.7.4c2-*`, `evidence/0.7.4c2-*` |
| 0.7.4c3 | Verified projection coverage separated from decision classification | #534 | `052cc27f` | `0.7.4c3-*`, `evidence/0.7.4c3-*` |
| 0.7.4d | Durable, rebuildable materialized read model | #535 | `3bc94891` | `TASK-0.7.4d-*`, `evidence/0.7.4d-*` |
| 0.7.4e | Headless governance query API | #536 | `d3ca7613` | `TASK-0.7.4e-*`, `evidence/0.7.4e-*` |
| 0.7.4f | Safe exposure and OpenTelemetry mapping | #537 | `33a1830f` | `TASK-0.7.4f-*`, `evidence/0.7.4f-*` |
| 0.7.4g | Adversarial rebuild and mutation proof | #538 | `7e628de7` | `TASK-0.7.4g-*`, `evidence/0.7.4g-*` |
| 0.7.4h1 | Test-fixture publication boundary | #539 | `9ddd5402` | `evidence/0.7.4h1-publication-boundary/` |
| 0.7.4h2a | CI gate-enrollment contract (build-logic) | #541 | `5c287cd5` | `evidence/0.7.4h2a-*` |
| 0.7.4h2b | Enforcement of the three gates plus the verifier (workflow-only) | #542 | `5222375e` | `evidence/0.7.4h2b-*` |

## Acceptance criteria → evidence

The Epic's acceptance criteria, each mapped to the artefact that supports it. A criterion with no evidence
would be listed as unmet; none are.

| Criterion | Where proved |
|---|---|
| Clients can query P0 governance state without reading unrelated runtime stores | 0.7.4d read model + 0.7.4e query API (`evidence/0.7.4d-*`, `evidence/0.7.4e-*`) |
| Rebuild is deterministic for the supported evidence set | 0.7.4d durability/rebuild proofs; 0.7.4g adversarial rebuild |
| Duplicate/reordered evidence obeys explicit semantics | 0.7.4g — duplicate, reorder, stale and missing-source discriminators; 15/15 mutants killed |
| Projection lag and source authority are observable | 0.7.4c1/c3 freshness authority table, pinned in 0.7.4g |
| Query/telemetry defaults do not leak protected payloads | 0.7.4f exposure privacy tests; canaries seeded for every excluded field |
| The XR1 Spring AI path is projected without pretending TramAI authored the workload | 0.7.4a XR1 discrepancy + 0.7.4b3 boundary provenance |
| Clients can distinguish enforced from instrumented/observed/imported evidence | 0.7.4b2/b3 provenance attributes; 0.7.4f keeps OTEL non-authoritative |
| Adversarial rejection list (projection-side policy, duplicate final state, stale overwrite, unavailable source as PASS, payload leakage, telemetry as prevention, client-supplied enforcement strength, external-runtime as TramAI-governed) | 0.7.4g mutation campaign plus 0.7.4f/b3 discriminators |

## Core invariants → enforcement

| Invariant | Mechanism | Proof |
|---|---|---|
| Projection cannot create or widen authority | decision facts come from the audit-backed source only | 0.7.4c2/c3 |
| Same authoritative event applied twice ⇒ same projection as once | idempotent upsert over a deterministic key | 0.7.4d, 0.7.4g |
| Projection lag is never presented as stronger authority than source | freshness authority table | 0.7.4c3, pinned in 0.7.4g |
| Missing evidence ≠ inferred success | unavailable source is classified unavailable, never PASS | 0.7.4c3, 0.7.4g |
| Observed telemetry ≠ proof of enforcement | OTEL mapping is additive and non-authoritative | 0.7.4f |
| Client/integration metadata cannot self-upgrade enforcement strength | enforcement strength comes from the evaluator, not the caller | 0.7.4b2/b3 |
| Protected payloads are excluded by default | exposure contract filters by default, canaries per field | 0.7.4f |

## CI enforcement state after 0.7.4h (measured, not assumed)

| Gate | Before 0.7.4h | After |
|---|---|---|
| `verifyModuleMatrixDrift` | reachable only from `verify060MaintainabilityRelease`, which no workflow runs | runs on every PR via `quality (governance-integration)` |
| `verifyModuleDocContract` | **unreachable** — its conditional edge never binds (plugin apply order) | runs on every PR |
| `verifyNoPublishedTestFixtures` | reachable from no aggregator and no workflow | runs on every PR |
| `verifyCiGateEnrollment` | did not exist | runs on every PR and audits the enrollment above |

Proven behaviour, not intent:

- removing any one of the three gates from the workflow makes `verifyCiGateEnrollment` fail, naming that gate;
- disabling the step (`if: false`) fails with "not proven to run for pull requests";
- masking the exit status (`|| true`) fails with "masks its exit status";
- a real matrix violation fails with `[GENERATED_DOCUMENT_DRIFT]`, a real documentation violation with
  `[MODULE_CARD_MISSING]`, a real fixture leak with 8 findings naming module/variant/artifact;
- every control restores byte-identically and passes again.

## Remaining limitations

These are carried forward deliberately. An Epic closed with named limits is auditable; one closed with
implied completeness is not.

**Authority and evidence**

| ID | Limitation | What would close it |
|---|---|---|
| S18 | Workload authenticity is not established — TramAI cannot prove it authored the workload it reports on | an authenticated workload identity outside the caller's control |
| S19 | Event-origin authenticity is not established for all evidence families | signed origin attestation at the producer boundary |
| T1 | Caller identity authentication remains tracked, not solved | an authenticated principal from the runtime host |
| G4 | `provider.route` has no live producer; the evidence family is declared non-production | a real provider-route producer in the runtime |
| — | Query and exposure representations remain caller-attested in the respects recorded by 0.7.4e/0.7.4f | an authenticated query principal |
| — | No metrics or trace correlation to the projection path beyond what 0.7.4f maps | correlation ids carried end to end |

**CI**

| Limitation | Consequence | What would close it |
|---|---|---|
| `release/0.7.0` has **no branch protection** (protection API returns `404`) | no check on this branch is merge-required server-side, including the four pre-existing `quality` legs | owner enables protection and marks required checks |
| Marking a check required is a merge gate, not an enrollment audit | removing `verifyCiGateEnrollment` from an otherwise green job still produces a green job | an independent mechanism: second verifier in another job, scheduled audit diffing the enrolled list against a committed expectation, or workflow-vs-contract diff |
| `verify060MaintainabilityRelease` cannot be configured locally | it configures `verifySovereignRuntimeSignedBundle`, which only supports `file://`, so it is not a usable local or CI vehicle for these gates | a configurable sovereign bundle location |
| 7 pre-existing `ReleaseVerificationPluginTest` failures (`Could not create task ':verifySovereignRuntimeSignedBundle'`) | build-logic tests cannot be reported as wholly green at any head examined | fixing the task-creation path |
| PR #400 integration failures against `master` | `master`-targeted validation shows failures unrelated to this branch | separate integration repair |

## Verdict

**COMPLETE** for Epic 0.7.4's scope, with the limitations above carried forward explicitly.

Explicitly **not** claimed: that TramAI authenticates the workload (S18) or the event origin (S19); that the
`provider.route` family has a live producer (G4); that any check on `release/0.7.0` is merge-required; that
removal of the CI enrollment verifier is detectable from inside CI.

## How to re-verify from a clean checkout

```bash
curl -s -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/GionaGranchelli/tramAI/pulls/542 \
  | jq -r '.merged_at, .merge_commit_sha'
./gradlew verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures verifyCiGateEnrollment
```

The four gates exiting 0 on this head is the same contract CI enforces for every pull request.
