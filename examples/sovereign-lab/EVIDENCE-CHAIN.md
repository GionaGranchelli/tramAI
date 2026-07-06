# Sovereign Lab Evidence Chain

The sovereign lab evidence chain is a local, reproducible workflow for capturing, finalizing, verifying, reviewing, and packaging evidence from a TramAI sovereign lab run.

It is designed for local evidence capture and reviewer handoff.

It does not certify production readiness, prove regulatory compliance, validate evidence truth, sign artifacts, guarantee performance, or replace an audit.

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
| re-verify | `verify-evidence-archive.sh` or `verify-evidence-bundle.sh` | Verifies the archived or extracted bundle |

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
- archive export is deterministic: packaging the same finalized bundle twice produces the same archive SHA-256
- bundles do not contain symlinks

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
- archive signer identity

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
| `.tar.gz.sha256.sig` | Optional detached signature over the archive checksum sidecar |
| `verify-evidence-archive.sh` | Optional safe archive verification before extraction |
| `verify-evidence-archive-signature.sh` | Optional signature verification before archive verification |

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

For archived bundles, prefer the archive verifier:

```bash
examples/sovereign-lab/verify-evidence-archive.sh <bundle>.tar.gz
```

The archive verifier checks the sidecar, rejects unsafe archive entries, extracts into a temporary directory, and runs the bundle verifier.

The archive verifier parses checksum sidecars strictly: one SHA-256 digest and one archive filename per sidecar. It rejects archives that do not contain a single top-level bundle directory.

The Gradle evidence-bundle verification includes negative archive fixtures for checksum failures, unsafe paths, symlinks, hardlinks, special files, empty archives, multiple top-level directories, and malformed sidecars.

---

## Deferred: Archive Signing

Archive signing is intentionally **not** part of the current evidence chain.

The current archive checksum sidecar provides transfer integrity only when the expected checksum is trusted. It does not prove artifact origin, signer identity, reviewer approval, legal compliance, or certification.

### Optional Sidecar Signature Verification

If a reviewer receives a detached signature for the archive checksum sidecar, optional verification is available:

```bash
examples/sovereign-lab/verify-evidence-archive-signature.sh \
  examples/sovereign-lab/build/evidence-archives/<timestamp>.tar.gz \
  reviewer-public-key.pem
```

The script verifies `<archive>.tar.gz.sha256.sig` against `<archive>.tar.gz.sha256` using the caller-supplied public key, then runs the existing archive verifier. This proves the checksum sidecar was signed by the holder of the matching private key — it does not prove operator identity, evidence truth, regulatory compliance, or production readiness.

See [ARCHIVE-SIGNING.md](./ARCHIVE-SIGNING.md) for the full signing boundary.

---

## Related

- [README.md](./README.md) — lab profile setup and quick start
- [EVIDENCE.md](./EVIDENCE.md) — evidence capture guide
- [RELEASE-READINESS.md](./RELEASE-READINESS.md) — release readiness checklist
- [REVIEWER-GUIDE.md](./REVIEWER-GUIDE.md) — reviewer-facing guide for inspecting finalized bundles
