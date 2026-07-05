# Sovereign Lab Evidence Chain

The sovereign lab evidence chain is a local, reproducible workflow for capturing, finalizing, verifying, reviewing, and packaging evidence from a TramAI sovereign lab run.

It is designed for local evidence capture and reviewer handoff.

It does **not** certify production readiness, prove regulatory compliance, validate evidence truth, sign artifacts, or replace an audit. The evidence chain does not certify production readiness, prove compliance, or guarantee performance.

---

## Lifecycle

```
create → fill → finalize → verify → readiness → review → package → extract → re-verify
```

| Step | Tool / Document | Purpose |
|------|----------------|---------|
| create | `create-evidence-bundle.sh` | Creates evidence bundle scaffold |
| fill | Manual operator step | Captures command output and evidence |
| finalize | `finalize-evidence-bundle.sh` | Refreshes manifest digests after evidence is filled |
| verify | `verify-evidence-bundle.sh` | Checks manifest, files, digests, sizes, paths, and claim boundary |
| readiness | `RELEASE-READINESS.md` | Defines release-candidate evidence criteria |
| review | `REVIEWER-GUIDE.md` | Explains reviewer inspection and safe interpretation |
| package | `package-evidence-bundle.sh` | Creates `.tar.gz` archive and `.sha256` sidecar |
| extract | Reviewer / operator step | Extracts archive |
| re-verify | `verify-evidence-bundle.sh` | Verifies extracted bundle |

---

## Machine-Verified Properties

The verifier checks:

- `manifest.json` is valid JSON
- schema version and bundle type are expected
- required files exist
- file paths are safe relative paths
- `files[]` entries are unique
- `manifest.json` does not digest itself
- SHA-256 digests match file contents
- file sizes match file contents
- copied reports are included and checked
- claim-boundary flags remain safe

---

## Not Verified

The evidence chain does **not** verify:

- evidence truth
- legal compliance
- EU AI Act conformity
- production readiness
- security certification
- model quality
- benchmark guarantees
- cloud impossibility beyond captured evidence
- operator identity
- audit sufficiency

---

## Claim Boundary

The finalized manifest preserves this `claimBoundary`:

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

---

## Evidence Artifacts

| Artifact | Description |
|----------|-------------|
| `manifest.json` | Machine-readable manifest |
| `MANIFEST.md` | Human-readable manifest |
| `command-log.md` | Commands and outputs |
| `environment.md` | Host and runtime environment |
| `run-log.md` | Runtime notes |
| `approval-flow.md` | Human approval evidence |
| `restart-proof.md` | Restart / continuation evidence |
| `jdbc-persistence.md` | Persistence evidence |
| `no-cloud-proof.md` | Local / no-cloud evidence |
| `benchmark.md` | Optional benchmark notes |
| `reports/` | Copied generated reports |
| `.tar.gz` | Optional archive handoff format |
| `.tar.gz.sha256` | Archive transfer-integrity checksum |

---

## Verification Commands

```bash
./gradlew verifySovereignLabEvidenceBundle
./gradlew verifySovereignLabProfile
./gradlew verifySovereignLabRuntimeSmoke
./gradlew verifySovereignRuntimeApiBoundary
./gradlew verifySovereignRuntimeClosureDocs
./gradlew verifySovereignRuntimeReleaseCandidate
./gradlew check
```

---

## Release Readiness

A verified bundle is not automatically production-ready.

Use [RELEASE-READINESS.md](./RELEASE-READINESS.md) to decide whether a sovereign lab run is sufficient as release-candidate evidence.

---

## Reviewer Handoff

Use [REVIEWER-GUIDE.md](./REVIEWER-GUIDE.md) when giving a finalized or archived bundle to a reviewer.

The reviewer guide explains how to inspect evidence files, validate checksums, run the verifier, and avoid unsupported claims.

---

## Related

- [README.md](./README.md) — lab profile setup and quick start
- [EVIDENCE.md](./EVIDENCE.md) — evidence capture guide
- [RELEASE-READINESS.md](./RELEASE-READINESS.md) — release readiness checklist
- [REVIEWER-GUIDE.md](./REVIEWER-GUIDE.md) — reviewer-facing guide for inspecting finalized bundles
