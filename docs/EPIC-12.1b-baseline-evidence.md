# Epic 12.1b — baseline variance evidence (3 deep-lane runs)

Measurement commit: `d2e6beef0e1253caf13218216d936377263b25a5` (branch
`epic/12.1b-measurement-authority`, identical to master after #382). Full
B01–B11 population (12 operation identities: B11 has `empty` + `loaded`).

Deep-lane runs (Sovereign Runtime Release Candidate, `workflow_dispatch`,
`-Dtramai.benchmark=true --rerun-tasks`; each run executes every benchmark
class and uploads `sovereign-benchmark-reports`):

| run | run id | started (UTC) | outcome | population |
|-----|--------|---------------|---------|------------|
| 1 | 33811488740 | 2026-09-03 22:07:11 | success | 12/12 |
| 2 | 33812722200 | 2026-09-03 22:22:19 | success | 12/12 |
| 3 | 33813904962 | 2026-09-03 22:37:00 | success | 12/12 |

No missing and no duplicate operation ids in any run. Raw JSON artifacts
preserved: every run's raw samples (10 latency samples per run, 5 throughput
samples per run) and per-run mean/p50/p95 are embedded **in the committed
baseline file** (`config/quality/performance/0.6.0-performance-baseline.json`,
durable in-repo evidence). GitHub artifact store
(`sovereign-benchmark-reports` per run) and the local archive
`/tmp/bench-runs/run{1,2,3}/` remain as provenance copies only.

## Inter-run spread (max/min across the three runs)

Latency ops (mean µs / p50 µs spread) and throughput ops (mean ops/sec spread):

| operation | unit | mean spread | p50 spread |
|-----------|------|-------------|------------|
| B01-service-proxy-creation | µs | 2.59x | 1.42x |
| B02-operation-plan-compilation | µs | 1.55x | 1.59x |
| B03-cached-invocation-dispatch | µs | 1.29x | 1.60x |
| B04-structured-contract-compilation | µs | 2.01x | 1.36x |
| B05-structured-validation | µs | 1.55x | 1.55x |
| B06-provider-routing | µs | **38.60x** | 1.68x |
| B07-tool-governance | µs | 2.04x | 2.57x |
| B08-approval-suspend-resume | µs | 1.40x | 1.57x |
| B09-evidence-export | µs | 1.35x | 1.26x |
| B10-checkpoint-resume | µs | 1.21x | 1.01x |
| B11-worker-polling-empty | ops/sec | 1.32x | 1.40x |
| B11-worker-polling-loaded | ops/sec | 1.08x | 1.10x |

## Variance review

- **B06 mean 38.6x spread is an outlier artifact, not fixture drift.** Run 3
  contains a single 2,736.7 µs sample in a 10-sample run where the other 9
  samples sit in 7.3–16.5 µs (runs 1–2 medians: 6.4 / 5.8 µs; run 3 median
  9.8 µs). A ~7 µs operation is at the noise floor of a shared CI runner —
  one scheduling/GC spike inflates the mean. **Recorded, not discarded**:
  the baseline references p50 for micro-latency operations and stores the raw
  samples per run for 12.1d review.
- **B01 / B04 / B07 mean spread 2–2.6x with p50 spread ≤ 2.6x** — same
  sub-100 µs noise profile on shared runners; expected, recorded.
- **B10 (1.2x / 1.01x) and B11-loaded (1.08x)** are the tightest — real
  workloads amortize scheduling noise.
- No run was discarded; no environmental failure was proven in any run.

## Baseline reference choice

For latency operations the committed baseline stores per-run mean/p50/p95
plus the raw sample population for every run and operation, and marks **p50**
as the reference metric (mean is outlier-sensitive at micro-latency scale on
shared runners). For throughput operations (B11) the mean ops/sec is the
reference. The baseline file is **evidence only**: no thresholds, no
regression gate — 12.1d owns regression policy. Independent audit of the
reference statistics and any recorded outlier (e.g. the B06 run-3 2,736.7 µs
sample) is possible from the committed baseline alone, without GitHub
artifacts or local archives.
