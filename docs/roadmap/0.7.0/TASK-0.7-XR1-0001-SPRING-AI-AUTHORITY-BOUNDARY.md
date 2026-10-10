# TASK 0.7-XR1-0001 — Spring AI authority boundary (Phase 1: boundary map)

**Checkpoint:** `0.7-XR1` — External Runtime Authority Proof
**Slice scope:** ALLOW, DENY and governance-unavailable at one declared external boundary.
REQUIRE_APPROVAL and durable continuation are **not** in this slice.
**Base:** `release/0.7.0` at `a6a43ae7` (epic 0.7.3 integrated).

## Purpose of this document

Deliver the boundary map before implementing any adapter: which existing TramAI component already owns
each authority, so XR1 reuses it instead of introducing a second policy model. Every row below is a
file that exists at the base commit; nothing here is proposed infrastructure.

| Boundary | Required finding | Existing owner (verified at `a6a43ae7`) |
| --- | --- | --- |
| Workload identity | Authoritative identity owner | `tramai-core/src/main/kotlin/dev/tramai/core/identity/GovernedRunIdentity.kt` — `WorkloadDeploymentIdentity + RunId`; scope and continuity in `tramai-core/src/main/kotlin/dev/tramai/core/identity/GovernedRunScope.kt`, `tramai-core/src/main/kotlin/dev/tramai/core/exception/GovernedRunContinuityException.kt` |
| Governance evaluation | Reusable policy/decision contract | `tramai-core/src/main/kotlin/dev/tramai/core/policy/PolicyEngine.kt` with `tramai-core/src/main/kotlin/dev/tramai/core/policy/PolicyContext.kt` and the `PolicyDecision` family; implemented deny-by-default by `tramai-security/src/main/kotlin/dev/tramai/security/DefaultPolicyEngine.kt` |
| Tool interception | Where execution can actually be prevented | `EnforcementPoint.BEFORE_TOOL_EXECUTION` (`tramai-core/src/main/kotlin/dev/tramai/core/policy/EnforcementPoint.kt`, line 20) — already a first-class TramAI enforcement point, alongside `BEFORE_TOOL_EXPOSURE` and `BEFORE_TOOL_RESULT_REINJECTION`. Native precedent: `tramai-engine/src/main/kotlin/dev/tramai/engine/invocation/ToolLoopCoordinator.kt` with `ToolExposureCoordinator` / `ToolReinjectionCoordinator` |
| Approval | Existing suspension/continuation authority | `tramai-security/src/main/kotlin/dev/tramai/security/approval/InMemoryApprovalContinuationStore.kt`, `tramai-security/src/main/kotlin/dev/tramai/security/approval/InMemoryApprovalRecoveryCoordinator.kt`; engine side `tramai-engine/src/main/kotlin/dev/tramai/engine/approval/ApprovalResumeCoordinator.kt`, `tramai-engine/src/main/kotlin/dev/tramai/engine/approval/ReplayAuthorizationService.kt`. **Out of scope for this slice** |
| Evidence | Existing decision correlation and provenance | `tramai-security/src/main/kotlin/dev/tramai/security/evidence/GovernanceDecisionEvidence.kt` — `GovernanceDecisionEnvelope` + `toRuntimeEvidenceRecord(at, actor)` → `RuntimeEvidenceRecord` (runtime-evidence.v1), provenance via `RuntimeEvidenceSource(component, module)`, correlation via caller-supplied `correlationId`/`eventId`, integrity via `subjectDigest`/`payloadDigest`. Writers and validators: `tramai-security/src/main/kotlin/dev/tramai/security/evidence/RuntimeEvidenceBundleWriter.kt`, `tramai-security/src/main/kotlin/dev/tramai/security/evidence/RuntimeEvidenceJsonlWriter.kt`, `tramai-security/src/main/kotlin/dev/tramai/security/evidence/RuntimeEvidenceContractValidator.kt` |
| Failure handling | How unavailable governance prevents execution | No fail-closed semantics exist in the policy path; the closest precedent is `tramai-security/src/main/kotlin/dev/tramai/security/evidence/ToolPermissionRuntimeEvidenceExporter.kt`, which **fails closed** when a recognised tool event lacks `metadata.toolName`. The external boundary must supply fail-closed itself: no explicit ALLOW ⇒ no execution |

## What XR1 reuses, unchanged

- `PolicyEngine.evaluate(PolicyContext)` as the **single** decision source, at
  `EnforcementPoint.BEFORE_TOOL_EXECUTION`. The adapter translates a Spring AI tool intent into a
  `PolicyContext` and honours the resulting `PolicyDecision`. It decides nothing itself.
- `GovernedRunIdentity` as the run identity — passed through, never re-minted.
- `GovernanceDecisionEnvelope.toRuntimeEvidenceRecord` for evidence. The reason code is derived from the
  typed decision inside the envelope, so the adapter cannot assert a reason the decision does not carry.
- The `tool.permission` evidence family and its allowed decisions (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`),
  whose metadata vocabulary (`toolName`, `enforcementPoint`, `riskLevel`, `classification`,
  `classificationSource`) already matches this boundary.

## What XR1 must add, and nothing more

1. One example module with a real Spring AI invocation path and one deterministic, side-effect-observable
   tool. Spring AI owns the application and the invocation; TramAI is consulted before the effect.
2. An adapter that wraps the declared tool callback, calls the existing engine, and enforces the outcome:
   ALLOW ⇒ delegate exactly once; DENY ⇒ do not invoke, return a structured refusal; evaluation
   unavailable/failed ⇒ do not invoke, no exception interpreted as permission.
3. A minimal, non-public marker that the evidence describes this boundary as **AUTHORITATIVE** rather than
   observed — checked: **no enforcement-strength representation exists today** (no `EnforcementStrength`
   or `AUTHORITATIVE` type in `tramai-security/src/main/kotlin`, verified by search at `a6a43ae7`). The
   checkpoint permits an internal/preview representation; this slice must not stabilise public API.

## Unresolved at the end of Phase 1

- Exact Spring AI version and tool-callback API shape to target: **UNRESOLVED** — the repository has no
  Spring AI dependency at any version (only prose references in `docs/`). To be settled in Phase 2 and
  recorded with the dependency coordinates.
- Whether Spring AI's own tool-calling manager permits intercepting **before** the effect for every
  invocation route, or only for the callback path this slice declares: **UNRESOLVED**, and the reason the
  boundary must be *declared* rather than assumed.
- Error semantics of `PolicyEngine.evaluate` when configuration is unavailable: **UNRESOLVED** — Phase 2
  must establish whether it throws, and the adapter must fail closed either way.

## Stop conditions for this slice

Stop and report an architecture blocker if enforcement turns out to require duplicating TramAI policy
here, if the action can bypass the declared interception boundary, or if preserving the run identity
requires TramAI to own the Spring AI workflow runtime. Do not paper over an authority mismatch with a
mock or a parallel policy model.

## Non-goals (unchanged from the checkpoint)

No production Spring Boot starter, no generic cross-framework adapter SPI, no new policy language, no
alternative approval engine, no dashboard, no Spring AI feature matrix, no public API stabilisation.
