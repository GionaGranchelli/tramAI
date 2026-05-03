# TASK-036: Implement Hermes and Codex Agent Step Types

- Status: planned
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
