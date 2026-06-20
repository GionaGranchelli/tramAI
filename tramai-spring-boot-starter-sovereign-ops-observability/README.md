# tramai-spring-boot-starter-sovereign-ops-observability

OpenTelemetry observer for the TramAI sovereign ops audit outbox worker.

Provides OpenTelemetry metrics for the
`SovereignOpsAuditOutboxBackgroundWorker` lifecycle — cycle success/failure,
latency, recovery record counts, and dispatch record counts.

## Quickstart

### 1. Add the dependency

```kotlin
implementation(project(":tramai-spring-boot-starter-sovereign-ops-observability"))
```

### 2. Provide an OpenTelemetry bean

The observer contribution activates **automatically** when:
- An `io.opentelemetry.api.OpenTelemetry` bean is in the Spring context

The base sovereign ops starter composes OT metrics with the status-recording
observer. Without an `OpenTelemetry` bean, no OT contribution is created.

To override the entire observer chain, provide a custom
`SovereignOpsAuditOutboxWorkerObserver` bean — the auto-configuration
respects `@ConditionalOnMissingBean` in the base module.

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

All instruments are registered under the meter `dev.tramai.sovereign.ops.observability`.

| Instrument | Type | Unit | Description |
|---|---|---|---|
| `tramai.sovereign.ops.outbox.worker.cycles` | LongCounter | `{cycle}` | Completed worker cycles per action and outcome |
| `tramai.sovereign.ops.outbox.worker.duration` | DoubleHistogram | `ms` | Cycle wall-clock duration |
| `tramai.sovereign.ops.outbox.worker.recovered.records` | LongCounter | `{record}` | Recovery result counts per result type |
| `tramai.sovereign.ops.outbox.worker.dispatched.records` | LongCounter | `{record}` | Dispatch result counts per result type |
| `tramai.sovereign.ops.outbox.worker.failures` | LongCounter | `{failure}` | Failure notifications emitted by the worker |

### Metric: worker.cycles

**Attributes:**

| Attribute | Values | Description |
|---|---|---|
| `tramai.sovereign.ops.outbox.worker.outcome` | `success`, `failure` | Whether the cycle completed successfully |
| `tramai.sovereign.ops.outbox.worker.failure_action` | `none`, `recoverPrepared`, `dispatchPending` | The action that failed (or `none` on success) |
| `tramai.sovereign.ops.outbox.worker.error_type` | `none`, simple exception class name | Error type when `outcome=failure` |

### Metric: worker.duration

**Attributes:** same as `worker.cycles`.

### Metric: worker.failures

**Attributes:**

| Attribute | Values | Description |
|---|---|---|
| `tramai.sovereign.ops.outbox.worker.failure_action` | `recoverPrepared`, `dispatchPending`, `unexpected` | Which action triggered the failure |
| `tramai.sovereign.ops.outbox.worker.error_type` | simple exception class name | Error type |

### Metric: worker.recovered.records

**Attributes:**

| Attribute | Values | Description |
|---|---|---|
| `tramai.sovereign.ops.outbox.recovery.result` | `inspected`, `moved_to_pending`, `failed_permanent`, `resolver_failure` | Recovery operation result |

### Metric: worker.dispatched.records

**Attributes:**

| Attribute | Values | Description |
|---|---|---|
| `tramai.sovereign.ops.outbox.dispatch.result` | `claimed`, `emitted`, `failed_retryable`, `failed_permanent` | Dispatch operation result |

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
