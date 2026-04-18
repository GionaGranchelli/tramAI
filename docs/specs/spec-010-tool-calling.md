# SPEC-010: Tool Calling

- Status: proposed
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: Phase 4 / post-0.1.0
- Related ADRs: [ADR-014](../adr/adr-014.md), [ADR-001](../adr/adr-001.md), [ADR-009](../adr/adr-009.md)
- Related docs: [Roadmap Summary](../roadmap.md), [Current Limitations](../reference/limitations.md)

## Problem

Aurora currently treats model execution as pure completion. That keeps the abstraction clean, but it prevents a growing class of workflows where the model must request external data or perform deterministic application actions before producing a final answer.

## Scope

- a portable Aurora tool abstraction
- explicit tool registration and execution
- argument-schema and result-shape handling
- engine orchestration for tool request, tool execution, and final response completion
- provider adaptation for native and emulated tool-calling paths

## Non-Goals

- agent frameworks or autonomous planners
- long-lived memory systems
- built-in filesystem, shell, or network tools as mandatory product features
- provider-specific tool APIs leaking directly into consumer code
- streaming-plus-tool-calling in the first milestone

## Functional Requirements

- Aurora must support explicit tool registration as part of runtime configuration rather than hidden global state.
- The engine must remain the owner of tool-calling orchestration, retry boundaries, and terminal failure behavior.
- Tool execution must be user-supplied and explicit rather than magical provider-side behavior.
- Tool arguments must be validated against an Aurora-owned schema or contract before execution where practical.
- Provider-specific native tool calling may be used as an optimization, but the consumer-facing contract must remain portable.
- Tool results must flow back into the model through the same orchestration layer rather than forcing applications to hand-roll multi-step loops.
- Tool failures must surface through typed Aurora errors with enough context for debugging.

## Quality Requirements

- The portable tool contract must remain small and testable.
- The first tool-calling milestone must not collapse into a general agent framework.
- Provider modules must not define the primary user-facing tool API.
- The design must keep room for future observability and testing support around tool execution.

## Design Notes

- Tool calling should extend Aurora's existing interface-method mental model rather than replace it with chain-style orchestration APIs.
- Native provider tool features should be treated as implementation details behind a stable engine-owned abstraction.
- The first milestone should focus on correctness, validation, and orchestration boundaries rather than a huge built-in tool catalog.

## Acceptance Criteria

- A configured Aurora operation can invoke at least one registered tool and continue to a final model response.
- Tool arguments are validated or rejected before execution through a deterministic Aurora path.
- Tool-calling behavior is covered by automated tests for successful execution, invalid arguments, and tool failures.
- The public API does not expose provider-specific tool payload classes as the main integration surface.

## Risks and Follow-Ups

- Some providers support tool calling more naturally than others, so portability may require emulation paths.
- Memory and retrieval design should build on the tool-calling foundation rather than predate it.
