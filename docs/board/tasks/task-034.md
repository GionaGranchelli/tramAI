# TASK-034: Implement Shell Step Type

- Status: done
- Priority: high
- Primary spec: [SPEC-015](../../specs/spec-015-agent-steps.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Add a first-class shell command step type so workflows can execute CLI tools,
scripts, and deployment commands as a step.

## Scope

- `shellStep(name) { ... }` DSL extension
- Configuration: command, workdir, env, timeout, retry, idempotent
- Captures stdout, stderr, and exit code
- Non-zero exit code triggers step failure with merged stderr in error context
- Timeout kills the subprocess (SIGTERM → wait → SIGKILL)
- Environment variable injection
- Working directory configuration
- Streaming output for long-running commands (optional v1 feature)
- OpenTelemetry events: duration, exit code, output size

## Exit Criteria

- [x] Shell step runs `echo "hello"` and captures output in state
- [x] Shell step with non-zero exit code fails with stderr content
- [x] Shell step timeout kills the subprocess correctly
- [x] Environment variables are passed to the subprocess
- [x] Working directory is respected
- [x] OpenTelemetry span captures command, exit code, and duration

## Implementation summary

- **Commits**: `c5615fc` (initial), `a9693eb` (hardening)
- **New file**: `ShellStep.kt` — `ShellCommand`, `ShellResult`, `ShellStepConfig`, `ShellWorkflowStep`, `WorkflowShellException`
- **Modified**: `Workflow.kt` — DSL `shellStep()`, dispatch, canonical rendering
- **Tests**: `WorkflowShellStepTest.kt` — 14 tests including happy path, stderr, non-zero exit, timeout, truncation, workdir, env vars, redaction, cancellation cleanup, command allow/deny
- **Security**: command allowlist/denylist, redacted observer events, cancellation-safe process cleanup, command name sanitized in exceptions
- **Total tests**: 74 (orchestration module), 0 failures
