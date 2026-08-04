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

| Code | Value | Default model message |
|------|-------|-----------------------|
| `INVALID_INPUT` | `tool.input.invalid` | Invalid tool input |
| `EXECUTION_FAILED` | `tool.execution.failed` | Tool execution failed |
| `RETRY_EXHAUSTED` | `tool.execution.retry_exhausted` | Tool execution failed |

Codes are domain-specific (`ToolFailureCode` covers tools only). They classify diagnostic events and select fixed model-visible defaults. They do not drive retry or policy: retry remains determined by `ToolResult.TransientFailure` and tool idempotency, and policy does not consume these codes. Caller-visible failure mapping is pending a later Epic 1.2 slice. Provider, workflow, approval, persistence, and policy failures get their own code families later; they can implement a shared marker interface without a repository-wide "god enum".

## Tool results

`ToolResult` retains exactly its four stable variants, so existing exhaustive Kotlin `when` expressions remain source-compatible:

- `ToolResult.Success(value, contentParts?)`;
- `ToolResult.InvalidInput(message)`;
- `ToolResult.PermanentFailure(message)`;
- `ToolResult.TransientFailure(cause)`.

The string-bearing constructors remain public and surface their text verbatim to the model. Application tools should use `ToolResult.safeInvalidInput(modelMessage?)` and `ToolResult.safePermanentFailure(modelMessage?)` when they want a validated trusted message or the fixed default. Built-in engine and standalone paths construct only fixed-default or explicitly trusted text and never derive it from `Throwable.message` or `cause.message`.

The short-lived round-1 `SafeInvalidInput` and `SafePermanentFailure` variants were removed before release because adding sealed subclasses breaks source compatibility for exhaustive `when` expressions. Custom tools written against those review-only variants must migrate to the safe factories or, when deliberately accepting verbatim text, the stable plain constructors.

## Model-visible messages

`ModelVisibleToolMessage` carries validated application-supplied text for the safe factories and `ToolInvalidInputException`. It is a regular, non-data class with a private constructor, so it exposes no generated `copy` or destructuring path around validation. Validation runs in the class initializer, so **every** JVM-visible construction path — including the synthetic `(String, DefaultConstructorMarker)` constructor Kotlin generates for the companion — rejects unsafe text; `trusted(value)` is the ergonomic entry point and enforces mechanical safety only:

- non-blank;
- at most 512 characters;
- no control characters, no U+2028/U+2029 line/paragraph separators, no Unicode FORMAT characters.

Validation examines Unicode code points, including supplementary characters. It rejects ISO control characters and common multiline/log-forging input. It does **not** detect prompt injection, secrets, Unicode spoofing, or unsafe semantic content — the `trusted` name makes the responsibility explicit: only deliberately reviewed text belongs there. The factory is `@JvmStatic` and takes an ordinary `String`, so Java callers have the same entry point as Kotlin callers.

`ToolInvalidInputException` distinguishes diagnostic text from model-visible text:

- `ToolInvalidInputException(message)` — `message` is diagnostic-only and is never forwarded to the model; the public `(String)` constructor is preserved;
- `ToolInvalidInputException.withSafeModelMessage(message, modelMessage)` — `message` stays diagnostic, `modelMessage` is the deliberately trusted validation feedback.

## Diagnostic observer

Original causes remain available — but only through an explicitly configured, fail-open `ToolFailureDiagnosticObserver`:

- the default is a no-op;
- the observer receives the original `Throwable` via `ToolFailureDiagnosticEvent(toolName, code, attempt, retryClassified, failure)`;
- `retryClassified` is true when the failure is classified as retryable (an idempotent tool); false for invalid-input and terminal-exhaustion events;
- `attempt` is the zero-based retry-loop index (the standalone adapter reports the engine's `ToolExecutionContext.attemptNumber`);
- observer data is never automatically forwarded to events, logs, audit, metrics, or model messages;
- observer exceptions are swallowed on ordinary failure paths; an observer that throws `CancellationException` while the enclosing coroutine is still active is treated as an ordinary observer failure and swallowed — only genuine coroutine cancellation propagates;
- `CancellationException` is never delivered as an ordinary tool-failure diagnostic.

## Wiring

- `tramai-engine`: `TramaiEngine(toolFailureDiagnosticObserver = ...)` records one `EXECUTION_FAILED` diagnostic per failed attempt — whether the tool threw or directly returned `ToolResult.TransientFailure` — and, for an idempotent tool, one terminal `RETRY_EXHAUSTED` event after the final permitted attempt. A non-idempotent tool's transient failure yields a single `EXECUTION_FAILED` event with `retryClassified = false` and the fixed `EXECUTION_FAILED` model message; it is never labelled `RETRY_EXHAUSTED` because no retry was attempted.
- `tramai-standalone`: `Tramai.Builder.toolFailureDiagnosticObserver(...)` configures both the engine and the `TramaiTool` adapter. Tools are resolved at `build()` against a frozen observer snapshot, so mutating the builder after `build()` can never redirect diagnostics of the built runtime.

## Scope and non-claims (PR #219)

PR #219 starts Epic 1.2; it does not complete it. It does not: sanitise provider HTTP response bodies, change structured-output exception fields, migrate shell/HTTP/MCP/Codex failures, add automatic secret detection, guarantee application-supplied trusted messages are secret-free, introduce a universal failure-code taxonomy, or change tool retry/idempotency semantics.
