# TASK-018: Promote Orchestration From Experimental To Stable

- Status: completed
- Priority: high
- Primary spec: [SPEC-012](../../specs/spec-012-orchestration-and-coordination.md)
- Related ADRs: [ADR-017](../../adr/adr-017.md), [ADR-001](../../adr/adr-001.md)
- Last updated: 2026-04-22

## Purpose

Promote `tramai-orchestration` from a shipped experimental module to a stable, marketable core pillar of Tramai.

## Scope

- freeze the stable orchestration surface intentionally rather than by drift
- harden workflow resume compatibility and persistence semantics
- tighten workflow-level observability correctness under concurrent usage
- make bounded parallel execution semantics explicit and defensible
- expand the orchestration stability test matrix
- remove the experimental classification only after the stabilization work is complete

## Exit Criteria

- `TASK-019` through `TASK-024` are complete or explicitly deferred
- `SPEC-012` reflects the actual stable public orchestration contract
- orchestration can be described as stable in code and docs without qualification gaps
- the module is ready to be positioned as a core Tramai capability rather than a prototype add-on

## Notes

This umbrella stabilization task completed on 2026-04-22 once `TASK-019` through `TASK-024` closed the remaining promotion blockers and aligned code, tests, and docs around stable orchestration.
