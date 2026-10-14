# Runtime Evidence Export Model

> **Status:** Design boundary — defines the shape and claim limits for exporting runtime decisions into evidence artifacts.
> **Phase:** Phase 5 — Runtime Evidence Export of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** Familiarity with the [Sovereign Lab Evidence Chain](../../examples/sovereign-lab/EVIDENCE-CHAIN.md), the [Approval Failure Taxonomy](../guides/approval-failure-taxonomy.md), and the [Governed Workflow Quickstart](../guides/governed-workflow-quickstart.md).

---

## What This Model Covers

This document defines the evidence shape for exporting TramAI runtime decisions into reviewable, digest-verifiable artifacts. It covers four event families, all implemented as exporters:

1. **Policy decisions** — allow/deny/require-approval outcomes produced by policy evaluation at runtime.
2. **Approval decisions** — approve/deny outcomes produced by the approval decision control plane.
3. **Provider routing decisions** — selected/fallback/blocked routes. The exporter below is implemented; **no live production producer is wired to it** (see [§C](#c-provider-routing-decisions-exporter-implemented--no-live-production-producer) and the [G4 task record](../roadmap/0.7.4/0.7.4b6-G4-PROVIDER-ROUTING-EVIDENCE.md)).
4. **Tool permission decisions** — allow/deny/require-approval outcomes for tool exposure, execution, and result reinjection.

The model defines the record shape, bundle placement, verification semantics, and privacy/sanitisation rules. All four exporters, the `RuntimeEvidenceBundleWriter`, and the bundle verifier are implemented. **Engine implementations and evidence producers are different things:** an exporter converts source records a caller supplies, and for `provider.route` the repository has not established a production path that supplies them.

---

## Runtime Evidence Record

Every exported runtime event follows a common record shape:

```json
{
  "schemaVersion": "runtime-evidence.v1",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "policy.decision",
  "workflowRunId": "wf-claim-123",
  "correlationId": "corr-claim-123",
  "actor": "policy-engine",
  "createdAt": "2026-07-08T12:00:00Z",
  "source": {
    "component": "policy-engine",
    "module": "claim-triage-policy"
  },
  "decision": {
    "kind": "ALLOW",
    "reasonCode": "local-route-allowed"
  },
  "digests": {
    "subjectDigest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "payloadDigest": "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
  },
  "metadata": {
    "classification": "RESTRICTED",
    "riskLevel": "HIGH",
    "providerName": "local-llama"
  }
}
```

### Field Constraints

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `schemaVersion` | Yes | `string` | Must be `"runtime-evidence.v1"`. The verifier rejects unknown versions. |
| `eventId` | Yes | `string` (UUID) | Unique identifier for the event. Must be globally unique across all event families. |
| `eventType` | Yes | `string` | One of the defined event family types. |
| `workflowRunId` | No | `string\|null` | Present when the decision is scoped to a workflow run. |
| `correlationId` | No | `string\|null` | Correlation identifier for cross-referencing. |
| `actor` | No | `string\|null` | Identity of the component or human that produced the decision. |
| `createdAt` | Yes | `string` (ISO-8601) | Timestamp of the decision. Validated with actual datetime parsing. |
| `source.component` | Yes | `string` | The component that produced the event. |
| `source.module` | No | `string\|null` | Optional sub-module or policy name. |
| `decision.kind` | Yes | `string` | The decision outcome. See event families below. |
| `decision.reasonCode` | No | `string\|null` | Sanitised reason code (no raw prompts, secrets, or medical details). |
| `digests.subjectDigest` | Yes | `string` | SHA-256 digest of the subject identifier. Must match `^sha256:[0-9a-f]{64}$`. |
| `digests.payloadDigest` | Yes | `string` | SHA-256 digest of the decision payload. Must match `^sha256:[0-9a-f]{64}$`. |
| `metadata` | No | `object` | Allowlisted key-value metadata. Must not contain raw prompts, tool arguments, secrets, or unbounded model output. |

### Excluded Fields

The following must never appear in a runtime evidence record:

- Raw prompts or model inputs
- Raw model output or tool call responses
- Secrets, credentials, API keys, tokens
- Medical details, PII, or other sensitive raw data
- Approval tokens, resume tokens, or replay envelopes
- Unbounded free-text comments (only `reasonCode` with allowlisted values is permitted)

---

## Event Families

### A. Policy Decisions

Policy decisions are produced by the policy evaluation engine when a tool or action is evaluated against the active policy rules.

| Field | Value |
|-------|-------|
| `eventType` | `"policy.decision"` |
| `source.component` | `"policy-engine"` |
| `decision.kind` | One of: `ALLOW`, `DENY`, `REQUIRE_APPROVAL` |

The current policy audit emitter records decision strings (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`), sanitised `reasonCode` values, and allowlisted metadata: `providerName`, `modelName`, `toolName`, `classification`, `classificationSource`, `riskLevel`, `fallbackProviderName`, plus explicit safe attributes such as `cacheReuse` and `fallbackReason`. Unknown attributes, including prompts, tool arguments, and secrets, are dropped.

**Source:** The existing policy audit behaviour in `tramai-security` produces these decisions at runtime. See the policy audit emitter for the current event shape.

### B. Approval Decisions

Approval decisions are produced by the `ApprovalDecisionControlPlane` when an operator or automation approves or denies a pending approval.

| Field | Value |
|-------|-------|
| `eventType` | `"approval.decision"` |
| `source.component` | `"approval-control-plane"` |
| `decision.kind` | One of: `APPROVED`, `DENIED` |

The existing approval decision control plane creates outbox records with event keys `approval-approved.<id>` and `approval-denied.<id>`, capturing actor, workflow run ID, correlation ID, approval status/version, and digest/length metadata. The raw decision comment is never stored — only its digest and length.

**Source:** The outbox records produced by `SovereignOpsApprovalDecisionControlPlane` (see [approval decision evidence tests](../../tramai-spring-boot-starter-sovereign-persistence-jdbc/src/test/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/JdbcSovereignOpsApprovalDecisionControlPlaneTest.kt)).

### C. Provider Routing Decisions (Exporter implemented — no live production producer)

The exporter is implemented and supported: `ProviderRoutingRuntimeEvidenceExporter` converts `ProviderRouteDecisionEvidenceSource` records a caller supplies into `provider.route` records, for all three decision kinds.

**The repository has not established a live production producer for this family.** No production call site constructs a source or invokes `ProviderRoutingRuntimeEvidenceExporter.export`; the only references in the tree are the exporter itself, its unit test, a bundle integration test, a benchmark and a build-logic preflight test. A caller can produce these records today, but nothing in the engine emits them on its own. The [G4 task record](../roadmap/0.7.4/0.7.4b6-G4-PROVIDER-ROUTING-EVIDENCE.md) carries the source-cited inventory and the wiring follow-up.

| Field | Value |
|-------|-------|
| `eventType` | `"provider.route"` |
| `source.component` | `"provider-router"` |
| `decision.kind` | `SELECTED`, `FALLBACK`, `BLOCKED` |

#### What each decision kind attests — and what it does not

The three kinds report **routing-authority decisions**, not execution outcomes. The distinctions below are the contract; the routing machinery collapses them unless the producer is careful, which is part of why the producer is not yet wired.

| Kind | Attests | Does **not** attest |
|------|---------|---------------------|
| `SELECTED` | The routing authority selected this route at the enforcement point, for this attempt or run. | That the provider was called, returned a response, or succeeded. Selection precedes dispatch. A `SELECTED` record with no terminal record says nothing about the outcome. |
| `FALLBACK` | Execution actually moved — or was authorized to move — away from one route to another according to the routing authority. | That a fallback was merely *configured*. A route order in `ProviderRoutingPlan` is not a fallback. `routeIndex > 0` alone is **not** proof a fallback occurred: it reports position in the configured order, so a run whose earlier candidates were excluded before any attempt can start at a later index. |
| `BLOCKED` | The routing authority refused an eligible route or reached a terminal refusal state. | That an exception occurred. Cancellation, ordinary provider transport failures after dispatch, and post-dispatch errors are not routing refusals and must not be recorded as `BLOCKED`. |

Reason-code mapping, stated per cause rather than by analogy: governance rejection → `policy-blocked`; model-registry rejection → `model-registry-blocked`; no viable or no authorized candidate → `no-route`; circuit-breaker rejection → `circuit-breaker-open`; provider transport failure → `provider-failure`; streaming startup failure → `streaming-startup-failure`. Only these six codes are allowlisted; anything else is normalised to the generic constant, so an unclassified refusal loses its cause rather than inventing one.

**Source:** supplied by the caller. Fixtures in the exporter's unit test, the benchmark, and the bundle integration test are **synthetic or manually supplied**; the `provider-routing.jsonl` file appearing in an example bundle shows the writer working on supplied records, and is **not** evidence of live routing capture. **The absence of `provider.route` records does not establish that no routing, fallback, or blocked decision occurred.**

### D. Tool Permission Decisions (Implemented)

Tool permission decisions are produced when the policy engine evaluates a tool at any enforcement point: before the tool is exposed to the model, before it is executed, or before its result is reinjected into the model context. All tool enforcement events are partitioned into the `tool.permission` family and excluded from `policy.decision`.

| Field | Value |
|-------|-------|
| `eventType` | `"tool.permission"` |
| `source.component` | `"policy-engine"` |
| `decision.kind` | `ALLOW`, `DENY`, `REQUIRE_APPROVAL` |

**Required metadata:**
- `toolName` — name of the tool being governed (non-blank string)
- `enforcementPoint` — one of `BEFORE_TOOL_EXPOSURE`, `BEFORE_TOOL_EXECUTION`, `BEFORE_TOOL_RESULT_REINJECTION`

**Optional metadata:**
- `riskLevel` — one of `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`
- `classification` — data classification label
- `classificationSource` — source of the classification

**Never includes:**
- Raw tool arguments, secrets, or API keys
- `REDACT_RESULT` or `ALLOW_INTERNAL_ONLY` decisions (not valid for tool.permission)
- Provider or model metadata

#### What a `tool.permission` decision attests — and what it does not

A `tool.permission` record is **evidence of an authorization decision at one enforcement point**. It is not
evidence that the operation ran, completed, succeeded, or changed external state. Those are different facts, and
the record carries only the first.

**Decision semantics (normative).**

| `decision.kind` | Attests | Does not attest |
|-----------------|---------|-----------------|
| `ALLOW` | The policy engine authorized the operation at the specified enforcement point. | Proof that execution started, completed or succeeded. |
| `DENY` | The policy engine refused permission at the specified enforcement point. | Proof about every possible execution path outside the governed boundary. |
| `REQUIRE_APPROVAL` | The policy engine required approval. | That approval was subsequently granted, or that execution resumed. |

**Three facts, kept apart.**

1. **Policy decision** — what the policy engine authorized or refused. This is the fact the record attests.
2. **Enforcement contract** — what a particular governed boundary is designed and tested to prevent. It is a
   property of that boundary and its tests, not of the record.
3. **Execution completion** — whether the operation ran, and what it returned. This is a **separate fact that
   `tool.permission` does not attest**, in either direction: a record's presence does not establish completion,
   and the absence of any completion evidence is not evidence of success *or* of failure.

**Why the decision is recorded before the effect.** A governed boundary records the authorization **before** it
invokes the guarded action, deliberately: if the authorization audit write cannot be accepted, the action must not
run at all (fail closed). One consequence is that an `ALLOW` record is produced both by an implementation whose
action then succeeded and by one whose action threw or failed after authorization — the record itself does not
distinguish those outcomes, and no reader may resolve the difference from it.

**Reserved metadata (framework-owned).** Two reserved key groups may accompany the family allowlist above. Both
are validated atomically — all keys of a group present, or none, which is legacy evidence — and both participate
in `digests.payloadDigest`:

- `identity.workloadId`, `identity.configurationId`, `identity.configurationVersion`, `identity.environmentId`,
  `identity.deploymentId` — the governed run identity; `workflowRunId` remains the single canonical run
  identifier and is deliberately not duplicated in metadata.
- `boundary.id` and `boundary.enforcementStrength` — the enforcement boundary a producer **declares**, and the
  strength its registration grants it. Strength is resolved from the framework-owned registration; a
  caller-supplied strength, or an unregistered identifier, is refused rather than accepted or downgraded.

A declared boundary does **not** become a verified origin through this: **a producer-declared boundary identifier
and its registered strength do not independently verify the event's origin**, and the strength is scoped to the
declared enforcement point only — it says nothing about any other path by which the host application might reach
the same side effect. Verified event-origin attribution is separate, unimplemented work and is not established by
this model.

---

## Evidence Bundle Placement

Runtime evidence records are placed in the sovereign evidence bundle under a `runtime-evidence/` directory by `RuntimeEvidenceBundleWriter`:

```
runtime-evidence/
  policy-decisions.jsonl     # Newline-delimited JSON policy decision records
  approval-decisions.jsonl   # Newline-delimited JSON approval decision records
  provider-routing.jsonl     # Newline-delimited JSON provider routing records
  tool-permissions.jsonl     # Newline-delimited JSON tool permission records
```

A human-readable summary could also be generated:

```
runtime-decisions.md   # Human-readable summary of runtime decisions
```

**JSONL** (newline-delimited JSON) is recommended for machine-verifiable runtime events: each line is a complete JSON record, making the file streamable and appendable. The human-readable `.md` parallel is optional and can be generated from the JSONL data.

No sub-manifest is placed under `runtime-evidence/`. The root `manifest.json` at the bundle level records the file digests of every artifact, including runtime evidence files.

---

## Verification Semantics

### What the Verifier Should Check

- Each JSONL line is valid JSON.
- Each record matches the `runtime-evidence.v1` schema version.
- Required fields (`schemaVersion`, `eventId`, `eventType`, `createdAt`, `source.component`, `decision.kind`, `digests.subjectDigest`, `digests.payloadDigest`) are present and non-null.
- `eventType` is one of the known event family types.
- `decision.kind` is valid for the given `eventType`.
- Digest strings match `^sha256:[0-9a-f]{64}$`.
- Records do not contain forbidden raw fields (prompts, secrets, tokens, raw comments).
- The evidence bundle manifest includes the expected `runtime-evidence/` files.
- File digests in the bundle manifest match the actual file contents.

### What the Verifier Must NOT Claim

- The decision was correct (legally, medically, financially, or otherwise).
- The policy was legally valid or compliant.
- The human reviewer was qualified or had sufficient context.
- The model output was true or accurate.
- The system is production-ready.
- The evidence proves EU AI Act or any regulatory compliance.
- The evidence chain replaces an audit.
- An `ALLOW` record proves the operation executed, completed or succeeded — or, conversely, that the absence of a
  completion record means the operation failed. `tool.permission` attests the authorization decision only.

This matches the existing evidence-chain boundary documented in the [Sovereign Lab Evidence Chain](../../examples/sovereign-lab/EVIDENCE-CHAIN.md): the verifier checks manifests, paths, sizes, digests, and claim boundaries, but does **not** verify evidence truth, compliance, production readiness, security certification, or audit sufficiency.

---

## Privacy and Sanitisation

All runtime evidence records must follow the same sanitisation rules already applied by the existing policy audit emitter and approval decision control plane:

- **No raw prompts:** The model input that triggered the decision is never included.
- **No raw tool arguments:** The arguments passed to a gated tool are never included.
- **No secrets or credentials:** API keys, tokens, and credentials are never included.
- **No raw decision comments:** Approval decision comments are represented by digest and length only.
- **No medical details or PII:** Subject identifiers are digests; reason codes are allowlisted.
- **Allowlisted metadata only:** Only explicitly configured metadata keys are exported.

These rules are not optional. A verifier that encounters raw sensitive data in a runtime evidence record should reject the record and flag a sanitisation violation.

---

## Non-Claims

- Evidence records prove that a decision was recorded with a specific outcome. They do not prove the decision was correct, compliant, or sufficiently reviewed.
- Bundle artifacts are local, reviewable evidence artifacts — they are not production deployments, compliance submissions, or certification materials.
- The bundle verifier checks structural integrity and digest consistency. It does not verify correctness of recorded decisions.
- An `ALLOW` record attests authorization, not execution completion: it does not establish that the operation ran, succeeded, or changed external state, and it does not let a reader infer either outcome from its absence.
- `provider.route` is a **supported record family without a live production producer**. The exporter converts caller-supplied sources; nothing in the engine emits these records yet (G4, 0.7.4b6). Records present in tests, benchmarks or example bundles are synthetic or manually supplied.
- A successful route selection does **not** prove the provider returned a response. The family reports routing decisions, and the outcome of the call that followed is a separate fact this family does not attest.
- The absence of `provider.route` records does **not** establish that no routing, fallback or blocked decision occurred.

---

## Future Implementation Plan

The following PRs (in order) will implement the runtime evidence export defined by this model:

| PR | Scope | Status |
|----|-------|--------|
| #184 | Implement policy decision exporter: capture `policy.decision` events during workflow execution and write to `policy-decisions.jsonl` | ✅ Implemented |
| #185 | Implement approval decision exporter: capture `approval.decision` events from the outbox store and write to `approval-decisions.jsonl` | ✅ Implemented |
| #186 | Implement provider routing exporter: capture `provider.route` events | ⚠️ Exporter implemented; **producer not wired** — see [G4](../roadmap/0.7.4/0.7.4b6-G4-PROVIDER-ROUTING-EVIDENCE.md) |
| #187 | Map runtime events to evidence bundle sections | ✅ Documented |
| #199 | Add verifier rules for runtime evidence records | ✅ Implemented |
| #199 | Integrate runtime evidence into the sovereign lab evidence bundle lifecycle | ✅ Implemented |
| #200 | Add dedicated tool.permission runtime evidence family | ✅ Implemented |

All four exporters and the bundle writer/verifier are implemented. No further PRs are required for the core runtime evidence lifecycle.

Automatic background evidence exporting and scheduled polling of approval outboxes remain unimplemented and are out of scope for 0.5.0.

---

## Where to Look Next

| Topic | Link |
|-------|------|
| Runtime Evidence Bundle Map | [Bundle Map](runtime-evidence-bundle-map.md) |
| Sovereign Lab Evidence Chain | [EVIDENCE-CHAIN.md](../../examples/sovereign-lab/EVIDENCE-CHAIN.md) |
| Approval Failure Taxonomy | [Failure Taxonomy](../guides/approval-failure-taxonomy.md) |
| Approval decision evidence tests | [JdbcSovereignOpsApprovalDecisionControlPlaneTest](../../tramai-spring-boot-starter-sovereign-persistence-jdbc/src/test/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/JdbcSovereignOpsApprovalDecisionControlPlaneTest.kt) |
| Governed Workflow Troubleshooting | [Troubleshooting Guide](../guides/governed-workflow-troubleshooting.md) |
