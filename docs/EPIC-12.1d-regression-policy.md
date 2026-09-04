# Epic 12.1d — Performance / resource regression policy

**Status: adopted** (Epic 12.1; supersedes the 12.1b "evidence only — no
thresholds" placeholder for comparisons, while keeping the 0.6.0 baseline
immutable).

Machine-readable implementation: `scripts/performance_regression_verifier.py`
(classification semantics tested in
`scripts/test_performance_regression_verifier.py`).

## Core policy split

### A. Resource/lifecycle regressions = hard correctness failures

The seven 12.1c proofs (see epic doc §9) are deterministic correctness
contracts. If any of those tests fails, ordinary PR CI fails and release/deep
CI fails. No statistical confirmation is required:

- an owned job survives `close()`;
- a shutdown hook remains registered after close;
- a subprocess survives cancellation;
- an HTTP response/stream is not closed;
- JDBC/file resources grow unbounded;
- `pathLocks` fails to return to baseline;
- repeated create/close retains owned state.

These are correctness regressions, not performance noise. They are enforced
by the 12.1c test suites themselves in every CI run (#385/#386/#387).

### B. Performance regressions = measured drift, not single-run PR failures

B01–B11 stay skipped in ordinary PR CI (`tramai.benchmark=true` gating).
Performance comparison belongs in the deep/release lane only.

No arbitrary ±10%/±20% thresholds. The 12.1b baseline demonstrates why:
several micro-latency p50s vary ~1.4–2.6× across healthy runs; B06 had a
38.6× mean distortion from one scheduler/GC outlier; larger workloads
(B10/B11) are considerably more stable. A single shared-runner measurement is
not enough evidence to reject a change.

## Baseline reference semantics

For latency operations (B01–B10):

- comparison metric = **p50**;
- baseline reference = **median of the three recorded run p50 values**;
- observed envelope = **min..max of those three p50 values**.

For throughput operations (B11-empty/B11-loaded):

- comparison metric = **mean ops/sec**;
- baseline reference = **median of the three recorded run means**;
- observed envelope = **min..max of those means**.

p95 and raw samples remain diagnostic evidence, not primary 0.6.0 regression
criteria.

## Candidate-regression protocol (one deep run)

- latency > baseline observed max → `REGRESSION_CANDIDATE`;
- throughput < baseline observed min → `REGRESSION_CANDIDATE`;
- inside the observed envelope → `WITHIN_BASELINE_VARIANCE`;
- beyond the good side of the envelope → `IMPROVEMENT_CANDIDATE`.

A single candidate **must not** automatically rewrite the baseline or hard-fail
the release.

For a worse candidate:

1. verify benchmark authority/applicability (fingerprint + env, below);
2. run the same exact commit 3 independent deep-lane times;
3. preserve every run — no cherry-picking;
4. compare the three confirmation reference metrics.

Classify as `CONFIRMED_REGRESSION` only when all three confirmation runs
remain worse than the corresponding worst observed 0.6.0 baseline boundary:

- latency: all three p50s > baseline max p50;
- throughput: all three means < baseline min mean.

If confirmation straddles the envelope → `INCONCLUSIVE_NOISE`, not regression.
This derives the boundary from measured baseline variance instead of an
invented percentage.

## What CONFIRMED_REGRESSION means (0.6.0)

A confirmed performance regression is **release-review blocking**, not generic
PR-CI blocking. It requires an explicit outcome before release:

- fix the regression; or
- document and accept the regression with rationale; or
- prove the baseline is no longer comparable and regenerate authority.

Never silently update the baseline to make a regression disappear.

## Applicability / stale-baseline rules

The baseline is comparable only while the measurement contract is unchanged.
A **benchmark authority fingerprint** covers files that affect the meaning of
a measurement:

- `BenchmarkHarness` / `BenchmarkSupport` measurement logic;
- B01–B11 benchmark definitions and fixtures;
- warm-up / iteration / throughput-window semantics;
- percentile/statistic implementation;
- benchmark JSON schema;
- relevant benchmark activation/output wiring.

Ordinary production implementation code is deliberately **excluded** —
production changes are exactly what the benchmark detects.

If the fingerprint changes: old numbers become `NON_COMPARABLE`; do not call
the difference a regression; establish a new baseline with the same ≥3-run
protocol. (The 0.6.0 baseline predates fingerprint recording; it is assumed
comparable and the verifier reports the current fingerprint for future
baselines — the historical file is not rewritten.)

Environment compatibility uses recorded metadata. At minimum, incompatible
changes in OS/architecture, JDK major/runtime family, or benchmark
methodology produce `NON_COMPARABLE`. Different GitHub runner
instances/hostnames alone are expected and never invalidate comparison.
Runner image/version captured going forward is diagnostic metadata only; the
historical baseline is not rewritten because it was not originally recorded.

## Hard failures (fail closed)

Even though timing drift is not an automatic PR failure, the measurement
machinery fails closed on structural errors:

- missing expected operation identity;
- duplicate operation identity;
- wrong population (expect 12 identities);
- malformed JSON;
- wrong/missing measured commit;
- incompatible schema;
- benchmark authority mismatch when comparison was requested;
- benchmark execution unexpectedly skipped in the deep lane.

Those are deterministic failures → `INVALID_MEASUREMENT`/`NON_COMPARABLE` and
a non-zero verifier exit.

## Baseline update policy

`config/quality/performance/0.6.0-performance-baseline.json` is immutable
historical evidence for 0.6.0. It is not mutated because master gets
faster/slower. Replacement requires an explicit reason:

- a new release baseline;
- a benchmark methodology/fixture change;
- an environment authority change making old evidence non-comparable.

Every replacement preserves provenance and uses ≥3 independent runs.

## Classification vocabulary

`WITHIN_BASELINE_VARIANCE`, `IMPROVEMENT_CANDIDATE`, `REGRESSION_CANDIDATE`,
`INCONCLUSIVE_NOISE`, `CONFIRMED_REGRESSION`, `NON_COMPARABLE`,
`INVALID_MEASUREMENT`.

A single deep run can only produce candidate/within/non-comparable states.
`CONFIRMED_REGRESSION` requires the explicit three-run confirmation set.

## Verification commands

```bash
# unit tests for classification semantics (ordinary CI, timing-free)
python3 scripts/test_performance_regression_verifier.py

# classify one deep run's raw benchmark JSONs against the committed baseline
python3 scripts/performance_regression_verifier.py verify \
  --baseline config/quality/performance/0.6.0-performance-baseline.json \
  --runs '**/build/reports/benchmark/*.json' \
  --expected-commit <sha> --repo-root . --summary

# three-run confirmation protocol
python3 scripts/performance_regression_verifier.py confirm \
  --baseline config/quality/performance/0.6.0-performance-baseline.json \
  --run-group '<run1 glob>' --run-group '<run2 glob>' --run-group '<run3 glob>' \
  --expected-commit <sha>
```

Exit codes: 0 = comparable report (candidates allowed); 1 =
`INVALID_MEASUREMENT` (structural, fail closed); 2 = `NON_COMPARABLE`.
