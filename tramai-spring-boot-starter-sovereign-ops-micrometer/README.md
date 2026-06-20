# TramAI Sovereign Ops Micrometer

Optional Micrometer metrics bridge for the sovereign ops audit outbox worker.

This module exposes five Micrometer metric instruments that mirror the
existing OpenTelemetry metric contract. All tags are low-cardinality and
sanitised.

## Metrics

| Name | Type | Tags |
|------|------|------|
| `tramai.sovereign.ops.outbox.worker.cycles` | Counter | outcome, failure_action, error_type |
| `tramai.sovereign.ops.outbox.worker.duration` | Timer | outcome, failure_action, error_type |
| `tramai.sovereign.ops.outbox.worker.failures` | Counter | failure_action, error_type |
| `tramai.sovereign.ops.outbox.worker.recovered.records` | Counter | result |
| `tramai.sovereign.ops.outbox.worker.dispatched.records` | Counter | result |

### Tag values

- `outcome`: `success` | `failure`
- `failure_action`: `none` | `recoverPrepared` | `dispatchPending` | `unexpected`
- `error_type`: `none` | sanitized error code
- `result`: `inspected` | `moved_to_pending` | `failed_permanent` | `resolver_failure` | `claimed` | `emitted` | `failed_retryable`

## Prometheus

If Prometheus is configured for your application, these metrics are
automatically scraped when `micrometer-registry-prometheus` is on the
classpath.

## Security

No sensitive data is ever used in metric names or tag values. The metrics
never contain:

- outbox IDs, approval IDs, aggregate IDs, or workflow run IDs
- actor names or reason text
- raw exception messages, file paths, or stack traces
- prompt, model, or tool data
- raw outbox record content

## Observer precedence

When this module is on the classpath and a `MeterRegistry` bean exists,
the Micrometer observer replaces the default recording observer.
Status snapshot recording (for the Actuator endpoint) does not update
when Micrometer metrics are active.

To use both metrics and status recording, wait for PR #68 (composite
observer support) or manually wire a
`RecordingSovereignOpsAuditOutboxWorkerObserver` with the Micrometer
observer as its delegate.

## Enable

Add the dependency and ensure a `MeterRegistry` bean exists (Spring Boot
auto-configures one when `micrometer-core` or
`micrometer-registry-prometheus` is on the classpath).
