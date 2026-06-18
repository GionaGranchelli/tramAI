# tramai-spring-boot-starter-sovereign-ops-observability

OpenTelemetry observer for the TramAI sovereign ops audit outbox worker.

Provides [OpenTelemetry metrics](https://opentelemetry.io/docs/specs/semconv/general/metrics/) for the
[SovereignOpsAuditOutboxBackgroundWorker](https://github.com/nousresearch/tramai) lifecycle — cycle success/failure,
latency, recovery record counts, and dispatch record counts.

## Quickstart

### 1. Add the dependency

```kotlin
implementation(project(":tramai-spring-boot-starter-sovereign-ops-observability"))
```

### 2. Provide an OpenTelemetry bean

The observer activates **automatically** when:
- An `io.opentelemetry.api.OpenTelemetry` bean is in the Spring context
- No custom `SovereignOpsAuditOutboxWorkerObserver` bean is registered

Without an `OpenTelemetry` bean, the sovereign ops starter's `Noop` observer remains active.

### 3. Enable the worker

```yaml
tramai:
  sovereign:
    ops:
      outbox:
        worker:
          enabled: true
```

## Emitted metrics

All instruments are registered under the meter `dev.tramai.sovereign.ops`.

| Instrument | Type | Unit | Description |
|---|---|---|---|
| `tramai.sovereign.ops.outbox.worker.cycles` | LongCounter | `{cycle}` | Completed worker cycles, keyed by `action` and `outcome` |
| `tramai.sovereign.ops.outbox.worker.duration` | DoubleHistogram | `ms` | Cycle wall-clock duration ms |
| `tramai.sovereign.ops.outbox.worker.recovered.records` | LongCounter | `{record}` | Recovery result counts: inspected, movedToPending, markedFailedPermanent, resolverFailures |
| `tramai.sovereign.ops.outbox.worker.dispatched.records` | LongCounter | `{record}` | Dispatch result counts: claimed, emitted, failedRetryable, failedPermanent |
| `tramai.sovereign.ops.outbox.worker.failures` | LongCounter | `{failure}` | Unexpected cycle failures (action="unexpected", error_type=exception simple name) |

### Attributes

All metrics receive these **low-cardinality, sanitized** attributes:

| Attribute | Values | Description |
|---|---|---|
| `action` | `recoverPrepared`, `dispatchPending`, `unexpected` | Which operation was in progress |
| `outcome` | `success`, `failure` | Whether the cycle completed successfully |
| `error_type` | exception simple class name, `"none"` | Only set on failure counters |

### Sanitization guarantees

The observer **never** emits:
- Approval IDs, denial reasons, tokens, replay envelopes
- Prompts, model responses, tool arguments
- Exception messages, stack traces, file paths
- Any raw string that could carry sensitive data

## Custom observers

Provide your own `SovereignOpsAuditOutboxWorkerObserver` bean — the auto-configuration respects `@ConditionalOnMissingBean` and never overrides user-provided beans.

```kotlin
@Bean
fun myObserver(): SovereignOpsAuditOutboxWorkerObserver = MyObserver()
```

## Dependencies

- `tramai-spring-boot-starter-sovereign-ops` — the observer SPI and worker DTOs
- `io.opentelemetry:opentelemetry-api` — meter creation (no SDK, no exporter)
