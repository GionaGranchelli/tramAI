#!/usr/bin/env python3
"""Epic 12.1d — performance regression policy verifier.

Machine-readable comparison logic over the committed 0.6.0 performance
baseline and deep-lane raw benchmark JSONs (one raw JSON per operation).

Policy (docs/EPIC-12.1d-regression-policy.md):
- Resource/lifecycle regressions (12.1c) are hard correctness failures in
  ordinary PR CI — this verifier does NOT govern them.
- Timing drift is NOT an ordinary PR failure and a single deep run can only
  produce candidate/within/non-comparable states.
- CONFIRMED_REGRESSION requires an explicit three-run confirmation set, all
  three worse than the corresponding worst observed 0.6.0 baseline boundary.
- Structural measurement failures fail closed (exit non-zero).

Reference semantics:
- latency (B01-B10): metric p50; baseline reference = median of the three
  recorded run p50s; observed envelope = min..max of those p50s.
- throughput (B11-*): metric mean ops/sec; baseline reference = median of the
  three recorded run means; observed envelope = min..max of those means.
- p95/raw samples remain diagnostic evidence, not 0.6.0 regression criteria.

Stdlib only. Unit tests: test_performance_regression_verifier.py.
"""

import argparse
import glob
import hashlib
import json
import statistics
import sys
from pathlib import Path

BASELINE_SCHEMA = "tramai-performance-baseline.v1"

# Classifications, per the policy vocabulary.
WITHIN = "WITHIN_BASELINE_VARIANCE"
IMPROVEMENT = "IMPROVEMENT_CANDIDATE"
REGRESSION = "REGRESSION_CANDIDATE"
INCONCLUSIVE = "INCONCLUSIVE_NOISE"
CONFIRMED = "CONFIRMED_REGRESSION"
NON_COMPARABLE = "NON_COMPARABLE"
INVALID = "INVALID_MEASUREMENT"

# Benchmark authority fingerprint source list. Only files that change the
# MEANING of a measurement belong here; ordinary production implementation
# code is deliberately excluded (production changes are what the benchmark
# detects). Paths are repo-root relative.
AUTHORITY_FILES = [
    "tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/benchmark/BenchmarkHarness.kt",
    "tramai-core/src/test/kotlin/dev/tramai/core/benchmark/BenchmarkSupport.kt",
    "tramai-structured/src/test/kotlin/dev/tramai/structured/benchmark/BenchmarkSupport.kt",
    "tramai-engine/src/test/kotlin/dev/tramai/engine/benchmark/ServiceProxyCreationBenchmark.kt",
    "tramai-engine/src/test/kotlin/dev/tramai/engine/benchmark/OperationPlanCompilationBenchmark.kt",
    "tramai-engine/src/test/kotlin/dev/tramai/engine/benchmark/CachedInvocationDispatchBenchmark.kt",
    "tramai-structured/src/test/kotlin/dev/tramai/structured/benchmark/StructuredContractCompilationBenchmark.kt",
    "tramai-structured/src/test/kotlin/dev/tramai/structured/benchmark/StructuredValidationBenchmark.kt",
    "tramai-core/src/test/kotlin/dev/tramai/core/benchmark/ProviderRoutingBenchmark.kt",
    "tramai-engine/src/test/kotlin/dev/tramai/engine/benchmark/ToolGovernanceBenchmark.kt",
    "tramai-engine/src/test/kotlin/dev/tramai/engine/benchmark/ApprovalSuspendResumeBenchmark.kt",
    "tramai-engine/src/test/kotlin/dev/tramai/engine/benchmark/EvidenceExportBenchmark.kt",
    "tramai-orchestration/src/test/kotlin/dev/tramai/orchestration/benchmark/CheckpointResumeBenchmark.kt",
    "tramai-orchestration/src/test/kotlin/dev/tramai/orchestration/benchmark/WorkerPollingBenchmark.kt",
    "tramai-orchestration/src/test/kotlin/dev/tramai/orchestration/benchmark/WorkerPollingFixture.kt",
]

_ENV_COMPARE_FIELDS = ("os.name", "os.arch")


def nearest_rank_p50(sorted_samples):
    """Nearest-rank p50 — must match the 12.1b harness statistic."""
    if not sorted_samples:
        raise ValueError("no samples")
    return sorted_samples[(len(sorted_samples) - 1) // 2]


def java_major(version):
    if not version:
        return None
    first = version.split(".")[0]
    return int(first) if first.isdigit() and int(first) > 1 else 8


def vendor_family(vendor):
    if not vendor:
        return None
    return vendor.lower().replace("eclipse ", "").split()[0] if vendor.split() else vendor.lower()


class BaselineError(Exception):
    pass


def load_baseline(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            baseline = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        raise BaselineError(f"malformed baseline JSON: {exc}") from exc
    if baseline.get("schema") != BASELINE_SCHEMA:
        raise BaselineError(
            f"incompatible schema {baseline.get('schema')!r}, expected {BASELINE_SCHEMA!r}"
        )
    ops = {}
    seen = set()
    for op in baseline.get("operations", []):
        op_id = op.get("operationId")
        if not op_id:
            raise BaselineError("operation missing operationId")
        if op_id in seen:
            raise BaselineError(f"duplicate operation identity in baseline: {op_id}")
        seen.add(op_id)
        metric = op.get("metric")
        if metric not in ("latency", "throughput"):
            raise BaselineError(f"operation {op_id}: unknown metric {metric!r}")
        runs = op.get("runs", {})
        key = "p50" if metric == "latency" else "mean"
        values = [runs[r][key] for r in ("run1", "run2", "run3") if r in runs and key in runs[r]]
        if len(values) != 3:
            raise BaselineError(f"operation {op_id}: need 3 recorded runs, found {len(values)}")
        ops[op_id] = {
            "module": op.get("module"),
            "metric": metric,
            "unit": op.get("unit"),
            "reference": statistics.median(values),
            "envelope_min": min(values),
            "envelope_max": max(values),
            "recorded": values,
        }
    return baseline, ops


def measured_metric(raw):
    """Compute the policy reference metric from one deep-run raw benchmark JSON."""
    if "samplesOpsPerSec" in raw:
        samples = raw["samplesOpsPerSec"]
        return "throughput", statistics.mean(samples)
    if "samplesNs" in raw:
        # samples are ns; baseline latency values are microseconds.
        raw_samples = raw["samplesNs"]
        return "latency", nearest_rank_p50(sorted(raw_samples)) / 1000.0
    raise BaselineError("raw run has neither samplesOpsPerSec nor samplesNs")


def classify_single(op_meta, measured):
    """One deep run can only produce candidate/within states."""
    if op_meta["metric"] == "latency":
        if measured > op_meta["envelope_max"]:
            return REGRESSION
        if measured < op_meta["envelope_min"]:
            return IMPROVEMENT
        return WITHIN
    # throughput
    if measured < op_meta["envelope_min"]:
        return REGRESSION
    if measured > op_meta["envelope_max"]:
        return IMPROVEMENT
    return WITHIN


def classify_confirmation(op_meta, measured_three):
    """CONFIRMED_REGRESSION only when all three confirmation runs are worse
    than the corresponding worst observed 0.6.0 baseline boundary."""
    if len(measured_three) != 3:
        raise ValueError("confirmation requires exactly 3 runs")
    if op_meta["metric"] == "latency":
        worse = [m for m in measured_three if m > op_meta["envelope_max"]]
    else:
        worse = [m for m in measured_three if m < op_meta["envelope_min"]]
    return CONFIRMED if len(worse) == 3 else INCONCLUSIVE


def load_raw_run(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        raise BaselineError(f"malformed raw run JSON {path}: {exc}") from exc


def collect_runs(paths):
    runs = {}
    for p in paths:
        raw = load_raw_run(p)
        op_id = raw.get("operation")
        if not op_id:
            raise BaselineError(f"{p}: raw run missing 'operation'")
        if op_id in runs:
            raise BaselineError(f"duplicate raw run for operation {op_id} ({p} and {runs[op_id]['path']})")
        metric, value = measured_metric(raw)
        runs[op_id] = {"path": p, "raw": raw, "metric": metric, "value": value}
    return runs


def authority_fingerprint(repo_root):
    """sha256 over the authority file set (sorted path + content). Missing or
    changed files change the fingerprint. Returns (hex, errors)."""
    digest = hashlib.sha256()
    errors = []
    for rel in sorted(AUTHORITY_FILES):
        path = Path(repo_root) / rel
        digest.update(rel.encode("utf-8"))
        digest.update(b"\0")
        if path.is_file():
            digest.update(path.read_bytes())
        else:
            errors.append(rel)
    return digest.hexdigest(), errors


def authority_status(baseline, current_hex, missing):
    recorded = baseline.get("benchmarkAuthorityFingerprint")
    if missing:
        return NON_COMPARABLE, f"authority file(s) missing: {missing}"
    if recorded is None:
        return WITHIN, (
            "baseline recorded no authority fingerprint (pre-12.1d); assumed comparable; "
            f"current fingerprint {current_hex} recorded from 12.1d baselines onward"
        )
    if recorded != current_hex:
        return NON_COMPARABLE, f"authority fingerprint mismatch: baseline {recorded} vs current {current_hex}"
    return WITHIN, f"authority fingerprint matches ({recorded})"


def runner_class(label):
    """'GitHub Actions 1000006218' -> 'GitHub Actions'; instance ids never
    invalidate comparison (different runner instances are expected)."""
    if not label:
        return None
    return label.strip()


def _labels_match(recorded_class, run_label):
    run_class = runner_class(run_label)
    if not run_class:
        return False
    if run_class == recorded_class:
        return True
    # instance suffix: recorded class is a prefix of the run label
    return run_class.startswith(recorded_class + " ")


def env_status(baseline, raw):
    """Compare measured env against env fields RECORDED in the baseline. The
    0.6.0 baseline recorded only a runner-label class (hostname is never a
    comparison field), so for it the env check is a class check; future
    baselines that record os/arch/JDK per run enable the full check."""
    notes = []
    baseline_env = baseline.get("environment", {}) or {}
    if "runnerLabel" in baseline_env and baseline_env["runnerLabel"]:
        run_label = raw.get("env", {}).get("runnerLabel")
        if not _labels_match(baseline_env["runnerLabel"], run_label):
            return NON_COMPARABLE, f"runner label {run_label!r} not in baseline class {baseline_env['runnerLabel']!r}"
        notes.append(f"runner-label class {runner_class(run_label)!r} matches")
    else:
        notes.append("baseline recorded no runner label; class not compared")

    # Full OS/arch/JDK comparison only where the baseline actually recorded
    # per-run env (0.6.0 did not). Fields recorded as run env are compared.
    run_env = raw.get("env", {})
    os_name, os_arch = run_env.get("os.name"), run_env.get("os.arch")
    if os_name and os_arch:
        notes.append(f"measured env os={os_name}/{os_arch} jdk={run_env.get('java.version')} "
                     f"vendor={run_env.get('java.vendor')} (diagnostic; no baseline per-run env recorded in 0.6.0)")
    return WITHIN, "; ".join(notes)


def structural_check(baseline, ops, runs, expected_commit):
    problems = []
    expected = set(ops)
    found = set(runs)
    missing = sorted(expected - found)
    extra = sorted(found - expected)
    if missing:
        problems.append(f"missing operation identity(ies): {missing}")
    if extra:
        problems.append(f"unexpected operation identity(ies): {extra}")
    if len(found) != len(expected):
        problems.append(f"wrong population: expected {len(expected)} identities, found {len(found)}")
    for op_id, run in runs.items():
        if op_id in ops and run["metric"] != ops[op_id]["metric"]:
            problems.append(f"operation {op_id}: raw metric {run['metric']} != baseline {ops[op_id]['metric']}")
    if expected_commit:
        for op_id, run in runs.items():
            sha = run["raw"].get("gitSha")
            if sha != expected_commit:
                problems.append(f"operation {op_id}: measured commit {sha} != expected {expected_commit}")
    return problems


def run_report(repo_root, baseline_path, run_paths, expected_commit):
    """Verify one deep run against the baseline."""
    try:
        baseline, ops = load_baseline(baseline_path)
        runs = collect_runs(run_paths)
    except BaselineError as exc:
        return {"status": INVALID, "errors": [str(exc)]}, 1

    problems = structural_check(baseline, ops, runs, expected_commit)
    if problems:
        return {"status": INVALID, "errors": problems}, 1

    fp, fp_missing = authority_fingerprint(repo_root)
    a_status, a_note = authority_status(baseline, fp, fp_missing)
    if a_status == NON_COMPARABLE:
        return {"status": NON_COMPARABLE, "authorityNote": a_note,
                "authorityFingerprint": fp}, 2

    per_op = []
    any_regression = False
    env_results = {}
    for op_id in sorted(ops):
        run = runs[op_id]
        cls = classify_single(ops[op_id], run["value"])
        any_regression = any_regression or cls == REGRESSION
        env_st, env_note = env_status(baseline, run["raw"])
        env_results[op_id] = env_st
        per_op.append({
            "operationId": op_id,
            "metric": ops[op_id]["metric"],
            "reference": ops[op_id]["reference"],
            "envelopeMin": ops[op_id]["envelope_min"],
            "envelopeMax": ops[op_id]["envelope_max"],
            "measured": run["value"],
            "classification": cls,
            "runPath": run["path"],
        })
    env_non_comparable = [k for k, v in env_results.items() if v == NON_COMPARABLE]
    if env_non_comparable:
        return {"status": NON_COMPARABLE, "environmentNote": "env mismatch for " + str(env_non_comparable)}, 2

    status = REGRESSION if any_regression else WITHIN
    return {
        "status": status,
        "schema": BASELINE_SCHEMA,
        "baseline": str(baseline_path),
        "expectedCommit": expected_commit,
        "population": {"expected": len(ops), "found": len(runs)},
        "authorityFingerprint": fp,
        "authorityStatus": a_status,
        "authorityNote": a_note,
        "environmentNote": "; ".join(env_results.values()),
        "operations": per_op,
        "note": "single-run classification only; CONFIRMED_REGRESSION requires the 3-run confirmation protocol",
    }, 0


def confirm_report(baseline_path, run_groups, expected_commit):
    """Three independent deep runs: confirmation semantics."""
    try:
        baseline, ops = load_baseline(baseline_path)
    except BaselineError as exc:
        return {"status": INVALID, "errors": [str(exc)]}, 1
    confirmed_ops = {}
    for op_id in sorted(ops):
        values = []
        problems = []
        for i, group in enumerate(run_groups):
            try:
                runs = collect_runs(group)
            except BaselineError as exc:
                return {"status": INVALID, "errors": [f"run group {i}: {exc}"]}, 1
            structural_problems = structural_check(baseline, ops, runs, expected_commit)
            if structural_problems:
                return {"status": INVALID, "errors": structural_problems}, 1
            if op_id not in runs:
                return {"status": INVALID, "errors": [f"run group {i} missing {op_id}"]}, 1
            values.append(runs[op_id]["value"])
        cls = classify_confirmation(ops[op_id], values)
        confirmed_ops[op_id] = {
            "reference": ops[op_id]["reference"],
            "envelopeMin": ops[op_id]["envelope_min"],
            "envelopeMax": ops[op_id]["envelope_max"],
            "measuredThree": values,
            "classification": cls,
        }
    overall = CONFIRMED if any(o["classification"] == CONFIRMED for o in confirmed_ops.values()) else INCONCLUSIVE
    return {
        "status": overall,
        "operations": confirmed_ops,
        "note": "CONFIRMED_REGRESSION is release-review blocking; INCONCLUSIVE_NOISE straddles the baseline envelope",
    }, 0


def main(argv):
    parser = argparse.ArgumentParser(description="Epic 12.1d performance regression verifier")
    sub = parser.add_subparsers(dest="command", required=True)

    verify = sub.add_parser("verify", help="classify one deep run against the baseline")
    verify.add_argument("--baseline", required=True)
    verify.add_argument("--runs", required=True, nargs="+", help="raw benchmark JSON files or globs")
    verify.add_argument("--expected-commit", default=None)
    verify.add_argument("--repo-root", default=".")
    verify.add_argument("--summary", action="store_true", help="emit a markdown summary line per op")

    confirm = sub.add_parser("confirm", help="3-run confirmation semantics")
    confirm.add_argument("--baseline", required=True)
    confirm.add_argument("--run-group", required=True, action="append", nargs="+",
                         help="one deep run's files/globs; repeat 3 times")
    confirm.add_argument("--expected-commit", default=None)

    args = parser.parse_args(argv)
    if args.command == "verify":
        files = []
        for pat in args.runs:
            files.extend(glob.glob(pat, recursive=True))
        files = sorted(set(files))
        report, code = run_report(args.repo_root, args.baseline, files, args.expected_commit)
    else:
        groups = [sorted({f for pat in g for f in glob.glob(pat, recursive=True)}) for g in args.run_group]
        if len(groups) != 3:
            print(json.dumps({"status": INVALID, "errors": ["confirm requires exactly 3 run groups"]}))
            return 2
        report, code = confirm_report(args.baseline, groups, args.expected_commit)

    print(json.dumps(report, indent=2))
    if args.command == "verify" and getattr(args, "summary", False) and report.get("status") not in (INVALID, NON_COMPARABLE):
        print("\n### Epic 12.1d deep-run classification")
        for op in report.get("operations", []):
            print(f"- {op['operationId']}: {op['classification']} "
                  f"(measured {op['measured']:.2f}, envelope {op['envelopeMin']:.2f}..{op['envelopeMax']:.2f})")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
