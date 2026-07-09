# Runtime Evidence Bundle Map

> **Status:** Mapping — defines where runtime-evidence.v1 records live in the sovereign evidence bundle and how reviewers, verifiers, and export tools should interpret them.
> **Phase:** Phase 5 — Runtime Evidence Export of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** [Runtime Evidence Export Model](runtime-evidence-export-model.md), [Sovereign Lab Evidence Chain](../../examples/sovereign-lab/EVIDENCE-CHAIN.md).

---

## Purpose

The three runtime evidence exporters introduced in #184–#186 produce runtime-evidence.v1 JSONL records. This document maps those records to their evidence bundle locations so reviewers, verifiers, and downstream export tools can locate them without guessing.

---

## Bundle Placement

Runtime evidence artifacts live under a dedicated `runtime-evidence/` directory in the sovereign evidence bundle:

```
runtime-evidence/
  manifest.json                # Optional sub-manifest for the runtime evidence directory
  policy-decisions.jsonl       # Machine-verifiable policy decision records
  approval-decisions.jsonl     # Machine-verifiable approval decision records
  provider-routing.jsonl       # Machine-verifiable provider routing records
```

A human-readable summary **may** be derived from the JSONL files as:

```
runtime-decisions.md           # Reviewer-facing summary (derived from JSONL, not a competing source of truth)
```

The bundle-level `manifest.json` (at the root of the evidence bundle) records the file digests of every artifact present. A separate `runtime-evidence/manifest.json` can later describe sub-file semantics but is not required at this stage.

---

## Event Family Mapping

Each event family maps to exactly one JSONL file:

| Event family | `eventType` | JSONL file | Exporter |
|---|---|---|---|
| Policy decisions | `policy.decision` | `runtime-evidence/policy-decisions.jsonl` | `PolicyDecisionRuntimeEvidenceExporter` (#184) |
| Approval decisions | `approval.decision` | `runtime-evidence/approval-decisions.jsonl` | `ApprovalDecisionRuntimeEvidenceExporter` (#185) |
| Provider routing | `provider.route` | `runtime-evidence/provider-routing.jsonl` | `ProviderRoutingRuntimeEvidenceExporter` (#186) |

---

## File Responsibilities

### policy-decisions.jsonl

**Expected decision kinds:** `ALLOW`, `DENY`, `REQUIRE_APPROVAL`

Each line records a single policy evaluation outcome. The exported record includes the schema version, event ID, decision kind, sanitised reason code, source component (`"policy-engine"`), policy version, workflow/correlation identifiers, `sha256` digests, and allowlisted metadata.

**Safe metadata examples:**
- `providerName`
- `modelName`
- `toolName`
- `classification`
- `classificationSource`
- `riskLevel`
- `fallbackProviderName`
- `attr_cacheReuse`
- `attr_fallbackReason`

**Never includes:**
- Raw prompts
- Raw tool arguments
- Secrets or credentials
- Full policy truth claims

### approval-decisions.jsonl

**Expected decision kinds:** `APPROVED`, `DENIED`

Each line records a single human approval decision outcome from the approval decision control plane outbox. The exported record includes the schema version, source component (`"approval-control-plane"`), actor, workflow/correlation identifiers, `sha256` digests, and safe metadata.

**Safe metadata examples:**
- `approvalVersion`
- `reasonDigest` (SHA-256 of reason)
- `reasonLength`
- `outboxStatus`
- `eventKeyDigest` (SHA-256 of eventKey, not the raw key)

**Never includes:**
- Raw approval ID
- Raw event key
- Raw decision comment
- Approval tokens, resume tokens, or replay envelopes

### provider-routing.jsonl

**Expected decision kinds:** `SELECTED`, `FALLBACK`, `BLOCKED`

Each line records a single provider route decision. The exported record includes the schema version, source component (`"provider-router"`), workflow/correlation identifiers, `sha256` digests, and safe metadata.

**Safe metadata examples:**
- `requestedModelDigest`
- `selectedProviderDigest`
- `selectedModelDigest`
- `previousProviderDigest`
- `previousModelDigest`
- `fallbackReason` (one of six allowlisted codes)
- `routeIndex`
- `attempt`

**Never includes:**
- Raw provider names
- Raw model names

**Allowlisted fallback/block reason codes:**
`provider-failure`, `streaming-startup-failure`, `circuit-breaker-open`, `model-registry-blocked`, `policy-blocked`, `no-route`.

Unknown reason codes are normalised to the generic `"provider-fallback"` or `"provider-blocked"` constant.

### runtime-decisions.md (optional reviewer summary)

A human-readable Markdown summary **may** be generated from the three JSONL files. It must be derived from the JSONL data, not manually edited as a competing source of truth.

---

## Reviewer Interpretation

Reviewers examining runtime evidence records should treat them as process artifacts, not correctness proofs:

| A reviewer **may** conclude | A reviewer **must not** conclude |
|---|---|
| A runtime decision record exists in the expected file | The decision was legally, medically, or financially correct |
| The record has the expected `runtime-evidence.v1` schema version | The policy was compliant |
| Decision digests match the `sha256:<64 hex>` format | The model output was true or accurate |
| Forbidden raw fields (prompts, secrets, tokens) are absent | The human reviewer was qualified |
| Bundle file digests match the manifest entries | The system is production-ready |
| | The evidence is auditor-level sufficient |

This matches the evidence-chain boundary: verifiers check manifests, paths, sizes, digests, and claim boundaries. They do **not** verify evidence truth, compliance, production readiness, security certification, or audit sufficiency.

---

## Verifier Responsibilities

A bundle verifier that inspects `runtime-evidence/` should:

- Confirm each expected JSONL file exists if the exporter was active.
- Confirm every line is valid JSON and matches the `runtime-evidence.v1` schema.
- Confirm `eventType` is one of `policy.decision`, `approval.decision`, or `provider.route`.
- Confirm `decision.kind` is valid for the given `eventType`.
- Confirm digest strings match `^sha256:[0-9a-f]{64}$`.
- Confirm records do not contain forbidden raw fields (prompts, secrets, tokens, raw comments).
- Confirm file digests in the bundle `manifest.json` match the actual JSONL file contents.

A verifier must **not** claim:

- The decision was correct.
- The policy was legally valid.
- The model output was true.
- The system is production-ready or certified.

---

## Privacy and Sanitisation

All runtime evidence records follow the sanitisation rules defined in the [Runtime Evidence Export Model](runtime-evidence-export-model.md):

- No raw prompts or model inputs
- No raw tool arguments
- No secrets, credentials, API keys, or tokens
- No raw decision comments (only digest + length)
- Policy records may include allowlisted `providerName` / `modelName`; provider routing records use digest form only for provider and model identifiers
- Only allowlisted metadata keys

A verifier that encounters raw sensitive data in a runtime evidence record must reject the record and flag a sanitisation violation.

---

## Non-Claims

- This document maps JSONL files to bundle locations. It does not generate bundle files.
- It does not define runtime wiring, export triggers, or scheduling.
- It does not add verifier enforcement rules.
- It does not modify the bundle creation, finalisation, or verification scripts.
- Evidence records prove that a decision was recorded with a specific outcome. They do not prove the decision was correct, compliant, or sufficiently reviewed.
- Bundle artifacts are local, reviewable evidence artifacts — not production deployments, compliance submissions, or certification materials.

---

## Where to Look Next

| Topic | Link |
|---|---|
| Runtime Evidence Export Model | [runtime-evidence-export-model.md](runtime-evidence-export-model.md) |
| Sovereign Lab Evidence Chain | [EVIDENCE-CHAIN.md](../../examples/sovereign-lab/EVIDENCE-CHAIN.md) |
| Policy Decision Evidence Exporter | `PolicyDecisionRuntimeEvidenceExporter` in `tramai-security` (#184) |
| Approval Decision Evidence Exporter | `ApprovalDecisionRuntimeEvidenceExporter` in `tramai-spring-boot-starter-sovereign-ops` (#185) |
| Provider Routing Evidence Exporter | `ProviderRoutingRuntimeEvidenceExporter` in `tramai-engine` (#186) |
