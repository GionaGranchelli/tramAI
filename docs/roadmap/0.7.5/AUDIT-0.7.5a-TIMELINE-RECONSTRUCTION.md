# 0.7.5a — Timeline and reconstruction audit

**Status:** Audit. No implementation. Documentation only.
**Base:** `release/0.7.0` = `e9ab1e8a` (0.7.4 closed)
**Design under audit:** [DESIGN-0.7.5-MODEL-INVOCATION-TIMELINE.md](../0.7.0/DESIGN-0.7.5-MODEL-INVOCATION-TIMELINE.md) (merged in #540)

This document answers three questions with code references: what TramAI can already reconstruct, what it
cannot, and the smallest next implementation task. It does not authorise any of it.

---

## 1. Existing capabilities

### 1.1 Per-attempt observation already exists — in process only

`tramai-core/src/main/kotlin/dev/tramai/core/observation/OperationObservation.kt`

```kotlin
data class OperationCallContext(
    val serviceInterface: String,   // invoked service interface
    val methodName: String,
    val providerId: String,         // provider selected for the attempt
    val requestedModel: String,     // model requested by the operation definition
    val attempt: Int,               // zero-based attempt within the retry loop
)

interface OperationObservation {
    fun onProviderResponse(response: ModelResponse)
    fun onProviderFailure(error: Throwable)
    fun onStructuredParseFailure(rawResponse: String, errorSummary: String)
    fun onEngineEvent(...)
    fun onCallCompleted(parseSuccess: Boolean?)
    fun onCallCancelled()
}
```

A **per-attempt seam with attempt index, provider and requested model already reaches a consumer**, and the
engine's failure-isolating wrapper (`FailureIsolatingOperationObserver.kt`) guarantees observer failures cannot
change the business outcome. Nothing in this path persists: the only shipped consumer forwards to OpenTelemetry.

**What the context does *not* carry** — and 0.7.5b must bridge from the engine rather than assume:

| Missing from `OperationCallContext` | Consequence if not bridged |
|---|---|
| `workflowRunId` / `correlationId` | an attempt cannot be attached to a governed run, so it cannot meet the governance decision at all |
| `routeIndex` / next-route identity | a retry cannot be distinguished from a fallback to a different route |
| a unique invocation id | **`attempt` alone is insufficient**: one run can invoke the same operation repeatedly, and attempt 0 of the second invocation collides with attempt 0 of the first |

Identity must therefore be joined in by the producer, from `GovernedRunIdentity`
(`tramai-core/.../identity/GovernedRunIdentity.kt:18`) and the route actually executed, not inferred from
`attempt`.

### 1.2 Response-side usage and identity

| Fact | Location | Shape |
|---|---|---|
| `inputTokens`, `outputTokens`, `thinkingTokens`, `modelUsed` | `tramai-core/.../model/ModelResponse.kt:29-39` | **nullable** — `null` means not reported |
| `finishReason` | `ModelResponse.kt:41` | **not nullable**: `FinishReason = FinishReason.STOP` |
| streaming `UsageMetrics(input, output, thinking, imageCount, imageTokensEstimate)` | `tramai-core/.../model/Streaming.kt:8-14` | all nullable |
| `StreamChunk` = `Token` / `Completion` / `Error` | `Streaming.kt:19-36` | sealed; no timestamps |

Nullability is the honest signal for the token fields: `null` means the provider did not report it. `modelUsed`
is the *answering* model, distinct from `requestedModel` — the two must never be collapsed (design §9,
"multiple deployments with same brand/model").

**`finishReason` is the exception and must not be treated like the token fields.** It defaults to
`FinishReason.STOP`, so a provider that reported nothing and a provider that reported a normal stop are
indistinguishable at this type. A timeline record must therefore carry the finish reason as *observed-or-absent*
(for example a nullable value plus an explicit "reported" flag), never copy `STOP` into a record as though the
provider had said it. This is a contract clarification for 0.7.5b, not a change to `ModelResponse` here.

### 1.3 Retry and fallback structure

`tramai-engine/src/main/kotlin/dev/tramai/engine/streaming/` — `StreamingRouteAttempts.kt`,
`StreamingGovernedWalk.kt`, plus `ProviderFallbackGate` / `ProviderFallbackTransition`, and
`StreamingCandidate(route, routeIndex, nextRoute)`. The engine already knows, per attempt, which route it is on,
which route is next, and whether fallback authority is still available. Attempts are distinguishable in memory;
they are not distinguishable after the process exits.

### 1.4 Durable audit chain (authoritative decisions)

`tramai-security/src/main/kotlin/dev/tramai/security/audit/AuditEvent.kt`

```kotlin
data class AuditEvent(
    val schemaVersion: Int, val hashAlgorithm: AuditHashAlgorithm,
    val auditStreamId: String, val eventId: String, val sequenceNumber: Long,
    val workflowRunId: String?, val correlationId: String?,
    val actor: String?, val enforcementPoint: String, val decision: String,
    val policyVersion: String?, val workflowDigest: String?,
    val previousEventHash: String?, val eventHash: String,
    val timestamp: Instant, val reasonCode: String?,
    val metadata: Map<String, String> = emptyMap(),
)
```

Implemented stores: `InMemoryAuditStore` (`tramai-security/.../audit/InMemoryAuditStore.kt`),
`FileAuditStore` (`tramai-persistence-file/.../file/FileAuditStore.kt:47`), and `JdbcAuditStore`
(`tramai-persistence-jdbc/.../jdbc/JdbcAuditStore.kt:76`, wired by
`tramai-spring-boot-starter-sovereign-persistence-jdbc/.../SovereignJdbcPersistenceAutoConfiguration.kt:195`).
Hash-chained and verifiable (`AuditChainVerifier.kt`). The policy-decision emitter already writes **`providerName` and `modelName`** into the
safe metadata map (`AuditEnginePolicyDecisionAuditEmitter.kt:95-96`), and `policyVersion` / `workflowDigest` give
the historical configuration reference the design asks for.

### 1.5 Governance projection and query (0.7.4)

`tramai-security/src/main/kotlin/dev/tramai/security/governance/JdbcGovernanceProjectionStore.kt`

- `governance_projection_snapshot`: `workload_id`, `environment_id`, `deployment_id`, `workflow_run_id`,
  `audit_stream_id`, `classification_revision`, `registration_version`, `audit_head_position`,
  `audit_head_hash`, `semantic_digest`, `materialized_at`, `format_version`.
- `governance_projection_fact`: `fact`, `authority`, `freshness`, `established`, `must_not_infer`,
  `value_present`, `observed_version`, `source_version`, `watermark`.
- `governance_projection_decision`: verified range, completeness, `duplicates_ignored`, `stale_ignored`,
  and the latest decision with boundary id / strength / authority / freshness / versions / watermark.

Query: `StoreBackedGovernanceQueryService` over `GovernanceQueryContract`/`GovernanceQueryView`, filtered by
workload / environment / deployment / `workflowRunId`. The result is **a fold over the audit stream into a
current-state snapshot**, not an append-only event timeline, and it carries `completeness`, `value_present` and
`must_not_infer` precisely so a consumer cannot read absence as a negative finding.

### 1.6 Budget bookkeeping (not forensic)

`tramai-engine/.../budget/TokenBudgetTracker.kt` — `internal`, in-memory aggregates
(`totalInputTokensObserved`, `totalOutputTokensObserved`, cost accumulators) exposed as a `snapshot()`. Run-scoped
totals with no attempt identity, no timestamps, no durability. It cannot answer "which attempt", and it is not
exposed as a public contract.

---

## 2. Available / missing evidence table

Legend: **YES** durable and queryable · **MEM** in memory only · **OTEL** optional best-effort export only ·
**NONE** does not exist.

| Fact needed by a semantic timeline | State | Evidence |
|---|---|---|
| Governance decision per run (allow/deny, reason, boundary, strength) | YES | audit chain + projection decision rows |
| Policy / config version at decision time | YES | `AuditEvent.policyVersion`, `workflowDigest`, `classification_revision`, `registration_version` |
| Workload / environment / deployment identity | YES | projection snapshot keys |
| `workflowRunId` and `correlationId` | YES (nullable) | `AuditEvent`; engine passes `authority.correlationId`; identity type `tramai-core/.../identity/GovernedRunIdentity.kt:18` |
| Provider name and model name at decision time | YES (metadata) | policy-decision emitter metadata |
| That an attempt happened at all | MEM/OTEL | `OperationCallContext` → observer only |
| Per-attempt index and provider/requested model | MEM/OTEL | `OperationCallContext.attempt/providerId/requestedModel` |
| Answering model (`modelUsed`) per attempt | NONE durable | `ModelResponse.modelUsed` never persisted |
| Attempt duration | OTEL | `OperationObservation` timing in the OTel observer |
| Input / output / thinking tokens per attempt | NONE durable | `ModelResponse`/`UsageMetrics` fields; observed, then dropped |
| Finish reason per attempt | NONE durable | `ModelResponse.finishReason` — **non-nullable, defaults to `STOP`**, so it cannot by itself distinguish a provider report from a default |
| TTFT (time to first token) | **NONE** | `grep -r 'timeToFirstToken\|ttft'` over `*.kt` → **no matches** |
| Retry / fallback lineage (which route, which next) | MEM | `StreamingCandidate.routeIndex/nextRoute`, `ProviderFallback*` |
| Streaming attempt terminal usage | NONE guaranteed | `StreamChunk` has no timestamps; design §9 "partial failed stream with no terminal usage" |
| Which governance decision constrained which attempt | **NONE** | audit events carry no attempt id; projection is a snapshot, not a per-attempt log |
| Prompt / response / tool content | Absent **by design** | 0.7.4f exposure contract; keep it that way |

### The three gaps that matter

1. **No durable per-attempt record.** Everything attempt-scoped is an in-process callback or an optional span.
   A restarted process, a sampled-out exporter or a default deployment reconstructs nothing.
2. **No attempt ↔ decision link.** The audit chain knows the decision, the provider and the model, and the run
   correlation ids; the engine knows the attempt index. Nothing joins them, because no attempt identity is ever
   written where the decision is written or vice versa.
3. **Usage and TTFT are observed and discarded.** The fields exist and the values arrive; there is simply no
   sink. TTFT is not even expressed.

---

## 3. Minimum recommended architecture

Reuse, do not invent. Three additions, each justified by a gap above and nothing more:

**A. One typed attempt record** — `ModelAttemptObservation` (attempt-scoped, content-free):

```text
schemaVersion, workflowRunId?, correlationId?, attempt, routeIndex,
providerId, requestedModel, modelUsed?,            // requested vs answered, never merged
startedAt, completedAt, durationMillis,
outcome (SUCCEEDED | FAILED | CANCELLED | UNKNOWN), failureClass?,
finishReason?, finishReasonReported: Boolean,      // ModelResponse defaults to STOP; do not copy a default as fact
inputTokens?, outputTokens?, thinkingTokens?,      // null = provider did not report
timeToFirstTokenMillis?,                           // null until a producer can measure it
source = OBSERVED_IN_PROCESS                        // never upgraded to "authoritative"
```

**B. An append-only attempts store** — `AttemptObservationStore` (append + read by run/correlation/attempt/
invocation id), with **JDBC persistence** reusing the existing `DataSource` wiring pattern and an **in-memory
implementation for tests**. A file implementation is optional, not required, and is not part of 0.7.5b's
acceptance.
Deliberately **not** the audit chain: attempt telemetry is observed, the chain is authoritative evidence, and
0.7.4's central invariant is that observed telemetry never becomes proof of enforcement. Same persistence
pattern, separate surface, explicit label.

**C. One producer** — an `OperationObserver` implementation that writes exactly one record per attempt. No new
event framework: the seam, the callbacks and the failure isolation already exist. No consumer configured means
no persistence, and the timeline query then answers `uninstrumented` rather than inventing attempts.

Correlation uses `GovernedRunIdentity`/`correlationId` when present; when absent the field stays `null` and the
timeline reports the run as uncorrelated instead of guessing (design §5).

Reconstruction is read-only: the store is read, nothing is re-executed, no provider or tool is invoked, and
tracing cannot be enabled retroactively.

---

## 4. Bounded scope for 0.7.5b

**In scope**

1. `ModelAttemptObservation` + `AttemptObservationStore` SPI, with **JDBC** persistence and an **in-memory**
   implementation for tests. A file store is optional and out of acceptance.
2. Producer on **one** native execution path: the non-streaming provider attempt in `tramai-engine` (where
   `ModelResponse` with usage is available and terminal).
3. Run identity bridged explicitly from the engine: `workflowRunId`, `correlationId`, `routeIndex` and a unique
   invocation id travel with the record, because `attempt` alone collides across repeated invocations of the same
   operation in one run.
4. `finishReason` recorded as observed-or-absent, never as the `STOP` default standing in for a provider report.
5. Record written once per attempt including failures, with `outcome` explicit.
6. Retention of nulls as nulls: unknown usage, unknown model, unknown TTFT, unknown correlation.
7. A read API for attempts by `workflowRunId`/`correlationId`/invocation id, returning attempts **plus** an
   explicit completeness/unavailability state, so a partial history cannot be read as a complete one.
8. Tests for the design's own discriminators that apply to this path: retry-then-succeed keeps two attempts; the
   same attempt recorded twice is not double-counted; thinking tokens already included in output are not added;
   missing usage is not zero; a failed attempt with no response is not a completion; ALLOW plus failure is not a
   completed execution; secret canaries do not appear in records, diagnostics or queries; zero provider/tool/
   network activity during reconstruction.

**Explicitly out of scope for 0.7.5b** (each needs its own justification and evidence)

- Streaming attempts and TTFT measurement (no terminal-usage guarantee, no chunk timestamps).
- Fallback lineage beyond `routeIndex` (the engine knows it; proving it durably is a separate claim).
- Cost, pricing, per-model rollups, aggregation across runs.
- Any UI (0.7.8), any new HTTP endpoint, any prompt/response/reasoning capture.
- External-runtime attempts and `provider.route` producers (0.7.4 G4 has no live producer).
- Correlating attempts to *individual* audit positions: 0.7.5b goes as far as run + correlation, and must state
  that limitation rather than imply per-decision provenance.

**Acceptance for 0.7.5b:** a deterministic, side-effect-free query returns the observed attempts of a run with
correct labels; absent history returns `unavailable`/`uninstrumented`; nothing is fabricated; no protected
content appears; and the 0.7.4 governance query is unchanged.

---

## 5. What this audit does not claim

No production code, schema, API or workflow change is proposed here. No statement in this document is evidence
that 0.7.5b is implemented. S18 (workload identity), S19 (event-origin) and T1 (caller authentication) remain
unresolved from 0.7.4, so an attempt record will inherit the same limit: it can say an attempt was observed, not
that the caller or the workload behind it was authenticated.
