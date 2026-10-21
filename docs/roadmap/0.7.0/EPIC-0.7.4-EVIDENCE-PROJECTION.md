# Epic 0.7.4 — Governance Evidence, Projection & Query API

**Branch:** delivered on `release/0.7.0` (task branches per sub-task)  
**Status:** 🟡 Closure proposed — 0.7.4h3 in PR #543, qualified at `5222375e`; the closure merge commit is
recorded when that PR lands, so this line never names a SHA that is not yet a closure  
**Closure record:** [TASK-0.7.4h3-GOVERNANCE-EVIDENCE-INTEGRATION-CLOSURE.md](../0.7.4/TASK-0.7.4h3-GOVERNANCE-EVIDENCE-INTEGRATION-CLOSURE.md)  
**Carried limitations:** S18 (authenticating the claimed workload identity), S19 (event-origin authenticity), T1 (caller identity), G4 (`provider.route` has no live producer), and the CI limits recorded in the closure record  
**Dependencies:** 0.7.1 HARD; 0.7.2/0.7.3 SOFT; XR1 required before evidence/projection contracts are frozen

## Executive decision

Project authoritative runtime truth into a stable operational read model without turning observability, telemetry, or the dashboard into a policy engine.

## Architecture

```text
runtime authority / governed external boundary → typed evidence → projection/read model → query API → clients
```

OTEL and best-effort telemetry may mirror facts but remain non-authoritative.

## Scope

- typed governance evidence/events;
- workload/run/config/classification/policy/routing/approval/control correlation;
- runtime/framework/integration identity where relevant to evidence interpretation;
- explicit evidence source, enforcement strength, and provenance sufficient to distinguish enforced, instrumented, observed, and imported facts;
- idempotent projection application;
- ordering/version handling;
- explicit projection lag/consistency/source authority;
- rebuild/durability semantics;
- stable query API and selected streaming surfaces if justified;
- default-safe field exposure;
- safe OTEL mapping where useful.

## Non-goals

- event sourcing every runtime detail;
- raw sensitive payload observability;
- dashboard-specific hidden stores;
- policy decisions performed in the projection layer.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.4a | Evidence baseline audit | Inventory authoritative events/stores, audit evidence and telemetry gaps |
| 0.7.4b | Governance evidence schema | Typed/versioned evidence families with identity, source authority, runtime/integration context where relevant, and explicit enforcement/evidence provenance |
| 0.7.4c | Projection contract | Idempotency, ordering/version, lag and rebuild semantics |
| 0.7.4d | Materialized read model | Durable/queryable operational projection without mutation authority |
| 0.7.4e | Query API | Stable headless workload/run/decision/control-state query contracts |
| 0.7.4f | Safe exposure/OTEL mapping | Protected-payload defaults; observed/instrumented/imported evidence remains distinguishable from authoritative enforcement; OTEL never upgrades telemetry into proof of prevention |
| 0.7.4g | Adversarial/rebuild/mutation proof | Duplicate/reorder/stale/missing-source and privacy discriminators |
| 0.7.4h1 | Publication boundary | Test fixtures are not published; the boundary is verified against real generated metadata, POMs and an isolated repository |
| 0.7.4h2a | CI gate-enrollment contract | A verifier that proves the governance gates are actually executed by CI, with adversarial workflow fixtures |
| 0.7.4h2b | CI enforcement | The three gates plus the verifier run on every pull request and fail for real violations |
| 0.7.4h3 | Integration/docs closure | Consolidated proof ledger, acceptance mapping and named remaining limitations |

## Core invariants

```text
projection cannot create or widen authority
same authoritative event applied twice => same projection as once
projection lag is never presented as stronger authority than source
missing evidence != inferred success
observed telemetry != proof of enforcement
integration/client metadata cannot self-upgrade enforcement strength
protected payloads are excluded by default
```

## Acceptance criteria

- Clients can query P0 governance state without reading unrelated runtime stores directly.
- Rebuild is deterministic for the supported evidence set.
- Duplicate/reordered evidence obeys explicit semantics.
- Projection lag/source authority is observable.
- Query/telemetry defaults do not leak protected payloads.
- The XR1 Spring AI reference path can be projected without pretending TramAI authored the workload.
- Clients can distinguish a TramAI-enforced boundary from instrumented, observed, or imported evidence.

## Adversarial proof

Reject projection-side policy decisions, duplicate evidence changing final state incorrectly, stale event overwriting newer version, unavailable source reported as PASS/current, payload leakage through generic DTO/telemetry, observed telemetry represented as authoritative prevention, client-supplied enforcement strength accepted without authoritative validation, and external-runtime evidence projected as though TramAI authored or governed boundaries it did not control.
