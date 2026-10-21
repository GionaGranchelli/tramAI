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
| The XR1 Spring AI path is projected without pretending TramAI authored the workload | 0.7.4a XR1 discrepancy + 0.7.4b3 boundary provenance, and the 0.7.4f mapping tests `GovernanceExposureMapperTest` (`spring ai boundary evidence maps without claiming authorship or enforcement`) plus `GovernanceExposurePrivacyTest`. What those prove is the **safe projection and exposure of XR1-shaped evidence** — not a live, end-to-end Spring AI execution trace, which no 0.7.4 artefact claims |
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

## Acceptance gates H0–H4 reconciled

The 0.7.4h acceptance matrix defines five gates. Each is reconciled here rather than asserted complete.

| Gate | Claim | Evidence form | State |
|---|---|---|---|
| H0 | Dependency state exact, no inference from old reports | this document's delivery ledger | **satisfied** |
| H1 | All eligible modules qualified; zero unexpected fixture publications; negative control fails for the intended reason | PR #539 + `evidence/0.7.4h1-publication-boundary/` | **satisfied** (46 modules, `0/0/0`, controls 8 findings and `8/20/0`, orphan `0/1/0`) |
| H2 | Exact-head CI proves the gates were selected, executed and passed; removal is mechanically detected | PRs #541, #542 + `evidence/0.7.4h2a-*`, `evidence/0.7.4h2b-*` | **satisfied for the three governance gates**; see the H4 qualification for the verifier itself |
| H3 | The seven invariants still qualified at the closure head, reusing 0.7.4g evidence | reuse argument below | **satisfied** |
| H4 | Zero surviving valid security-critical mutants in the new integration protections | D4 control record below | **NOT satisfied in its strict form** — 8 killed, 1 security-relevant bypass (R1) survives; closure conditional on acceptance of R1 |

### H3 — why the 0.7.4g evidence still applies

Reuse is argued from the tree, not from dates. Both forms below were run at this head and produced no output;
they are recorded together because one names the seven module trees while the other is the wider claim.

```text
# A - all seven runtime production module trees, both endpoints
git diff --stat 7e628de7 5222375e -- tramai-security/src/main tramai-observability/src/main tramai-engine/src/main tramai-core/src/main tramai-structured/src/main tramai-control-plane/src/main tramai-standalone/src/main
(no output; 0 bytes)

# B - stronger form: every src/main path in the range that is not build-logic
git diff --name-only 7e628de7 5222375e | grep 'src/main' | grep -v '^build-logic/'
(no output)
```

No runtime production module changed between the 0.7.4g merge (`7e628de7`) and the post-0.7.4h2b integration
head (`5222375e`); every change in that range is `build-logic`, `.github/workflows/ci.yml`, tests, or
documentation. The invariants proved by the 0.7.4g adversarial campaign and the 15/15 mutant kills therefore
hold at the closure head, because the code they were proved against is identical.

### H4 — mutation and bypass record, and the one escape

The new integration protections in 0.7.4h are the publication-boundary verifier (0.7.4h1) and the CI
enrollment verifier (0.7.4h2a). Their adversarial record:

| Protection | Mutation / bypass applied | Verdict |
|---|---|---|
| enrollment verifier | remove `verifyModuleMatrixDrift` from the workflow | **killed** — exit 1, names that gate |
| enrollment verifier | remove `verifyModuleDocContract` | **killed** — exit 1, names that gate |
| enrollment verifier | remove `verifyNoPublishedTestFixtures` | **killed** — exit 1, names that gate |
| enrollment verifier | disable the step (`if: false`) | **killed** — exit 1, "not proven to run for pull requests" |
| enrollment verifier | mask the Gradle exit status (`\|\| true`, `\|\| exit 0`) | **killed** — exit 1, "masks its exit status" |
| enrollment verifier | conditions that merely contain an allowed expression (`… && false`) | **killed** — exit 1, exact-match allowlist |
| enrollment verifier | name a gate in `echo`, a comment, or another program | **killed** — exit 1, "no workflow step executes it" |
| publication verifier | re-publish a fixture variant / artifact | **killed** — 8 findings; repository level `8/20/0`; orphan `0/1/0` |
| **enrollment verifier** | **remove the verifier's own invocation** | **SURVIVES** — exit 0, nothing runs the verifier |

**Classification of the escape.** The last row is a *documented, classified escape*, not a missed mutant. A
gate cannot observe its own removal from inside itself, and no 0.7.4 artefact claims otherwise. It is
detectable only by an independent mechanism (a second verifier in another job, a scheduled audit diffing the
enrolled list against a committed expectation, or a workflow-vs-contract diff) — none of which exist, and
building one is outside 0.7.4's scope.

Consequently H4 is recorded as **not satisfied in its strict form**. H4's acceptance criterion is "zero
surviving valid security-critical mutants". Eight controls are killed and one security-relevant bypass
survives, so **strict zero-survivor acceptance is not established**. The implementation result is successful;
the acceptance criterion is not met, and the difference is recorded instead of reconciled by wording:

> **H4 status: eight adversarial controls killed. One security-relevant self-removal bypass remains and
> requires an independent enforcement mechanism. Strict zero-survivor acceptance is not established. Epic
> closure is conditional on explicit acceptance of this residual limitation (R1).**

R1 is tracked as a residual control weakness in its own right, not as a documentation nuance, because the
property is architectural: a passing self-enrollment verifier proves the required gates were present **when
the verifier executed**; it cannot prove the verifier will always be invoked. More tests inside the same
verifier cannot close R1 — only an independent mechanism can.

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
| S18 | Workload **identity** is not authenticated against the registration authority: a registered boundary's strength never proves an event passed through it, so an evidence record can name a workload identity it cannot prove it interacted with. External workloads are explicitly *not* supposed to be TramAI-authored — the missing property is authenticating the identity they claim, not authorship | an authenticated workload identity issued by the registration authority, outside the caller's control |
| S19 | Event-origin authenticity is not established for all evidence families | signed origin attestation at the producer boundary |
| T1 | Caller identity authentication remains tracked, not solved | an authenticated principal from the runtime host |
| G4 | `provider.route` has no live producer; the evidence family is declared non-production | a real provider-route producer in the runtime |
| — | Query and exposure representations remain caller-attested in the respects recorded by 0.7.4e/0.7.4f | an authenticated query principal |
| — | No metrics or trace correlation to the projection path beyond what 0.7.4f maps | correlation ids carried end to end |

**CI**

| Limitation | Consequence | What would close it |
|---|---|---|
| `release/0.7.0` has **no branch protection** (protection API returns `404`) | no check on this branch is merge-required server-side, including the four pre-existing `quality` legs | owner enables protection and marks required checks |
| **R1 (residual control weakness)** — a verifier cannot establish that it will always be invoked: removing `verifyCiGateEnrollment` from an otherwise green job still produces a green job | H4's strict zero-survivor criterion is not established; Epic closure is conditional on owner acceptance of R1 | an independent mechanism: second verifier in another job, scheduled audit diffing the enrolled list against a committed expectation, or workflow-vs-contract diff. Marking the check required is **not** sufficient |
| `verify060MaintainabilityRelease` cannot be configured locally | it configures `verifySovereignRuntimeSignedBundle`, which only supports `file://`, so it is not a usable local or CI vehicle for these gates | a configurable sovereign bundle location |
| 7 pre-existing `ReleaseVerificationPluginTest` failures (`Could not create task ':verifySovereignRuntimeSignedBundle'`) | build-logic tests cannot be reported as wholly green at any head examined | fixing the task-creation path |
| PR #400 integration failures against `master` | `master`-targeted validation shows failures unrelated to this branch | separate integration repair |

## Verdict

**COMPLETE for implementation; CONDITIONAL for Epic closure.**

Every 0.7.4 task is delivered, merged and evidenced. Closure is conditional on one explicit acceptance: the
residual control weakness **R1** (a verifier cannot prove it will always be invoked). H4's strict
zero-survivor criterion is **not** established and is not presented as if it were. Accepting R1 closes the
Epic; declining it leaves H4 open by definition, requiring an independent enforcement mechanism.

Explicitly **not** claimed: that TramAI authenticates the workload identity a record names, or that it
authors external workloads (S18); that event origin is authenticated (S19); that the `provider.route` family
has a live producer (G4); that any check on `release/0.7.0` is merge-required; that removal of the CI
enrollment verifier is detectable from inside CI.

## How to re-verify from a clean checkout

```bash
curl -s -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/GionaGranchelli/tramAI/pulls/542 \
  | jq -r '.merged_at, .merge_commit_sha'
./gradlew verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures verifyCiGateEnrollment
```

The four gates exiting 0 on this head is the same contract CI enforces for every pull request.
