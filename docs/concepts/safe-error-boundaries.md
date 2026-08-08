# Safe Error Boundaries

> **Status:** implemented for tool execution (PR #219) and provider HTTP/transport failures (PR #222). Workflow steps, persistence, MCP, shell, and public structured-output exceptions remain future slices of Epic 1.2.

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

Codes are domain-specific. `ToolFailureCode` covers tools, while `ProviderFailureCode` covers provider HTTP rejection, timeout, connection, transport, and unexpected failures. They classify diagnostic events and select fixed safe defaults; retry remains represented by the relevant domain contract rather than inferred from message text. Workflow, approval, persistence, and policy failures can gain their own code families without introducing a repository-wide "god enum".

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

## Provider failures

Provider adapters use shared safe boundaries for HTTP and transport failures:

- non-2xx responses produce the fixed public message `Provider request failed with HTTP {status}`;
- transport categories use fixed messages such as `Provider request timed out`, `Provider connection failed`, and `Provider transport failed`;
- public `ProviderException` instances retain neither response bodies nor original causes, so ordinary stack traces and OpenTelemetry exception events cannot expose provider-controlled detail;
- `ProviderFailureDiagnosticObserver` is the only channel for the original transport throwable or an HTTP body preview. The default observer is a no-op, and observer failures are fail-open;
- HTTP error bodies are acquired as `InputStream` values and read through one byte-bounded path. At most 8 KiB plus one sentinel byte is consumed, only the retained 8 KiB is decoded as UTF-8, and truncation is reported. A multibyte code point split at the boundary may be replaced or omitted from the diagnostic suffix;
- streaming adapters protect request construction, serialization, `HttpClient.send()`, bounded error-body acquisition, and SSE parsing. Transport or body-read failures therefore become one safe terminal error chunk instead of escaping the flow;
- provider debug logs contain only the failure code, HTTP status, and retryability. Provider ids and aliases, bodies, headers, credentials, and cause messages are excluded;
- diagnostic events use stable lowercase registry ids. A caller-configured display name can be carried separately as diagnostic-only `providerAlias`, but never enters public messages or logs;
- `ProviderException.failureCode` is a class-body property with an internal setter. The exact 0.5.0 five-argument constructor and Kotlin default-argument constructor descriptors remain intact and are exercised by a fixture compiled against the 0.5.0 jar;
- `safeProviderFailure(message, code, ...)` is the explicit trusted-message escape hatch. Its message is emitted verbatim, so only caller-controlled text belongs there. Built-in adapter parse errors use it for informative fixed text;
- arbitrary caller-constructed `ProviderException` values are not trusted. The transport boundary emits the original only to diagnostics and returns a fresh cause-free exception preserving status/retry metadata. Only built-in safe factories pass through unchanged.

Retry behavior remains structural: `statusCode`, `retryable`, and `retryAfterMillis` survive HTTP mapping, while transport categories select their established retryability. Cancellation input is rethrown before classification. Observer-thrown cancellation is swallowed only while the current coroutine remains active; cancellation of the enclosing job remains primary.

## Scope and non-claims (PRs #219 and #222)

These slices do not complete Epic 1.2. Built-in HTTP, shell, MCP, Codex, and Hermes workflow steps now use the same safe-boundary shape; persistence and structured-output exception fields remain outside this slice. The work does not add automatic secret detection, guarantee application-supplied trusted messages are secret-free, introduce a universal failure-code taxonomy, or change tool retry/idempotency semantics.
