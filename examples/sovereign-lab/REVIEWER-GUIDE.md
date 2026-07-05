# Sovereign Lab Evidence Bundle Reviewer Guide

This guide explains how to inspect a finalized TramAI sovereign lab evidence bundle.

It helps reviewers verify bundle structure, manifest metadata, file digests, and claim-boundary flags.

This guide does not certify production readiness, prove legal compliance, prove EU AI Act conformity, or replace an audit.

For the full evidence chain overview, see [EVIDENCE-CHAIN.md](./EVIDENCE-CHAIN.md).

---

## 1. Reviewer Inputs

A reviewer should receive a finalized evidence bundle directory containing:

- `manifest.json`
- required evidence markdown files
- optional copied reports under `reports/`

The bundle should already have been finalized with:

```bash
examples/sovereign-lab/finalize-evidence-bundle.sh <bundle-dir>
```

and should be verifiable with:

```bash
examples/sovereign-lab/verify-evidence-bundle.sh <bundle-dir>
```

---

## 2. Verify the Bundle

Run:

```bash
examples/sovereign-lab/verify-evidence-bundle.sh <bundle-dir>
```

Expected success output:

```
Evidence bundle verified: ...
```

If verification fails, treat the bundle as **not review-ready**.

The verifier intentionally rejects malformed bundles, unsafe paths, duplicate file metadata, weakened claim-boundary flags, missing files, and digest mismatches.

---

## 3. What Verification Checks

The verifier checks:

- `manifest.json` is valid JSON
- `schemaVersion` is supported
- `bundleType` is `sovereign-lab-evidence-bundle`
- required evidence files exist
- file paths are safe relative paths
- `manifest.json` does not digest itself
- every `files[]` entry matches file SHA-256 and size
- copied reports listed in `files[]` have not changed since finalization
- claim-boundary flags remain safe

---

## 4. What Verification Does Not Check

The verifier does **not** check:

- whether the evidence is true
- whether evidence is complete
- whether commands were actually run by the claimed operator
- whether benchmark results are representative
- whether a model is safe or high quality
- whether cloud access was impossible
- whether the setup is production-ready
- whether the system is legally or regulatorily compliant
- whether the evidence satisfies an external audit standard

---

## 5. Review manifest.json

Inspect:

| Field | Expected |
|-------|----------|
| `schemaVersion` | `1` |
| `bundleType` | `sovereign-lab-evidence-bundle` |
| `createdUtc` | Present |
| `finalizedUtc` | Present after finalization |
| `generator` | `examples/sovereign-lab/create-evidence-bundle.sh` |
| `finalizer` | `examples/sovereign-lab/finalize-evidence-bundle.sh` |
| `requiredFiles` | Non-empty |
| `files` | Non-empty file inventory |
| `claimBoundary` | Safe claim flags |

---

## 6. Claim Boundary Review

The reviewer should confirm this exact claim boundary:

```json
{
  "localEvidenceScaffold": true,
  "certifiesProductionReadiness": false,
  "definesPerformanceGuarantees": false,
  "runsLocalModel": false,
  "runsBenchmark": false,
  "validatesEvidenceTruth": false
}
```

Interpretation:

- Bundle verification is **structural and tamper-evidence only**.
- Local model execution is **separate and opt-in**.
- Benchmark execution is **separate and opt-in**.
- The bundle does **not** certify production readiness.
- The bundle does **not** validate the truth of evidence.

---

## 7. Evidence Files to Inspect

Review these files manually:

| File | Review Focus |
|------|-------------|
| `command-log.md` | Commands run, timestamps, outputs, failures |
| `environment.md` | Host environment, Java, Gradle, OS, local setup |
| `run-log.md` | Runtime behavior notes |
| `approval-flow.md` | Human approval / denial flow |
| `restart-proof.md` | Restart and continuation behavior |
| `jdbc-persistence.md` | Persistence behavior |
| `no-cloud-proof.md` | Local/no-cloud evidence |
| `benchmark.md` | Optional benchmark notes |
| `reports/` | Generated reports included at finalization |

---

## 8. Reviewer Decision

A bundle can be considered **structurally review-ready** if:

- `verify-evidence-bundle.sh` succeeds
- `manifest.json` contains `finalizedUtc`
- all required evidence files exist
- copied reports are included in `files[]`
- the claim boundary is safe
- the evidence files are filled enough for human review

A bundle should be **rejected or returned for correction** if:

- verification fails
- `finalizedUtc` is missing
- evidence files are empty placeholders
- copied reports are missing from `files[]`
- claim-boundary flags are weakened
- docs or notes imply certification, compliance, or production readiness

---

## 9. Safe Reviewer Statement

A reviewer may say:

> This evidence bundle was structurally verified against its manifest. The verifier checked required files, safe paths, file digests, file sizes, copied report integrity, and claim-boundary flags. The bundle is tamper-evident from finalization time.

A reviewer should **not** say:

> This evidence bundle certifies production readiness, proves legal compliance, proves EU AI Act conformity, validates evidence truth, or guarantees benchmark performance.

---

## Reviewing an Archived Bundle

If the reviewer receives a `.tar.gz` archive, validate the checksum first:

```bash
cd <archive-directory>
sha256sum -c <bundle>.tar.gz.sha256
```

Then extract:

```bash
tar -xzf <bundle>.tar.gz
```

Then run:

```bash
examples/sovereign-lab/verify-evidence-bundle.sh <extracted-bundle-dir>
```

The archive checksum confirms archive transfer integrity. The bundle verifier confirms manifest and file integrity after extraction.

Archive export does not sign, certify, or validate evidence truth.
