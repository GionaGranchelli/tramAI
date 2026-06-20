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

## Observer composition

When used with the sovereign ops starter, Micrometer metrics are composed
with the default status-recording observer. The Actuator worker status
snapshot continues to update while metrics are emitted.

No manual wiring is required -- the base sovereign ops starter automatically
collects observer contributions from Micrometer and OpenTelemetry modules
and composes them behind the status-recording observer.

## Enable

Add the dependency and ensure a `MeterRegistry` bean exists (Spring Boot
auto-configures one when `micrometer-core` or
`micrometer-registry-prometheus` is on the classpath).
