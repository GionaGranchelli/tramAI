# Epic 0.7.4 — Governance Evidence, Projection & Query API

**Branch:** `epic/0.7.4-evidence-projection`  
**Status:** ⚪ Planned  
**Dependencies:** 0.7.1 HARD; 0.7.2/0.7.3 SOFT

## Executive decision

Project authoritative runtime truth into a stable operational read model without turning observability, telemetry, or the dashboard into a policy engine.

## Architecture

```text
runtime authority → typed evidence → projection/read model → query API → clients
```

OTEL and best-effort telemetry may mirror facts but remain non-authoritative.

## Scope

- typed governance evidence/events;
- workload/run/config/classification/policy/routing/approval/control correlation;
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
| 0.7.4b | Governance evidence schema | Typed/versioned evidence families with identity and source authority |
| 0.7.4c | Projection contract | Idempotency, ordering/version, lag and rebuild semantics |
| 0.7.4d | Materialized read model | Durable/queryable operational projection without mutation authority |
| 0.7.4e | Query API | Stable headless workload/run/decision/control-state query contracts |
| 0.7.4f | Safe exposure/OTEL mapping | Protected-payload defaults and non-authoritative telemetry distinction |
| 0.7.4g | Adversarial/rebuild/mutation proof | Duplicate/reorder/stale/missing-source and privacy discriminators |
| 0.7.4h | Integration/docs | Evidence schema/query docs and Epic acceptance |

## Core invariants

```text
projection cannot create or widen authority
same authoritative event applied twice => same projection as once
projection lag is never presented as stronger authority than source
missing evidence != inferred success
protected payloads are excluded by default
```

## Acceptance criteria

- Clients can query P0 governance state without reading unrelated runtime stores directly.
- Rebuild is deterministic for the supported evidence set.
- Duplicate/reordered evidence obeys explicit semantics.
- Projection lag/source authority is observable.
- Query/telemetry defaults do not leak protected payloads.

## Adversarial proof

Reject projection-side policy decisions, duplicate evidence changing final state incorrectly, stale event overwriting newer version, unavailable source reported as PASS/current, and payload leakage through generic DTO/telemetry.
