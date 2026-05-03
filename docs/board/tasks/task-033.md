# TASK-033: Implement HTTP Step Type

- Status: planned
- Priority: high
- Primary spec: [SPEC-015](../../specs/spec-015-agent-steps.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Add a first-class HTTP step type to the workflow DSL so workflows can call
external APIs as a step.

## Scope

- `httpStep(name) { ... }` DSL extension on `AbstractWorkflowBuilder`
- Configuration: url, method, headers, body, timeout, retry policy
- Support GET, POST, PUT, PATCH, DELETE
- Response mapped into workflow state via `merge` function
- Timeout kills the underlying HTTP connection
- Retry with configurable backoff (fixed, exponential, jitter)
- Non-2xx responses trigger step failure with response body as error context
- OpenTelemetry events: duration, status code, response size, retry count

## Exit Criteria

- [ ] HTTP step makes a GET request and merges response into state
- [ ] HTTP step with POST body sends the correct content type and body
- [ ] Timeout kills the connection and fails the step
- [ ] Retry fires on 5xx responses and succeeds on 3rd attempt
- [ ] Non-idempotent step with retry fires a warning (idempotent = false)
- [ ] OpenTelemetry span captures method, URL, status, and duration
