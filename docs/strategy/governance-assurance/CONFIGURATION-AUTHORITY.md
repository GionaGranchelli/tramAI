# CFG-0001 — Governed Configuration Authority

> Status: Architectural requirements; no existing hot-change API is implied.

## Problem

TramAI's Spring properties, Kotlin builders and future governance policies will proliferate. Operations should be able to alter *eligible* supported configuration without application releases, while preserving integrity, identity, least privilege and evidence. A dashboard must not mutate an engine singleton or become a second source of truth.

## Proposed model

Every managed setting has a typed descriptor, namespace, scope (organization/environment/workload), authoritative source, defaults, constraints, sensitivity, activation mode, required authority, version and provenance. Declarative files, Kotlin builders, GitOps, REST and dashboard write through a single canonical validated representation.

Change classes:
- **Live operational:** limited to behavior inside the existing authorized set, e.g. allowed provider preference.
- **Governed policy:** may affect risk treatment, provider eligibility, sensitive data or human oversight; require stronger authorization and impact checking.
- **Structural/deployment:** new adapters, workflow schema/ABI, crypto infrastructure; may require restart or software release.
- **Emergency restrictive:** deny/quarantine/kill switches; preserve existing runtime-control ownership and audit.

## Candidate revision lifecycle

DRAFT → VALIDATE (schema, semantics, restrictive scope) → ASSESS (affected controls/workloads/approvals, simulator) → AUTHORIZE (actor, RBAC, independent approval where required) → STAGE (immutable digest) → ACTIVATE (atomic, declared boundary) → OBSERVE (node acknowledgements/drift) → SUPERSEDE or REVOKE.

An activation never edits a historical revision. A rollback creates a new authorized transition. Runtime evidence names the effective revision used. Historical reconstruction must not silently use current defaults. A revived cancelled run or retrospective approval escalation remains forbidden.

Existing in-flight work can retain its pinned snapshot for deterministic processing, **but** a fresh authorization check is required before sensitive side effects where an emergency/current restrictive policy would otherwise be bypassed. These semantics need explicit design and tests.

## Single and multi-node profiles

Embedded/local mode requires no hosted service. Managed mode uses authoritative durable storage and coordinated activation. Nodes must not mistake a stale projection for authority. Outage/staleness behavior must be declared per control criticality; no blanket fail-open.

GitOps and UI editing must have an explicit source-of-authority policy and conflict reconciliation. Secret *references* may be managed but raw secret values must not be revealed through ordinary configuration diff or evidence export.

## Required tests before shipping

Concurrent updates with the same expected revision; unauthorized write; policy widening from lower scope; invalid typed property; stale node; lost activation ack; restart; rollback/revocation; concurrent approval and cancellation; pinned-history reconstruction; sensitive redaction. Discriminating adverse tests are mandatory for critical invariants.

## Release placement

0.7: preserve identity and immutable configuration/evidence boundaries (no expansion of release blockers).

0.8: typed registry, validation, simulation and authoring projections.

0.9: managed mutation APIs, enterprise RBAC, persistent rollout coordination, dashboard editor.

0.10+: monitored change impact and assurance-driven recommendations, without autonomous authority widening.
