# Epic 0.7.8 — Dashboard 2.0 & Release Integration

**Branch:** `epic/0.7.8-dashboard-integration`  
**Status:** ⚪ Planned  
**Dependencies:** 0.7.3–0.7.7 HARD

## Executive decision

Deliver Dashboard 2.0 as a first-party, replaceable client of the headless control-plane contracts and use it to prove the complete 0.7 operational story without relocating authority into the UI.

## Scope

- workload inventory/detail;
- identity/version/owner/purpose/environment/lifecycle posture;
- runtime/framework identity plus integration/enforcement provenance where relevant, without implying that TramAI authored external workloads;
- effective classification/policy/trust posture;
- provider/model authorization/selection reasons;
- semantic run timeline, including evidenced per-model-attempt timing/usage and governance correlation from 0.7.5;
- approval/runtime state supported by existing contracts;
- authorized controls through 0.7.6/0.7.7 APIs;
- reconstruction/evidence availability;
- authentication integration as a client;
- reference scenario and release certification integration.

## Non-goals

- UI policy engine;
- direct database/checkpoint/store mutation;
- rich policy authoring/simulation/debugger;
- enterprise deployment packaging;
- exposing sensitive payloads by default;
- reconstructing attempt history from browser telemetry or implementing provider/token accounting in the UI;
- full LangSmith-style trace ingestion, OMP local-session synchronization, pricing catalogues, subscription analytics or unrestricted transcript browsing in the 0.7.0 core.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.8a | Dashboard baseline/API inventory | Remove/avoid hidden authority assumptions; map required headless APIs |
| 0.7.8b | Control-plane client layer | Typed client models for workload/run/decision/timeline/control/reconstruction |
| 0.7.8c | Workload/detail surface | P0 governance posture, recent operational state, runtime/framework identity, and truthful integration/enforcement provenance |
| 0.7.8d | Semantic run/timeline surface | Render authoritative/best-effort/missing distinctions; optional trace-tree/waterfall grouping for run → operation → model attempts, retries, governance/tool/approval events and per-attempt safe usage, latency and TTFT from the 0.7.5 headless API |
| 0.7.8e | Approval/control surface | Only server-authorized actions; stale/denied states handled safely |
| 0.7.8f | Reconstruction/evidence surface | Expose availability/completeness without re-execution |
| 0.7.8g | Security/privacy/adversarial proof | No direct-store mutation, UI-only auth, or protected-payload leakage |
| 0.7.8h | End-to-end release proof | Reference governed route + persisted cancellation scenarios; release docs/certification |

## Core invariants

```text
Dashboard is replaceable without changing governance semantics
Dashboard contains no policy engine
Dashboard cannot bypass server-side authorization
Dashboard does not mutate authoritative stores directly
protected payloads remain hidden by default
```

## Acceptance criteria

- Full P0 loop is usable headlessly and through Dashboard 2.0.
- UI displays structured reasons/evidence from control-plane contracts rather than recreating decisions.
- Invocation attempt details, where observed, display provider/model, timing, outcome and token counts without fabricating missing values or implying that a policy decision proves a provider call completed.
- Usage/cost summaries are shown only when source data and accounting provenance support them; reasoning token counters are not silently added to output totals, and inferred API-equivalent prices are never represented as billed amounts.
- The XR1 reference external workload can display its runtime separately from TramAI governance authority and cannot be visually upgraded from observed/instrumented evidence to authoritative enforcement.
- Authorized controls flow through typed server APIs.
- Reference sensitive-input scenario proves classification → policy → authorized route → evidence → observation/control → reconstruction.
- Persisted suspended-run scenario proves authoritative cancellation and no-reactivation.
- Exact release-head certification is green before promotion to `master`.

## Adversarial proof

Reject hidden frontend authorization, direct persistence access, reconstructed policy logic in TypeScript/UI, stale controls without server rejection, protected-payload default exposure, reconstruction implemented as rerun/retry, or UI language that represents observed/instrumented external-runtime evidence as authoritative TramAI enforcement. Reject UI-only token aggregation that double-counts retries, silently substitutes zero for unknown usage, or treats cost estimates as actual invoices.

The invocation metadata, persistence and query semantics belong to [0.7.5](EPIC-0.7.5-TIMELINE-RECONSTRUCTION.md), with a field-by-field design and reference comparison in [the 0.7.5 invocation timeline design](DESIGN-0.7.5-MODEL-INVOCATION-TIMELINE.md).
