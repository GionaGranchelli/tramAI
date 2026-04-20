# TASK-015: Implement Tool Calling

- Status: done
- Priority: high
- Primary spec: [SPEC-010](../../specs/spec-010-tool-calling.md)
- Related ADRs: [ADR-014](../../adr/adr-014.md)
- Last updated: 2026-04-19

## Purpose

Implement engine-owned, provider-portable tool calling orchestration.

## Implementation Summary

- Defined `TramaiTool` and `ResolvedTool` contracts in `tramai-core`.
- Built the model-tool-result loop in `TramaiEngine` with support for multi-tool execution.
- Extended `tramai-structured` to generate JSON schemas for tool inputs from Kotlin types.
- Implemented `AiToolScanner` in `tramai-spring` for automatic tool discovery.
- Updated `OpenAiProvider` to support tool definitions and tool calls.
- Added `MockTool` and fluent assertions to `tramai-testing`.

## Exit Criteria

- [x] Tool calling loop implemented in `TramaiEngine`.
- [x] JSON Schema generation for tool inputs works via Jackson.
- [x] Spring adapter automatically registers `@AiTool` beans.
- [x] `OpenAiProvider` correctly handles tool execution messages.
- [x] Automated tests verify successful tool execution, invalid input rejection, and idempotent transient retry handling.
