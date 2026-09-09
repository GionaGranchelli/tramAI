# Epic 0.7.5 — Semantic Timeline & Forensic Reconstruction

**Branch:** `epic/0.7.5-timeline-reconstruction`  
**Status:** ⚪ Planned  
**Dependencies:** 0.7.4 HARD

## Executive decision

Turn authoritative evidence into a typed governance timeline and reconstruct supported historical runs without re-executing providers, tools, approvals, network actions, or workflow side effects.

## Core invariants

```text
reconstruction != re-execution
missing historical evidence remains missing
current configuration is never silently substituted for historical configuration
best-effort telemetry remains distinguishable from authoritative evidence
```

## Scope

- semantic run event model;
- deterministic ordering/grouping rules;
- authoritative vs best-effort/missing markers;
- reconstruction input contract and completeness model;
- historical configuration/policy/topology identity resolution;
- side-effect-free reconstruction API/result;
- explicit unsupported/incomplete reconstruction semantics.

## Non-goals

- public deterministic policy replay/simulation;
- incident-analysis product suite;
- rerunning providers/tools to fill evidence gaps.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.5a | Timeline/reconstruction audit | Characterize available evidence and missing historical identities |
| 0.7.5b | Semantic timeline contract | Typed governance transitions and authority/source markers |
| 0.7.5c | Timeline projection/query | Deterministic semantic ordering from 0.7.4 evidence |
| 0.7.5d | Reconstruction contract | Inputs, completeness states, historical identity requirements |
| 0.7.5e | Side-effect-free reconstructor | Produce historical governed narrative/state without external invocation |
| 0.7.5f | Missing/history semantics | Explicit partial/unsupported outcomes; no current-default substitution |
| 0.7.5g | Adversarial/mutation proof | Trap provider/tool/network invocation and config substitution |
| 0.7.5h | Integration/docs | Reference timeline/reconstruction examples and Epic gate |

## Acceptance criteria

- Reference run renders classification, policy, routing, approval/control and execution transitions where evidence exists.
- Missing evidence is visible.
- Reconstruction causes zero provider/tool/approval/network/workflow side effects.
- Historical configuration is used when available; absence is explicit.

## Adversarial proof

Use traps/fakes that fail if reconstruction invokes external behavior. Reject silent current-config fallback and timeline entries inferred from absence of evidence.
