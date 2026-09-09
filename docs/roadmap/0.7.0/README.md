# TramAI 0.7.0 — Execution Board

> **Release:** 0.7.0 — Governed AI Control Plane  
> **Integration branch:** `release/0.7.0`  
> **Stable branch:** `master`  
> **Release authority:** [`../../ROADMAP-0.7.0-RELEASE-CUT.md`](../../ROADMAP-0.7.0-RELEASE-CUT.md)  
> **Status:** IN DEVELOPMENT

The roadmap defines **what** ships. This directory defines **how 0.7.0 is executed and proven**.

## Release thesis

> **The model proposes. TramAI decides. The control plane shows why.**

Supported-profile loop:

```text
identify → classify → constrain → authorize → select → execute
        → evidence → observe → control → reconstruct
```

## Working model

```text
master
  └── release/0.7.0
        ├── epic/0.7.1-control-plane-authority
        ├── epic/0.7.2-policy-trust-zones
        ├── epic/0.7.3-authorized-selection
        ├── epic/0.7.4-evidence-projection
        ├── epic/0.7.5-timeline-reconstruction
        ├── epic/0.7.6-auth-runtime-control
        ├── epic/0.7.7-suspended-run-cancellation
        └── epic/0.7.8-dashboard-integration
```

Task/candidate branches target their Epic branch. Completed Epic branches target `release/0.7.0`. The release branch targets `master` only after all release gates are green.

See [`WAY-OF-WORKING.md`](WAY-OF-WORKING.md).

## Epic board

| Epic | Outcome | Dependency | Status |
|---|---|---|---|
| [0.7.1](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md) | Authoritative control-plane/workload identity boundary | — | ⚪ Planned |
| [0.7.2](EPIC-0.7.2-POLICY-TRUST-ZONES.md) | Classification-before-exposure, named trust topology, restrictive policy | 0.7.1 soft | ⚪ Planned |
| [0.7.3](EPIC-0.7.3-AUTHORIZED-SELECTION.md) | Explainable authorized/viable/selected provider-model decision | 0.7.2 hard | ⚪ Planned |
| [0.7.4](EPIC-0.7.4-EVIDENCE-PROJECTION.md) | Typed governance evidence + authoritative read model/query API | 0.7.1 hard; 0.7.2/3 soft | ⚪ Planned |
| [0.7.5](EPIC-0.7.5-TIMELINE-RECONSTRUCTION.md) | Semantic timeline + side-effect-free forensic reconstruction | 0.7.4 hard | ⚪ Planned |
| [0.7.6](EPIC-0.7.6-AUTH-RUNTIME-CONTROL.md) | Generic OIDC boundary, capability authorization, typed controls | 0.7.1/4 hard | ⚪ Planned |
| [0.7.7](EPIC-0.7.7-SUSPENDED-RUN-CANCELLATION.md) | Authoritative persisted-run cancellation and resume fencing | 0.7.6 hard | ⚪ Planned |
| [0.7.8](EPIC-0.7.8-DASHBOARD-INTEGRATION.md) | Dashboard 2.0 as replaceable control-plane client + release proof | 0.7.3–0.7.7 hard | ⚪ Planned |

Status values: `⚪ Planned` · `🟡 Active` · `🟣 Review` · `✅ Complete` · `⛔ Blocked`.

## Waves

### Wave A — Governance foundations
- 0.7.1 Control-plane authority & workload identity
- 0.7.2 Classification, trust zones & restrictive policy

### Wave B — Authoritative decisions
- 0.7.3 Authorized provider/model selection

### Wave C — Evidence & observability
- 0.7.4 Evidence/projection/query API
- 0.7.5 Timeline/reconstruction

### Wave D — Control & product surface
- 0.7.6 Authentication/authorization/runtime control
- 0.7.7 Suspended-run cancellation/resume fencing
- 0.7.8 Dashboard 2.0 + end-to-end release integration

Parallel work is allowed when dependencies are soft or absent. A branch must rebase onto the current `release/0.7.0` before Epic integration.

## Release-wide non-negotiable invariants

```text
classification before provider exposure
organization ∩ environment ∩ workload = effective policy
selected ∈ viable
viable ⊆ authorized
cancelled(run) => no subsequent authoritative execution(run)
reconstruction != re-execution
Dashboard != policy authority
```

## Release completion

0.7.0 is complete only when:

1. all eight Epic specs are satisfied;
2. all Epic acceptance/adversarial proofs are green;
3. [`QUALITY-GATES.md`](QUALITY-GATES.md) is green at the exact release head;
4. the reference control-plane scenario and persisted-cancellation scenario pass end to end;
5. no deferred 0.8/0.9/0.10 scope has leaked into the release without an explicit release-cut change;
6. `release/0.7.0 → master` is a certification/promotion PR, not a first-time integration event.

## Execution documents

- [`WAY-OF-WORKING.md`](WAY-OF-WORKING.md)
- [`DEPENDENCIES.md`](DEPENDENCIES.md)
- [`QUALITY-GATES.md`](QUALITY-GATES.md)
- [`SPEC-TEMPLATE.md`](SPEC-TEMPLATE.md)
- Epic specs linked in the board above.
