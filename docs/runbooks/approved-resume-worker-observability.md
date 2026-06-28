---
title: Approved Resume Worker Observability
description: Operator guidance for observing, triaging, and alerting on the approved-continuation auto-resume worker
---

# Approved Resume Worker Observability

## Purpose

The approved-resume worker is responsible for automatically resuming approved continuations (workflows that were paused pending human review and subsequently approved). This observability surface exists so operators can:

- Monitor the worker's health and throughput
- Detect when approved items are stuck and not being resumed
- Identify lease expiration issues that signal worker crashes or stuck DB transactions
- Observe retry backlogs caused by transient failures
- Distinguish terminal failures (safe reasons like `rejection-revoked`, `superseded`) from transient errors
- Verify that the queue-snapshot refresh is working (metrics are fresh)

## Required Configuration

Before the metrics and alerts described in this document become available, the following must be enabled:

| Setting | Value | Description |
|---------|-------|-------------|
| `tramai.sovereign.approved-resume.worker.metrics-enabled` | `true` | Enables Micrometer metric export for the approved-resume worker |
| `tramai.sovereign.approved-resume.queue.snapshot-refresh-interval` | — | Controls how often the in-memory queue snapshot is refreshed (affects staleness of `eligible_now`, `delayed_retry`, etc.) |
| Prometheus scrape target | — | The application's `/actuator/prometheus` endpoint must be scraped |
| Grafana datasource | `prometheus` | A Prometheus datasource with UID `prometheus` must exist if using the example dashboard |

## Metrics

All metrics are exported via Micrometer under the `tramai.sovereign.*` namespace. The table below shows Prometheus-normalized names (dots → underscores).

| Metric (Prometheus form) | Type | Description |
|--------------------------|------|-------------|
| `tramai_sovereign_approved_resume_worker_cycles_total` | Counter | Total worker cycles completed. Tagged by `outcome` (e.g. `completed`, `failed`). |
| `tramai_sovereign_approved_resume_worker_items_scanned_total` | Counter | Total items scanned across all cycles. |
| `tramai_sovereign_approved_resume_worker_items_resumed_total` | Counter | Total items successfully resumed. |
| `tramai_sovereign_approved_resume_worker_items_skipped_total` | Counter | Total items skipped (e.g. still within cooldown, already resumed by another process). |
| `tramai_sovereign_approved_resume_worker_items_failed_total` | Counter | Total items that failed during resume. |
| `tramai_sovereign_approved_resume_worker_cycle_duration_seconds` | Timer (histogram) | Duration of each worker cycle. Use `_bucket`, `_sum`, `_count` suffixes for Prometheus histogram queries. |
| `tramai_sovereign_approved_resume_worker_failures_total` | Counter | Non-item-specific worker failures. Tagged by `error_code`. |
| `tramai_sovereign_approved_resume_queue_eligible_now` | Gauge | Number of approved continuations currently eligible for immediate resume. |
| `tramai_sovereign_approved_resume_queue_delayed_retry` | Gauge | Number of items in retry cooldown (transient failure, waiting for next attempt). |
| `tramai_sovereign_approved_resume_queue_active_leases` | Gauge | Number of items currently leased by a worker. |
| `tramai_sovereign_approved_resume_queue_expired_leases` | Gauge | Number of items with expired leases (worker died mid-cycle or DB transaction stuck). |
| `tramai_sovereign_approved_resume_queue_terminal_failures` | Gauge | Number of items in terminal failure state (CANCELLED with a safe reason code). |
| `tramai_sovereign_approved_resume_queue_oldest_eligible_age_seconds` | Gauge | Age in seconds of the oldest eligible item waiting for resume. |
| `tramai_sovereign_approved_resume_queue_oldest_retry_due_in_seconds` | Gauge | Seconds until the oldest retry item becomes eligible again. |
| `tramai_sovereign_approved_resume_queue_snapshot_failures_total` | Counter | Total failures refreshing the in-memory queue snapshot (DB connectivity issues). |

## Dashboard

An importable Grafana dashboard JSON is available at:

[`docs/observability/grafana-approved-resume-worker-dashboard.json`](../observability/grafana-approved-resume-worker-dashboard.json)

The dashboard provides three row groups:

1. **Queue Status** — Stat panels for eligible now, delayed retry, active leases, expired leases, terminal failures.
2. **Worker Throughput** — Time series panels for resume cycles (by outcome), items resumed, items failed, and snapshot failures.
3. **Timing & Age** — Time series panels for cycle duration (p95), oldest eligible age, and oldest retry due.

## Alerts

Prometheus alert rule examples are available at:

[`docs/observability/prometheus-approved-resume-worker-alerts.yml`](../observability/prometheus-approved-resume-worker-alerts.yml)

| Alert | Severity | Trigger |
|-------|----------|---------|
| `TramAIApprovedResumeWorkerFailures` | warning | Non-zero failures in the last 15 minutes |
| `TramAIApprovedResumeQueueBacklogGrowing` | warning | Eligible items not cleared for > 15 minutes |
| `TramAIApprovedResumeExpiredLeases` | warning | Expired leases present for > 10 minutes |
| `TramAIApprovedResumeTerminalFailures` | critical | Terminal failures detected |
| `TramAIApprovedResumeSnapshotFailures` | warning | Snapshot refresh failing |
| `TramAIApprovedResumeOldestEligibleTooOld` | warning | Oldest eligible item > 5 minutes old |

## Triage: Approved but Not Resumed

🔴 `tramai_sovereign_approved_resume_queue_eligible_now > 0`

1. **Check the worker is running.** Verify the pod/process is alive and the worker schedule (if cron-driven) has not been paused or overridden.
2. **Check snapshot freshness.** If `tramai_sovereign_approved_resume_queue_snapshot_failures_total` has increased recently, the snapshot may be stale — the worker may have already processed items but the metrics are not reflecting it.
3. **Check `oldest_eligible_age_seconds`.** If this is climbing, the worker is scanning but not selecting items — investigate the eligibility predicate (cooldown check, approval status filter, etc.).
4. **Check worker cycle outcome.** Query `increase(tramai_sovereign_approved_resume_worker_cycles_total[$__rate_interval])` grouped by `outcome`. If cycles are failing (`outcome=failed`), see "Triage: retry backlog" below.
5. **Check for stuck DB transactions.** Prolonged transactions holding row-level locks on the continuation table can prevent the worker from progressing items.

## Triage: Retry Backlog

🔴 `tramai_sovereign_approved_resume_queue_delayed_retry > 0`

1. **Examine `oldest_retry_due_in_seconds`.** If this value is high, items are in long cooldown — likely a persistent failure mode.
2. **Check items_failed_total.** A spike in `increase(tramai_sovereign_approved_resume_worker_items_failed_total[$__rate_interval])` indicates a systematic problem (downstream service unavailable, invalid continuation state, etc.).
3. **Review error codes.** Use `tramai_sovereign_approved_resume_worker_failures_total` (tagged by `error_code`) to identify the dominant failure mode.
4. **Escalate if delayed_retry keeps growing and oldest_retry_due_in_seconds does not decrease.** This means new items are failing faster than retries are succeeding.

## Triage: Expired Leases

🔴 `tramai_sovereign_approved_resume_queue_expired_leases > 0`

1. **Check if the worker is still alive.** If the worker process has died, leases will expire after the lease timeout.
2. **Check for stuck DB transactions.** A DB transaction that is holding locks but not progressing may cause a worker to appear dead even if the process is running. Check the database for long-running queries on the continuation table.
3. **Check worker cycle duration.** If `histogram_quantile(0.95, ...)` for `cycle_duration_seconds` exceeds the lease timeout, the worker may be timing out its own leases. This suggests the worker is overloaded or a downstream call is slow.
4. **Monitor for oscillation.** If expired leases appear and then clear as another worker reaps them, the system is self-healing but may be under-provisioned.

## Triage: Terminal Failures

🔴 `tramai_sovereign_approved_resume_queue_terminal_failures > 0`

1. **Verify the reason code.** Terminal failures are expected in some cases (e.g., an approval was revoked, or the continuation was superseded by a newer run). The reason is stored in the safe reason code field on the continuation record.
2. **Check if terminal failures are cumulative or increasing.** A stable count of old terminal failures is normal. An increasing count suggests a new systemic issue causing items to be cancelled rather than resumed.
3. **Correlate with upstream signals.** Terminal failures are often caused by external state changes (workflow cancelled upstream, approval revoked, parent workflow already terminated).
4. **Manual cleanup.** Items in terminal failure state are marked CANCELLED and will not be retried. If they should be retried, manual intervention in the database may be needed.

## Safety Boundary

The following information is **never** included in metric labels, alert annotations, or dashboard panel descriptions:

- **Approval identifiers** — Individual approval IDs are not exposed in metrics.
- **Workflow run identifiers** — Specific workflow run IDs are not exposed.
- **Continuation resume tokens** — Resume tokens are not exposed.
- **Exception messages or stack traces** — Raw exception details are not propagated into observability labels or annotations.

All observability surfaces described in this document use only **safe aggregate metrics**: counters, gauges, and histograms with high-level tags (e.g., `outcome`, `error_code`). No personally identifiable information (PII) or internal request identifiers are included. If deeper investigation of a specific item is required, the operator must query the database directly using the appropriate tooling outside of the observability pipeline.
