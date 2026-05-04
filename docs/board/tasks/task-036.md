# TASK-036: Implement Hermes and Codex Agent Step Types

- Status: done
- Priority: medium
- Primary spec: [SPEC-015](../../specs/spec-015-agent-steps.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Add step types that delegate work to Hermes Agent CLI and Codex CLI, enabling
workflows that orchestrate external AI agents.

## Scope

- `hermesStep(name) { ... }` DSL extension
  - Configuration: prompt, model, timeout
  - Calls `hermes chat -q "<prompt>"` via CLI
  - Captures full CLI output into workflow state
- `codexStep(name) { ... }` DSL extension
  - Configuration: prompt, workdir, timeout
  - Calls `codex "<prompt>"` via CLI
  - Captures full CLI output into workflow state
- Both steps support: timeout, retry, non-idempotent marker
- Agent CLI path configurable (for non-standard installs)
- OpenTelemetry events: agent type, prompt length, response length, duration

## Exit Criteria

- [ ] Hermes step sends a prompt and captures the response
- [ ] Codex step sends a prompt and captures the response
- [ ] Timeout kills the CLI process and fails the step
- [ ] Custom CLI path configuration works
- [ ] OpenTelemetry span captures agent type, prompt size, and duration

## Implementation Summary

**Completed**: 2026-05-04

### Files
- `AgentCliSupport.kt` (new, 275 lines) — shared subprocess helper with process-tree cleanup, stderr capture, timeout handling (SIGTERM → SIGKILL grace period)
- `HermesStep.kt` (new, 93 lines) — `HermesStepConfig`, `HermesWorkflowStep<S>`, invokes `hermes chat -q "<prompt>" --model <model>`
- `CodexStep.kt` (new, 97 lines) — `CodexStepConfig`, `CodexWorkflowStep<S>`, invokes `codex exec -- <prompt>` (double-dash safe)
- `Workflow.kt` (+64 lines) — DSL `hermesStep`/`codexStep`, dispatch, canonical rendering
- `WorkflowAgentStepTest.kt` (new, 454 lines) — 10 tests covering: prompt forwarding, response capture, custom CLI path, workdir, timeout, truncation, dash-prefixed Codex prompts, stderr-in-error, cancellation cleanup, descendant cleanup

### Review Cycle
- Copilot implemented (1.1M tokens, 5m 27s)
- Codex reviewed: FAIL (4 findings: process tree cleanup, Codex arg injection, stderr discarded, test gaps)
- Copilot fixed all 4 findings (+289/-40 lines, 4m 55s)
- Full suite: 60 tasks, BUILD SUCCESSFUL
