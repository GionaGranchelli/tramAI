# SPEC-015: Agent Step Types (HTTP, Shell, MCP, Hermes, Codex)

- Status: proposed
- Owner: maintainer
- Last updated: 2026-05-12
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
- MCP step: lambda-based arguments, raw `McpToolResult`, tool allowlist
- Hermes step: prompt as lambda, model selection, raw string output capture
- Codex step: prompt as lambda, workdir, raw string output capture
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
mcpStep(
    name = "run-audit",
    definition = McpToolCallDefinition(
        serverCommand = listOf("a11y-server"),
        toolName = "audit_url",
        argumentKeys = setOf("url"),
    ),
    toolCall = { state, _ ->
        McpToolCall(
            serverCommand = listOf("a11y-server"),
            toolName = "audit_url",
            arguments = mapOf("url" to state.deployedUrl),
        )
    },
    merge = { state, result, _ -> state.copy(rawAuditResult = result) },
)
```

- Server command, environment, tool name, and argument keys are declared explicitly via `McpToolCallDefinition`
- Runtime tool arguments are provided by the `toolCall` lambda
- Result is currently delivered to workflow state as raw `McpToolResult`
- `McpToolResult` exposes `content`, `structuredContent`, and `isError` as strings/flags only
- Timeout: propagated to the MCP client transport
- Transport failure: reconnect once, then fail
- Cancellation: sends transport shutdown by closing the active session/subprocess
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
- Output: captured as raw string in workflow state
- Token/output capped at configurable limit
- Redaction: prompt content redacted in logs/dashboard by default
- Transcript retention policy: configurable (default: not retained)
- Replay policy default: NON_REPLAYABLE
- Hermes CLI path configurable

### Codex Step

Same pattern as Hermes step, targeting `codex` CLI. Output is currently exposed as a raw string.

## Phase 3 — Typed Overloads

Phase 3 adds additive typed overloads without changing the existing raw-result APIs:

```kotlin
hermesStep(
    name = "review-ui",
    prompt = { state, _ -> "Audit ${state.deployedUrl}" },
    decode = { raw -> json.decodeFromString<UiReview>(raw) },
    merge = { state, review, _ -> state.copy(review = review) },
)

codexStep(
    name = "summarize",
    prompt = { state, _ -> state.prompt },
    decode = { raw -> raw.trim().toInt() },
    merge = { state, count, _ -> state.copy(issueCount = count) },
)

mcpStep(
    name = "run-audit",
    definition = definition,
    toolCall = { state, _ -> buildAuditCall(state) },
    decode = { result -> decodeAudit(result) },
    merge = { state, audit, _ -> state.copy(audit = audit) },
)
```

- Existing overloads remain unchanged and continue to expose raw `String`/`McpToolResult`
- Typed overloads are implemented in `tramai-orchestration` as thin wrappers around the existing steps
- The decoder boundary is caller-provided: orchestration does not choose or depend on a JSON library
- Decode failures surface through the existing step-specific exceptions (`WorkflowHermesException`, `WorkflowCodexException`, `WorkflowMcpException`)
- This preserves the module boundary: orchestration owns step execution, callers own structured decoding policy

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
- [ ] MCP step calls a tool and returns raw `McpToolResult`
- [ ] Hermes step sends a prompt lambda (not string) and captures raw string output
- [ ] Typed Hermes/Codex/MCP overloads apply caller-provided decoders and surface decode failures through step-specific exceptions
- [ ] Output > 1MB is truncated with a footer recording the truncation
- [ ] Secrets are redacted in observer events and logs
- [ ] Every step type emits observer events without OTel on classpath
- [ ] Every step declares a replay policy (default NON_REPLAYABLE for side-effecting)
