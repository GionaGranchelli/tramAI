# TramAI Roadmap Design Notes

> **Status:** Supporting design material, not release scope authority.  
> **Release ownership:** Defined by `../LONG-TERM-ROADMAP-0.7-0.10.md` and the corresponding top-level `../ROADMAP-<version>*.md` files.  
> **Historical note:** Several documents in this directory originated while the broader roadmap was still called “0.7.0”. Their embedded `0.7.0` target labels and P0/P1 wording are historical design context and do **not** override the normalized release ownership below.

These documents preserve detailed architecture, invariants, threat considerations, and test ideas without forcing every future capability into the 0.7 release cut.

## Current ownership

| Design note | Current implementation ownership |
|---|---|
| `WORKFLOW-DX-AND-DSL.md` | **0.8 — Governance DX & Intelligence** |
| `GOVERNANCE-VOCABULARY-AND-FACTS.md` | **0.8** |
| `STRUCTURED-SEMANTIC-CONTRACTS.md` | **0.8** |
| `TOOL-INVOCATION-CONTRACTS.md` | **0.8** |
| `TOOL-OBLIGATION-LIFECYCLE-REFINEMENT.md` | **0.8** |
| `APPROVAL-VALIDITY-REPLACEMENT-AND-LIFECYCLE.md` | **0.8**, except defects required for existing P0 safety |
| `DOCUMENT-METADATA-CLASSIFICATION.md` | **0.8** |
| `ECOSYSTEM-GOVERNANCE-STRATEGY.md` | **Cross-release strategy**; simulation/testing/debugger implementation is primarily **0.8** |
| `GOVERNANCE-RECONSTRUCTION-AND-REPLAY.md` | **0.7:** forensic reconstruction; **0.8:** deterministic policy replay; later: sandbox execution replay |
| `ADAPTIVE-ROUTING-AND-SELECTION.md` | **0.7:** authorization/viability/basic selection separation; **0.10:** rich adaptive/FinOps optimization |
| `LEARNING-TRACES-AND-GOVERNED-DATASET-CAPTURE.md` | **0.10 — Governed Learning & Optimization** |

## Authority rule

If a design note conflicts with release ownership or priority stated by an authoritative roadmap file, the authoritative roadmap wins.

For 0.7 specifically, `../ROADMAP-0.7.0-RELEASE-CUT.md` decides what blocks the release.

A future architectural concern may constrain an earlier design without becoming an earlier implementation commitment.
