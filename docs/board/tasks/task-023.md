# TASK-023: Freeze Stable Orchestration API Surface

- Status: completed
- Priority: high
- Primary spec: [SPEC-012](../../specs/spec-012-orchestration-and-coordination.md)
- Related ADRs: [ADR-017](../../adr/adr-017.md), [ADR-001](../../adr/adr-001.md)
- Last updated: 2026-04-22

## Purpose

Reconcile the orchestration spec and public APIs so the stable promise is narrow, explicit, and defensible.

## Scope

- decide and document the stable `WorkflowContext`, `StopPolicy`, workflow builder, persistence, and lease contracts
- remove or defer speculative controls that are still only partially realized
- align `SPEC-012`, guides, and API stability docs with the actual shipped orchestration surface
- identify any behavior that must remain explicitly out of scope after stabilization

## Definition Of Done

- the stable orchestration surface is explicit in specs and docs
- no major intended breaking change remains hidden behind the current API shape
- experimental or deferred concepts are named clearly instead of implied by incomplete public types

## Notes

The goal is not a bigger API. The goal is a smaller API that can be frozen honestly.

Completed on 2026-04-22 by narrowing `SPEC-012`, the orchestration guide, and API stability docs to the shipped public surface:

- `WorkflowContext(workflowId, attributes)`
- `StopPolicy(maxStepExecutions, maxParallelBranches)`
- explicit step shapes, checkpoint/resume SPI, and optional lease SPI

Speculative controls such as typed deadlines, budget hints, terminal predicates, and max-round semantics are now named as deferred instead of being implied by the current API.
