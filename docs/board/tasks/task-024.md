# TASK-024: Promote Orchestration In Code And Public Docs

- Status: completed
- Priority: medium
- Primary spec: [SPEC-012](../../specs/spec-012-orchestration-and-coordination.md)
- Related ADRs: [ADR-017](../../adr/adr-017.md)
- Last updated: 2026-04-22

## Purpose

Remove the experimental classification and present orchestration as a stable Tramai capability once the stabilization work is complete.

## Scope

- remove `@ExperimentalTramAIOrchestration` from the stable surface
- update guides, reference docs, roadmap, board, and API stability docs to reflect stable status
- add or refresh public-facing examples that show orchestration as a practical backend workflow tool
- ensure release-facing docs describe orchestration consistently with its new status

## Definition Of Done

- orchestration is no longer labeled experimental in code or docs
- public documentation positions orchestration consistently with the stabilized API contract
- at least one clear example or guide demonstrates the stable orchestration story end to end

## Notes

This task should close last. It is the promotion step, not the stabilization work itself.

Completed on 2026-04-22 by:

- removing `@ExperimentalTramAIOrchestration` from the runtime and observability surfaces
- promoting orchestration to stable in the guides, API stability docs, roadmap, and module overview
- updating the main orchestration guide to show a stable end-to-end workflow pattern
