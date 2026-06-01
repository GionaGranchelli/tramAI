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

**Known gap — classified request propagation:**
The current engine does not yet propagate classified request context through
every provider invocation path. `BEFORE_PROVIDER_INVOCATION` still does not
consistently receive request classification, so full data-sovereignty
enforcement is not implemented yet. Closing that gap requires:
- `Topic 1.6`: Propagate classified request context (payload/metadata, not a new annotation) through provider invocation for all paths.
- `Topic 1.7`: Enforce egress policy at `BEFORE_PROVIDER_INVOCATION` once classification context is available.

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

#### 2.2 — Rule-based classification engine
- Implement regex/metadata-based classification rules
- Configurable via application.yml or programmatic
- **Acceptance:** Document matching rule → correct classification

#### 2.3 — Provider routing by classification
- RESTRICTED → LOCAL_ONLY, block cloud
- CONFIDENTIAL → LOCAL_ONLY or EU_ONLY
- INTERNAL → APPROVED_CLOUD
- PUBLIC → ANY_APPROVED
- **Acceptance:** Wrong provider for classification → Deny

#### 2.4 — No silent fallback
- If local model unavailable, do NOT fall back to cloud for RESTRICTED data
- Configurable fallback policy per classification
- **Acceptance:** RESTRICTED + local model down → PolicyViolationException, not silent cloud call

#### 2.5 — Classification routing tests
- Each classification level routed correctly
- Fallback blocked for restricted classifications
- Provider policy annotation overrides defaults
- **Acceptance:** Classification matrix tests pass

**Epic Exit Criteria:**
- [ ] RESTRICTED data never reaches unauthorized provider
- [ ] Classification routing works for all 4 levels (reference defaults; organizations may override)
- [ ] No silent fallback from local to cloud for restricted data

---

## Epic 2B: Output and Tool-Result Filtering

**Goal:** Prevent sensitive data leakage through model outputs and tool results.

### Issues

#### 2B.1 — DlpInterceptor SPI
- Define DlpInterceptor interface for output scanning
- Default no-op implementation
- **Acceptance:** SPI compiles, default passes through

#### 2B.2 — Field-level output policies
- Annotate output fields with sensitivity level
- Redact fields above configured threshold before returning
- **Acceptance:** Sensitive fields redacted; non-sensitive fields preserved

#### 2B.3 — Tool-result minimization hook
- Filter tool results before reinjection into model context
- Configurable per-tool minimization rules
- **Acceptance:** Tool results filtered before model sees them

#### 2B.4 — Redaction audit events
- Emit audit event when DLP redacts content
- Record field name, rule matched, not the redacted value
- **Acceptance:** Redaction events in audit trail; no sensitive data in audit

#### 2B.5 — Negative tests for valid-schema data leakage
- Test: valid JSON output containing PII → redacted
- Test: tool result containing secrets → filtered
- Test: redaction events emitted and verifiable
- **Acceptance:** Schema-valid outputs with PII are caught by DLP layer

**Epic Exit Criteria:**
- [ ] DLP SPI implemented with rule-based first pass
- [ ] Field-level redaction works for annotated fields
- [ ] Tool results filtered before context reinjection
- [ ] Schema-valid-but-leaky outputs blocked by DLP layer

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
| 5 | Epic 2: ClassifiedDocument, rule-based classification, provider routing |
| 5–6 | Epic 2B: DlpInterceptor SPI, field-level policies, tool-result filtering |
| 6 | Epic 3: ApprovalStateMachine, workflow suspension |
| 7 | Epic 4: AuditEngine, hash chain, fail modes |
| 8 | Epic 5: Sovereign Invoice Analyzer demo, integration tests, evidence pack |

---

*Phase 0 delivery plan. Issues created in GitHub with labels: `phase-1`, `epic-{n}`. See ROADMAP.md for Phase 1 exit criteria.*
