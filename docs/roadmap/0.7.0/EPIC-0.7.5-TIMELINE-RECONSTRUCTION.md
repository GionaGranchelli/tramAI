# Epic 0.7.5 — Semantic Timeline & Forensic Reconstruction

**Branch:** `epic/0.7.5-timeline-reconstruction`  
**Status:** ✅ Closed — all eight candidates delivered; epic gate 5 MET / 3 PARTIAL / 0 NOT MET, final slice merged as [#552](https://github.com/GionaGranchelli/tramAI/pull/552) (`83a69734`). Scope and gaps: [qualification](../0.7.5/evidence/0.7.5h-integration-examples/qualification.md)  
**Dependencies:** 0.7.4 HARD

## Executive decision

Turn authoritative evidence into a typed governance timeline and reconstruct supported historical runs without re-executing providers, tools, approvals, network actions, or workflow side effects.

## Core invariants

```text
reconstruction != re-execution
missing historical evidence remains missing
current configuration is never silently substituted for historical configuration
best-effort telemetry remains distinguishable from authoritative evidence
reconstruction preserves historical enforcement/evidence provenance rather than upgrading it
```

## Scope

- semantic run event model;
- correlated, safe per-model-invocation and per-attempt execution metadata (request/response lifecycle, provider/model identity, outcome, duration, observed usage and optional TTFT); see [model-invocation timeline design](DESIGN-0.7.5-MODEL-INVOCATION-TIMELINE.md);
- truthful distinction between durable evidence, best-effort observation, missing usage and absent production instrumentation;
- deterministic ordering/grouping rules;
- authoritative vs best-effort/missing markers;
- preservation of evidence source, enforcement strength, and runtime/integration provenance from 0.7.4;
- reconstruction input contract and completeness model;
- historical configuration/policy/topology identity resolution;
- side-effect-free reconstruction API/result;
- explicit unsupported/incomplete reconstruction semantics;
- a headless timeline/query view joining governance decisions and execution attempts by evidenced correlation, without introducing a second governance authority.

## Non-goals

- public deterministic policy replay/simulation;
- incident-analysis product suite;
- rerunning providers/tools to fill evidence gaps;
- raw prompts, completions, tool arguments/results, or model reasoning text in generic timeline/query/telemetry;
- model-generated reasoning recovery, provider-specific token accounting guarantees, full cost/billing analytics, subscription windows or a LangSmith/OMP feature-complete dashboard;
- frontend implementation, which belongs to 0.7.8.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.5a | Timeline/reconstruction audit | Characterize available evidence, missing historical identities, existing provider/stream observers, safe token-usage sources and durability/retention gaps; establish which attempts can actually be reconstructed |
| 0.7.5b | Semantic timeline contract | Typed governance transitions and per-attempt model execution metadata, with source/authority markers, provenance, nullability and explicit correlation; no payload content by default |
| 0.7.5c | Timeline projection/query | Deterministic, causally truthful joining of 0.7.4 decisions with observed model attempts, retry/fallback/tool/approval events; support headless attempt detail and safe run-level usage summaries without double counting |
| 0.7.5d | Reconstruction contract | Inputs, historical identity requirements and independent completeness of governance evidence vs execution observations (including unknown usage and missing attempt records) |
| 0.7.5e | Side-effect-free reconstructor | Produce historical governed narrative/state without external invocation |
| 0.7.5f | Missing/history semantics | Explicit partial/unsupported outcomes, unobserved vs zero usage, absent TTFT/reasoning figures and missing historical source data; no current-default substitution |
| 0.7.5g | Adversarial/mutation proof | Trap provider/tool/network invocation, config substitution, duplicate-attempt aggregation, cross-run correlation, invented metrics, provenance upgrades and payload leakage |
| 0.7.5h | Integration/docs | Reference timeline/reconstruction examples and Epic gate |

### Delivery status

| Candidate | State |
|---|---|
| 0.7.5a | Delivered — [timeline/reconstruction audit](../0.7.5/AUDIT-0.7.5a-TIMELINE-RECONSTRUCTION.md) (PR #544) |
| 0.7.5b | Delivered for its **durable attempt-observation** slice only: content-free record, JDBC + in-memory stores, one producer on the non-streaming path, read API. Evidence: [qualification](../0.7.5/evidence/0.7.5b-attempt-observation/qualification.md). The *typed governance transition model* in the same candidate row is **not** delivered; joining decisions to attempts is 0.7.5c's own scope and stays there. |
| 0.7.5c | Delivered for its **read-only projection/query** slice: the timeline contract, the projection over the 0.7.4 governance query and the 0.7.5b attempt store, correlation limited to shared run identity, and the usage summary. Evidence: [qualification](../0.7.5/evidence/0.7.5c-timeline-query/qualification.md). Retry/fallback/tool/approval *events* are **not** delivered: no event log exists to read, and inventing one is 0.7.5d/e scope. |
| 0.7.5d | Delivered for its **contract** slice: reconstruction inputs, a status vocabulary (`SUPPORTED`/`PARTIAL`/`UNSUPPORTED`), derived limitations, and historical configuration identity that is cited rather than substituted. Evidence: [qualification](../0.7.5/evidence/0.7.5d-reconstruction-contract/qualification.md). The reconstruction **engine** is not delivered — that remains 0.7.5e. |
| 0.7.5e | Delivered: `EvidenceBackedReconstructor` reads the materialized governance projection and the recorded attempts through the 0.7.5c query, takes the historical reference from a `HistoricalConfigurationSource` port so a caller cannot inject one, and returns the 0.7.5d result. Side-effect-free by construction: it has no collaborator through which a provider, tool, approval or network call could be made. Evidence: [qualification](../0.7.5/evidence/0.7.5e-reconstructor/qualification.md). No engine loop, export or report assembly. |
| 0.7.5f | Delivered: semantic correctness pass over 0.7.5c-e. "No attempt recorded" no longer reads as "nothing executed" (the limitation follows the observation, not the decisions), a correctly shaped configuration reference is reported as unauthenticated, and the missing/unknown semantics for uninstrumented stores, unqueried governance, zero vs unknown usage, incomplete component totals, finish-reason provenance and retry-vs-invocation are pinned by tests on the shipped read path, including a write-trapping store proving no writes or external invocations. Evidence: [qualification](../0.7.5/evidence/0.7.5f-missing-evidence-semantics/qualification.md). Status vocabulary unchanged. |
| 0.7.5g | Delivered: end-to-end confidentiality proven through the real producer path (engine, OTel span, PostgreSQL columns) with canaries at five origins; zero-write reconstruction with exact read counts; causal, accounting and provenance integrity on both the read and recording paths; 15/15 admissible mutants killed (four first-pass survivors exposed hand-built fixtures and forced two real-path detectors); one production defect found and fixed — OTel provider-failure export carried the raw upstream message and stack trace. Evidence: [qualification](../0.7.5/evidence/0.7.5g-adversarial-mutation/qualification.md). Boundary: caller-supplied collaborators and non-attempt logging remain unverified. |
| 0.7.5h | Delivered: epic gate. Kotlin **and** Java reference examples over the shipped read path (`examples/timeline-reconstruction`, 10 tests), real persistence-to-reconstruction qualification in PostgreSQL for `SUPPORTED`, `PARTIAL` (no governance scope, and empty instrumented history) and `UNSUPPORTED` (uninstrumented store), plus a criterion-by-criterion acceptance matrix that scores the epic **5 MET / 3 PARTIAL / 0 NOT MET** and names each gap instead of re-describing it. Evidence: [qualification](../0.7.5/evidence/0.7.5h-integration-examples/qualification.md). Merged as [#552](https://github.com/GionaGranchelli/tramAI/pull/552) (`83a69734`). Release blocker tracked separately: [#551](https://github.com/GionaGranchelli/tramAI/issues/551) (workflow/worker OTel exception export) must merge before 0.7.0 promotion. |

## Acceptance criteria

- Reference run renders classification, policy, routing, approval/control and execution transitions where evidence exists.
- Missing evidence is visible.
- Reconstruction causes zero provider/tool/approval/network/workflow side effects.
- Historical configuration is used when available; absence is explicit.
- A reconstructed external-runtime event retains whether it was authoritatively enforced, instrumented, observed, or imported according to the evidence available at execution time.
- A supported run with observed provider attempts exposes a request-to-response attempt timeline correlated to the governing run/operation and the corresponding governance decision where correlation exists, including retries and fallback as distinct attempts.
- Where the runtime reports them, input/output token counts, optional thinking-token usage, model identity, latency, finish/error status and streaming TTFT retain their provenance and are available through a headless safe query; unavailable metrics remain unknown rather than zero.
- Repeated imports and multiple observations of one attempt do not inflate run usage; thinking tokens are not added to output tokens unless the provider contract establishes they are disjoint.
- The generic timeline cannot expose request/response bodies, raw reasoning, tool arguments/results, credentials or other protected payloads; content access, if ever needed, requires a separate authorized surface outside this epic.

## Adversarial proof

Use traps/fakes that fail if reconstruction invokes external behavior. Reject silent current-config fallback and timeline entries inferred from absence of evidence. Add discriminators for duplicate and retried model attempts, missing usage mistaken for zero, reasoning-token double counts, telemetry presented as audited authority, incorrect cross-run attribution and protected payload canaries.

## Scope boundary and dashboard handoff

This extends the existing P0 semantic timeline with a **narrow safe invocation-metadata view**, not general LLM observability or content capture. An attempt record is an observation unless backed by an independently verifiable authoritative source; joining it to a policy decision does not change its evidence strength. An external-runtime integration may lack some or all attempt instrumentation; report that gap.

0.7.5 owns durable/observed-source inventory, typed semantics, safe projection, historical query and reconstruction. 0.7.8 owns trace-tree/waterfall presentation, per-attempt details and optional model/usage summaries **only from those headless contracts**. OMP-style local session ingestion, subscription analytics, model pricing catalogs and rich project dashboards are inspiration, not 0.7.0 obligations.

See [DESIGN-0.7.5-MODEL-INVOCATION-TIMELINE.md](DESIGN-0.7.5-MODEL-INVOCATION-TIMELINE.md) for field semantics, evidence requirements, examples, privacy rules, reference comparison and negative test matrix.
