# Epic 0.7.8 — Dashboard 2.0 & Release Integration

**Branch:** `epic/0.7.8-dashboard-integration`  
**Status:** ⚪ Planned  
**Dependencies:** 0.7.3–0.7.7 HARD

## Executive decision

Deliver Dashboard 2.0 as a first-party, replaceable client of the headless control-plane contracts and use it to prove the complete 0.7 operational story without relocating authority into the UI.

## Scope

- workload inventory/detail;
- identity/version/owner/purpose/environment/lifecycle posture;
- effective classification/policy/trust posture;
- provider/model authorization/selection reasons;
- semantic run timeline;
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
- exposing sensitive payloads by default.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.8a | Dashboard baseline/API inventory | Remove/avoid hidden authority assumptions; map required headless APIs |
| 0.7.8b | Control-plane client layer | Typed client models for workload/run/decision/timeline/control/reconstruction |
| 0.7.8c | Workload/detail surface | P0 governance posture and recent operational state |
| 0.7.8d | Semantic run/timeline surface | Render authoritative/best-effort/missing distinctions |
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
- Authorized controls flow through typed server APIs.
- Reference sensitive-input scenario proves classification → policy → authorized route → evidence → observation/control → reconstruction.
- Persisted suspended-run scenario proves authoritative cancellation and no-reactivation.
- Exact release-head certification is green before promotion to `master`.

## Adversarial proof

Reject hidden frontend authorization, direct persistence access, reconstructed policy logic in TypeScript/UI, stale controls without server rejection, protected-payload default exposure, and reconstruction implemented as rerun/retry.
