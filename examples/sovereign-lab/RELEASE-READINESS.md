# Sovereign Lab Release Readiness Checklist

This checklist defines the minimum evidence required before treating a sovereign lab run as release-candidate evidence.

It does not certify production readiness, legal compliance, benchmark guarantees, security compliance, or regulatory conformity.

For the full evidence chain overview, see [EVIDENCE-CHAIN.md](./EVIDENCE-CHAIN.md).

---

## 1. Required Verification Commands

Run all required repository verification gates:

```bash
./gradlew verifySovereignRuntimeReleaseCandidate
./gradlew verifySovereignLabProfile
./gradlew verifySovereignLabRuntimeSmoke
./gradlew verifySovereignLabEvidenceBundle
./gradlew verifySovereignRuntimeApiBoundary
./gradlew verifySovereignRuntimeClosureDocs
./gradlew check
```

All commands must pass before the evidence bundle can be considered release-candidate evidence.

---

## 2. Evidence Bundle Lifecycle

A valid evidence bundle follows this lifecycle:

```
create → fill → finalize → verify
```

Required commands:

```bash
examples/sovereign-lab/create-evidence-bundle.sh

# Fill generated evidence files and copy reports into reports/

examples/sovereign-lab/finalize-evidence-bundle.sh \
  examples/sovereign-lab/build/evidence-bundles/<timestamp>

examples/sovereign-lab/verify-evidence-bundle.sh \
  examples/sovereign-lab/build/evidence-bundles/<timestamp>
```

The verifier must print:

```
Evidence bundle verified: ...
```

---

## 3. Required Evidence Files

The finalized evidence bundle must contain:

| File | Purpose |
|------|---------|
| `README.md` | Bundle overview |
| `MANIFEST.md` | Human-readable bundle manifest |
| `manifest.json` | Machine-readable manifest |
| `command-log.md` | Commands executed and results |
| `environment.md` | Host, Java, Gradle, OS, and local environment |
| `run-log.md` | Runtime execution notes |
| `approval-flow.md` | Human approval / denial evidence |
| `restart-proof.md` | Restart and continuation proof |
| `jdbc-persistence.md` | JDBC persistence proof |
| `no-cloud-proof.md` | Local/no-cloud boundary evidence |
| `benchmark.md` | Optional benchmark notes |
| `reports/` | Generated reports copied into the bundle |

`manifest.json` must be finalized and verified after evidence files are filled.

---

## 4. Required Claim Boundary

The finalized `manifest.json` must preserve this claim boundary:

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

These values mean:

| Field | Meaning |
|-------|---------|
| `localEvidenceScaffold` | The bundle is a local evidence scaffold |
| `certifiesProductionReadiness` | The bundle does not certify production readiness |
| `definesPerformanceGuarantees` | The bundle does not define performance guarantees |
| `runsLocalModel` | Bundle verification does not run a local model |
| `runsBenchmark` | Bundle verification does not run benchmarks |
| `validatesEvidenceTruth` | Bundle verification does not validate evidence truth |

---

## 5. Allowed Claims

The evidence bundle may support these claims:

- TramAI provides a sovereign lab evidence capture workflow.
- Evidence bundles are machine-readable through `manifest.json`.
- Evidence bundles are tamper-evident after finalization.
- The standalone verifier checks required files, file digests, file sizes, safe paths, and claim-boundary flags.
- The Gradle verification proves clean, edited, finalized, copied-report, and tampered-bundle behavior.
- Local model and benchmark execution are opt-in and separate from bundle verification.

---

## 6. Forbidden Claims

Do **not** claim that the evidence bundle:

- certifies production readiness,
- proves legal or regulatory compliance,
- proves EU AI Act conformity,
- proves security certification,
- proves evidence truth,
- defines benchmark guarantees,
- proves model quality,
- proves cloud isolation beyond the captured local evidence,
- replaces an audit,
- replaces a risk assessment,
- replaces legal review,
- replaces operational approval.

---

## 7. Release Candidate Blockers

A sovereign lab release candidate is **blocked** if:

- any required Gradle verification task fails,
- the evidence bundle cannot be finalized,
- the finalized evidence bundle cannot be verified,
- `manifest.json` contains weakened claim-boundary flags,
- any required evidence file is missing,
- copied reports are not included in `files[]`,
- post-finalization tampering is not detected,
- malformed or unsafe manifests are accepted by the verifier or finalizer.
- docs imply certification, production readiness, benchmark guarantees, or evidence truth validation.

---

## 8. Final Checklist

Before treating a sovereign lab run as release-candidate evidence:

- [ ] Required Gradle verification commands pass.
- [ ] Evidence bundle is created.
- [ ] Evidence files are filled.
- [ ] Generated reports are copied into `reports/`.
- [ ] Bundle is finalized.
- [ ] Bundle verifier passes.
- [ ] `manifest.json` contains the expected claim boundary.
- [ ] `files[]` includes copied reports.
- [ ] Post-finalization tamper detection has been tested.
- [ ] No production-readiness or certification claims are made.

After the bundle is finalized and verified, reviewers should use [REVIEWER-GUIDE.md](./REVIEWER-GUIDE.md) to inspect the evidence files and interpret what the verified bundle does and does not prove.

### Optional Handoff Checks

- [ ] Optional archive export was created only after bundle verification passed.
- [ ] Extracted archive contents were verified again before handoff.

Archive export does not sign, certify, or validate evidence truth.
