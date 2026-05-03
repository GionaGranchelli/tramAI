# TASK-034: Implement Shell Step Type

- Status: planned
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

- [ ] Shell step runs `echo "hello"` and captures output in state
- [ ] Shell step with non-zero exit code fails with stderr content
- [ ] Shell step timeout kills the subprocess correctly
- [ ] Environment variables are passed to the subprocess
- [ ] Working directory is respected
- [ ] OpenTelemetry span captures command, exit code, and duration
