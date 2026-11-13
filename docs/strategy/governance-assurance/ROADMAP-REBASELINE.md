# GAP-000 — Cross-Release Roadmap Rebaseline

> Status: proposed strategic priority and release-direction adjustment. Existing 0.7 P0 release cut is preserved.

## Program thesis

AI governance assurance becomes the first product-ordering principle for TramAI while the Kotlin/JVM governed runtime remains the product. Trace applicable obligations to controlled behavior, operational evidence and accountable human processes. Avoid a separate compliance policy engine or full organizational GRC platform.

## Workstreams and sequencing

1. **GAP-100 Regulatory applicability and evidence schema (P0):** model versioned source/role/system applicability, owned controls, explicit gaps and provenance. Start with selected high-value use cases; expand clause/control catalogue only after normative review.
2. **CFG-0001/GAP-200 Governed configuration authority (P0 design):** typed descriptors, immutable revisions, scope precedence, validation, change authorization, safe activation and rollback, consistent among JVM DSL, YAML, GitOps, API and dashboard.
3. **GAP-300 Technical assurance (P0 after contracts):** evidence-to-control mapping, adversarial tests, missing-proof semantics and safe export.
4. **GAP-400 Lifecycle assessment (P1):** risk/impact references, evaluation evidence, monitoring, changes and incident integrations.
5. **GAP-500 Enterprise operation (P1):** reviewer/admin UX, IAM, deployment and GRC integrations.

## Release allocation

| Release | Preserve | Proposed addition or priority change | Not promised |
|---|---|---|---|
| 0.7 | Existing P0 identity, restrictive policy, runtime evidence, controls, dashboard read client and cancellation safety | Architectural compatibility with versioned evidence/config and future control mappings | No new broad compliance release blockers |
| 0.8 | Typed governance facts, DSL, structured contracts, simulation/testing | Typed configuration schema/projection, candidate validation, GAP-100 crosswalk schema, test/evidence traceability | No managed production config UI requirement |
| 0.9 | Enterprise IAM, key lifecycle and reference deployment | Authorized persistent config mutation and distribution, safe operator dashboard editing, scoped assurance export | No generic GRC suite or automatic certification |
| 0.10 | Governed learning and policy-bounded optimization | Continuous assurance and evaluation/monitoring evidence integration ahead of non-essential optimization | No ungoverned learning or autonomous authority expansion |
| 1.0 decision | Stable public contracts | Stability of authoritative configuration/evidence/approval/replay interfaces assessed explicitly | No version-date commitment |

## How to migrate existing work

- Keep open and in-flight 0.7 authority/security correctness work; do not rebase or merge mechanically.
- Mark each existing task KEEP, ALIGN, DEFER or REMOVE with a rationale and explicit issue/PR ownership.
- Do not rewrite completed evidence, turn historical tests into current coverage, or claim a component is certified by mere article linkage.
- New governance-affecting tasks use the engineering protocol; unrelated quality maintenance keeps normal gates.
- Public positioning changes only once demonstrated code and evidence justify the stronger promise.

## Readiness / decision gates

1. Strategic direction recorded, without stealth-changing 0.7.
2. Detailed applicability crosswalk reviewed against official legal sources and licensed ISO text by an accountable reviewer.
3. CFG-0001 authority and activation invariants agreed before any public hot-change API.
4. 0.8/0.9/0.10 implementation epics explicitly re-scoped with acceptance evidence, dependency order and open-task treatment.
5. CI/release discipline remains intact.

## Metrics

Prefer: percentage of *selected applicable controls* with verified evidence and explicit gap owners; evidence freshness; configuration-change failure/rollback tests; operator time to safely change an eligible property; integration adoption. These are operational diagnostics, **not legal compliance percentages**.
