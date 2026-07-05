# Sovereign Lab Evidence Archive Signing Boundary

This document defines the boundary between current archive checksum verification and future optional archive signing for sovereign lab evidence bundles.

---

## Current State

Sovereign lab archive export currently creates:

- `<timestamp>.tar.gz`
- `<timestamp>.tar.gz.sha256`

The checksum sidecar provides **transfer-integrity evidence**. It does **not** prove signer identity, operator identity, audit acceptance, production readiness, or regulatory compliance.

Deterministic archive packaging means the same finalized bundle always produces the same `.tar.gz` SHA-256. This allows reproducible verification but does not add cryptographic provenance.

---

## What a Checksum Sidecar Proves

| Property | Status |
|----------|--------|
| Archive transfer integrity | ✅ Detects accidental or malicious changes when the expected checksum is trusted |
| Deterministic archive hash | ✅ Same finalized bundle → same archive hash |
| Signer identity | ❌ Not provided |
| Operator identity | ❌ Not provided |
| Reviewer approval | ❌ Not provided |
| Audit acceptance | ❌ Not provided |
| Legal compliance | ❌ Not provided |
| Regulatory certification | ❌ Not provided |
| Production readiness | ❌ Not provided |

---

## Future Optional Signing Boundary

A future signing workflow may add detached signatures for archive checksum sidecars or archive manifests.

Potential future artifacts:

```
<timestamp>.tar.gz
<timestamp>.tar.gz.sha256
<timestamp>.tar.gz.sha256.sig
provenance.json
provenance.json.sig
```

This document defines the boundary only. It does not implement signing.

### Design Principles (Future)

If signing is added later, these principles should apply:

1. **Optional** — Existing unsigned archives remain valid. Signing is an additional trust layer, not a replacement.
2. **Detached** — Signatures are separate sidecar files, not embedded in the archive.
3. **No key management in this repo** — The signing workflow should reference externally managed keys, not store private keys in the repository.
4. **No implied certification** — A signed archive proves cryptographic origin, not correctness, compliance, audit acceptance, or production readiness.

---

## Non-Goals

- This document does not implement archive signing.
- This document does not generate cryptographic keys.
- This document does not define a key management or PKI workflow.
- This document does not certify production readiness, legal compliance, EU AI Act conformity, or regulatory certification.
- This document does not replace an audit.

---

## Related

- [EVIDENCE-CHAIN.md](./EVIDENCE-CHAIN.md) — evidence chain overview
- [REVIEWER-GUIDE.md](./REVIEWER-GUIDE.md) — reviewer guide for verifying finalized bundles
- [RELEASE-READINESS.md](./RELEASE-READINESS.md) — release readiness checklist
