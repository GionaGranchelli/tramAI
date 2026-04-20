# TASK-011: Reconcile Delivery Docs and Freeze 0.1.0 MVP Scope

- Status: done
- Priority: high
- Primary spec: [SPEC-008](../../specs/spec-008-documentation-publishing.md)
- Related ADRs: [ADR-005](../../adr/adr-005.md), [ADR-010](../../adr/adr-010.md), [ADR-012](../../adr/adr-012.md)
- Last updated: 2026-04-18

## Rationale

Tramai has moved beyond its initial documentation plan. The board and spec set must reflect implementation reality before the project can credibly freeze a first public MVP scope.

## Scope

- reconcile task and spec statuses against the current repository
- define and publish the frozen `0.1.0` scope
- record an explicit MVP release checklist
- distinguish shipped Phase 3 work from follow-up hardening
- commit Phase 4 streaming and tool-calling design docs without scheduling their execution yet

## Definition Of Done

- the execution board reflects what is done, in progress, and merely designed
- the specs index reflects implemented versus future work accurately
- a `0.1.0` scope and checklist doc exists and is linked from the docs set
- streaming and tool-calling have committed specs and ADRs, but are clearly outside the frozen `0.1.0` scope

## Notes

This task is about delivery clarity and scope control, not about implementing streaming or tool calling yet.
The board, specs, roadmap, and release-scope docs now reflect the implemented repository state and frozen `0.1.0` scope.
