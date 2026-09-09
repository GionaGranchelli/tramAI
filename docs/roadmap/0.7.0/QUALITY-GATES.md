# TramAI 0.7.0 — Quality & Release Gates

These gates supplement, not replace, repository CI. Existing 0.6 architecture, static-analysis, mutation, cancellation, provider/store TCK, consumer-smoke, dependency-hygiene, zero-egress, and evidence gates remain authoritative where applicable.

## G0 — Scope integrity

- Every implementation change maps to an Epic/task.
- No 0.8/0.9/0.10 capability is pulled into 0.7 without a release-cut amendment.
- Dashboard code cannot expand runtime governance semantics.

## G1 — Authority integrity

Prove:

- workload/configuration/run identity has one authoritative source;
- classification required by policy occurs before provider exposure;
- lower policy scopes cannot widen higher-level denial;
- authorization, viability, and selection remain separate;
- privileged controls cross server-side authorization/runtime authority;
- projection/telemetry cannot mutate authority.

## G2 — Failure-closed semantics

Required negative cases include, where applicable:

- missing/unknown classification;
- missing/unknown provider deployment/trust zone;
- stale version/precondition;
- reordered/duplicate evidence;
- unavailable projection source;
- authorization denial;
- concurrent control commands;
- restart/recovery;
- late approval after cancellation;
- incomplete historical evidence.

## G3 — Sensitive-data safety

Generic control-plane/query/telemetry surfaces do not expose by default:

- prompts/completions;
- credentials/secrets;
- tool arguments/results;
- document bodies;
- equivalent protected payloads.

Explicit payload surfaces, if any are required, need separate authorization and tests.

## G4 — Compatibility

- Public API changes obey existing compatibility policy.
- Cross-provider/store/module contracts use TCK/contract tests where appropriate.
- Structured reason/evidence IDs are deterministic and intentionally versioned.
- 0.7 implementation does not couple core semantics to future DSL, vendor IdP, dashboard, or deployment packaging.

## G5 — Adversarial proof

Every high-value invariant must have a discriminator that fails if the semantic rule is weakened. Examples:

```text
classify-after-exposure      => FAIL
lower-scope policy widening  => FAIL
selected outside authorized  => FAIL
UI-only authorization        => FAIL
resume after cancellation    => FAIL
reconstruction side effect   => FAIL
```

## G6 — Mutation proof

Use mutation testing selectively for authority/correctness semantics where a plausible one-line mutation could bypass a rule. Surviving relevant mutants require either stronger proof or an explicit justified classification.

## G7 — Determinism & reconstruction

- reason/evidence output used for authority is deterministic for the same authoritative input;
- projections are idempotent;
- ordering/version semantics are explicit;
- reconstruction never invokes provider/tool/approval/network/workflow side effects;
- missing historical evidence is represented as missing, not substituted from current configuration.

## G8 — Epic integration gate

Before `epic/0.7.x → release/0.7.0`:

1. Epic spec updated to final behavior;
2. all required task proofs green;
3. exact-head repository CI green;
4. required mutation/adversarial proofs green;
5. docs and evidence complete;
6. branch rebased onto current release head.

## G9 — Release certification gate

Before `release/0.7.0 → master`, run the complete repository/release verification at the exact candidate head, including all existing release-critical lanes plus:

- reference governed-control-plane scenario;
- persisted suspended-run cancellation scenario;
- dashboard/headless parity for supported operations;
- safe payload exposure checks;
- reconstruction no-side-effect checks;
- migration/release documentation validation.

The final promotion PR should be integration-boring: failures discovered there indicate the release branch was not maintained correctly.
