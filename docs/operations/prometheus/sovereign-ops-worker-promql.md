# Sovereign Ops Worker — PromQL Query Reference

PromQL queries for the sovereign ops audit outbox worker Micrometer
metrics. All metric names assume the Prometheus naming convention
(dots → underscores, counters suffixed with `_total`).

## Metric reference

### Worker cycles

Prometheus name: `tamai_sovereign_ops_outbox_worker_cycles_total`

Tags: `outcome`, `failure_action`, `error_type`

### Worker cycle duration (timer)

Prometheus names:

- `tamai_sovereign_ops_outbox_worker_duration_seconds_count`
- `tamai_sovereign_ops_outbox_worker_duration_seconds_sum`
- `tamai_sovereign_ops_outbox_worker_duration_seconds_max`

Tags: `outcome`, `failure_action`, `error_type`

Histogram buckets (`_bucket`) are absent by default — emitted only
when percentile histograms are explicitly enabled.

### Worker failures

Prometheus name: `tamai_sovereign_ops_outbox_worker_failures_total`

Tags: `failure_action`, `error_type`

### Recovery records

Prometheus name: `tamai_sovereign_ops_outbox_worker_recovered_records_total`

Tags: `result` (`inspected`, `moved_to_pending`, `failed_permanent`,
`resolver_failure`)

### Dispatch records

Prometheus name: `tamai_sovereign_ops_outbox_worker_dispatched_records_total`

Tags: `result` (`claimed`, `emitted`, `failed_retryable`,
`failed_permanent`)

---

## Queries

### Worker cycle rate

Rate of completed worker cycles (all outcomes).

```promql
rate(tramai_sovereign_ops_outbox_worker_cycles_total[5m])
```

### Failed cycle rate

Rate of worker cycles that completed with a failure outcome.

```promql
rate(tramai_sovereign_ops_outbox_worker_cycles_total{outcome="failure"}[5m])
```

### Failure ratio

Proportion of worker cycles that ended in failure.

```promql
sum(rate(tramai_sovereign_ops_outbox_worker_cycles_total{outcome="failure"}[5m]))
/
sum(rate(tramai_sovereign_ops_outbox_worker_cycles_total[5m]))
```

If no cycles are observed, the denominator is zero and the query
returns `NaN`. Use `clamp_min(coalesce(..., 0), 1)` in alert rules
to avoid division by zero.

### Average cycle duration

Average wall-clock duration of worker cycles.

```promql
rate(tramai_sovereign_ops_outbox_worker_duration_seconds_sum[5m])
/
rate(tramai_sovereign_ops_outbox_worker_duration_seconds_count[5m])
```

### Maximum cycle duration (latest rolling window)

```promql
tramai_sovereign_ops_outbox_worker_duration_seconds_max
```

### Recovery throughput by result

Per-result-type rate of records processed by the recovery phase.

```promql
sum by (result) (
  rate(tramai_sovereign_ops_outbox_worker_recovered_records_total[5m])
)
```

### Dispatch throughput by result

Per-result-type rate of records processed by the dispatch phase.

```promql
sum by (result) (
  rate(tramai_sovereign_ops_outbox_worker_dispatched_records_total[5m])
)
```

### Permanent failure signal

Rate of records that were permanently failed (not retryable) in either
the recovery or dispatch phase.

```promql
rate(tramai_sovereign_ops_outbox_worker_recovered_records_total{result="failed_permanent"}[5m])
+
rate(tramai_sovereign_ops_outbox_worker_dispatched_records_total{result="failed_permanent"}[5m])
```

Any sustained non-zero value for this query indicates records that
cannot be processed and require operator attention.

### Worker not running (absent signal)

Alert when the worker is expected to be running but no cycle metrics
appear.

```promql
absent(tramai_sovereign_ops_outbox_worker_cycles_total) == 1
```

Note: This fires a single alert when the metric is absent. Use
`for: 5m` to avoid flapping during restarts.

### Failure rate by failure_action

Break down failure rates by the action that failed.

```promql
sum by (failure_action) (
  rate(tramai_sovereign_ops_outbox_worker_failures_total[5m])
)
```

### Error type distribution

Identify which error types contribute to failures.

```promql
sum by (error_type) (
  rate(tramai_sovereign_ops_outbox_worker_failures_total[5m])
)
```

---

## Query guidance

- **Use rates, not raw counters.** Counter values reset on restart and
  are meaningless as absolute values. Always use `rate()` or
  `increase()` over a time window.
- **Match duration window to cycle interval.** A 5m rate window works
  for workers with polling intervals up to 60 seconds. For slower
  intervals (e.g., 5 minutes), use a correspondingly longer window
  (15m+).
- **Division by zero.** When computing ratios (failure ratio, average
  duration), the denominator may be zero if no cycles have completed.
  Wrap denominators in `clamp_min(..., 1)` for alert rules.
- **Timer buckets are opt-in.** If you need latency SLOs with
  percentile histograms, enable them in Micrometer configuration:
  ```yaml
  management:
    metrics:
      distribution:
        percentiles-histogram:
          tramai.sovereign.ops.outbox.worker.duration: true
  ```
