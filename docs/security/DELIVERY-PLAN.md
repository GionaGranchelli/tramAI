# DELIVERY-PLAN.md — TramAI Enterprise Phase 1

Translates the Phase 1 roadmap into concrete epics, issues, and acceptance criteria.

## Implementation Notes — Engine Policy Hooks (PR 3) 🚧

**Where:** `tramai-engine/src/main/kotlin/dev/tramai/engine/PolicyEnforcementHelper.kt`

**How compatibility works in 0.4.x:**
- `TramaiEngine` accepts optional `PolicyEngine?` (defaults to `LegacyPermissivePolicyEngine`)
- When no explicit `PolicyEngine` is configured, all operations proceed with one migration warning using `java.util.logging`
- Migration guard is shared at engine scope (not per proxy)
- `LegacyPermissivePolicyEngine` is the 0.4.x compatibility fallback — allows all operations
- Deny-by-default enforcement will be introduced by `tramai-security` (future PR #4)

**Covered paths:**
- non-streaming raw execution (including cache hits with BEFORE_RESPONSE_RETURN enforcement)
- structured provider invocation (including cache hits and parsed response enforcement BEFORE persist/cache)
- tool loops (exposure, execution, reinjection)
- fallback (all 4 transition points: provider failure, streaming startup failure, circuit breaker open, route unavailable)
- streaming execution (cold Flow: policy gates inside flow{} for per-collection evaluation; BEFORE_RESPONSE_RETURN with providerId/modelName)
- raw response return
- structured response return (BEFORE_RESPONSE_RETURN before persistStructuredSuccess and cacheValue)
- circuit breaker open → BEFORE_FALLBACK enforcement before skipping

**Pending:**
- `BEFORE_WORKFLOW_RESUME` — orchestration integration (separate PR)

---

## PR 4: tramai-security Module + DefaultPolicyEngine ✅

**Module:** `tramai-security` (depends on `tramai-core`, no runtime deps)

**DefaultPolicyEngine:**
- Deny-by-default for unknown tools, models, providers, fallbacks
- HIGH/CRITICAL risk tools → RequireApproval
- RESTRICTED data → Deny for non-local providers
- BEFORE_WORKFLOW_RESUME → Explicit Deny (unimplemented)
- `PolicyConfiguration.preview()` — permissive default for 0.4.x
- `PolicyConfiguration.secure()` — deny-by-default for sovereign/1.0

**Integration:**
- `DefaultPolicyEngine` is available as an optional secure runtime
- `TramaiEngine` uses `LegacyPermissivePolicyEngine` when no explicit policy engine is supplied for 0.4.x backward compatibility
- Migration warning logged once per engine instance
- `LegacyPermissivePolicyEngine` available for explicit opt-in

**Implemented in feat/classified-request-egress (PR #5):**
Topic 1.6 ✅ — classified request context (via `ClassifiedDocument<T>`)
propagated through all provider invocation paths for raw, structured,
and streaming execution.

Topic 1.7 ✅ — classification egress enforced at `BEFORE_PROVIDER_INVOCATION`
and `BEFORE_RESPONSE_RETURN` via `evaluateClassificationEgress()`.

Implementation note:
- classification derives from `ClassifiedDocument<T>` wrapper
- no annotation-based classification exists
- no automatic rule-based classification exists yet
- ranking is exhaustive over the current enums; new values require an explicit rank
- among equal classifications, the least-authoritative source is retained for conservative audit metadata (authority order: DECLARED > RULE_BASED > LOCAL_MODEL_ASSISTED)

Remaining follow-up:
Topic 1.8 ✅ — closed in feat/secure-cache-provenance (PR #6). See `OperationResponseCache.kt`, `InMemoryOperationResponseCache.kt`, and the eight cache-provenance tests in `PolicyEnforcementTest.kt`.

---

## Epic 1: Policy Engine Core

**Goal:** Introduce `tramai-security` with deny-by-default policy enforcement at all 8 mandatory policy enforcement points.

### Issues

#### 1.1 — PolicyEngine SPI, PolicyDecision, and enums
- Create `PolicyEngine` interface, `PolicyContext`, `PolicyDecision` sealed interface in `tramai-core`
- Add `EnforcementPoint` enum (8 mandatory policy points; 7 active + `BEFORE_WORKFLOW_RESUME` deferred)
- Add `DataClassification`, `RiskLevel`, `ApprovalMode`, `ManagedNetworkEgress`, `AuditDetail`, `ProviderPolicy`, `CompatibilityMode` enums
- **Acceptance:** Interfaces compile, all enums have documented values

#### 1.2 — tramai-security module scaffold
- Create `tramai-security` Gradle module with policy/approval/audit packages
- Add to `tramai-bom`
- **Acceptance:** Module compiles, appears in BOM, no dependency on platform

#### 1.3 — DefaultPolicyEngine implementation
- Implement deny-by-default PolicyEngine
- Unknown tools → Deny
- Missing permissions → Deny
- Unregistered models → Deny
- **Acceptance:** All unknown operations denied; known operations require explicit allow rules

#### 1.4 — Enforcement hooks in TramaiEngine
- Add mandatory `policyEngine.evaluate()` calls at all active policy enforcement points (7 active + 1 deferred)
- Remove any code path that reaches provider/tool executor without evaluation
- Version-scoped migration: 0.3.x unchanged, 0.4.x opt-in with warning, `tramai-sovereign` always secure, 1.0 secure-by-default
- **Acceptance:** Cannot reach provider without policy evaluation; LEGACY_PERMISSIVE requires explicit config; sovereign profile enforces SECURE

#### 1.5 — TramaiTool security metadata and @AiTool adapter ✅
- Add `ToolSecurityMetadata` to `ResolvedTool` and wire into `TramaiEngine` enforcement hooks
- Provide legacy-permissive default for backward compatibility
- Implement `@AiTool` as optional convenience adapter that produces `TramaiTool` with security metadata
- **Acceptance:** Existing tools compile; security metadata accessible; @AiTool generates equivalent TramaiTool

#### 1.6 — Negative test suite
- Unknown tool → Deny (PolicyViolationException)
- Tool without permission → Deny
- Unregistered model → Deny
- RESTRICTED data → cloud provider → Deny
- Missing policy engine → fail-closed
- LEGACY_PERMISSIVE → all allowed
- **Acceptance:** All negative tests pass; every deny produces audit event

**Epic Exit Criteria:**
- [ ] Policy engine evaluates every provider and tool call
- [ ] Deny-by-default for all operations
- [ ] Negative test suite with 6+ scenarios passing
- [ ] LEGACY_PERMISSIVE mode for backward compatibility

---

## Epic 2: Data Classification and Provider Routing

**Goal:** Enforce classification-driven provider routing.

### Issues

#### 2.1 — ClassifiedDocument wrapper
- Implement `ClassifiedDocument<T>` data class
- Add `ClassificationSource` enum (DECLARED, RULE_BASED, LOCAL_MODEL_ASSISTED)
- **Acceptance:** ClassifiedDocument wraps any payload with classification metadata

#### 2.2 — Rule-based classification engine ✅
- Implement regex/metadata-based classification rules
- Configurable via application.yml or programmatic
- **Acceptance:** Document matching rule → correct classification

#### 2.3 — Provider routing by classification ✅
- RESTRICTED → LOCAL_ONLY, block cloud
- CONFIDENTIAL → LOCAL_ONLY or EU_ONLY
- INTERNAL → APPROVED_CLOUD
- PUBLIC → ANY_APPROVED
- **Acceptance:** Wrong provider for classification → Deny
- **Implementation:** `ProviderRoutingConfiguration`, `ProviderTrustZone`, `ClassificationRoutingRule` in `tramai-security`; `evaluateProviderRouting()` in `DefaultPolicyEngine` evaluated at `BEFORE_PROVIDER_INVOCATION`, `BEFORE_FALLBACK`, `BEFORE_RESPONSE_RETURN`.

#### 2.4 — No silent fallback ✅
- If local model unavailable, do NOT fall back to cloud for RESTRICTED data
- Configurable fallback policy per classification via `ClassificationRoutingRule.allowedFallbackZones`
- **Acceptance:** RESTRICTED + local model down → PolicyViolationException, not silent cloud call
- **Implementation:** Fallback zone set evaluated at `BEFORE_FALLBACK` enforcement point; RESTRICTED has `allowedFallbackZones = emptySet()` in sovereign defaults

## Implementation Notes — Classification-Aware Provider Routing Matrix (PR for Epic 2.3/2.4) ✅

**Where:** `tramai-security/.../ProviderRoutingConfiguration.kt`, `DefaultPolicyEngine.kt`

### Routing Model

Three new types in `tramai-security`:

- **`ProviderTrustZone`** — `LOCAL`, `EU_CLOUD`, `GLOBAL_CLOUD`
- **`ClassificationRoutingRule`** — `allowedZones` (primary) + `allowedFallbackZones` (fallback)
- **`ProviderRoutingConfiguration`** — `providerZones: Map<String, ProviderTrustZone>`, `rules: Map<DataClassification, ClassificationRoutingRule>`, `enabled: Boolean`

### Sovereign Default Routing Matrix

| Classification | allowedZones | allowedFallbackZones |
|---|---|---|
| RESTRICTED | LOCAL | (empty — no fallback) |
| CONFIDENTIAL | LOCAL, EU_CLOUD | LOCAL, EU_CLOUD |
| INTERNAL | LOCAL, EU_CLOUD, GLOBAL_CLOUD | LOCAL, EU_CLOUD, GLOBAL_CLOUD |
| PUBLIC | LOCAL, EU_CLOUD, GLOBAL_CLOUD | LOCAL, EU_CLOUD, GLOBAL_CLOUD |

### Enforcement Points

`evaluateProviderRouting()` is called at:
1. `BEFORE_PROVIDER_INVOCATION` — checks primary routing
2. `BEFORE_FALLBACK` — checks fallback routing (uses `allowedFallbackZones`)
3. `BEFORE_RESPONSE_RETURN` — re-checks on response return (cache reauthorization)

### Stable Reason Codes

- `classification-provider-missing` — classified request without provider ID
- `provider-zone-missing` — classified request where provider has no zone mapping
- `classification-routing-rule-missing` — classification has no routing rule
- `classification-routing-blocked` — provider zone not in `allowedZones`
- `classification-fallback-blocked` — provider zone not in `allowedFallbackZones`

### Compatibility Strategy

- `ProviderRoutingConfiguration.enabled = false` by default
- Legacy `trustedLocalProviders` and `allowCloudForClassifications` fields retained with `@Deprecated` annotations
- When `enabled = true`, the routing matrix is authoritative and legacy fields are ignored for routing
- When `enabled = false`, the legacy `evaluateClassificationEgress()` logic continues to work unchanged
- `PolicyConfiguration.preview()` keeps `enabled = false` for 0.4.x backward compatibility

### Deferred Work
- Annotation-based operation-level provider policy overrides (@ProviderPolicy on methods) are intentionally deferred to a separate PR
- Provider zone auto-detection (classpath scanning, SPI) is not implemented — zones must be configured explicitly

---

## Implementation Notes — Rule-Based Classifier (PR for Epic 2.2) ✅

- Module: `tramai-security` (independent of Spring)
- API: `DocumentClassifier`, `RuleBasedDocumentClassifier`, `ClassificationInput`, `ClassificationRule`, `ClassificationDecision`, `classifyDocument` helper
- Activation: Spring Boot auto-configuration is explicit; set `tramai.security.classification.enabled=true` to create a classifier bean
- Matching semantics: regex rules are compiled once at construction; regex matching is case-sensitive by default; metadata key/value matching is exact and case-sensitive; if both regex and metadata are configured, both must match; conditionless rules are rejected; blank patterns and blank metadata keys are rejected
- Trust model: regex patterns are trusted administrative configuration, not end-user input
- Precedence: highest `DataClassification` wins via exhaustive ranking; `matchedRuleIds` includes all matched rules, ordered by priority desc, then id asc
- Default: `INTERNAL` (secure default)
- No automatic argument classification is performed in `TramaiEngine` — classification is an explicit caller action
- `LOCAL_MODEL_ASSISTED` classification is intentionally deferred
- Spring binding: `tramai.security.classification.*` properties; bean created only when explicitly enabled; `tramai-security` remains Spring-independent
- Deferred hardening: evaluate RE2/J or bounded-regex execution to mitigate administrative misconfiguration that could otherwise allow regex-based ReDoS
- Configuration example, programmatic:

```kotlin
val classifier = RuleBasedDocumentClassifier(
    RuleBasedClassifierConfiguration(
        defaultClassification = DataClassification.INTERNAL,
        rules = listOf(
            ClassificationRule(
                id = "national-id",
                classification = DataClassification.RESTRICTED,
                pattern = "\\b\\d{3}-\\d{2}-\\d{4}\\b",
            ),
        ),
    ),
)
```

- Configuration example, Spring YAML:

```yaml
tramai:
  security:
    classification:
      enabled: true
      default-classification: INTERNAL
      max-text-length: 100000
      rules:
        - id: national-id
          classification: RESTRICTED
          pattern: "\\b\\d{3}-\\d{2}-\\d{4}\\b"
```

#### 2.5 — Classification routing tests ✅
- Each classification level routed correctly
- Fallback blocked for restricted classifications
- Provider policy annotation overrides defaults — deferred (annotation-based operation-level overrides not yet implemented)
- **Acceptance:** Classification matrix unit tests pass; fallback and cache integration tests pass
- **Status:** Matrix-level unit tests complete. Engine-level fallback and cache integration tests complete. Annotation override tests deferred to separate PR.

**Epic Exit Criteria:**
- [x] RESTRICTED data never reaches unauthorized provider
- [x] Classification routing works for all 4 levels (reference defaults; organizations may override)
- [x] No silent fallback from local to cloud for restricted data

---

## Epic 2B: Output and Tool-Result Filtering

**Goal:** Prevent sensitive data leakage through model outputs and tool results.

### Issues

#### 2B.1 — DlpInterceptor SPI ✅
- Define DlpInterceptor interface for output scanning
- Default no-op implementation (NoOpDlpInterceptor)
- Rule-based first pass implementation (RuleBasedDlpInterceptor)
- **Acceptance:** SPI compiles, default passes through
- **Status:** Completed — see Implementation Notes below

#### 2B.2 — Field-level output policies ⏳
- Annotate output fields with sensitivity level
- Redact fields above configured threshold before returning
- **Acceptance:** Sensitive fields redacted; non-sensitive fields preserved
- **Status:** Deferred to follow-up PR

#### 2B.3a — Textual tool-result minimization hook ✅
- Tool-result reinjection filtering: sanitizes adjacent text runs in the final provider-bound `TOOL` message using format -> sanitize -> append, with cross-boundary detection via an all-text projection
- Aggregate size limit: combined textual size checked before DLP (exceeds 100K → fail closed, no retry)
- Sanitized: `Success.value`, `TextPart` (coalesced), `InvalidInput.message`, `PermanentFailure.message`
- Preserved: `ContentPart.ImagePart`, `ContentPart.ImageUrlContent` (deferred to 2B.3b)
- Per-tool scoping via `DlpRule.toolNames` — whitespace rejected at construction
- DLP failure: fail closed, no raw reinjection, no tool replay, no provider failure/retry
- **Status:** Complete for text. URLs and binary channels remain deferred.

#### 2B.3b — URL and binary tool-result minimization ⏳
- Image URLs passed through unchanged — may contain signed query params, SAS tokens, internal hostnames
- OCR scanning, image-byte inspection, URL-token/credential detection deferred
- **Status:** Deferred to follow-up PR

#### 2B.4 — Redaction audit events ✅
- Emit audit event when DLP redacts content
- Record field name, rule matched, not the redacted value
- **Acceptance:** Redaction events in audit trail; no sensitive data in audit
- **Status:** Completed in feat/dlp-redaction-audit-bridge (PR #13)

#### 2B.5 — Negative tests for valid-schema data leakage ✅
- Test: valid JSON output containing PII → redacted
- Test: tool result containing secrets → filtered
- Test: redaction events emitted and verifiable
- **Acceptance:** Schema-valid outputs with PII are caught by DLP layer
- **Status:** Completed in feat/dlp-redaction-audit-bridge (PR #13)

**Epic Exit Criteria:**
- [x] DLP SPI implemented with rule-based first pass
- [ ] Field-level redaction works for annotated fields
- [x] Textual tool results filtered before context reinjection
- [ ] URL and binary tool-result channels protected before external reinjection
- [ ] Schema-valid-but-leaky outputs blocked by DLP layer

---

## Implementation Notes — DLP Interceptor Foundation (PR 9)

**Where:**
- `tramai-core/.../security/DlpInterceptor.kt` — SPI, context, result types, NoOpDlpInterceptor
- `tramai-security/.../RuleBasedDlpInterceptor.kt` — rule-based implementation
- `tramai-engine/.../TramaiEngine.kt` — engine hook inside `callProviderWithRetries()`

### DLP API (`tramai-core`)

| Type | Role |
|------|------|
| `DlpContentType` | Enum: `MODEL_OUTPUT`, `TOOL_RESULT` |
| `DlpContext` | Metadata about the operation producing the text (contentType, service/method, provider/model, correlationId, dataClassification, classificationSource) |
| `DlpResult` | Result containing `sanitizedText`, `redactions: List<DlpRedaction>`, and computed `hasRedactions` (renamed from `modified`) |
| `DlpRedaction` | Rule reference with count — **no raw matched values** |
| `DlpInterceptor` | `fun interface` with `inspect(context, text): DlpResult` |
| `NoOpDlpInterceptor` | Pass-through singleton |
| `DlpInspectionException` | Thrown when DLP inspection fails — distinct from provider failures; constructor remains intentionally minimal (`message`, `cause`) with no `ruleId` parameter |

### RuleBasedDlpInterceptor (`tramai-security`)

- **Configuration:** `RuleBasedDlpConfiguration(rules, maxTextLength)` with default 100K limit
- **Validation (init):**
  - `maxTextLength` in (0, 10_000_000]
  - Non-blank rule IDs (unique)
  - Non-blank patterns
  - Non-empty `enabledFor` sets
- **Deterministic ordering:** Rules applied in constructor-declared order
- **Content-type filtering:** Each rule has `enabledFor: Set<DlpContentType>` (default `MODEL_OUTPUT`)
- **Per-tool filtering:** Each rule has `toolNames: Set<String>` (default empty). Empty means "all tools"; non-empty means the rule only applies when `DlpContext.toolName` matches one of those tool names. `enabledFor` and `toolNames` compose additively: both filters must match for the rule to apply.
- **Security properties:** No raw matched values in `DlpRedaction` or exceptions; fixed exception messages
- **Oversized input:** Rejected with `IllegalArgumentException("Input text exceeds maximum allowed length")` — no input content leaked
- **Duplicate redaction counting:** `DlpRedaction.replacementCount` reports total matches per rule
- **Zero-width safety:** Uses `Matcher.find() + appendReplacement() + appendTail()` loop — `appendReplacement` safely handles zero-width matches (lookahead, boundary anchors) without consuming characters or looping infinitely. `quoteReplacement()` escapes `$` and `\` for literal insertion.
- **Literal replacements:** Replacement strings are passed through `Matcher.quoteReplacement()` before `appendReplacement()` — `$1`, `\value`, and other special characters are escaped for literal output.

### Engine Hook Location (`TramaiEngine`)

DLP is applied at the **earliest safe response boundary** — inside `callProviderWithRetries()`,
after `OperationInterceptor.interceptResponse()` and **before** `onProviderResponse()`:

```
ModelResponse
  → interceptResponse (operation interceptor)
  → DLP inspection (inside callProviderWithRetries, earliest safe boundary)
    → observation.onProviderResponse(sanitized)
    → structured parsing (if structured output)
    → cache storage
    → chatMemory storage
    → return
```

Consequences:
- **Observers** see only sanitized output
- **Tool-loop assistant responses** are sanitized before reinjection as next-turn context
- **Tool-result reinjection** now scans provider-bound `TOOL` messages after `BEFORE_TOOL_RESULT_REINJECTION` policy enforcement and before the sanitized message is appended to `messages`
- **Raw return, structured parsing, chat memory, and cache** all use sanitized content
- `DlpContentType.MODEL_OUTPUT` and textual `DlpContentType.TOOL_RESULT` reinjection paths are scanned
- Registered tool calls preserve their metadata through DLP sanitization
- Unregistered tool calls are normalized to a safe placeholder (`unregistered_tool` with cleared arguments) before provider reinjection to prevent raw model-generated names from appearing in the conversation history
- Short-circuit when `dlpInterceptor === NoOpDlpInterceptor` (zero overhead for default config)

Textual tool-result branches covered by the engine hook:
- Plain-text `TOOL` message `content`
- Rich `TOOL` message `contentParts` adjacent `ContentPart.TextPart` runs
- Formatted `ToolResult.InvalidInput` messages
- Formatted `ToolResult.PermanentFailure` messages

Tool-result textual filtering details:
- The engine formats `ToolResult` into the final provider-bound `TOOL` `Message` first, then applies DLP to that assembled message
- The engine sanitizes adjacent text runs in the final provider-bound `TOOL` message using a format -> sanitize -> append flow
- TextParts are coalesced per adjacent run, and cross-image or other non-text-separated runs are detected via a global all-text projection
- Non-text parts (`ImagePart`, `ImageUrlContent`) remain in their original positions
- Aggregate textual limits are validated incrementally with `Long` accounting before any concatenation/allocation of the coalesced text
- Aggregate-limit rejection fails closed and emits a bounded `tramai.dlp.tool_result_rejected` engine event with safe metadata only
- `ToolResultFilteringSettings` configures the default aggregate limit and per-tool overrides
- `NoOpDlpInterceptor` preserves legacy reinjection behavior and skips the tool-result filtering path entirely

Tool-result branches intentionally preserved or deferred:
- `ContentPart.ImagePart` preserved unchanged
- `ContentPart.ImageUrlContent` preserved unchanged
- `ToolResult.TransientFailure` remains on the existing retry path and is not DLP-scanned here
- Image-byte inspection, OCR/image scanning, URL-token inspection, and other URL/binary channels remain deferred to **2B.3b**
- Redaction audit events remain deferred to **2B.4**

### DLP Failure Isolation

DLP failures are strictly separated from provider failures:

- A DLP inspection exception (`DlpInspectionException`) does NOT call `observation.onProviderFailure()`
- It does NOT call `circuitBreaker.onFailure()` — DLP failures do not poison provider circuit breakers
- It does NOT trigger provider retries (DLP is deterministic per response)
- It does NOT trigger fallback (response content cannot be returned unsanitized)
- Aggregate-limit rejection emits `tramai.dlp.tool_result_rejected` with bounded metadata only (`reasonCode`, aggregate length, configured limit, correlation ID, tool name)
- For provider responses that contain tool calls, `observation.onCallCompleted(parseSuccess = null)` now completes at the provider boundary before tool execution/DLP reinjection
- It DOES emit `tramai.dlp.inspection_failed` engine event for observability
- `CancellationException` is caught separately and rethrown unchanged before DLP or provider failure handling
- The `DlpInspectionException` propagates directly to the caller

### Cache Behaviour

Cache eligibility (`isSafeCacheEligible`) requires `dlpInterceptor === NoOpDlpInterceptor`.
Custom DLP interceptors **bypass the cache entirely** — every provider response is freshly
sanitized.

### Standalone Builder Wiring (`tramai-standalone`)

- `dev.tramai.standalone.Tramai` now stores a private `dlpInterceptor: dev.tramai.core.security.DlpInterceptor`
- The standalone builder defaults that field to `dev.tramai.core.security.NoOpDlpInterceptor`
- `Tramai.Builder.dlp(interceptor: DlpInterceptor)` wires custom DLP into the standalone runtime without adding any property surface
- `Tramai.create(...)` forwards the configured interceptor into `TramaiEngine(dlpInterceptor = ...)`
- Standalone integration test expectation: a builder-configured custom interceptor sanitizes provider output before `OperationObserver.onProviderResponse()` runs

### Spring Bean Wiring (`tramai-spring`)

- `TramaiAutoConfiguration` receives `ObjectProvider<dev.tramai.core.security.DlpInterceptor>` through auto-configuration method parameter injection
- Zero beans: no action; the standalone builder keeps `NoOpDlpInterceptor`
- One bean: auto-configuration calls `builder.dlp(interceptor)`
- Multiple beans: startup fails fast with `IllegalArgumentException` describing the ambiguous `DlpInterceptor` beans
- YAML/property binding for `tramai.dlp.*` remains deferred; this PR only exposes bean-based wiring

### Test Coverage

#### NoOpDlpInterceptorTest (tramai-core)
- Returns exact text unchanged
- No redactions produced
- `hasRedactions` is always false
- Works with all DlpContentType values

#### RuleBasedDlpInterceptorTest (tramai-security)
- Email regex redacts matching text
- API-key regex uses custom replacement string
- Multiple rules apply deterministically (in declaration order)
- Duplicate rule IDs rejected with clear message
- Blank rule IDs rejected
- Blank patterns rejected
- Oversized input rejected with fixed message (no input leakage)
- `TOOL_RESULT`-only rule does not affect `MODEL_OUTPUT`
- `MODEL_OUTPUT`-only rule does not affect `TOOL_RESULT`
- Rule applies to both content types when `enabledFor` includes both
- `TOOL_RESULT` rule applies when `toolName` matches
- `TOOL_RESULT` rule is skipped when `toolName` differs
- Empty `toolNames` applies to any tool
- Blank `toolNames` entries are rejected
- Content-type and tool-name filters compose correctly
- Empty rules list passes text through unchanged
- Input at `maxTextLength` boundary passes through
- `DlpResult` properties on modified output (sanitizedText, hasRedactions, redactions)
- Multiple occurrences of same pattern all redacted with correct count
- `maxTextLength = 0` rejected
- `maxTextLength` exceeding maximum rejected
- Zero-width lookahead terminates safely (no infinite loop)
- End-of-string anchor (`$`) terminates safely
- Replacement with `$1` stays literal (no backreference interpretation)
- Replacement with `\value` stays literal

#### Engine Integration Tests (tramai-engine)
- Raw response redacted before caller return
- Structured JSON redacted before parser input
- Chat memory stores sanitized assistant response
- Operation observer sees sanitized response
- Tool calls remain unchanged after DLP (sanitized assistant content, preserved tool call metadata)
- Successful tool-result text is redacted before the second provider call
- `InvalidInput` tool-result messages are sanitized before reinjection
- `PermanentFailure` tool-result messages are sanitized before reinjection
- Mixed tool-result `TextPart` content is sanitized before reinjection
- `ImagePart` and `ImageUrlContent` branches are preserved during tool-result sanitization
- Per-tool DLP rules are skipped for unrelated tools
- Tool-result DLP failure does not reinject raw content and does not replay the tool
- No-op DLP preserves tool-result reinjection behaviour
- Custom DLP disables cache
- NoOp DLP preserves existing behavior
- Custom DLP interceptor without redaction metadata still applies sanitized text

### Deferred Work (follow-up PRs)
- **Field-level output policies** (2B.2) — annotation-driven per-field redaction
- **Redaction audit events** (2B.4) — emit events via audit engine
- **Streaming DLP** — apply DLP to streaming `Flow<StreamChunk>`
- **Binary content DLP** — image/document scanning for embedded sensitive data

---

## Epic 3: Approval Gates

**Goal:** Suspend high-risk actions until human approval.

### Issues

#### 3.1 — ApprovalStateMachine
- States: PENDING, APPROVED, DENIED, TIMED_OUT
- Transitions: PENDING→APPROVED, PENDING→DENIED, PENDING→TIMED_OUT
- **Acceptance:** State machine enforces valid transitions

#### 3.2 — ApprovalStore SPI
- Interface for storing and retrieving approval requests
- In-memory implementation for dev/test
- **Acceptance:** SPI defined; in-memory impl passes tests

#### 3.3 — Workflow suspension for approval
- When policy returns RequireApproval, suspend workflow
- Resume on approval; auto-deny on timeout
- **Acceptance:** Workflow suspends; resumes after approval; auto-denies after timeout

#### 3.4 — @AiTool approval attributes
- `approval = HUMAN_REQUIRED` → always suspend
- `approval = HUMAN_REQUIRED_WITH_TIMEOUT` → suspend with configurable timeout
- **Acceptance:** Tool with HUMAN_REQUIRED → suspended; AUTO → proceeds

#### 3.5 — Approval audit events
- Emit event on approval request, grant, deny, timeout
- Include requestedBy, decidedBy, decidedAt, decisionComment
- **Acceptance:** Every approval decision has audit event with full actor chain

#### 3.6 — Approval binding and replay prevention
- Bind approval to workflowRunId, toolName, argumentsDigest, policyVersion, and workflowDigest
- Enforce single-use approval (one execution per token)
- Reject expired approvals
- Revalidate policy before workflow resume
- Provide idempotency key for tool execution
- Reject when requester == approver for critical actions
- **Acceptance:** Changed arguments → new approval required; expired token → rejected; policy changed → revalidation; double execution → idempotency prevents

**Epic Exit Criteria:**
- [ ] HIGH-risk tool without approval → suspended
- [ ] Approval granted → workflow resumes
- [ ] Approval timeout → auto-deny
- [ ] All approval decisions audited

---

## Implementation Notes — Approval Gates Foundation (PR #14)

**Where:**
- `tramai-core/.../approval/` — domain model: ApprovalStatus, ApprovalBinding, ApprovalRequest, ApprovalStore (SPI), ApprovalTransition, IllegalApprovalTransitionException, Sha256Digest
- `tramai-security/.../approval/InMemoryApprovalStore.kt` — in-memory implementation

### ApprovalRequest Model

All binding fields are mandatory (non-nullable):

| Field | Type | Description |
|-------|------|-------------|
| approvalId | String | Unique identifier |
| binding | ApprovalBinding | Full binding context (see below) |
| status | ApprovalStatus | Current state |
| requestedBy | String | Actor requesting approval |
| requestedAt | Instant | Creation timestamp |
| expiresAt | Instant | Deadline — must be in the future at creation |
| decidedBy | String? | Deciding actor (null until resolved) |
| decidedAt | Instant? | Decision timestamp (null until resolved) |
| decisionComment | String? | Optional justification |
| consumedBy | String? | Consumer identity (null until consumed) |
| consumedAt | Instant? | Consumption timestamp (null until consumed) |
| version | Long | Optimistic concurrency version |

### ApprovalBinding (mandatory fields)

| Field | Type | Validation |
|-------|------|------------|
| workflowRunId | String | Non-blank, ≤ 256 chars, no surrounding whitespace |
| toolName | String | Non-blank, ≤ 256 chars, no surrounding whitespace |
| argumentsDigest | Sha256Digest | Format: `sha256:<64 lowercase hex chars>` |
| policyVersion | String | Non-blank, ≤ 256 chars, no surrounding whitespace |
| workflowDigest | Sha256Digest | Format: `sha256:<64 lowercase hex chars>` |
| approvalTokenDigest | Sha256Digest | SHA-256 of generated nonce; raw token given to requestor at creation time |

### SHA-256 Digest Validation

`Sha256Digest` is a `@JvmInline value class` backed by `String` with a companion `validate()` method. The regex pattern `^sha256:[0-9a-f]{64}$` ensures exact format compliance. Used for argumentsDigest, workflowDigest, and approvalTokenDigest.

### State Machine

```
PENDING ──┬── Approve ──→ APPROVED (terminal)
          ├── Deny ──────→ DENIED (terminal)
          └── Timeout ───→ TIMED_OUT (terminal)
          (when now >= expiresAt)
```

- **Timeout semantics:** Timeout from PENDING succeeds only when `now >= expiresAt`. Timeout before expiry throws `IllegalApprovalTransitionException`. Expired approvals can only be timed out — approve/deny are rejected.
- **Terminal states:** APPROVED, DENIED, and TIMED_OUT are terminal. Any transition from a terminal state throws `IllegalApprovalTransitionException`.
- **targetStatus():** Each `ApprovalTransition` subclass implements `targetStatus()` returning the resolved `ApprovalStatus`. The `resolveNextStatus()` method uses this uniformly instead of inline `when` branches.

### Hardened create()

`InMemoryApprovalStore.create()` validates:
- `version == 0L` (initial version invariant)
- `status == PENDING` (no pre-set status)
- No decision fields set (`decidedBy`, `decidedAt`, `decisionComment` must be null)
- All ID/binding fields: non-blank, ≤ max length, no surrounding whitespace
- All digests: valid SHA-256 format via `Sha256Digest.of()`
- Expiry: must be in the future and after `requestedAt`
- Atomic insert: `putIfAbsent` rejects duplicates

### Simplified Optimistic Concurrency

`transition()` and `consumeApproved()` use `ConcurrentHashMap.compute()` for atomic read-modify-write:
- No CAS retry loop or `maxCasRetries` parameter
- Version check inside the compute lambda (atomic with the update)
- `compute()` returns `null` when the key is absent, detected as "not found"

### consumeApproved — Atomic Single-Use Consumption

`InMemoryApprovalStore.consumeApproved()` implements single-use consumption:
- Version-gated via `ConcurrentHashMap.compute()` (same atomicity model as transition)
- Requires `status = APPROVED`, `consumedAt == null`, and `now < expiresAt`
- Constant-time token-digest comparison via `MessageDigest.isEqual()` to prevent timing attacks
- Records `consumedBy` and `consumedAt` atomically with the version increment
- Second consume of the same approval fails with clear message

This is a **store-level atomic primitive**. Raw-token presentation (matching a raw bearer token against `approvalTokenDigest`) is handled at the coordinator level in PR #15 via `DefaultApprovalGateCoordinator.authorizeResume()`. Engine-level resume flow is deferred to PR #16.

### No Engine Integration Yet

This PR (PR #14) establishes the domain model and store implementation only. No engine integration, no workflow suspension, no approval resume. PR #15 builds the coordinator, token handling, binding revalidation, and safe exception boundary. PR #16 will build engine suspension/resume on top of this foundation.

### Deferred to PR #16
- TramaiEngine workflow suspension
- Provider-loop continuation storage
- BEFORE_WORKFLOW_RESUME enforcement
- Tool execution resume
- Auto-deny on timeout (engine job, not store concern)
- Approval audit events
- Idempotency keys
- REST endpoints, Spring controllers
- Persistent database store
- OIDC identity mapping
- Multi-node coordination

---

## Implementation Notes — Approval Gate Coordinator (PR #15) ✅

**Branch:** `feat/approval-gate-coordinator`

**Where:**
- `tramai-core/.../approval/` — ApprovalToken, ApprovalTokenGenerator (SPI), ApprovalTokenDigester (SPI), ApprovalIdGenerator (SPI), ApprovalDecisionValidator (SPI), ApprovalGateCoordinator (interface), coordinator command/response types
- `tramai-core/.../exception/` — ApprovalStoreException, ApprovalNotFoundException, ApprovalBindingMismatchException, ApprovalTokenRejectedException, ApprovalAuthorizationException
- `tramai-security/.../approval/` — SecureRandomApprovalTokenGenerator, Sha256ApprovalTokenDigester, UuidApprovalIdGenerator, DefaultApprovalGateCoordinator, AllowAnyApprovalDecisionValidator, RequireDistinctRequesterAndConsumer

### Architecture

```
CreateApprovalCommand
  → ApprovalGateCoordinator.createApproval()
    → generate approvalId + raw token + SHA-256 digest
    → store only digest on ApprovalBinding.approvalTokenDigest
    → return ApprovalChallenge (approvalId + raw token + expiry)

AuthorizeResumeCommand
  → ApprovalGateCoordinator.authorizeResume()
    → store.get() → revalidate exact binding (5 fields)
    → validate decision → digest presented token
    → store.consumeApproved() → return safe ApprovalAuthorization
```

### ApprovalToken Security

- `@JvmInline value class` with `private constructor` — raw value never exposed as public property
- `toString()` always returns `[REDACTED]`
- `reveal()` is the only escape hatch, explicitly named to discourage automatic use
- Max 512 chars, no control characters, no whitespace (leading, trailing, or internal), not blank
- Whitespace is rejected, not silently trimmed

### Token Generation

- `SecureRandomApprovalTokenGenerator` uses `java.security.SecureRandom`
- Default: 32 bytes = 256 bits of entropy
- URL-safe Base64 encoding without padding
- Minimum 256 bits enforced (tokenBytes >= 32)

### Token Hashing

- `Sha256ApprovalTokenDigester` computes SHA-256 over `token.reveal().toByteArray(StandardCharsets.UTF_8)`
- Always lowercase hex format: `sha256:<64 hex chars>`
- Raw token is never persisted — only the digest goes into `ApprovalBinding.approvalTokenDigest`

### Binding Revalidation

`authorizeResume()` checks all 5 binding fields **before** calling `consumeApproved()`:
1. workflowRunId
2. toolName
3. argumentsDigest
4. policyVersion
5. workflowDigest

Any mismatch throws `ApprovalBindingMismatchException(approvalId, field)` and the approval remains unconsumed.

### Safe Exceptions

| Exception | Properties | Secret-free? |
|-----------|------------|-------------|
| `ApprovalNotFoundException(approvalId)` | `approvalId` only | ✅ |
| `ApprovalBindingMismatchException(approvalId, field)` | `approvalId`, `field` name | ✅ |
| `ApprovalTokenRejectedException(approvalId)` | `approvalId` only | ✅ |
| `ApprovalAuthorizationException(approvalId?)` | `approvalId?` only | ✅ |
| `ApprovalStoreException(approvalId)` | abstract base with `approvalId` | ✅ |

Raw tokens, token digests, arguments, and workflow payloads are NEVER included in exception messages.

- Store failures are now caught as typed `ApprovalStoreException` subtypes, not via message-string parsing
- `mapStoreError()` uses a `when` dispatch on exception type, not `exception.message.contains(...)`
- Unexpected `RuntimeException` from the store is wrapped in `ApprovalAuthorizationException`

### Approval Lifetime Bounding

- `DefaultApprovalGateCoordinator` accepts a `maxApprovalTtl: Duration` parameter (default: 15 minutes)
- `createApproval()` validates that `command.expiresAt` is within `maxApprovalTtl` of `clock.instant()`
- Past-expiry and beyond-TTL requests are both rejected with `IllegalArgumentException`
- Constructor validates `maxApprovalTtl > Duration.ZERO`

### AuthorizeResume Validation

`authorizeResume()` now validates ALL identifier fields before store lookup:
1. `approvalId`
2. `consumedBy`
3. `workflowRunId`
4. `toolName`
5. `policyVersion`
6. `expectedVersion >= 0`

### Trusted SPI Boundaries

The following SPIs are **trusted computing-base extensions** — they operate within the security
boundary and must adhere to strict safety invariants:

- `ApprovalTokenGenerator` — must not log, serialize, or expose raw token values
- `ApprovalTokenDigester` — must not log, serialize, or expose raw token or digest values
- `ApprovalDecisionValidator` — must not leak binding or token data in exception messages
- `ApprovalStore` — must not log raw token digests or binding payloads; must not expose token data through any public API surface
- `ApprovalIdGenerator` — must produce non-empty, non-blank, unique identifiers

Implementations are considered part of the trusted computing base and should be reviewed
for security properties. Default implementations in `tramai-security` meet all invariants.

### Decision Validator Extension Point

`ApprovalDecisionValidator.validate(request, consumedBy)` is invoked immediately before `store.consumeApproved()`:

- `AllowAnyApprovalDecisionValidator` — default, preserves backward compatibility
- `RequireDistinctRequesterAndConsumer` — enforces separation of duties

### Exception Boundary Hardening

The exception hierarchy has been refactored to enforce strict separation between **internal store failures** and **safe public exceptions**:

**Sealed store exceptions** (thrown by `ApprovalStore` implementations):

```
ApprovalStoreException (sealed, extends RuntimeException)
├── ApprovalStoreNotFoundException(approvalId)
├── ApprovalStoreTokenRejectedException(approvalId)
├── ApprovalStoreConflictException(approvalId)     — version mismatch or duplicate ID
└── ApprovalStoreNotConsumableException(approvalId) — wrong status, expired, already consumed
```

- All sealed. No custom implementations can add arbitrary messages.
- Accept only `approvalId` — no message or `cause` parameter.
- `InMemoryApprovalStore` now throws these instead of `IllegalArgumentException` or the old `ApprovalStoreException` subtypes.

**Coordinator-facing safe exceptions** (thrown by `DefaultApprovalGateCoordinator`):

```
ApprovalNotFoundException(approvalId)                          — extends ApprovalException (TramaiException hierarchy)
ApprovalTokenRejectedException(approvalId)                     — fixed safe message, no cause parameter
ApprovalBindingMismatchException(approvalId, field)            — fixed safe message, no cause parameter
ApprovalAuthorizationException(approvalId?)                    — fixed safe message, no cause parameter
ApprovalCreationException(approvalId?)                         — fixed safe message, no cause parameter
```

- All extend `ApprovalException` (which extends `TramaiException` → `RuntimeException`).
- Not part of the `ApprovalStoreException` sealed hierarchy.
- Fixed, caller-safe messages. No caller-provided message strings.
- Do NOT accept a `cause` parameter. Store internal details are never leaked through cause chains.
- `mapStoreError()` maps each sealed `ApprovalStoreException` subtype to the corresponding safe exception.

**Cause-chain sanitization:**

- No `cause = exception` is passed to coordinator-facing constructors.
- `mapStoreError()` discards the original exception after mapping.
- An `else` branch catches stray `RuntimeException` (IAE, etc.) and maps to `ApprovalAuthorizationException`.

### Non-Interfering Diagnostic Observer (PR #15)

The `observeFailure()` helper wraps all `failureObserver?.record()` calls in `try/catch` catching only `RuntimeException`, so a throwing observer can never bypass the safe public exception boundary. Fatal `Error` types are NOT caught — they propagate to the caller:

```kotlin
private fun observeFailure(
    operation: String,
    approvalId: String?,
    failure: RuntimeException,
) {
    try {
        failureObserver?.record(operation, approvalId, failure)
    } catch (_: RuntimeException) {
        // Diagnostic observers must not replace safe public failures.
    }
}
```

- Called in every catch block: `createApproval()`, `authorizeResume()` store.get, `authorizeResume()` store.consumeApproved.
- If the observer throws a `RuntimeException`, it is silently swallowed. The caller always receives the safe exception.
- If the observer throws `Error`, it propagates uncaught — the coordinator call itself will throw the `Error`.
- Tests verify that an observer throwing `RuntimeException("observer-secret-marker")` does not leak the marker into the exception message, `toString()`, or cause chain.

### Consumed-Result Contract Validation (PR #15)

After `store.consumeApproved()` returns, the coordinator performs a full contract validation against the command and the stored request:

```kotlin
if (consumed.approvalId != command.approvalId) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.binding != request.binding) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.status != ApprovalStatus.APPROVED) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.consumedBy != command.consumedBy) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.consumedAt == null) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.version != Math.addExact(command.expectedVersion, 1L)) throw ApprovalAuthorizationException(command.approvalId)
```

- All 6 checks use the fixed safe message `"Approval authorization failed"`.
- No mismatch details are exposed to the caller.
- 7 fake-store tests verify each contract breach.

### Recursive Leakage Traversal (PR #15)

The `containsSecret()` helper traverses the entire throwable tree — message, `toString()`, all suppressed exceptions, and the entire cause chain:

```kotlin
private fun containsSecret(throwable: Throwable, secret: String): Boolean {
    if (throwable.message?.contains(secret) == true) return true
    if (throwable.toString().contains(secret)) return true
    for (suppressed in throwable.suppressed) {
        if (containsSecret(suppressed, secret)) return true
    }
    val cause = throwable.cause
    if (cause != null && containsSecret(cause, secret)) return true
    return false
}
```

- 4 tests verify that `"secret"` is absent from the full exception tree for leaky store.get(), store.create(), store.consumeApproved(), and throwing observer paths.

### Test Coverage (58+ tests)

**ApprovalFailureObserver (trusted diagnostic SPI):**

```kotlin
fun interface ApprovalFailureObserver {
    fun record(operation: String, approvalId: String?, failure: RuntimeException)
}
```

- Optional `failureObserver` parameter on `DefaultApprovalGateCoordinator`.
- Records the original exception before it is sanitized.
- Called in every catch block in `createApproval()` and `authorizeResume()`.
- Trusted diagnostic SPI. Not part of the public API contract.
- Implementations must not leak sensitive data through external channels.

**Bounded TTL in InMemoryApprovalStore:**

- Added `maxCreationTtl: Duration = Duration.ofMinutes(15)` constructor parameter.
- `create()` validates that `expiresAt - requestedAt <= maxCreationTtl`.
- Defense-in-depth: callers that bypass the coordinator are still bounded.

- **ApprovalTokenTest** (11): toString, blank, whitespace in token, leading whitespace, trailing whitespace, tab rejected, non-whitespace control char, control chars, oversized, reveal, format
- **SecureRandomApprovalTokenGeneratorTest** (7): non-blank, 256-bit entropy, uniqueness, URL-safe, tokenBytes below 32 rejected, tokenBytes at 32 accepted, tokenBytes at 64 accepted
- **Sha256ApprovalTokenDigesterTest** (5): known vector, deterministic, different, format, no leakage
- **DefaultApprovalGateCoordinatorTest** (58): create (15) + authorizeResume (23) + exception taxonomy (3) + observer (5: existing 2 + RuntimeException swallowed, safe public exception visible, Error not swallowed) + consumed-result contract validation (7) + recursive leakage (4) + version overflow (1: Long.MAX_VALUE rejected)
- **InMemoryApprovalStoreTest** (added 2 version-overflow tests)
- **ApprovalDecisionValidatorTest** (3): AllowAny, RequireDistinct rejects same, accepts different

## Epic 4: Audit Engine

**Goal:** Emit versioned, hash-chained audit events for every policy decision.

### Issues

#### 4.1 — AuditEvent model (v1)
- Implement AuditEvent with all fields: schemaVersion, hashAlgorithm, auditStreamId, eventId, sequenceNumber, workflowRunId, correlationId, actor, enforcementPoint, decision, policyVersion, workflowDigest, previousEventHash, eventHash, timestamp, reasonCode, metadata
- Hash chain: `eventHash = sha256(canonicalJson(event.copy(eventHash = "")))` — covers every immutable field
- Sequence per auditStreamId (typically per workflowRunId); serialize concurrent writes
- **Acceptance:** AuditEvent serializable; hash chain verifiable; tampering any field invalidates chain

#### 4.2 — AuditEngine and AuditStore SPI
- Synchronous emission: policy decision → audit event → store
- AuditStore SPI (in-memory, file, database)
- **Acceptance:** Audit engine emits events synchronously; SPI pluggable

#### 4.3 — Fail mode configuration
- FAIL_CLOSED: block operation if audit unavailable
- FAIL_SAFE_READ_ONLY: allow read-only queries
- Durable local buffer with size limit for offline batch
- **Acceptance:** Each fail mode behaves correctly in tests

#### 4.4 — Audit event tests
- Every allow → audit event
- Every deny → audit event
- Every approval decision → audit event
- Hash chain integrity verified
- Storage failure → correct fail mode
- **Acceptance:** Full audit coverage; hash chain verifiable

**Epic Exit Criteria:**
- [ ] Every policy decision produces an audit event
- [ ] Hash chain makes tampering detectable
- [ ] Fail modes tested for each operation type
- [ ] Storage full → correct behavior (not silent data loss)

---

## Epic 5: Sovereign Invoice Analyzer Demo

**Goal:** Package Phases 1–4 into a single vertical slice demo.

### Issues

#### 5.1 — Evolve InvoiceAnalyzer to Sovereign Invoice Analyzer
- Replace `InvoiceAnalyzer` with `InvoiceReviewAgent` using new annotations
- Add ClassifiedDocument wrapper
- Add policy configuration for the demo
- **Acceptance:** Same workflow, now policy-enforced

#### 5.2 — Demo scenario 1: RESTRICTED document blocked from cloud
- Classify document as RESTRICTED
- Attempt cloud routing → blocked
- Audit event emitted
- **Acceptance:** Test passes; audit event verifiable

#### 5.3 — Demo scenario 2: Payment tool requires approval
- HIGH-risk payment tool invoked
- Workflow suspends
- Approval granted → resumes and completes
- **Acceptance:** Full approval flow works end-to-end

#### 5.4 — Demo scenario 3: Audit trail reconstruction
- Run full workflow
- Query audit events
- Reconstruct decision timeline
- **Acceptance:** Every step visible in audit trail

#### 5.5 — Demo scenario 4: Zero external egress execution
- Run reference workflow without internet connectivity
- Verify zero external network egress (loopback/localhost calls to local models are expected)
- Verify policy enforcement and acceptance-test outcomes match online execution
- **Acceptance:** Offline execution preserves contract-compatible typed behavior and policy enforcement

**Epic Exit Criteria:**
- [ ] 4 demo scenarios pass
- [ ] RESTRICTED → cloud blocked
- [ ] HIGH-risk → approval required
- [ ] Full audit trail reconstructable
- [ ] Offline execution verified

---

## Dependency Graph

```
Epic 1 (Policy Core) ─────────────────────────────┐
    │                                              │
    ├── Epic 2 (Classification + Routing) ──────┐  │
    ├── Epic 2B (DLP + Output Filtering) ───────┤  │
    │                                           │  │
    ├── Epic 3 (Approval Gates) ────────────────┤  │
    │                                           │  │
    └── Epic 4 (Audit Engine) ──────────────────┤  │
                                                │  │
                                    Epic 5 (Demo) ─┘
```

Epics 1, 2, 2B, 3, and 4 can partially overlap. Epic 5 requires the relevant vertical-slice capabilities from each.

---

## Timeline (Phase 1: Months 1–2)

| Week | Focus |
|------|-------|
| 1–2 | Epic 1: PolicyEngine SPI, tramai-security scaffold, enforcement hooks |
| 3–4 | Epic 1 continued: annotations, negative tests |
| 5 | Epic 2: Epic 2.2 closed (ClassifiedDocument + rule-based classification); provider routing in progress |
| 5–6 | Epic 2B: DlpInterceptor SPI, field-level policies, tool-result filtering |
| 6 | Epic 3: ApprovalStateMachine, workflow suspension |
| 7 | Epic 4: AuditEngine, hash chain, fail modes |
| 8 | Epic 5: Sovereign Invoice Analyzer demo, integration tests, evidence pack |

---

## Implementation Notes — Audit Engine Foundation (PR 11) ✅

**Where:** `tramai-security/.../audit/`

**Epic 4.1–4.2 status:** Foundation complete.

**Implemented:**
- `AuditEvent` v1 with `schemaVersion`, `AuditHashAlgorithm`, `auditStreamId`, `eventId`, `sequenceNumber`, `previousEventHash`, `eventHash` (SHA-256 over canonical JSON), `timestamp` (via `java.time.Clock`), and typed metadata map
- `AuditStore` SPI with atomically sequenced `appendNext(auditStreamId, eventFactory)` — store owns sequence, linking, and invariant enforcement
- `InMemoryAuditStore` — thread-safe via `Mutex` + `ConcurrentHashMap`, enforces streamId match, sequence continuity, previousEventHash match, eventHash recalculation, duplicate eventId rejection, and schemaVersion check
- `AuditEngine` — synchronous `emit()` with injected `Clock` and `idGenerator`, delegates persistence to `AuditStore.appendNext()`
- `AuditChainVerifier` — verifies stream consistency, schema/hashAlgorithm consistency, unique eventIds, sequence continuity, hash chain, and hash recalculation
- Deterministic canonical JSON serializer (manual `StringBuilder`, no third-party lib) with stable field ordering, sorted metadata keys, explicit null serialization, and ISO-8601 UTC timestamps

**Deferred to PR #12 (completed):**
- ✅ **Centralized audit emission in PolicyEnforcementHelper.enforce()** — all active policy enforcement points produce hash-chained events (7 active); DLP redaction events are emitted separately via `DlpRedactionAuditEmitter`
- ✅ **PolicyDecisionAuditEmitter SPI** — covers DefaultPolicyEngine and custom implementations
- ✅ **AuditEnginePolicyDecisionAuditEmitter** with safe metadata allowlist and stable stream ID
- ✅ **NoOpPolicyDecisionAuditEmitter** for backward compatibility
- ✅ **Standalone builder and Spring auto-configuration** wiring

**Deferred to follow-up PRs:**
- AuditMode enum (MINIMAL/DECISION_ONLY/FULL) and PolicyConfiguration wiring
- Configurable audit failure modes (FAIL_SAFE_READ_ONLY)
- Durable offline buffer and buffer limits
- Storage-full strategy
- DLP redaction audit bridge (2B.4) (completed in PR #13)
- Approval audit events (Epic 3 integration)
- File store and database store implementations
- Audit retention and governance UI
- BEFORE_WORKFLOW_RESUME runtime call site (Epic 3 approval workflow)
- Streaming `BEFORE_RESPONSE_RETURN` semantics clarification (pre-stream egress preflight)

---

## Implementation Notes — Runtime Policy Decision Audit Wiring (PR 12) ✅

**Branch:** `feat/audit-policy-decision-wiring`

**Central audit-emission boundary:** `PolicyEnforcementHelper.enforce()` — the mandatory shared runtime enforcement path through which every policy evaluation passes.

**Architecture:**
- `PolicyDecisionAuditEmitter` SPI in `tramai-core` with `NoOpPolicyDecisionAuditEmitter`
- `AuditEnginePolicyDecisionAuditEmitter` in `tramai-security` backed by `AuditEngine`
- `AuditStreamIdResolver` resolves stable stream ID: `workflowRunId > required non-blank correlationId`
- Wiring at `PolicyEnforcementHelper.enforce()`: evaluate policy → audit synchronously → then enforce side effects
- Standalone builder (`Tramai.Builder.policyDecisionAudit()`) and Spring auto-configuration (`ObjectProvider<PolicyDecisionAuditEmitter>`)

**Enforcement points covered (7 active):**
- BEFORE_PROVIDER_RESOLUTION
- BEFORE_PROVIDER_INVOCATION
- BEFORE_FALLBACK
- BEFORE_TOOL_EXPOSURE
- BEFORE_TOOL_EXECUTION
- BEFORE_TOOL_RESULT_REINJECTION
- BEFORE_RESPONSE_RETURN

⚠️ `BEFORE_WORKFLOW_RESUME` is enumerated in the `EnforcementPoint` enum but has **no active runtime call site** in `TramaiEngine` yet. It is reserved for the approval-resume flow (Epic 3) and will become active when workflow suspension is implemented. All 7 active policy enforcement points are audited.

**Streaming `BEFORE_RESPONSE_RETURN` semantics:**
In streaming execution, `BEFORE_RESPONSE_RETURN` is evaluated as an egress preflight *before* `BEFORE_TOOL_EXPOSURE` and `BEFORE_PROVIDER_INVOCATION`. It is not literally "before returning a response" during streaming — it acts as a streaming egress preflight gate. The audit metadata field `enforcementPoint` will read `BEFORE_RESPONSE_RETURN` but the event represents a pre-stream authorization decision, not a post-stream response check.

**Decision mapping:**
| PolicyDecision | audit decision | audit reasonCode |
|---------------|----------------|------------------|
| ALLOW | `"ALLOW"` | `"policy_allowed"` |
| DENY | `"DENY"` | `decision.reasonCode` |
| REQUIRE_APPROVAL | `"REQUIRE_APPROVAL"` | `"policy_requires_approval"` |

**Ordering:**
- ALLOW: evaluate → audit → proceed
- DENY: evaluate → audit → throw PolicyViolationException
- REQUIRE_APPROVAL: evaluate → audit → throw ApprovalRequiredException

**Safe metadata allowlist (bounded to 256 chars, max 16 entries):**
- providerName, modelName, toolName, classification, classificationSource, riskLevel, fallbackProviderName
- Attributes are filtered through an explicit allowlist: only `cacheReuse` and `fallbackReason` are exported (prefixed `attr_`). All other attributes (prompt, toolArguments, secret, etc.) are dropped.
- `Deny.reasonCode` is normalized through the `SAFE_REASON_CODE` pattern `[a-z0-9][a-z0-9._:-]{0,127}` — invalid, oversize, or secret-like values are replaced with `"policy_denied"`.

**Failure behavior:** Fail-closed by propagation — when a configured emitter fails, the exception propagates before the protected operation proceeds. Default unconfigured behavior uses `NoOpPolicyDecisionAuditEmitter`.

**Tests:** 30+ tests covering:
- Emitter unit tests: ALLOW, DENY, enforcement point mapping, stream ID stability, chain integrity, safe metadata, attribute allowlist (prompt/toolArguments dropped, cacheReuse/fallbackReason retained)
- Reason code normalization: valid, overlong, whitespace, secret-like, newline, empty, digit-starting
- Stream ID validation: blank workflowRunId falls back to correlationId, both blank throws
- Leakage tests: no prompt secret, no tool argument secret, no DLP match, no raw model-generated tool name in serialized audit event
| Enforcement boundary tests: exactly-once ALLOW/DENY, audit failure propagation (6 engine-level fail-closed), NoOp backward compatibility
- Standalone builder tests: NoOp default, custom emitter receives events, custom PolicyEngine enforces deny, custom PolicyEngine+audit emitter records deny events
- Spring wiring tests: zero emitter beans preserves default, one emitter bean wired, multiple emitter beans fail fast; zero PolicyEngine beans preserves legacy permissive, one PolicyEngine bean wired, multiple PolicyEngine beans fail fast

---

## Implementation Notes — Secure Cache Provenance (PR 6) ✅

**Where:** `tramai-engine/.../OperationResponseCache.kt`, `InMemoryOperationResponseCache.kt`, `TramaiEngine.kt`.

**Cache data model:**
- `OperationCacheKey` now carries `requestDigest: String` (SHA-256 hex of canonical rendered messages) and `securityPartition: CacheSecurityPartition` (dataClassification + classificationSource).
- `CachedOperationResult` wraps the stored value with `CachedResponseProvenance(providerId, modelName, dataClassification, classificationSource)`.

**Authorization on cache hit:**
- Every cache hit re-evaluates three policy gates using the cached `providerId`/`modelName` and the **current** request's `ExecutionSecurityContext`:
  - `BEFORE_PROVIDER_RESOLUTION` — model allowlist re-check
  - `BEFORE_PROVIDER_INVOCATION` — provider allowlist re-check
  - `BEFORE_RESPONSE_RETURN` — classified egress re-check
- Each gate is tagged with `attribute("cacheReuse", "true")` for audit.
- The cached envelope is validated against the security partition before any policy call (`IllegalStateException` on mismatch).
- Policy changes after a cache write take effect on the next hit.
- Classified cache reuse is enabled for pure, non-streaming, non-conversational operations without tools or custom interceptors.

**Cache eligibility (`isSafeCacheEligible`):**
- `operation.cacheable` is true
- return type is not `STREAMING`
- no tools declared on the operation
- no `chatMemory` in scope (active conversation)
- engine is using the default `NoOpOperationInterceptor`

**Limitations:**
- `requestDigest` is SHA-256, not a keyed hash. A configurable HMAC strategy is intentionally deferred.
- Streaming responses are not cached (no change).
- A `RESTRICTED` request whose cached response came from a cloud provider is denied on re-use without re-invoking the provider.
- Operations using custom `OperationInterceptor` implementations bypass cache until interceptor-aware cache fingerprints and cached-response hooks exist.

**Removed:**
- The temporary `securityContext.dataClassification == null` bypass in raw + structured cache read/write paths.
- The `classified raw cacheable calls bypass cache reuse` and `classified structured cacheable calls bypass cache reuse` tests (bypass closed).

---

## Implementation Notes — DLP Redaction Audit Bridge (PR 13) ✅

**Where:**
- `tramai-core/.../security/DlpRedactionAuditEmitter.kt` — SPI, NoOp emitter
- `tramai-security/.../audit/AuditEngineDlpRedactionAuditEmitter.kt` — implementation, DlpAuditStreamIdResolver
- `tramai-engine/.../TramaiEngine.kt` — integration with authoritative vs detection-only scans

**Epic 2B.4 & 2B.5 status:** Complete.

**Key Design Elements:**
- **SPI Safety:** `DlpRedactionAuditEmitter` exposes only `DlpContext` and `List<DlpRedaction>`. No raw matches, sanitized values, replacement strings, or regex patterns are leaked.
- **DLP Audit Labels:** `DLP_MODEL_OUTPUT` and `DLP_TOOL_RESULT` are emitted as audit-event labels, not `PolicyEngine` enforcement points.
- **Audit Normalization:** Enforces deterministic alphabetical sorting of rule IDs, groupings of duplicate rule IDs, safe metadata allowlists (only specific metadata attributes from context), and rule ID validation.
- **Safe Exception Handling:** All exceptions generated during ID validation and audit emission avoid leaking raw or invalid values (e.g. invalid rule IDs).
- **Engine Integration:** Separates authoritative (affects context, audits) from detection-only (e.g. projection matching checks, does not audit) scans. DLP audit emission failure propagates as `DlpInspectionException`, executing fail-closed behavior immediately to bypass retry/fallback loops and prevent circuit poisoning.
- **Wiring:** Programmatic standalone builder support via `.dlpRedactionAudit(emitter)` and Spring Boot auto-configuration support with ObjectProvider resolution (zero/one/multi resolution).

---

## Implementation Notes — Approval Continuation Store Foundation (PR #16) ✅

**Branch:** `feat/approval-continuation-store`

**Where:**
- `tramai-core/.../approval/` — SensitiveToolArguments, ToolArgumentsDigester (SPI), ApprovalContinuationStatus, ApprovalContinuation, ApprovalContinuationStore (SPI)
- `tramai-core/.../exception/` — ApprovalContinuationStoreException (sealed hierarchy)
- `tramai-security/.../approval/` — Sha256ToolArgumentsDigester, InMemoryApprovalContinuationStore

### Why the approval record alone is insufficient for resume

`ApprovalRequest` (PR #14) stores only the *digest* of tool arguments (`argumentsDigest`), not the raw arguments themselves. This is correct for the approval-facing API — a digest is sufficient for binding validation. However, to *resume* a suspended tool execution, the engine needs the exact raw arguments that were present at suspension time. These raw arguments are the only way to re-invoke the tool after approval.

PR #16 introduces a strict metadata/payload split:
- `ApprovalContinuation` retains metadata only, including `approvalExpiresAt`
- raw tool arguments are stored separately behind `SensitiveToolArguments`
- ordinary `get()` calls never expose the raw payload
- `claimForExecution()` is the single release path and returns the payload exactly once
- stored payload is scrubbed on claim, expiry, and cancellation
- terminal records retain metadata only
- persistent stores must encrypt the sensitive payload at rest

`approvalExpiresAt` is the approval challenge expiry. PR #17 must pass `ApprovalChallenge.expiresAt` through unchanged when creating the continuation; there is no independent continuation expiry that may extend raw-argument retention.

### Raw arguments stored only behind SensitiveToolArguments

```kotlin
class SensitiveToolArguments private constructor(
    private val rawValue: String,
) {
    fun reveal(): String = rawValue
    override fun toString(): String = "[REDACTED]"
    companion object {
        fun of(raw: String): SensitiveToolArguments
        private const val MAX_TOOL_ARGUMENTS_LENGTH = 1_000_000
    }
}
```

- `rawValue` is private — no public property exposes it
- `reveal()` is the only escape hatch, explicitly named to discourage automatic use
- `toString()` always returns `[REDACTED]`
- `ApprovalContinuation` no longer carries raw arguments at all
- persistent stores must encrypt values returned by `reveal()` at rest

### Metadata-only continuation record

```kotlin
data class ApprovalContinuation(
    val approvalId: String,
    ...
    val argumentsDigest: Sha256Digest,
    val createdAt: Instant,
    val approvalExpiresAt: Instant,
    ...
)
```

- `approvalExpiresAt` documents that continuation expiry is bound to the approval challenge expiry
- no second TTL is introduced for continuation payload retention
- `ApprovalContinuation.toString()` is safe by construction because the payload is absent

### Store SPI split

```kotlin
interface ApprovalContinuationStore {
    suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation

    suspend fun get(approvalId: String): ApprovalContinuation?

    suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation
}
```

- `create()` accepts metadata and sensitive payload separately
- `get()` returns metadata only
- `claimForExecution()` returns metadata plus payload exactly once
- `complete()`, `expire()`, and `cancel()` return metadata only

### Exact UTF-8 digest behavior

`Sha256ToolArgumentsDigester` computes SHA-256 over `arguments.reveal().toByteArray(StandardCharsets.UTF_8)` using `java.security.MessageDigest.getInstance("SHA-256")`, producing lowercase hex output in `sha256:<64 hex chars>` format.

Key properties:
- **Deterministic**: same input → same output
- **Whitespace-sensitive**: `"abc"` and `"abc "` produce different digests
- **Key-order-sensitive**: `{"a":1,"b":2}` and `{"b":2,"a":1}` produce different digests
- No normalization or trimming — exact byte-for-byte hashing

### Continuation lifecycle

```
PENDING ──→ CLAIMED ──→ COMPLETED
  │                        │
  ├──→ EXPIRED             │
  └──→ CANCELLED           │
                           └── (terminal)
```

- **PENDING**: initial state, awaiting claim
- **CLAIMED**: claimed by a specific runner for execution
- **COMPLETED**: tool execution finished successfully
- **EXPIRED**: deadline passed, no claim was made
- **CANCELLED**: manually cancelled before claim

`CLAIMED` cannot return to `PENDING`.

### Atomic claim semantics

`claimForExecution()` uses `ConcurrentHashMap.compute()` for atomic read-modify-write and scrubbing:

```kotlin
store.compute(approvalId) { _, current ->
    // exists? version? status == PENDING? now < approvalExpiresAt?
    // capture arguments
    // set status = CLAIMED, claimedBy, claimedAt, version++
    // store arguments = null
}
```

- Single winner under concurrent claims (CAS via `compute()`)
- Stale version → `ApprovalContinuationConflictException`
- Already claimed → `ApprovalContinuationNotClaimableException`
- Expired → `ApprovalContinuationNotClaimableException`
- Winner receives the exact raw JSON payload
- After the winning claim, the stored payload is gone

### Scrubbing rules

- Claim scrubs the stored payload atomically while returning it to the winner
- Expire scrubs the stored payload before persisting `EXPIRED`
- Cancel scrubs the stored payload before persisting `CANCELLED`
- Complete requires the payload to already be scrubbed and retains metadata only

### Claimed-but-incomplete execution is an uncertain outcome

A continuation in `CLAIMED` status means a runner has claimed it but has not yet reported completion. If the runner crashes or times out between claim and completion, the continuation remains `CLAIMED` indefinitely. Future PR #17 will handle this through:
- Claim timeout detection
- Manual override (admin cancel + restart)
- Idempotency keys for safe retry

### No automatic retry after CLAIMED

Once claimed, a continuation is not automatically retried. The engine integration (PR #17) must explicitly handle:
- Claim timeout → manual intervention or policy-driven re-claim
- No `retry(continuation)` logic exists in this PR

### PR #17 owns engine integration

This PR (#16) provides the continuation store only. The following are explicitly deferred to PR #17:
- TramaiEngine suspension
- `ApprovalRequiredException` replacement
- Create `ApprovalChallenge` at `BEFORE_TOOL_EXECUTION`
- Persist continuation
- Expose engine resume API
- Call `ApprovalGateCoordinator.authorizeResume()`
- Call `ApprovalContinuationStore.claimForExecution()`
- Enforce `BEFORE_WORKFLOW_RESUME`
- Execute tool once
- Complete continuation
- Reinject result into provider loop
- Lifecycle audit events
- Idempotency strategy for uncertain outcomes

### BEFORE_WORKFLOW_RESUME remains deferred to PR #17

`BEFORE_WORKFLOW_RESUME` is enumerated in `EnforcementPoint` but has no runtime call site in this PR.

### Test Coverage (32+ tests)

- **SensitiveToolArgumentsTest (6):** toString redacted, reveal returns original, whitespace preserved, empty permitted, oversized rejected, raw JSON absent from toString
- **Sha256ToolArgumentsDigesterTest (6):** known SHA-256 vector, deterministic output, whitespace changes digest, key-order changes digest, typed output, raw JSON absent from output
- **InMemoryApprovalContinuationStoreTest:** validation coverage, leakage coverage, exact-raw winner semantics, metadata-only `get()`, and scrubbing assertions for claim, complete, expire, cancel, and concurrent claim races

### Roadmap split

| PR | Scope |
|----|-------|
| **#16** | Continuation domain model, store SPI, in-memory implementation, sensitive argument wrapper, exact argument digester, atomic claim and completion semantics |
| **#17** | TramaiEngine suspension, ApprovalRequiredException replacement, create ApprovalChallenge at BEFORE_TOOL_EXECUTION, persist continuation, expose engine resume API, call ApprovalGateCoordinator.authorizeResume(), call ApprovalContinuationStore.claimForExecution(), enforce BEFORE_WORKFLOW_RESUME, execute tool once, complete continuation, reinject result into provider loop, lifecycle audit events, idempotency strategy for uncertain outcomes |

*Phase 0 delivery plan. Issues created in GitHub with labels: `phase-1`, `epic-{n}`. See ROADMAP.md for Phase 1 exit criteria.*
