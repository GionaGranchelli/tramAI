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

**Goal:** Introduce `tramai-security` with deny-by-default policy enforcement at all 8 mandatory enforcement points.

### Issues

#### 1.1 — PolicyEngine SPI, PolicyDecision, and enums
- Create `PolicyEngine` interface, `PolicyContext`, `PolicyDecision` sealed interface in `tramai-core`
- Add `EnforcementPoint` enum (8 values)
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
- Add mandatory `policyEngine.evaluate()` calls at all 8 enforcement points
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

#### 2B.3 — Tool-result minimization hook ⏳
- Filter tool results before reinjection into model context
- Configurable per-tool minimization rules
- **Acceptance:** Tool results filtered before model sees them
- **Status:** Deferred to follow-up PR (DlpContentType.TOOL_RESULT type exists, RuleBasedDlpInterceptor supports `enabledFor` including TOOL_RESULT, but engine hook only applies to MODEL_OUTPUT)

#### 2B.4 — Redaction audit events ⏳
- Emit audit event when DLP redacts content
- Record field name, rule matched, not the redacted value
- **Acceptance:** Redaction events in audit trail; no sensitive data in audit
- **Status:** Deferred to follow-up PR

#### 2B.5 — Negative tests for valid-schema data leakage ⏳
- Test: valid JSON output containing PII → redacted
- Test: tool result containing secrets → filtered
- Test: redaction events emitted and verifiable
- **Acceptance:** Schema-valid outputs with PII are caught by DLP layer
- **Status:** Deferred to follow-up PR (compensating coverage provided by RuleBasedDlpInterceptor unit tests)

**Epic Exit Criteria:**
- [x] DLP SPI implemented with rule-based first pass
- [ ] Field-level redaction works for annotated fields
- [ ] Tool results filtered before context reinjection
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
| `DlpInspectionException` | Thrown when DLP inspection fails — distinct from provider failures |

### RuleBasedDlpInterceptor (`tramai-security`)

- **Configuration:** `RuleBasedDlpConfiguration(rules, maxTextLength)` with default 100K limit
- **Validation (init):**
  - `maxTextLength` in (0, 10_000_000]
  - Non-blank rule IDs (unique)
  - Non-blank patterns
  - Non-empty `enabledFor` sets
- **Deterministic ordering:** Rules applied in constructor-declared order
- **Content-type filtering:** Each rule has `enabledFor: Set<DlpContentType>` (default `MODEL_OUTPUT`)
- **Security properties:** No raw matched values in `DlpRedaction` or exceptions; fixed exception messages
- **Oversized input:** Rejected with `IllegalArgumentException("Input text exceeds maximum allowed length")` — no input content leaked
- **Duplicate redaction counting:** `DlpRedaction.replacementCount` reports total matches per rule
|- **Zero-width safety:** Uses `Matcher.find() + appendReplacement() + appendTail()` loop — `appendReplacement` safely handles zero-width matches (lookahead, boundary anchors) without consuming characters or looping infinitely. `quoteReplacement()` escapes `$` and `\` for literal insertion.
|- **Literal replacements:** Replacement strings are passed through `Matcher.quoteReplacement()` before `appendReplacement()` — `$1`, `\value`, and other special characters are escaped for literal output.

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
- **Raw return, structured parsing, chat memory, and cache** all use sanitized content
- Only `DlpContentType.MODEL_OUTPUT` is scanned in this pass
- `toolCalls` on the response are never modified
- Short-circuit when `dlpInterceptor === NoOpDlpInterceptor` (zero overhead for default config)

### DLP Failure Isolation

DLP failures are strictly separated from provider failures:

- A DLP inspection exception (`DlpInspectionException`) does NOT call `observation.onProviderFailure()`
- It does NOT call `circuitBreaker.onFailure()` — DLP failures do not poison provider circuit breakers
- It does NOT trigger provider retries (DLP is deterministic per response)
- It does NOT trigger fallback (response content cannot be returned unsanitized)
- It DOES call `observation.onCallCompleted(parseSuccess = null)` exactly once — the call lifecycle completes even when DLP rejects the response
- It DOES emit `tramai.dlp.inspection_failed` engine event for observability
- `CancellationException` is caught separately and rethrown unchanged before DLP or provider failure handling
- The `DlpInspectionException` propagates directly to the caller

### Cache Behaviour

Cache eligibility (`isSafeCacheEligible`) requires `dlpInterceptor === NoOpDlpInterceptor`.
Custom DLP interceptors **bypass the cache entirely** — every provider response is freshly
sanitized.

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
- Custom DLP disables cache
- NoOp DLP preserves existing behavior
- Custom DLP interceptor without redaction metadata still applies sanitized text

### Deferred Work (follow-up PRs)
- **Field-level output policies** (2B.2) — annotation-driven per-field redaction
- **Tool-result filtering** (2B.3) — engine hook for `DlpContentType.TOOL_RESULT`
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

*Phase 0 delivery plan. Issues created in GitHub with labels: `phase-1`, `epic-{n}`. See ROADMAP.md for Phase 1 exit criteria.*
