#!/usr/bin/env python3
"""Epic 12.1d — classification-semantics tests (synthetic fixtures, stdlib only).

Covers: reference/envelope computation from recorded runs, single-run
candidate semantics for latency and throughput, the 3-run confirmation
protocol (all-worse => CONFIRMED_REGRESSION, straddle => INCONCLUSIVE_NOISE),
structural fail-closed checks, authority fingerprint behaviour, and env
compatibility. Run: python3 test_performance_regression_verifier.py
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
import performance_regression_verifier as v  # noqa: E402

EXPECTED_OP_IDS = [f"B{i:02d}" for i in range(1, 12)]  # B01..B11 = 11 ids -> make 12 via B11-empty/loaded? see below


def op_record(op_id, metric, runs):
    """runs: list of 3 reference values (p50 for latency, mean for throughput)."""
    unit = "microseconds" if metric == "latency" else "ops/sec"
    return {
        "operationId": op_id,
        "module": "tramai-test",
        "fixture": f"fixture-{op_id}",
        "metric": metric,
        "unit": unit,
        "runs": {f"run{i + 1}": {"runId": f"r{i}", "startedUtc": "2026-01-01T00:00:00Z",
                                 "runner": "GitHub Actions", "hostname": f"h{i}",
                                 "p50": runs[i] if metric == "latency" else None,
                                 "mean": runs[i] if metric == "throughput" else None}
                 for i in range(3)},
    }


def synthetic_baseline(op_meta, env_extra=None):
    """op_meta: {op_id: (metric, [3 recorded ref values])}."""
    baseline = {
        "schema": v.BASELINE_SCHEMA,
        "methodology": "test",
        "measurementCommit": "0123456789abcdef",
        "measurementRuns": [{"runId": f"r{i}", "startedUtc": "2026-01-01T00:00:00Z",
                             "outcome": "success", "populationComplete": True} for i in range(3)],
        "environment": {"note": "test", "runnerLabel": "GitHub Actions",
                        **(env_extra or {})},
        "policy": "test",
        "operations": [op_record(op_id, metric, runs) for op_id, (metric, runs) in op_meta.items()],
    }
    return baseline


def raw_run(op_id, metric, value, extra_env=None, commit="0123456789abcdef"):
    if metric == "latency":
        samples_ns = [int(value * 1000)] * 10
        samples_ns[0] += 1  # make nearest-rank p50 == value
        raw = {"operation": op_id, "module": "tramai-test", "fixture": "f",
               "gitSha": commit, "timestamp": "2026-01-01T00:00:00Z",
               "iterationOverride": None, "samplesNs": samples_ns,
               "unit": "microseconds",
               "env": {"java.version": "21.0.1", "java.vendor": "Eclipse Adoptium",
                       "os.name": "Linux", "os.arch": "amd64", "hostname": "runner-x",
                       "runnerLabel": "GitHub Actions", **(extra_env or {})}}
        return raw
    samples = [value] * 5
    raw = {"operation": op_id, "module": "tramai-test", "fixture": "f",
           "gitSha": commit, "timestamp": "2026-01-01T00:00:00Z",
           "iterationOverride": None, "samplesOpsPerSec": samples,
           "unit": "ops/sec",
           "env": {"java.version": "21.0.1", "java.vendor": "Eclipse Adoptium",
                   "os.name": "Linux", "os.arch": "amd64", "hostname": "runner-x",
                   "runnerLabel": "GitHub Actions", **(extra_env or {})}}
    return raw


def write_json(path, obj):
    Path(path).write_text(json.dumps(obj), encoding="utf-8")


def two_op_meta():
    return {
        "B01-service-proxy-creation": ("latency", [100.0, 110.0, 120.0]),
        "B11-worker-polling-loaded": ("throughput", [30.0, 25.0, 20.0]),
    }


class BaselineEnvelopeTest(unittest.TestCase):
    def test_reference_is_median_and_envelope_is_minmax(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "baseline.json"
            write_json(p, synthetic_baseline(two_op_meta()))
            _, ops = v.load_baseline(str(p))
            self.assertEqual(ops["B01-service-proxy-creation"]["reference"], 110.0)
            self.assertEqual(ops["B01-service-proxy-creation"]["envelope_min"], 100.0)
            self.assertEqual(ops["B01-service-proxy-creation"]["envelope_max"], 120.0)
            self.assertEqual(ops["B11-worker-polling-loaded"]["reference"], 25.0)
            self.assertEqual(ops["B11-worker-polling-loaded"]["envelope_min"], 20.0)
            self.assertEqual(ops["B11-worker-polling-loaded"]["envelope_max"], 30.0)


class SingleRunClassificationTest(unittest.TestCase):
    def test_latency_within_improvement_regression(self):
        meta = two_op_meta()["B01-service-proxy-creation"]
        self.assertEqual(v.classify_single({"metric": "latency", "envelope_min": 100.0,
                                            "envelope_max": 120.0}, 110.0), v.WITHIN)
        self.assertEqual(v.classify_single({"metric": "latency", "envelope_min": 100.0,
                                            "envelope_max": 120.0}, 99.0), v.IMPROVEMENT)
        self.assertEqual(v.classify_single({"metric": "latency", "envelope_min": 100.0,
                                            "envelope_max": 120.0}, 121.0), v.REGRESSION)

    def test_throughput_inverted(self):
        self.assertEqual(v.classify_single({"metric": "throughput", "envelope_min": 20.0,
                                            "envelope_max": 30.0}, 25.0), v.WITHIN)
        self.assertEqual(v.classify_single({"metric": "throughput", "envelope_min": 20.0,
                                            "envelope_max": 30.0}, 31.0), v.IMPROVEMENT)
        self.assertEqual(v.classify_single({"metric": "throughput", "envelope_min": 20.0,
                                            "envelope_max": 30.0}, 19.0), v.REGRESSION)

    def test_single_run_can_never_be_confirmed(self):
        meta = {"metric": "latency", "envelope_min": 100.0, "envelope_max": 120.0}
        self.assertNotEqual(v.classify_single(meta, 500.0), v.CONFIRMED)


class ConfirmationProtocolTest(unittest.TestCase):
    def test_all_three_worse_than_worst_boundary_confirms_latency(self):
        meta = {"metric": "latency", "envelope_min": 100.0, "envelope_max": 120.0}
        self.assertEqual(v.classify_confirmation(meta, [121.0, 130.0, 125.0]), v.CONFIRMED)

    def test_straddle_is_inconclusive_latency(self):
        meta = {"metric": "latency", "envelope_min": 100.0, "envelope_max": 120.0}
        self.assertEqual(v.classify_confirmation(meta, [121.0, 110.0, 115.0]), v.INCONCLUSIVE)

    def test_all_three_worse_confirms_throughput(self):
        meta = {"metric": "throughput", "envelope_min": 20.0, "envelope_max": 30.0}
        self.assertEqual(v.classify_confirmation(meta, [19.0, 15.0, 18.0]), v.CONFIRMED)

    def test_one_improvement_makes_inconclusive(self):
        meta = {"metric": "throughput", "envelope_min": 20.0, "envelope_max": 30.0}
        self.assertEqual(v.classify_confirmation(meta, [19.0, 25.0, 18.0]), v.INCONCLUSIVE)

    def test_requires_exactly_three(self):
        meta = {"metric": "latency", "envelope_min": 100.0, "envelope_max": 120.0}
        with self.assertRaises(ValueError):
            v.classify_confirmation(meta, [121.0, 122.0])


class StructuralFailClosedTest(unittest.TestCase):
    def test_missing_identity_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_json(td / "baseline.json", synthetic_baseline(two_op_meta()))
            write_json(td / "run1.json", raw_run("B01-service-proxy-creation", "latency", 110.0))
            report, code = v.run_report(".", str(td / "baseline.json"), [str(td / "run1.json")],
                                        expected_commit="0123456789abcdef")
            self.assertEqual(report["status"], v.INVALID)
            self.assertNotEqual(code, 0)
            self.assertTrue(any("missing" in e and "B11" in e for e in report["errors"]))

    def test_duplicate_identity_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_json(td / "baseline.json", synthetic_baseline(two_op_meta()))
            r1 = td / "a.json"
            r2 = td / "b.json"
            write_json(r1, raw_run("B01-service-proxy-creation", "latency", 110.0))
            write_json(r2, raw_run("B01-service-proxy-creation", "latency", 115.0))
            report, code = v.run_report(".", str(td / "baseline.json"), [str(r1), str(r2)],
                                        expected_commit="0123456789abcdef")
            self.assertEqual(report["status"], v.INVALID)
            self.assertTrue(any("duplicate" in e for e in report["errors"]))

    def test_malformed_json_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_json(td / "baseline.json", synthetic_baseline(two_op_meta()))
            (td / "bad.json").write_text("{not json", encoding="utf-8")
            report, code = v.run_report(".", str(td / "baseline.json"), [str(td / "bad.json")],
                                        expected_commit=None)
            self.assertEqual(report["status"], v.INVALID)

    def test_wrong_measured_commit_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_json(td / "baseline.json", synthetic_baseline(two_op_meta()))
            for op_id, metric, value in (("B01-service-proxy-creation", "latency", 110.0),
                                         ("B11-worker-polling-loaded", "throughput", 25.0)):
                write_json(td / f"{op_id}.json", raw_run(op_id, metric, value, commit="deadbeef"))
            report, code = v.run_report(".", str(td / "baseline.json"),
                                        [str(td / f"{op_id}.json") for op_id, _, _ in
                                         (("B01-service-proxy-creation", "latency", 0),
                                          ("B11-worker-polling-loaded", "throughput", 0))],
                                        expected_commit="0123456789abcdef")
            self.assertEqual(report["status"], v.INVALID)
            self.assertTrue(any("measured commit" in e for e in report["errors"]))

    def test_incompatible_schema_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            b = synthetic_baseline(two_op_meta())
            b["schema"] = "tramai-performance-baseline.v2"
            write_json(td / "baseline.json", b)
            report, code = v.run_report(".", str(td / "baseline.json"), [],
                                        expected_commit=None)
            self.assertEqual(report["status"], v.INVALID)

    def test_wrong_population_size_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            meta = {"B01-service-proxy-creation": ("latency", [100.0, 110.0, 120.0])}
            write_json(td / "baseline.json", synthetic_baseline(meta))
            report, code = v.run_report(".", str(td / "baseline.json"), [],
                                        expected_commit=None)
            self.assertEqual(report["status"], v.INVALID)
            self.assertTrue(any("wrong population" in e for e in report["errors"]))


class AuthorityFingerprintTest(unittest.TestCase):
    def test_authority_changed_is_non_comparable_when_recorded(self):
        baseline = synthetic_baseline(two_op_meta())
        baseline["benchmarkAuthorityFingerprint"] = "old-fingerprint"
        self.assertEqual(v.authority_status(baseline, "old-fingerprint", [])[0], v.WITHIN)
        self.assertEqual(v.authority_status(baseline, "new-fingerprint", [])[0], v.NON_COMPARABLE)

    def test_missing_authority_file_is_non_comparable(self):
        status, _ = v.authority_status({}, "hex", ["tramai-testing/.../BenchmarkHarness.kt"])
        self.assertEqual(status, v.NON_COMPARABLE)

    def test_absent_recorded_fingerprint_assumes_comparable(self):
        status, note = v.authority_status({}, "hex", [])
        self.assertEqual(status, v.WITHIN)
        self.assertIn("pre-12.1d", note)


class EnvCompatibilityTest(unittest.TestCase):
    def test_runner_label_mismatch_is_non_comparable(self):
        baseline = synthetic_baseline(two_op_meta())
        raw = raw_run("B01-service-proxy-creation", "latency", 110.0,
                      extra_env={"runnerLabel": "SomeOtherRunner"})
        status, _ = v.env_status(baseline, raw)
        self.assertEqual(status, v.NON_COMPARABLE)

    def test_hostname_never_invalidates(self):
        baseline = synthetic_baseline(two_op_meta())
        raw = raw_run("B01-service-proxy-creation", "latency", 110.0,
                      extra_env={"hostname": "different-runner-99"})
        status, _ = v.env_status(baseline, raw)
        self.assertEqual(status, v.WITHIN)


class EndToEndVerifyTest(unittest.TestCase):
    def test_healthy_single_run_is_within(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_json(td / "baseline.json", synthetic_baseline(two_op_meta()))
            write_json(td / "a.json", raw_run("B01-service-proxy-creation", "latency", 110.0))
            write_json(td / "b.json", raw_run("B11-worker-polling-loaded", "throughput", 25.0))
            report, code = v.run_report(".", str(td / "baseline.json"),
                                        [str(td / "a.json"), str(td / "b.json")],
                                        expected_commit="0123456789abcdef")
            self.assertEqual(code, 0)
            self.assertEqual(report["status"], v.WITHIN)
            classes = {o["operationId"]: o["classification"] for o in report["operations"]}
            self.assertEqual(classes["B01-service-proxy-creation"], v.WITHIN)
            self.assertEqual(classes["B11-worker-polling-loaded"], v.WITHIN)


class EndToEndConfirmTest(unittest.TestCase):
    def test_three_bad_runs_confirm(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_json(td / "baseline.json", synthetic_baseline(two_op_meta()))
            groups = []
            for val in (130.0, 140.0, 135.0):
                g = []
                write_json(td / f"a{val}.json", raw_run("B01-service-proxy-creation", "latency", val))
                write_json(td / f"b{val}.json", raw_run("B11-worker-polling-loaded", "throughput", 15.0))
                g = [str(td / f"a{val}.json"), str(td / f"b{val}.json")]
                groups.append(g)
            report, code = v.confirm_report(str(td / "baseline.json"), groups,
                                            expected_commit="0123456789abcdef")
            self.assertEqual(code, 0)
            self.assertEqual(report["status"], v.CONFIRMED)
            self.assertEqual(report["operations"]["B01-service-proxy-creation"]["classification"], v.CONFIRMED)
            self.assertEqual(report["operations"]["B11-worker-polling-loaded"]["classification"], v.CONFIRMED)

    def test_straddle_is_inconclusive_not_confirmed(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_json(td / "baseline.json", synthetic_baseline(two_op_meta()))
            groups = []
            for a_val, b_val in ((130.0, 15.0), (110.0, 25.0), (135.0, 16.0)):
                write_json(td / f"a{a_val}.json", raw_run("B01-service-proxy-creation", "latency", a_val))
                write_json(td / f"b{b_val}.json", raw_run("B11-worker-polling-loaded", "throughput", b_val))
                groups.append([str(td / f"a{a_val}.json"), str(td / f"b{b_val}.json")])
            report, code = v.confirm_report(str(td / "baseline.json"), groups,
                                            expected_commit="0123456789abcdef")
            self.assertEqual(code, 0)
            self.assertEqual(report["status"], v.INCONCLUSIVE)
            self.assertEqual(report["operations"]["B01-service-proxy-creation"]["classification"], v.INCONCLUSIVE)


if __name__ == "__main__":
    unittest.main(verbosity=2)
