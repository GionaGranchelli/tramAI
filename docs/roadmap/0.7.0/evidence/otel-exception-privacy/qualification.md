# 0.7.0 — Observability exception privacy follow-up (issue #551)

**Scope:** remove exception-message and stack-trace export from the workflow and worker OpenTelemetry
observers. This is the release blocker recorded by Epic 0.7.5h and the same defect class 0.7.5g fixed on
the operation observer.

## 1. What was leaking

`Span.recordException(Throwable)` exports `exception.message` and `exception.stacktrace`. Six callbacks did
that:

| Observer | Callback |
|---|---|
| `OpenTelemetryWorkflowObserver` | `onStepFailed` |
| `OpenTelemetryWorkflowObserver` | `onWorkflowFailed` (+ `error.message` as the span status description) |
| `OpenTelemetryTramaiWorkerObserver` | `onLeaseRenewalFailed` |
| `OpenTelemetryTramaiWorkerObserver` | `onLeaseReleaseFailed` |
| `OpenTelemetryTramaiWorkerObserver` | `onPollFailed` |
| `OpenTelemetryTramaiWorkerObserver` | `onStepAttemptFailed` |

A failure message is upstream-controlled — a provider body, a URL with a query string, a file path, a
request fragment — and a stack trace carries more. None of that is telemetry; the diagnostic value is the
failure *type*.

## 2. The fix

One shared helper, three observers:

```kotlin
internal fun Span.recordSafeException(error: Throwable) {
    addEvent(
        "exception",
        Attributes.of(
            AttributeKey.stringKey("exception.type"), error.javaClass.name,
            AttributeKey.booleanKey("exception.escaped"), false,
        ),
    )
}
```

`tramai-observability/src/main/kotlin/dev/tramai/observability/SafeExceptionEvents.kt`.

- All six callbacks now call `recordSafeException`; `OpenTelemetryOperationObserver.onProviderFailure` was
  refactored onto the same helper, so the privacy rule exists **once** instead of four times.
- `onWorkflowFailed` sets the fixed status description `Workflow failed` instead of `error.message`.
- Zero `recordException` calls remain in `tramai-observability` production code (verified by grep; the only
  matches are the helper's own KDoc).
- Non-sensitive diagnostics are untouched: event names (`tramai.workflow.step.failed`,
  `tramai.worker.lease.renewal_failed`, `tramai.worker.lease.release_failed`, `tramai.worker.poll.failed`,
  `tramai.worker.step.failed`), `ERROR_TYPE` attributes, run/workflow/worker/attempt identifiers, counters
  and latency metrics.

Sharing the helper is the point: the previous arrangement relied on each observer author remembering the
rule, which is exactly how two of three observers kept the leak for a release.

## 3. Adversarial proof — six paths, six canaries

`ExceptionPrivacyObserverTest` (6 tests) drives each callback with a canary-bearing
`IllegalStateException("CANARY-EXC-4f19 message body with bearer CANARY-EXC-4f19")` raised from a real
frame, through a real `SdkTracerProvider` with an `InMemorySpanExporter`, and asserts for every exported
span:

- the canary appears in **no** span attribute, **no** event name, **no** event attribute key or value, **no**
  status description, and not in the span's full string form;
- no event attribute value contains a stack-trace frame (`\tat `);
- the **failure type survives** (`IllegalStateException`), so the test cannot pass by recording nothing;
- the event name and the identifiers survive (run id, step name, attempt id), so the events remain useful;
- `onWorkflowFailed`'s status description is exactly `Workflow failed`.

**Mutation-verified.** Restoring `recordException(error)` (and `error.message` as the status description) on
all six paths kills **6/6** tests; the fix restores them. No test was weakened to obtain the pass.

## 4. Documentation

`docs/modules/tramai-observability.md`: the workflow span-status line, the workflow lifecycle diagram and the
failure-semantics section now state the type-only rule, name the shared helper, list all six callbacks, and
record the same for the operation observer fixed in 0.7.5g.

## 5. Verification at this head

```
:tramai-observability:test --tests '*ExceptionPrivacyObserverTest'   tests=6 failures=0
mutation (recordException restored on all six paths)                 tests=6 failures=6  (6/6 killed)
```

Repository gates: spotlessCheck, verifyStaticAnalysis, verifyChangePolicy, verifyModuleDocContract,
`:tramai-observability:apiCheck`, verifyJUnitTestSignatures, verifyCompilerWarnings, and the full
`:tramai-observability:test` suite. Exact-head CI: see PR #554.

No public API change: `recordSafeException` is `internal`, and no shipped signature moved.

## 6. Remaining limits

- This covers the six observer callbacks in `tramai-observability`. **Application logging, exporter
  back-ends and third-party observers are outside this boundary** and are not claimed confidential.
- Caller-supplied observer implementations remain the caller's responsibility, same as the reconstruction
  ports.
- Retention of `model_attempt_observations` (**R1-action**) is a separate accepted temporary risk that must
  be reconciled in the final 0.7.0 promotion review; this change does not touch it.
