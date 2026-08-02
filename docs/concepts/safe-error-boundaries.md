# Safe Error Boundaries

> **Status:** implemented for the tool-execution slice (PR #219). Provider HTTP failures, workflow steps, persistence, MCP, shell, and public structured-output exceptions remain future slices of Epic 1.2.

## The rule

**Internal detail is denied by default; model-visible detail must be explicitly constructed.**

A caught `Throwable.message` must never automatically become:

- a model-visible tool result;
- a public exception message;
- an engine event attribute;
- audit or telemetry metadata.

Redaction-based sanitisation is not a reliable security boundary: no function can identify every token, SQL fragment, path, prompt, customer identifier, or future secret format. The safe design is to never derive external text from internal exceptions in the first place.

## Failure classification

Tool failures are classified with stable machine-readable codes — never by parsing human-readable text:

| Code | Value | Default model message | Default public message |
|------|-------|-----------------------|------------------------|
| `INVALID_INPUT` | `tool.input.invalid` | Invalid tool input | Tool input was rejected |
| `EXECUTION_FAILED` | `tool.execution.failed` | Tool execution failed | Tool execution failed |
| `RETRY_EXHAUSTED` | `tool.execution.retry_exhausted` | Tool execution failed | Tool execution failed after retry attempts |

Codes are domain-specific (`ToolFailureCode` covers tools only). Provider, workflow, approval, persistence, and policy failures get their own code families later; they can implement a shared marker interface without a repository-wide "god enum".

## Model-visible messages

`ModelVisibleToolMessage` is the only type allowed to carry text into the model conversation. Its `trusted(value)` factory enforces mechanical safety only:

- non-blank;
- at most 512 characters;
- no control characters (including newlines, preventing log/prompt injection).

It cannot prove the text contains no secret — the factory name makes the responsibility explicit: only deliberately reviewed text belongs there. `ToolResult.InvalidInput` and `ToolResult.PermanentFailure` accept an optional `modelMessage`; when absent, the fixed default for the code is resolved internally.

`ToolInvalidInputException` distinguishes diagnostic text from model-visible text:

- `ToolInvalidInputException(message)` — `message` is diagnostic-only and is never forwarded to the model;
- `ToolInvalidInputException.withSafeModelMessage(message, modelMessage)` — `message` stays diagnostic, `modelMessage` is the deliberately trusted validation feedback.

## Diagnostic observer

Original causes remain available — but only through an explicitly configured, fail-open `ToolFailureDiagnosticObserver`:

- the default is a no-op;
- the observer receives the original `Throwable` via `ToolFailureDiagnosticEvent(toolName, code, attempt, retryable, failure)`;
- observer data is never automatically forwarded to events, logs, audit, metrics, or model messages;
- observer exceptions are swallowed on ordinary failure paths; when a primary exception is being thrown the observer failure may be attached as suppressed but never replaces it;
- `CancellationException` is never delivered as an ordinary tool-failure diagnostic.

## Wiring

- `tramai-engine`: `TramaiEngine(toolFailureDiagnosticObserver = ...)` records one diagnostic per failed attempt and a terminal `RETRY_EXHAUSTED` event on retry exhaustion.
- `tramai-standalone`: `Tramai.Builder.toolFailureDiagnosticObserver(...)` configures both the engine and the `TramaiTool` adapter. The adapter resolves the observer lazily at failure time, so tools registered before the observer still report diagnostics.

## Scope and non-claims (PR #219)

PR #219 starts Epic 1.2; it does not complete it. It does not: sanitise provider HTTP response bodies, change structured-output exception fields, migrate shell/HTTP/MCP/Codex failures, add automatic secret detection, guarantee application-supplied trusted messages are secret-free, introduce a universal failure-code taxonomy, or change tool retry/idempotency semantics.
