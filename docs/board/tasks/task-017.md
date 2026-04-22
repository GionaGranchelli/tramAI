# TASK-017: Design Typed Orchestration and Coordination

- Status: done
- Priority: medium
- Primary spec: [SPEC-012](../../specs/spec-012-orchestration-and-coordination.md)
- Related ADRs: [ADR-017](../../adr/adr-017.md)
- Last updated: 2026-04-22

## Purpose

Define an orchestration module that coordinates multiple typed AI services without turning Tramai into a generic agent framework.

## Proposed Scope

- optional `tramai-orchestration` module
- typed workflow state and typed step handoffs
- sequential, conditional, and bounded parallel composition
- explicit workflow stop policies
- integration with engine-level retries, budgets, caching, and observability

## Canonical Proof Cases

- `plan -> execute[] -> review -> finalize`
- `route -> specialist -> validate`
- `generateCandidates[] -> judge -> return`

## Exit Criteria

- [x] `SPEC-012` is reviewed and accepted or intentionally revised.
- [x] The engine/orchestration boundary is preserved and documented.
- [x] At least one prototype API sketch proves typed workflow composition without hidden memory.
- [x] The design remains aligned with Tramai's typed backend-first identity.

## Notes

This design task is complete.
The follow-on stabilization and promotion work is now tracked under `TASK-018` through `TASK-024`.
