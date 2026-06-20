# Sovereign Ops Worker Observability Runbook

## Purpose

This runbook describes how to observe, monitor, and troubleshoot the
TramAI sovereign ops audit outbox worker at runtime. It covers the
four operational surfaces — Actuator status endpoint, Actuator health
component, Micrometer metrics (Prometheus), and OpenTelemetry metrics — along with
PromQL queries, alert examples, and troubleshooting flows.

The audience is operators who deploy applications using the sovereign
ops audit outbox worker and need to understand what healthy operation
looks like, how to detect problems, and what is safe to expose.

## What this runbook covers

- Actuator worker status endpoint (`/actuator/tramaiSovereignOpsWorker`)
- Actuator worker health component
- Micrometer/Prometheus metric surface (five metric families)
- OpenTelemetry metric surface
- PromQL query examples for all metric families
- Example Prometheus alert rules (mark as examples only)
- Troubleshooting flows for common operational scenarios
- Security boundaries and safe-exposure rules
- Observer composition and custom-override behavior

## What this runbook does not cover

- Production Grafana dashboards or environment-specific alert thresholds
- SRE-certified alert sensitivities or on-call run procedures
- Deployment topology, networking, or load balancing
- Database-backed outbox configuration or migration
- Distributed worker leader election or multi-node coordination
- Key rotation or credential management
- A full production deployment guide

These remain non-goals unless explicitly implemented in a later PR.

---

## Operational surfaces

### 1. Actuator worker status endpoint

**Endpoint:** `GET /actuator/tramaiSovereignOpsWorker`

**Purpose:** Returns a read-only snapshot of the audit outbox worker's
current operational state: whether the worker is running, the last
recorded cycle result, the number of cycles since last reset, and
counts of recovered and dispatched records.

#### Property fields (representative subset)

The snapshot exposes worker configuration and recent cycle state:

| Field | Type | Description |
|-------|------|-------------|
| `enabled` | boolean | Whether the worker is enabled in configuration |
| `running` | boolean | Whether the worker schedule is active |
| `recoverPreparedEnabled` | boolean | Whether the PREPARED recovery phase is active |
| `dispatchPendingEnabled` | boolean | Whether the PENDING dispatch phase is active |
| `batchSize` | int | Records processed per cycle |
| `intervalMillis` | long | Polling interval |
| `totalCyclesCompleted` | long | Cycles completed since last reset |
| `totalCyclesFailed` | long | Cycles that recorded a failure |
| `lastCycleDurationMillis` | long? | Duration of the most recent cycle |
| `lastRecovered` | object? | Most recent recovery summary (inspected, movedToPending, markedFailedPermanent, skippedUnresolved, resolverFailures) |
| `lastDispatched` | object? | Most recent dispatch result (claimed, emitted, failedRetryable, failedPermanent) |
| `lastFailure` | object? | Most recent failure summary (action, errorCode) |
| `lastFailureAt` | instant? | Timestamp of the most recent failure |

**Activation:**

The endpoint is:

- **Read-only** — never writes or mutates state
- **Disabled by default** — the TramAI bean is created only when
  `tramai.sovereign.ops.actuator.worker-status.enabled=true`
- **Sanitized** — never returns outbox IDs, approval IDs, reason text,
  tokens, replay envelopes, prompts, model responses, or tool arguments
- **Externally exposed only if the application configures it** through
  Spring Boot Actuator's `management.endpoints.web.exposure.include`

**Security:**

The TramAI module provides the endpoint bean. It does NOT provide
authentication. Applications that expose the endpoint over HTTP MUST
secure it with their own authentication and authorization layer
(e.g., Spring Security, network policies, or API gateways).

**Example configuration:**

```yaml
tramai:
  sovereign:
    ops:
      actuator:
        worker-status:
          enabled: true

management:
  endpoints:
    web:
      exposure:
        include: tramaiSovereignOpsWorker
```

---

### 2. Micrometer / Prometheus metrics

The Micrometer bridge exposes five metric families. In Prometheus,
dot-separated metric names become underscore-separated.

#### Metric families

| Name | Type | Tags |
|------|------|------|
| `tramai.sovereign.ops.outbox.worker.cycles` | Counter | outcome, failure_action, error_type |
| `tramai.sovereign.ops.outbox.worker.duration` | Timer | outcome, failure_action, error_type |
| `tramai.sovereign.ops.outbox.worker.failures` | Counter | failure_action, error_type |
| `tramai.sovereign.ops.outbox.worker.recovered.records` | Counter | result |
| `tramai.sovereign.ops.outbox.worker.dispatched.records` | Counter | result |

#### Prometheus-exported names

| Meter name | Prometheus name |
|------------|----------------|
| `tramai.sovereign.ops.outbox.worker.cycles` | `tramai_sovereign_ops_outbox_worker_cycles_total` |
| `tramai.sovereign.ops.outbox.worker.duration` | `tramai_sovereign_ops_outbox_worker_duration_seconds_count` / `_sum` / `_max` |
| `tramai.sovereign.ops.outbox.worker.failures` | `tramai_sovereign_ops_outbox_worker_failures_total` |
| `tramai.sovereign.ops.outbox.worker.recovered.records` | `tramai_sovereign_ops_outbox_worker_recovered_records_total` |
| `tramai.sovereign.ops.outbox.worker.dispatched.records` | `tramai_sovereign_ops_outbox_worker_dispatched_records_total` |

For the timer (`duration`), Prometheus exports three time series:

- `tramai_sovereign_ops_outbox_worker_duration_seconds_count` — count of
  recorded observations
- `tramai_sovereign_ops_outbox_worker_duration_seconds_sum` — sum of
  durations in seconds
- `tramai_sovereign_ops_outbox_worker_duration_seconds_max` — maximum
  duration in seconds over the rolling window

Histogram buckets (`_bucket`) are emitted **only** when percentile
histograms are explicitly enabled in the Micrometer configuration.
By default they are absent.

#### Tag values

| Tag | Values |
|-----|--------|
| `outcome` | `success`, `failure` |
| `failure_action` | `none`, `recoverPrepared`, `dispatchPending`, `unexpected` |
| `error_type` | `none` (on success), sanitized error code (on failure) |
| `result` | `inspected`, `moved_to_pending`, `failed_permanent`, `resolver_failure`, `claimed`, `emitted`, `failed_retryable` |

Tags are low-cardinality by design. No tenant IDs, user IDs, document
IDs, approval IDs, workflow IDs, or correlation IDs appear in labels.

#### Enabling Micrometer

```yaml
dependencies:
  - tramai-spring-boot-starter-sovereign-ops-micrometer
  - micrometer-registry-prometheus      # required for Prometheus scrape endpoint

management:
  endpoints:
    web:
      exposure:
        include: prometheus
```

---

### 3. OpenTelemetry metrics

The OpenTelemetry module registers five instruments under the meter
`dev.tramai.sovereign.ops.observability`.

| Instrument | Type | Unit | Description |
|------------|------|------|-------------|
| `tramai.sovereign.ops.outbox.worker.cycles` | LongCounter | `{cycle}` | Completed worker cycles per action and outcome |
| `tramai.sovereign.ops.outbox.worker.duration` | DoubleHistogram | `ms` | Cycle wall-clock duration |
| `tramai.sovereign.ops.outbox.worker.recovered.records` | LongCounter | `{record}` | Recovery result counts per result type |
| `tramai.sovereign.ops.outbox.worker.dispatched.records` | LongCounter | `{record}` | Dispatch result counts per result type |
| `tramai.sovereign.ops.outbox.worker.failures` | LongCounter | `{failure}` | Failure notifications emitted by the worker |

**Attributes** follow the same structure as Micrometer tags (see tag
values table above), prefixed with the meter namespace.

**Sanitization:** Same guarantees as the Micrometer surface — no
sensitive data in instrument names, attributes, or values.

**Activation:** The OT observer activates automatically when an
`io.opentelemetry.api.OpenTelemetry` bean is present in the Spring
context. The module depends only on `opentelemetry-api` and does not
bring an SDK or exporter. Applications that want to export OT metrics
must provide their own OpenTelemetry SDK and exporter configuration.

---

### 4. Actuator health component

**Purpose:** Provides a coarse Spring Boot health signal for the audit
outbox worker without exposing the full worker status snapshot.

**Activation:**

```yaml
tramai:
  sovereign:
    ops:
      actuator:
        worker-health:
          enabled: true
```

The health indicator is disabled by default. It is created only when a
`SovereignOpsAuditOutboxWorkerStatusStore` bean exists and no bean named
`tramaiSovereignOpsWorkerHealthIndicator` has already been registered.

#### Health status mapping

| Worker state | Health status | Meaning |
|--------------|---------------|---------|
| `enabled=false` | `UNKNOWN` | Worker is intentionally disabled, not failed |
| `enabled=true`, `running=false` | `DOWN` | Worker is expected but not running |
| `enabled=true`, `running=true`, `totalCyclesCompleted=0`, `totalCyclesFailed>0` | `DOWN` | Worker is failing before its first successful cycle |
| `enabled=true`, `running=true`, `totalCyclesCompleted=0`, `totalCyclesFailed=0` | `UP` | Worker has started and has not failed yet |
| `enabled=true`, `running=true`, `totalCyclesCompleted>0` | `UP` | Worker has completed at least one cycle; later transient failures may be normal |

The health details include only flat operational values such as
`enabled`, `running`, cycle counters, scheduling configuration, and
boolean presence flags (`hasLastRecovered`, `hasLastDispatched`,
`hasLastFailure`). Nested recovery, dispatch, and failure objects are not
returned directly.

#### Relationship to other surfaces

- **Health component:** coarse status for platform health checks and
  alert routing. It answers whether the worker appears operational.
- **Custom status endpoint:** full sanitized snapshot for operators
  investigating current state and recent cycle summaries.
- **Metrics:** time-series signals for trend analysis, dashboards,
  PromQL alerts, and failure ratios.

Enabling `tramai.sovereign.ops.actuator.worker-health.enabled=true` does
not enable the custom worker status endpoint. Enabling
`tramai.sovereign.ops.actuator.worker-status.enabled=true` does not enable
the health indicator.

---

## Safe exposure model

The observability surface is designed to be safe for operators to read,
alert on, and include in dashboards without exposing sensitive data.

**The following are NEVER exposed** in metrics, labels, logs, examples,
or screenshots:

- Approval IDs, denial reasons, or reason text
- Tokens, session IDs, or replay envelopes
- Prompts, model responses, or tool arguments
- Raw outbox records or record IDs
- File paths, stack traces, or raw exception messages
- Tenant IDs, user IDs, document IDs, workflow IDs, or correlation IDs
  as metric labels

**Prometheus labels are low-cardinality.** Do not add high-cardinality
labels (tenant IDs, user IDs, document IDs, approval IDs, workflow IDs,
correlation IDs) to any metric in this surface.

---

## Normal operating behavior

Under normal conditions:

- **Worker cycles** execute at the configured polling interval. The
  `cycles` counter increments with `outcome=success` on each cycle.
- **Cycle duration** reflects the time taken for recovery and dispatch
  phases. Duration depends on batch size, outbox volume, and dispatch
  target latency.
- **Recovery** (`recovered.records`) shows `inspected` for empty
  stores or `moved_to_pending` for records promoted from PREPARED to
  PENDING.
- **Dispatch** (`dispatched.records`) shows `claimed` and `emitted`
  for successfully dispatched records.
- **Failures** are zero or near-zero during normal operation. Occasional
  transient dispatch errors may produce `failed_retryable` entries,
  which are expected if the dispatch target is briefly unavailable.
- **Actuator status** shows `running=true`, with cycle counts and
  duration updating after each cycle.

---

## Troubleshooting flows

### Worker is not running

**Symptoms:** Actuator returns `running=false`. No metrics updating.

**Checklist:**

1. Verify `tramai.sovereign.ops.outbox.worker.enabled=true` in
   application configuration.
2. Check the Actuator status endpoint for `running=false`.
3. Inspect startup logs for invalid worker configuration errors.
4. Check whether both `recoverPrepared` and `dispatchPending` are
   disabled — the worker needs at least one active phase.
5. If `dispatchPending` requires a dispatcher, verify the dispatcher
   bean is present and configured.

### Worker cycles are failing

**Symptoms:** `cycles` counter shows `outcome="failure"`. Failure
ratio exceeds expected threshold.

**Checklist:**

1. Inspect the `failure_action` and `error_type` tags on the failure
   counter or cycle counter.
2. Determine whether failures originate in recovery
   (`failure_action=recoverPrepared`) or dispatch
   (`failure_action=dispatchPending`).
3. Check the `failed_retryable` vs `failed_permanent` result tags on
   the record-level counters to understand whether the worker is
   recovering from transient errors.
4. Review worker logs for the error type identified in the metric tags.
5. If failures are retryable, confirm that the retry policy matches
   expected behavior.

### Recovery is not moving prepared records

**Symptoms:** `recovered.records` shows only `inspected` but no
`moved_to_pending`. The PENDING store remains empty.

**Checklist:**

1. Verify the PREPARED store actually contains records.
2. Check that the resolver is correctly resolving outbox records —
   `resolver_failure` entries on the recovery counter indicate
   resolution problems.
3. Confirm the `recoverPrepared` phase is enabled.
4. Inspect the outbox store provider configuration (e.g., file path,
   encryption key presence).

### Dispatch is not emitting records

**Symptoms:** `dispatched.records` shows only `claimed` but no
`emitted`. Records are being claimed but not completed.

**Checklist:**

1. Check for `failed_retryable` or `failed_permanent` dispatch results.
2. Verify the dispatch target (e.g., the auditor bean or emitter) is
   available and responsive.
3. Check whether the dispatch implementation has retry limits or
   throttling that is being hit.
4. Confirm the `dispatchPending` phase is enabled.

### Metrics are present but Actuator status is stale

**Symptoms:** Micrometer/OpenTelemetry metrics are updating with each
cycle, but the Actuator endpoint returns unchanged values.

**Explanation:** This should be unexpected after PR #68 introduced the
composite observer pipeline. However, it can still happen if the
application provides a custom `SovereignOpsAuditOutboxWorkerObserver`
bean that does not delegate to the composite observer.

**Checklist:**

1. Check whether the application defines a custom observer bean.
2. If a custom bean exists, confirm it implements the
   `SovereignOpsAuditOutboxWorkerObserver` interface and does NOT
   bypass the status-recording observer.
3. The base module's `@ConditionalOnMissingBean` means a custom bean
   **replaces** the entire observer chain, including status recording.
4. To preserve status recording with custom logic, compose your custom
   observer with the existing one via the composite pattern.

### Actuator status works but Prometheus has no data

**Symptoms:** Actuator endpoint returns valid status. Worker is
running. Prometheus scrape target is configured but no
`tramai_sovereign_ops_outbox_worker_*` metrics appear.

**Checklist:**

1. Confirm `tramai-spring-boot-starter-sovereign-ops-micrometer` is on
   the classpath.
2. Confirm a `MeterRegistry` bean exists — Spring Boot auto-configures
   one when a Micrometer implementation is on the classpath.
3. If using Prometheus, confirm `micrometer-registry-prometheus` is on
   the classpath.
4. If using Spring Boot Actuator, confirm the Prometheus endpoint is
   exposed: `management.endpoints.web.exposure.include=prometheus`.
5. Confirm the Prometheus scrape target is healthy and pointing to the
   correct port and path (`/actuator/prometheus`).

---

## PromQL quick reference

See the companion [PromQL reference](./prometheus/sovereign-ops-worker-promql.md)
for a full set of example queries.

### Worker cycle rate

```promql
rate(tramai_sovereign_ops_outbox_worker_cycles_total[5m])
```

### Failed cycle rate

```promql
rate(tramai_sovereign_ops_outbox_worker_cycles_total{outcome="failure"}[5m])
```

### Failure ratio

```promql
sum(rate(tramai_sovereign_ops_outbox_worker_cycles_total{outcome="failure"}[5m]))
/
sum(rate(tramai_sovereign_ops_outbox_worker_cycles_total[5m]))
```

### Average cycle duration

```promql
rate(tramai_sovereign_ops_outbox_worker_duration_seconds_sum[5m])
/
rate(tramai_sovereign_ops_outbox_worker_duration_seconds_count[5m])
```

### Recovery throughput by result

```promql
sum by (result) (
  rate(tramai_sovereign_ops_outbox_worker_recovered_records_total[5m])
)
```

### Dispatch throughput by result

```promql
sum by (result) (
  rate(tramai_sovereign_ops_outbox_worker_dispatched_records_total[5m])
)
```

### Permanent failure signal

```promql
rate(tramai_sovereign_ops_outbox_worker_recovered_records_total{result="failed_permanent"}[5m])
+
rate(tramai_sovereign_ops_outbox_worker_dispatched_records_total{result="failed_permanent"}[5m])
```

---

## Example alert rules

Example Prometheus alert rules are available at
[prometheus/sovereign-ops-worker-alerts.example.yml](./prometheus/sovereign-ops-worker-alerts.example.yml).

**These are examples — not production defaults.** Thresholds must be
tuned based on workload, retry policy, batch size, and expected
traffic. Always validate alert sensitivity against historical data
before deploying to production.

---

## Security checklist

Before exposing any part of this observability surface in a production
environment:

- [ ] Actuator endpoint is secured (Spring Security, network policy, or
      API gateway).
- [ ] Actuator endpoint is not exposed to untrusted networks.
- [ ] Prometheus scrape endpoint is restricted to authorized scrapers.
- [ ] No high-cardinality labels are added to metrics.
- [ ] Metric examples and dashboards do not contain sensitive samples.
- [ ] Logging configuration does not expose approval IDs, tokens,
      prompts, model responses, or tool arguments at non-debug levels.
- [ ] Custom observer implementations follow the same sanitization
      guarantees as the built-in observers.

---

## Known limitations

1. **No persistent counters across restarts.** Worker cycle and record
   counters reset when the application restarts. Use rates rather than
   absolute counter values in dashboards.
2. **No distributed state.** The worker is single-node. Metrics reflect
   only the local instance.
3. **No percentile histograms by default.** Timer histogram buckets are
   not emitted unless the application explicitly enables percentile
   histogram support in Micrometer.
4. **Custom observer replaces the chain.** Providing a custom
   `SovereignOpsAuditOutboxWorkerObserver` bean replaces the entire
   composite pipeline, including status recording. Compose manually if
   you need both custom logic and built-in surfaces.
5. **Actuator endpoint enabled but not registered.** Setting
   `tramai.sovereign.ops.actuator.worker-status.enabled=true` creates
   the bean, but the endpoint is not reachable until it is also
   included in `management.endpoints.web.exposure.include`.

---

## Verification commands

```bash
# Full test suite
./gradlew test --no-configuration-cache

# Sovereign runtime release evidence generation
./gradlew generateSovereignReleaseEvidenceIndex --no-configuration-cache

# Sovereign runtime consumer smoke test
./gradlew verifySovereignRuntimeConsumerSmoke --no-configuration-cache
```

---

## Custom observer override behavior

The sovereign ops starter uses `@ConditionalOnMissingBean` for the
`SovereignOpsAuditOutboxWorkerObserver` bean. This means:

- If no custom observer bean is defined, the composite observer chain
  (status recording + Micrometer + OpenTelemetry) is auto-configured.
- If a custom `SovereignOpsAuditOutboxWorkerObserver` bean is defined
  by the application, it **replaces the entire auto-configured chain**,
  including status recording.
- To extend rather than replace, inject the
  `SovereignOpsAuditOutboxWorkerStatusStore` and any
  `SovereignOpsAuditOutboxWorkerObserverContribution` beans, then
  compose your custom observer with the recording observer.

Example of composing a custom observer:

```kotlin
@Bean
fun myObservingObserver(
    statusStore: SovereignOpsAuditOutboxWorkerStatusStore,
    contributions: ObjectProvider<SovereignOpsAuditOutboxWorkerObserverContribution>,
): SovereignOpsAuditOutboxWorkerObserver {
    val composite = CompositeSovereignOpsAuditOutboxWorkerObserver(
        contributions.orderedStream().map { it.observer }.toList()
    )

    val defaultChain = RecordingSovereignOpsAuditOutboxWorkerObserver(
        statusStore = statusStore,
        delegate = composite,
    )

    return object : SovereignOpsAuditOutboxWorkerObserver {
        override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
            // Custom sanitized logic here
            defaultChain.onCycleCompleted(summary)
        }

        override fun onCycleFailed(action: String, errorCode: String) {
            // Custom sanitized logic here
            defaultChain.onCycleFailed(action, errorCode)
        }
    }
}
```

**Important:** This is an advanced use case. The default composite
chain is designed to cover the common case. If you override it,
ensure all desired surfaces (status recording, Micrometer, OTel) are
still called.
