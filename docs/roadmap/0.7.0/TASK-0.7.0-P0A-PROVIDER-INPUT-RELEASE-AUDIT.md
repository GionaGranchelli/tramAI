# TASK-0.7.0-P0A — Provider-Input Release Enforcement Audit

**Target:** `release/0.7.0`  
**Working branch:** `task/0.7.0-p0-provider-input-release-audit`  
**Scope:** Audit of `docs/ROADMAP-0.7.0-RELEASE-CUT.md` (P0.2, P0.5, P0.6, P0.12), Epic 0.7.2, and Epic 0.7.3 against the actual merged implementation.

---

## Executive Summary & Definition of Done

> **Can an authorized provider receive input before the required provider-specific release/minimization decision has been successfully enforced?**

**YES.**

While binary zone-and-classification release gating (`ProviderInputRelease.refusalFor`) is enforced during `CandidateAuthorization` before any provider is selected or invoked, **provider-specific data-release/minimization (`ALLOW_RAW` vs `ALLOW_WITH_MINIMIZATION` vs `DENY`), canonical-input projection, and fail-closed minimization enforcement before provider transport do not exist**. An authorized provider receives the raw, unprojected `ModelRequest.messages` on both synchronous and streaming paths.

---

## Phase 1 — Source-Linked Execution-Boundary Map

### 1.1 Synchronous Execution Path
1. **Service Proxy / Engine Entry** -> `InvocationExecutionCoordinator.execute` (`tramai-engine/src/main/kotlin/dev/tramai/engine/invocation/InvocationExecutionCoordinator.kt#L455-L512`)
   - Dispatches `ReturnKind.STRING` / `UNIT` -> `RawResponseExecutionCoordinator.execute`
   - Dispatches `ReturnKind.STRUCTURED` -> `StructuredResponseExecutionCoordinator.execute`
2. **Governed vs Legacy Routing** -> `ProviderExecutionCoordinator.execute` (`tramai-engine/src/main/kotlin/dev/tramai/engine/provider/ProviderExecutionCoordinator.kt#L162-L183`)
   - Calls `beforeResolution.beforeResolution(...)` (`EnforcementPoint.BEFORE_PROVIDER_RESOLUTION`).
   - Resolves candidates via `routingPlan.resolveCandidates(request.operation.operation)`.
   - Branches on `if (governance == null || run == null) return executeLegacy(request, resolvedRoutes)` (`#L181-L183`).
3. **Classification, Authorization & Viability** -> `ProviderExecutionCoordinator.governedCandidateSet` (`#L347-L424`)
   - Resolves `workloadZone` (`#L355`).
   - Resolves classification & workload governance via `WorkloadGovernanceResolver.resolve(...)` (`#L369-L374`); throws `ProviderException` on `Refused` (`#L375-L377`).
   - Constructs `CandidateAuthorization(ProviderInputRelease(configuration.trustZonePolicy, configuration.rules), routingPlan)` (`#L360-L364`).
   - Calls `authorization.authorizedCandidates(...)` (`#L401-L406`), which invokes `ProviderInputRelease.refusalFor(workloadZone, classification, candidate.deployment.trustZone.category)` (`tramai-security/src/main/kotlin/dev/tramai/security/governance/CandidateAuthorization.kt#L284`).
   - Filters by circuit-breaker availability via `CandidateViability` (`#L410-L417`).
4. **Selection, Retry & Fallback Loop** -> `ProviderExecutionCoordinator.execute` (`#L197-L248`)
   - Selects next candidate from `remaining` via `selection.select(remaining, strategy)` (`#L200`).
   - Acquires circuit-breaker permit (`#L208`).
   - Calls `request.beforeRoute.beforeRoute()` (`#L224`).
   - Calls `attemptExecutor.execute(routeRequest(...))` (`#L225-L227`).
   - On failure, narrows `remaining` via `walk.continuationAfter(...)` (`#L232`) and enforces `EnforcementPoint.BEFORE_FALLBACK`.
5. **Provider Attempt & Transport** -> `ProviderAttemptExecutor.execute` (`tramai-engine/src/main/kotlin/dev/tramai/engine/provider/ProviderAttemptExecutor.kt#L85-L147`)
   - `startAttempt(request)` (`#L204-L230`): runs `operationInterceptor.interceptRequest(context, request.request.messages)` (`#L213`) and `authorizationService.authorize(...)` (`#L225`).
   - `beforeProviderInvocation.invoke(...)` (`EnforcementPoint.BEFORE_PROVIDER_INVOCATION`, `#L93`).
   - `requireVisionSupport(...)` (`#L96`).
   - `callProvider(...)` (`#L98`, `#L165-L182`) -> `callOnce(...)` -> `provider.chat(...)`.
   - `responseSanitizer.sanitize(...)` (`#L175-L181`) sanitizes **response** only (`DlpContentType.MODEL_OUTPUT`).

### 1.2 Streaming Execution Path
1. **Engine Entry** -> `InvocationExecutionCoordinator.execute` (`ReturnKind.STREAMING`, `#L500-L510`) -> `StreamingExecutionCoordinator.execute` (`tramai-engine/src/main/kotlin/dev/tramai/engine/streaming/StreamingExecutionCoordinator.kt`).
2. **Governed vs Legacy Routing** -> `StreamingExecutionCoordinator.execute` (`#L164`):
   - `if (governance == null || request.governedRun == null) executeLegacy(request) else executeGoverned(request)`.
3. **Governed Envelope** -> `GovernedProviderEnvelope` (`tramai-engine/src/main/kotlin/dev/tramai/engine/provider/GovernedProviderEnvelope.kt#L156`) constructs `CandidateAuthorization(ProviderInputRelease(governed.trustZonePolicy, governed.rules), input.routingPlan)`.
4. **Route Execution & Transport** -> `StreamingExecutionCoordinator.executeStreamingRoute` (`#L361-L390`):
   - `authorizeStreamingRoute(route, observation)` (`#L371`)
   - `beforeResponseReturn.enforce(...)` (`#L372`)
   - `toolExposureCoordinator.enforce(...)` (`#L373`)
   - `beforeInvocation.invoke(...)` (`#L374`)
   - `modelRequest = request.operation.toRequest(arguments, modelName = route.effectiveModelName)` (`#L387`)
   - `memoryInjectedRequest = modelRequest.copy(messages = request.memoryMessages)` (`#L388`)
   - `collectStreamingRouteChunks(...)` -> `streamCapable.stream(request)` (`#L492`).

### 1.3 Input Construction & Existing Sanitization
- `OperationDefinition.toRequest` (`tramai-engine/src/main/kotlin/dev/tramai/engine/TramaiEngine.kt#L879`) applies optional `promptSanitizer?.sanitize(rendered) ?: rendered` **before** provider selection, without deployment/trust-zone/policy awareness.
- `RuleBasedDlpInterceptor` (`tramai-security/src/main/kotlin/dev/tramai/security/RuleBasedDlpInterceptor.kt#L22-L28`) only supports `DlpContentType.MODEL_OUTPUT` and `DlpContentType.TOOL_RESULT`. There is no `PROVIDER_INPUT` DLP content type.

---

## Phase 2 — Release-Invariant Coverage Matrix

| # | Invariant | Status | Evidence & Source Locations |
|---|-----------|--------|-----------------------------|
| 1 | Classification occurs before provider eligibility is finalized | **VERIFIED** | `ProviderExecutionCoordinator.governedCandidateSet` (`#L368-L406`) resolves `WorkloadGovernanceResolver.resolve(...)` before `authorization.authorizedCandidates(...)`. Tested in `ProviderExecutionCoordinatorTest`. |
| 2 | Provider authorization and data-release authorization are distinct | **PARTIAL** | `CandidateAuthorization` (`tramai-security/.../CandidateAuthorization.kt#L284`) calls `ProviderInputRelease.refusalFor(...)` as one of several checks, and `ProviderInputReleaseTest` tests zone/classification agreement. However, there is no distinct post-selection data-release decision (`DENY \| ALLOW_RAW \| ALLOW_WITH_MINIMIZATION`) separate from candidate authorization. |
| 3 | Every supported governed invocation requires an explicit data-release result | **PARTIAL** | Binary zone/classification release is checked in `CandidateAuthorization` for governed runs. However, no explicit typed data-release result (`DENY \| ALLOW_RAW \| ALLOW_WITH_MINIMIZATION`) is produced or required before provider invocation. |
| 4 | Required minimization/redaction produces a provider-specific representation | **MISSING** | `ProviderInputRelease` (`ProviderInputRelease.kt#L31-L32`) explicitly states: *"This is a decision only. It does not select a provider, invoke one, transform the input, or minimize it"*. No `projection(canonicalInput, selectedDeployment, effectivePolicy)` exists. |
| 5 | Canonical input remains unchanged | **PARTIAL** | Canonical input is not mutated because no provider-specific projection/minimization is performed at all. Once projection is added, immutability of canonical input must be enforced and tested. |
| 6 | A changed provider deployment triggers a new release decision and projection | **PARTIAL** | On fallback, `ProviderExecutionCoordinator` selects from `remaining` (already filtered by `ProviderInputRelease` at candidate-set time). However, no per-deployment release decision or projection is recomputed at attempt/fallback time. |
| 7 | Failed required inspection or minimization prevents invocation | **MISSING** | No provider-input inspection/minimization step exists on `ProviderAttemptExecutor` or `StreamingExecutionCoordinator`, so it cannot fail closed before `provider.chat` / `streamCapable.stream`. |
| 8 | Safe evidence records release outcomes without leaking sensitive values | **PARTIAL** | `ReleaseRefusal` (`ProviderInputRelease.kt#L84-L90`) is safe, and `AuditEngineDlpRedactionAuditEmitter` exists for `MODEL_OUTPUT`/`TOOL_RESULT`, but no provider-input release/minimization evidence is emitted. |
| 9 | Neither synchronous nor streaming execution can bypass the release decision | **PARTIAL** | Neither path can bypass `CandidateAuthorization` (`ProviderInputRelease.refusalFor`) when `governance != null && governedRun != null`. Both paths bypass provider-specific minimization/projection because it does not exist. |
| 10 | Governed execution cannot silently downgrade to an ungoverned path | **VERIFIED** | `ProviderExecutionCoordinator.execute` (`#L181-L183`) and `StreamingExecutionCoordinator.execute` (`#L164`) branch structurally on `governance == null \|\| request.governedRun == null`; governed refusals throw and never fall back to `executeLegacy`. |

---

## Phase 3 — Adversarial Qualification Summary

- **Authorized provider, data-release denied (zone/classification):** Enforced at `CandidateAuthorization` via `ProviderInputRelease.refusalFor`; candidate is excluded from `authorizedSet` and never invoked.
- **Authorized provider requiring redaction / minimization:** **Bypasses.** No `ALLOW_WITH_MINIMIZATION` state or provider-input redaction exists; raw `ModelRequest` is sent to `provider.chat` / `streamCapable.stream`.
- **Required sanitizer unavailable or failing:** **Bypasses.** `promptSanitizer` is optional (`null` by default) and runs pre-selection; `DlpInterceptor` is only wired for `MODEL_OUTPUT` and `TOOL_RESULT`.
- **Fallback from one trust zone to another:** Candidate eligibility is pre-filtered by `ProviderInputRelease`, but no per-deployment projection is recomputed.
- **Direct invocation / streaming divergence:** Both `ProviderAttemptExecutor` and `StreamingExecutionCoordinator` lack a pre-transport data-release/minimization gate.

---

## Phase 4 — Minimal Gap-Closure Plan (0.7.0 P0 Backlog)

1. **P0-A1: Extend `ProviderInputRelease` Decision Model (`tramai-security`)**
   - Introduce typed release outcome: `DENY`, `ALLOW_RAW`, `ALLOW_WITH_MINIMIZATION` (plus safe `ReleaseRefusal` / obligation metadata) derived from `EffectivePolicy` + `TrustZonePolicy` + `ClassificationRoutingRule`.
2. **P0-A2: Provider-Bound Projection & Minimization Seam (`tramai-security` / `tramai-engine`)**
   - Add `DlpContentType.PROVIDER_INPUT` (or dedicated `ProviderInputProjector`) to derive `providerBoundInput = projection(canonicalInput, selectedDeployment, effectivePolicy)` without mutating canonical input.
   - Fail closed (`DlpInspectionException` / `ProviderException`) if minimization is required and unavailable or fails.
3. **P0-A3: Enforce at Both Sync & Streaming Transport Boundaries (`tramai-engine`)**
   - Invoke the release/projection seam per selected deployment in `ProviderAttemptExecutor` (sync) and `StreamingExecutionCoordinator.executeStreamingRoute` (streaming), recomputing on every fallback/retry deployment change.
4. **P0-A4: Safe Release Evidence (`tramai-security` / `tramai-engine`)**
   - Emit safe release/minimization outcome metadata (decision, rule/reason IDs, counts) without raw sensitive payloads.
5. **Deferred (0.8.0 / 0.9.0 / 0.10.0):**
   - Generalized enterprise DLP across all tools, telemetry, persistence, and learning boundaries (explicitly excluded from 0.7.0 P0.2).

---

## Verification

- `./gradlew :tramai-security:test :tramai-engine:test --no-daemon` — **PASSED** (VERIFIED).
