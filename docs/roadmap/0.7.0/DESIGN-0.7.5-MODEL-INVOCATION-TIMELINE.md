# 0.7.5 — Model Invocation Timeline: Backend Contract and Dashboard Handoff

**Status:** Design proposal; no runtime/API implementation or new source-of-truth asserted by this document.
**Owner:** Epic 0.7.5 (backend), Epic 0.7.8 (first-party display).
**Release discipline:** Interpret the 0.7.0 P0 semantic execution timeline narrowly. Do not enlarge the authoritative release-cut contract or move 0.8–0.10 products into 0.7.

## 1. Problem and objective

The current 0.7.5 scope provides a historical governance timeline but does not explicitly require **per-model-invocation / per-attempt execution observations**. Without them an operator cannot reliably answer: Which calls occurred? Which model/deployment? How long did each attempt take? How many tokens were reported? Where did retry/fallback and tool calls occur? Which recorded governance decision constrained each attempt?

Deliver a **headless, privacy-safe, evidence-labelled attempt timeline** usable by Kotlin/Java clients, with a later Dashboard 2.0 view. This is not a mandate to store model prompts or completions.

An example logical hierarchy (concurrent branches may be siblings, not falsely serialized):

~~~
governed run
  operation / step
    governance decision + provider-input release  [authoritative where supported]
    model invocation A                           [observed, source-labelled]
      attempt A.1 — provider timeout             [observed failure]
      attempt A.2 — successful retry             [observed usage]
    tool authorization / execution               [separate decisions and outcomes]
  approval / suspension / resume                 [where evidence exists]
~~~

**An authorization is not execution proof.** A persisted projection is not evidence of a model response unless a real execution-side observation supports it. An OTel span is not automatically durable forensic evidence.

## 2. Existing measured seams, to audit in 0.7.5a

- ModelResponse in tramai-core already contains nullable inputTokens, outputTokens, thinkingTokens, modelUsed and finishReason.
- Streaming UsageMetrics already has nullable input/output/thinking token counters; StreamChunk distinguishes token, completion and error.
- tramai-observability reports model/attempt spans, duration and token metrics when observed. Its opt-in, best-effort export is not a guaranteed historic record.
- tramai-engine TokenBudgetTracker observes some response usage, but its budget snapshot and cost bookkeeping are not per-attempt forensic records.
- 0.7.4 governance query deliberately excludes prompt/response content and requires explicit source currency, raw coverage and record completeness. Its projection is not a complete provider-attempt event log.
- 0.7.4 evidence freeze records that provider.route has no production producer and that S18 workload identity authenticity, S19 event-origin authenticity and T1 authentication are unresolved; do not infer these properties from correlation.

0.7.5a MUST map concrete producer → signal → historical persistence → query availability per supported native and external path. A missing producer or durable store is an explicit gap, not a reason to fabricate reconstructed calls. The task decides the smallest compatible append/retention seam if the supported profile needs persisted safe attempt events; do not introduce a second audit or policy authority.

## 3. Proposed safe per-attempt contract (field meanings, not frozen public API)

| Category | Data | Required/semantics |
|---|---|---|
| Scope | workload/configuration identity, run ID, operation/step ID, invocation ID, attempt ID, parent/causal ID | Scope must be authoritative where claimed; otherwise labelled producer-declared. Every attempt uniquely identified for deduplication; do not merge separate attempts into one response. |
| Governing link | policy/route decision and provider-input release record references | Only if source-backed, never synthesized by matching model names. Explicit unresolved link when correlation is absent. |
| Model/deployment | concrete deployment ID (when known), provider ID, requested model, reported model | Differentiate configured from provider-reported names; brand alone is not deployment authority. |
| Lifecycle | attempt started, completed/failed/cancelled, retry/fallback relation, finish reason/error category | Status reflects observed attempt outcome, never converts ALLOW into completion; non-sensitive error taxonomy only. |
| Timing | start/end time, duration, streaming first-token timestamp/TTFT if captured | Distinguish measured vs inferred; never assume synchronized clocks across sources or provide TTFT for unobserved streams. |
| Usage | nullable provider-reported input, output, thinking/reasoning, cache-read/cache-write counters where available | Preserve source, accounting convention and whether subtypes are included in parent totals. Missing is not zero. |
| Supplemental | response status, optional safe structured counters and opaque content *digest only when its threat model is reviewed* | Closed allowlist, no raw content, free text, user-supplied arbitrary metadata or payload-derived previews. |
| Evidence | source type, trust/enforcement claim, source position/coverage, version/freshness, recorded-at time | Runtime observation is not audited policy authority; historical availability and completeness independently reported. |

Request metadata and response metadata are in scope. **Request bodies, response bodies, tool arguments/results, messages, user text and raw chain-of-thought/reasoning are not.** A provider's thinking-token *count* is not access to its private reasoning.

## 4. Usage and aggregation invariants

1. Source counters are nullable and non-negative when supplied. Unknown must render as unknown/N/A, not zero or free.
2. Preserve the provider's token semantics: thinking/reasoning is often a subtype of output and cache read/write may be subtypes of input. Never add subtypes twice. A breakdown may be labelled non-additive or unavailable until the adapter establishes the provider convention.
3. Aggregate unique terminal attempts, **not** operation summaries plus child attempts. Retried attempts are distinct billable/usage observations when reported; a second export of the same attempt does not add usage.
4. A request rejected before dispatch has no provider-usage observation; do not infer a zero-token provider call. Aborted streams can have partial usage or no terminal usage: report the exact state.
5. Distinguish measured wall duration from service latency, TTFT and generation throughput. Derive tokens/sec only where a compatible output-token denominator and measured interval exist, with a stated convention.
6. Cost is optional and **non-authoritative**: preserve reported cost vs estimated API-equivalent price, currency, pricing source/version and valuation timestamp. Never equate estimates or subscription-plan usage with charges. A pricing engine and billing dashboard are out of scope for 0.7.0.
7. No per-tool attribution of a turn's full model tokens without a documented attribution rule; never present a heuristic split as provider-measured tool usage.
8. Parent rollups cannot count child token observations twice. Test session/run/operation/attempt aggregation, duplicate ingestion, branching/fork and missing attribution independently.

## 5. Ordering, history and query constraints

- Model causal relationships using evidenced parent/correlation and per-source position; concurrent calls do not become a fabricated global total order.
- Stable deterministic ordering/tie-breaking for presentation must not claim that wall-clock order proves causality. Distinguish observed occurrence time, persisted time and query time.
- Side-effect-free timeline/reconstruction reads may combine persisted authoritative governance facts and separately persisted safe observations, with two independent completeness/currency results.
- Failed, unsupported, incomplete, uninstrumented, sampled-out and not-retained histories must remain distinguishable where the source can establish the reason; otherwise report unknown.
- Implement bounded pagination/filtering by run, operation, attempt, provider/model, time and outcome as justified in 0.7.5a; no UI-specific data authority.
- Source traps must prove zero provider/tool/approval/network execution during reconstruction. Reconstruction does not refetch missing prompts or turn on tracing retroactively.
- Headless historical query access is subject to the current 0.7.0 privilege/exposure boundary. Do not add a new unauthenticated HTTP endpoint.

## 6. Privacy and provenance

The 0.7.0 release cut and QUALITY-GATES G3 prohibit raw prompts, completions, credentials, tool arguments/results and protected document bodies on **generic** control-plane, query and telemetry surfaces. Reuse the established safe allowlist and test secret-shaped canaries across object accessors, serialization, error messages and OTEL.

A raw payload capture feature, if ever desired, requires its own explicit authorization, purpose, retention/erasure controls, separate storage surface, redaction policy, encryption/access tests and release-cut decision. It is **not implemented or authorized by this design**. Do not store or reveal model chain-of-thought.

External-runtime instrumentation is only authoritative at a separately proven declared enforcement boundary; attempt telemetry from an external application remains producer-attested/observed unless authenticated independently.

## 7. Inspiration — transfer the pattern, not the product

**LangSmith:** nested traces of model, tool and agent operations; per-child usage and rollups; latency/cost visibility; input/output token categories and subtypes including reasoning and cache. Adopt trace hierarchy, causal navigation and truthful usage provenance. Do not copy default content inspection or make a hosted tracing backend mandatory.

**Oh My Pi (OMP):** local session-derived usage statistics, per-model/project breakdowns, TTFT, tokens/sec, cache read rate/savings and API-equivalent cost estimates. OMP also added an interactive trace timeline with searchable/filterable transcripts and tool timing. Adopt low-overhead local-first optionality and useful metadata-driven metrics, but do not add OMP's transcript capture, project analytics, subscription windows or user-frustration scoring to 0.7.0.

Reference documents (consulted 2026-10-19):
- LangSmith observability: https://www.langchain.com/langsmith/observability
- LangSmith cost tracking / token subtypes: https://github.com/langchain-ai/docs/blob/main/src/langsmith/cost-tracking.mdx
- OMP stats README: https://github.com/can1357/oh-my-pi/blob/main/packages/stats/README.md
- OMP stats changelog (2026-09-01 Traces, 2026-10-07 dashboard): https://github.com/can1357/oh-my-pi/blob/main/packages/stats/CHANGELOG.md

These are external product references, not evidence that TramAI currently implements comparable functionality.

## 8. Explicit division of responsibility

**0.7.5 backend:** audit real producers, define safe typed attempt metadata and provenance, decide minimum durable historical availability, correlate with governance, expose headless detail, truthful completeness and supported rollups, prove security/causality/side-effect guarantees.

**0.7.8 Dashboard:** optional waterfall/trace-tree visualization, per-attempt status/tokens/timing and appropriate per-model/run summaries through the 0.7.5 API. Disable unavailable metrics and do not read raw stores or reconstruct authorization in browser code.

**Later (0.8–0.10 or separately approved):** broader trace-product UX, cross-session analytics, price catalogs and invoice reconciliation, rich live monitoring, content/response inspection with explicit authorization, generalized ingestion of external session stores.

## 9. Test and acceptance matrix

| Discriminator | Expected failure if weakened |
|---|---|
| Logical call retries after timeout then succeeds | Collapsing attempts, inventing success on attempt 1, dropping attempt 2 |
| Same attempt exported twice or session fork imported | Double-counting usage |
| Provider reports output=100 and thinking=30 already included | Incorrect total of 130 rather than 100 |
| Missing thinking/input/TTFT/cost | Presenting zero, free, or measured TTFT without a signal |
| Partial failed stream with no terminal usage | Fabricated provider count |
| Multiple deployments with same brand/model | Cross-linking decisions by display name |
| Concurrent sibling attempts with skewed wall clocks | Fabricated causal order |
| Absent live producer or dropped telemetry | Reconstructing imaginary attempt/completion |
| Policy ALLOW, attempt fails before response | Claiming completed execution from authorization |
| Observed external attempt vs registered governed boundary | Upgrading observer claim to authoritative enforcement |
| Secret canaries in prompt, response, tool arguments, provider exception | Payload leakage in timeline/query, diagnostics or OTEL |
| Provider/tool/network trap during reconstruction | Any external invocation |
| Mutated historical policy/config IDs | Silent substitution of current policy/config |
| Per-run aggregation with nested operation and attempt summaries | Double-counting child/parent usage |

**0.7.5 acceptance:** deliver a deterministic side-effect-free headless timeline that correlates real observed attempts with governance decisions where evidence exists, exposes correctly labelled optional request/response metadata, and never fabricates unavailable history or discloses protected content. It is acceptable to expose an explicit uninstrumented/unsupported result for external providers not qualified by 0.7.5a.

No UI, runtime, adapter, schema migration, or publishable API change is authorized by this documentation PR.
