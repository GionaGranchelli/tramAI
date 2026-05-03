# SPEC-015: Agent Step Types (HTTP, Shell, MCP, Hermes, Codex)

- Status: proposed
- Owner: maintainer
- Last updated: 2026-05-03
- Related roadmap milestone: Phase 8 — Agent Steps
- Related ADRs:
- Related docs: [Orchestrator Vision](../architecture/orchestrator-vision.md)

## Problem

TramAI workflows currently execute steps within the JVM process. Agent step types
make external interactions (HTTP, shell, MCP, agent CLIs) first-class citizens
in the workflow DSL, with timeout, retry, observability, and checkpointing.

## Scope

- HTTP step: method, URL, headers, body, timeout, retry, response handling
- Shell step: argv mode (default), shell mode (explicit opt-in)
- MCP step: typed decode, lambda-based arguments, tool allowlist
- Hermes step: prompt as lambda, model selection, output capture
- Codex step: prompt as lambda, workdir, output capture
- All steps share: timeout, retry policy, output limits, redaction, observer events
- Every step type declares a replay policy for distributed safety

## Non-Goals

- Replacing aiStep — use aiStep for TramAI-owned LLM calls, agent steps for external systems
- SSH/container orchestration — shell steps run locally

## Functional Requirements

### HTTP Step

```kotlin
httpStep("submit-invoice") {
    method = HttpMethod.POST
    url { state -> "https://api.example.com/invoices/${state.id}" }
    header("Authorization") { state -> "Bearer ${state.token}" }
    jsonBody { state -> state.invoiceData }
    timeout = 30.seconds
    retry = RetryPolicy.none()            // explicit opt-in for retry
    idempotencyKey { state -> state.invoiceId.toString() }  // for POST idempotency
    onResponse { state, response -> state.copy(result = response.bodyAs<Result>()) }
}
```

- Lambdas for runtime state: `url { state -> ... }`, `header { state -> ... }`
- Build-time config: `method`, `timeout`, `retry`
- `RetryPolicy.none()` is the default — explicit opt-in required
- Idempotency key support for POST/PATCH/DELETE
- Response classification: 2xx = success, 4xx = invalid input, 5xx = transient failure
- Body size limits, redirect policy (follow by default, max 5 redirects)
- Auth/secret injection via header lambdas (values never logged)
- SSRF protection: network allowlist configured at server level
- Timeout: kills the underlying connection, not just stops waiting

### Shell Step

```kotlin
// DEFAULT: argv mode (safe, no shell interpretation)
shellStep("build") {
    command("npm", "run", "build")
    workdir = Path.of("/home/deploy/app")
    env("NODE_ENV") { state -> "production" }
    timeout = 5.minutes
    retry = RetryPolicy.none()
    onResult { state, result -> state.copy(output = result.stdout.truncatedText) }
}

// EXPLICIT shell mode (opt-in, shell expansion, pipes, redirects)
shellStep("deploy") {
    shell {
        interpreter = ShellInterpreter.BASH
        script = "set -euo pipefail\nhelm upgrade --install myapp ./chart"
        variable("CHART") { state -> state.chartPath }
    }
    allowShellExpansion = true
    timeout = 5.minutes
    retry = RetryPolicy.none()
}
```

- **argv is default** — no shell involved, args passed directly to ProcessBuilder
- **Shell mode requires explicit opt-in** with `shell { ... }` block
- Shell mode emits a warning observer event unless explicitly suppressed
- State interpolation into shell scripts uses named escaped variables only
- Working directory: explicit, restricted to allowed base dirs when platform policy enabled
- Environment variables: allowlist enforced by platform policy
- Secret-like values redacted in observer events, logs, dashboard
- Stdout/stderr capped at configurable limit (default 1MB), truncated with byte footer
- Exit code: 0 = success, non-zero = failure with stderr in error context
- Timeout: close stdin → SIGTERM → terminationGracePeriod → SIGKILL
- Process group cleanup: all child processes terminated on timeout/cancel
- Replay policy default: NON_REPLAYABLE

### MCP Step

```kotlin
mcpStep<A11yReport>("run-audit") {
    server = McpServerRef.named("a11y")
    tool = "audit_url"
    argument("url") { state -> state.deployedUrl }
    timeout = 2.minutes
    retry = RetryPolicy.none()
    decodeWith(A11yReport.serializer())          // typed decode at build time
    onResult { state, report -> state.copy(a11yReport = report) }
}
```

- Server reference: named MCP server from configuration
- Tool: must be in the MCP server's tool allowlist
- Arguments as lambdas for runtime state
- `decodeWith(serializer)` establishes result type at build time
- Supports Kotlin Serialization, Jackson, and caller-provided decoder
- Timeout: propagated to the MCP client transport
- Transport failure: reconnect once, then fail
- Cancellation: send MCP cancellation notification for active request
- Replay policy default: NON_REPLAYABLE

### Hermes Step

```kotlin
hermesStep("review-ui") {
    prompt { state -> "Audit the UI at ${state.deployedUrl} for usability issues" }
    model = "claude-sonnet-4"
    timeout = 3.minutes
    retry = RetryPolicy.none()
    onResult { state, response -> state.copy(review = response) }
}
```

- Prompt is a lambda — evaluated at execution time, not build time
- Model: forwarded to Hermes CLI
- Output: captured as string in workflow state
- Token/output capped at configurable limit
- Structured output contract: if Hermes returns JSON, parse via decoder
- Redaction: prompt content redacted in logs/dashboard by default
- Transcript retention policy: configurable (default: not retained)
- Replay policy default: NON_REPLAYABLE
- Hermes CLI path configurable

### Codex Step

Same pattern as Hermes step, targeting `codex` CLI.

### Cross-Cutting: Replay Policies

Every step type must declare a replay policy:

| Policy | Safe to re-run? | Default for |
|--------|-----------------|-------------|
| `PURE` | Yes (no external side effects) | localStep |
| `IDEMPOTENT` | Yes (step semantics guarantee it) | GET, PUT, DELETE |
| `EXTERNALLY_IDEMPOTENT` | Yes (with idempotency key) | HTTP POST with key |
| `NON_REPLAYABLE` | No — fail on unknown attempt | Shell, Hermes, Codex |

### Cross-Cutting: Security

- Shell: argv default, shell mode opt-in, env allowlist, workdir policy
- HTTP: SSRF allowlist, body limits, redirect limits, secret redaction
- MCP: tool allowlist, schema validation before call
- All: output caps (1MB default), redaction of secrets in events/logs/dashboard
- All: input caps (request body, prompt length)
- All: audit events for every step execution

### Cross-Cutting: Observability

- Every step type emits observer events: `onStepStarted`, `onStepCompleted`, `onStepFailed`
- When tramai-observability is present, events bridge to OTel spans
- Event payloads include: step name, type, duration, status, output size
- Secret fields are never included in event payloads

## Acceptance Criteria

- [ ] HTTP step POST sends correct body/headers and merges response into state
- [ ] HTTP step with RetryPolicy.none() does NOT retry on 5xx
- [ ] HTTP step with idempotency key includes it in the request header
- [ ] Shell step in argv mode runs without invoking a shell
- [ ] Shell step in shell mode requires explicit opt-in and emits warning event
- [ ] Shell step timeout kills the process group
- [ ] MCP step calls a tool, decodes the result with the provided serializer
- [ ] Hermes step sends a prompt lambda (not string) and captures output
- [ ] Output > 1MB is truncated with a footer recording the truncation
- [ ] Secrets are redacted in observer events and logs
- [ ] Every step type emits observer events without OTel on classpath
- [ ] Every step declares a replay policy (default NON_REPLAYABLE for side-effecting)
