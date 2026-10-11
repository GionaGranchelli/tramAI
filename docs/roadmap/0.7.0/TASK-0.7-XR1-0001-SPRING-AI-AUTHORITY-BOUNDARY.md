# TASK 0.7-XR1-0001 — Spring AI authority boundary (Phase 1: boundary map)

**Checkpoint:** `0.7-XR1` — External Runtime Authority Proof
**Slice scope:** ALLOW, DENY, REQUIRE_APPROVAL *without continuation*, and governance-unavailable at one
declared external boundary. Durable approval continuation is **not** in this slice.
**Base:** `release/0.7.0` at `a6a43ae7` (epic 0.7.3 integrated).
**Status:** **complete** — Phases 2–5 implemented and verified (evidence in the Phase 2 section below).
Approval continuation is deferred by owner decision. The XR1 **checkpoint** is *not* complete (see the
checkpoint's own exit criteria), and nothing in this document claims otherwise.

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

---

# Phase 2 — one real external execution path

**Branch:** `task/0.7-xr1-0001-spring-ai-boundary` (base `a6a43ae7`).
**Slice scope unchanged:** ALLOW, DENY, REQUIRE_APPROVAL-without-continuation, governance-unavailable.
Durable approval continuation is **not** implemented here and remains a separate qualification step.

## Phase 1 items, now resolved

| Was UNRESOLVED | Resolution |
| --- | --- |
| Spring AI version and callback API shape | **VERIFIED — Spring AI `1.0.9`.** `org.springframework.ai.tool.ToolCallback` declares `getToolDefinition()`, `getToolMetadata()`, `call(String)` and `call(String, ToolContext)`. Established with `javap` against the resolved jar, not from documentation. |
| Whether interception precedes the effect on every invocation route | **PARTIAL, and the boundary is declared accordingly.** Spring AI dispatches instrumented tools through the registered `ToolCallback`, so wrapping it prevents the effect on that path. Any route that does not go through the registered callback is *not* governed by this boundary, and nothing here claims otherwise. |
| `PolicyEngine` behaviour when configuration is unavailable | **VERIFIED by test:** the engine throws; the adapter catches it, records it, refuses with `governance-unavailable`, and never delegates. |

## Correction to a Phase 1 assumption

Phase 1 stated evidence would be projected by `GovernanceDecisionEnvelope.toRuntimeEvidenceRecord`. That
is **wrong for this boundary** and is recorded as withdrawn rather than quietly changed:
`GovernanceDecisionOutcome.of(decision: Any)` maps only the candidate families
(`CandidateAuthorizationDecision`, `CandidateViabilityDecision`, `CandidateSelectionDecision`), and
`GovernanceDecisionEvidence.kt` contains zero references to the core `PolicyDecision` used at
`BEFORE_TOOL_EXECUTION`. Evidence therefore goes through the existing **`tool.permission`** family and
`ToolPermissionRuntimeEvidenceExporter` — the repository's own vocabulary for this enforcement point.

## What this slice adds

1. `examples/spring-ai-authority-boundary/` — one example module, registered in `settings.gradle.kts`.
   Spring AI is an example-only dependency; no shipped module depends on it.
2. `GovernedToolCallback` — the declared boundary. Every decision comes from `PolicyEngine.evaluate` at
   `EnforcementPoint.BEFORE_TOOL_EXECUTION`. Both `call` overloads are overridden, because governing only
   the single-argument overload would leave the context-carrying path a bypass. ALLOW delegates exactly
   once; DENY and REQUIRE_APPROVAL return a structured refusal; an evaluation failure refuses without
   delegating. The adapter contains no policy logic.
3. `BoundaryEvidenceRecorder` — evidence through the existing hash-chained `AuditEngine` and the existing
   `tool.permission` exporter. A governance *availability* failure is recorded as `DENY` with reason code
   `governance-unavailable` so the operation counts as refused while the reason stays distinguishable,
   inside the existing vocabulary rather than a second one.
4. `SideEffectfulToolCallback` — the governed tool. Its observable effect is its invocation ledger, so
   "prevented" is distinguishable from "observed".
5. `StubToolRequestingChatModel` + `FrameworkDispatchTest` — a deterministic local test provider (the
   substitute the checkpoint permits), driven through Spring AI's own tool-calling machinery
   (`ToolCallingManager`, whose production implementation is `DefaultToolCallingManager` — the class
   `ChatClient` delegates to). The enforcement boundary is *not* substituted. See the measured
   limitations below for why the manager is driven directly rather than through `ChatClient`.

## Checkpoint proofs → evidence

Twenty proofs across four suites: seven boundary proofs, two on Spring AI's dispatch path, seven
adversarial-suite cases (one positive control plus six weakened implementations), and four
evidence-integrity cases added after review of head `d7d10d05`.

| # | Proof | Evidence |
| --- | --- | --- |
| 1 | ALLOW executes and correlates | `an allowed action executes exactly once and correlates to the authoritative decision`; on the framework path `an allowed tool executes exactly once on the framework dispatch path` |
| 2 | DENY does not execute | `a denied action is never invoked and returns a structured refusal`; `a denied tool is prevented on the framework dispatch path` |
| 3 | No execution before approval | `an action requiring approval cannot execute without one`. Continuation after approval: **not implemented in this slice** (owner-deferred) |
| 4 | Identity continuity | `the governed run identity and the caller correlation survive the boundary` |
| 5 | Failure semantics | `an unavailable authority prevents execution and is never read as permission` |
| 6 | No second policy model | Structural: the adapter holds no policy rules and calls `PolicyEngine.evaluate` for every decision (VERIFIED by inspection of `GovernedToolCallback`) |
| 7 | Evidence provenance | `evidence identifies this boundary and is exported through the repository schema` — partial, see gaps |
| 8 | No projection bypass | Structural: authorization happens only through `PolicyEngine.evaluate`; the adapter writes no read model, control plane or UI state (VERIFIED by inspection) |

## Review of head `d7d10d05` — findings and fixes

The owner requested changes and confirmed two Copilot findings. All five were corrected at the source, not
argued away:

| Finding | Correction |
| --- | --- |
| `build.gradle.kts` declared an application entrypoint with no such class, so `:run` would fail while tests passed | `SpringAiBoundaryMain` added: `:run` now executes a real ALLOW and DENY demonstration through the framework's dispatch |
| `GovernanceBoundaryTest` asserted `metadata["enforcementPoint"] ?: BOUNDARY_ENFORCEMENT_POINT`, which passed when the metadata was absent | the fallback is gone; the assertion fails if the metadata is missing or wrong |
| Recorder and evaluator identities were supplied independently, so evidence could be attributed to a run that was not evaluated | the recorder is constructed from the same `BoundaryContext` the engine is evaluated with, so divergence is structurally impossible; negative tests assert attribution follows the context in both directions |
| ALLOW evidence was recorded after the tool ran, so a failed audit write could leave a completed side effect with no decision evidence | the authorization is recorded **before** the effect; a failing audit store now prevents execution entirely |
| Manual JSON serialization did not escape the full control-character range, so a reason could produce a malformed refusal | every JSON-required escape is applied, control characters included |

The review's test-count correction is adopted: the adversarial suite is one positive control plus six
weakenings, not seven separate weakenings.

## Gaps recorded, not papered over

- **Workload and configuration identity are not transported through the evaluator.** `PolicyContext`
  carries `workflowId`, `workflowRunId`, `correlationId`, `actorId` and `policyVersion`; it does not carry
  the deployment's `WorkloadId` or `WorkloadConfigurationIdentity`. The adapter holds that identity but
  cannot hand it to the evaluator. Passing the external workload id as `workflowId` would imply TramAI
  owns the host application's workflow, which the checkpoint forbids — so it was not done. **UNRESOLVED**;
  material for 0.7.4 identity work.
- **Evidence cannot self-identify the external runtime.** The `tool.permission` exporter admits a fixed
  metadata allowlist (`toolName`, `enforcementPoint`, `riskLevel`, `classification`,
  `classificationSource`); an integration identity cannot be added through it, and the exporter fails
  closed on unrecognised keys. The evidence therefore identifies the enforcement point and the governed
  operation but not "this was a Spring AI runtime". **UNRESOLVED**; a 0.7.4 evidence-provenance finding.
- **Approval continuation** is out of scope by owner decision, not by omission.

## Measured limitations of the dispatch path

- **`ChatClient.create(model)` dispatches no tools without a Spring application context.** Measured
  before the correction: the `ChatClient` path made exactly one model call and recorded zero decisions,
  even though the request carried `DefaultToolCallingChatOptions`, `internalToolExecutionEnabled=true`
  and the governed callback. Its tool-calling manager comes from the context, which this slice
  deliberately does not build (a starter is a checkpoint non-goal). The dispatch proofs therefore drive
  `ToolCallingManager` directly: the same machinery, the same `Prompt`/`ChatResponse`/`ToolCallback`
  types, no framework behaviour substituted. The invocation is still the framework's — the test does not
  call the boundary itself.
- **A first correction attempt is recorded as ruled out, not deleted.** It set the *model's* default
  options to tool-capable options. That is a genuine requirement for a tool-capable provider, but it was
  not the cause: the framework's gate reads the *prompt's* options, and those already satisfied it.

## Deliberate simplifications

- The Spring AI callback contract is synchronous, so the coroutine-based engine is bridged with
  `runBlocking`: one blocking hop per governed invocation. Marked in code with its ceiling and upgrade
  path (a suspend-aware framework callback contract).
- The example is not a starter and has no Spring `ApplicationContext`; it wires the engine directly, which
  is what makes the enforcement boundary inspectable.

