# TASK-021: Tighten Parallel Execution Bounds

- Status: completed
- Priority: high
- Primary spec: [SPEC-012](../../specs/spec-012-orchestration-and-coordination.md)
- Related ADRs: [ADR-017](../../adr/adr-017.md)
- Last updated: 2026-04-22

## Purpose

Make `parallelStep` and `StopPolicy` semantics precise enough to freeze publicly.

## Scope

- enforce `maxParallelBranches` without requiring full materialization of large or lazy iterables
- define the exact relationship between top-level step execution limits and parallel branch accounting
- decide whether the current `StopPolicy` shape is the stable surface or whether additional controls must land first
- document the resulting bounded-execution model clearly

## Definition Of Done

- branch-width limits are enforced early and deterministically
- step-budget accounting across top-level steps and parallel branches is documented and tested
- lazy or oversized branch sources do not bypass the intended execution bounds
- `StopPolicy` behavior is explicit enough to include in the stable API contract

## Notes

Stable orchestration should promise bounded behavior with no surprising pre-limit work.
Implemented by enforcing `maxParallelBranches` through bounded iterator inspection rather than full materialization, documenting the exact `StopPolicy` accounting rule for top-level steps and parallel branches, and adding tests for lazy iterable overflow and parallel step budget accounting.
