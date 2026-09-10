# Task 0.7.1d — Authoritative Run Attribution

**Parent:** [Epic 0.7.1 — Control-Plane Authority & Workload Identity](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md)

**Branch:** `task/0.7.1d-run-attribution` → `epic/0.7.1-control-plane-authority`

**Baseline:** epic head `30f6c290` (0.7.1c authoritative registration merged, #403)

**Change class:** `runtime-behaviour` + `public-api` (additive constructor default)

**Status:** In progress.

## Decision

A governed run acquires exactly one authoritative workload/configuration context at
creation and that context survives every supported runtime boundary unchanged:

```text
RegisteredWorkload                     (authority, 0.7.1c)
      │ snapshot once, at run creation
      ▼
GovernedRunIdentity = WorkloadDeploymentIdentity + RunId
      │
      ├── WorkflowContext        (initial execution)
      ├── WorkflowRunRecord      (server run + idempotency)
      ├── WorkflowCheckpoint     (durable attribution)
      ├── resume                 (server)
      ├── worker / recovery      (supervisor)
      └── scheduler continuation (delay / wakeup ticks)
```

Every copy MUST represent the same canonical identity. Reuse the 0.7.1b types; no
new identity type, no parallel attribution store, and no second run identifier.

## Contract

### Identity rules

- **`RunId` is the existing `workflowId`.** The server already creates exactly one
  `workflowId` per run; `RunId(workflowId)` wraps it. Invariant, enforced structurally:
  `governedRunIdentity.runId.value == WorkflowContext.workflowId`. A second generated
  identifier would immediately create two competing run identities.
- **Snapshot at creation, then immutable.** At governed run start, resolve the runtime
  binding against the 0.7.1c authoritative registration and snapshot
  `registration.identity`. The registration is never consulted again to reconstruct
  this run's identity: later metadata/lifecycle/version changes cannot rewrite
  historical attribution.
- **Attribution is typed, never an attribute.** No
  `attributes["governedRunIdentity"] = …`; that would recreate the untyped-metadata
  problem 0.7.1 exists to remove.
- **The binding is a pointer, not authority.** The runtime binding from an executable
  workflow to `WorkloadDeploymentScope` (workload/environment/deployment) carries no
  fingerprint, lifecycle or configuration semantics. `WorkflowRegistry` keeps owning
  executable lookup and gains no authority over configuration identity.

### Fail-closed rules

- No authoritative registration for the bound scope → **governed run does not start**.
- Checkpoint or resume path missing attribution where the run is governed → **fail closed**
  (corruption / unsupported state), never a silent un-attributed resume.
- Checkpoint attributed to deployment B resumed through a binding for deployment A →
  **fail closed**; the current binding is never substituted for the persisted one.
- Legacy ungoverned workflows/checkpoints remain supported where intentionally
  un-attributed (attribution `null`).

## Surfaces

| Surface | Change |
|---|---|
| `tramai-core` identity types | reuse `GovernedRunIdentity` / `RunId`; no new type |
| `tramai-control-plane` | authoritative registration lookup at run creation; binding → scope → `RegisteredWorkload` snapshot |
| `tramai-orchestration` | `WorkflowContext` + `WorkflowCheckpoint` carry typed attribution; checkpoint SPI/impls persist and restore it; supervisor fails closed on absent/conflicting attribution |
| checkpoint stores + TCK | durable attribution round-trip in every implementation, including JDBC explicit identity columns (not JSON, not metadata) |
| `tramai-server` | start path resolves + snapshots; idempotent replay returns the existing attribution; resume loads it, never rebuilds it |
| worker / recovery | reconstructs context from persisted attribution, not from `workflowId` alone |
| scheduler | every framework-created context on delay/wakeup continuation carries attribution through |

## Test matrix (mandatory)

1. Governed run start snapshots the exact authoritative `WorkloadDeploymentIdentity`.
2. `governedRunIdentity.runId.value == workflowId`.
3. Missing authoritative registration prevents a governed run from starting.
4. Two deployment scopes sharing one executable/workflow name produce distinct identities.
5. Initial execution context carries the canonical identity.
6. Checkpoint round-trip preserves attribution across **every** supported implementation.
7. Resume receives exactly the same `GovernedRunIdentity` as initial execution.
8. Worker/recovery resume preserves it too.
9. Missing attribution on a checkpoint expected to be governed fails closed.
10. A checkpoint attributed to deployment B cannot be resumed through deployment A.
11. Reusing an idempotency key returns the pre-existing run identity.
12. Changing mutable registration metadata after creation cannot change historical attribution.
13. Framework-generated scheduled/delayed continuation cannot drop attribution.
14. Legacy ungoverned workflow/checkpoint compatibility remains intact.
15. Mutation tests kill removal of workload/configuration/run identity checks.

## Non-goals

0.7.1d does NOT: expose the new attribution through REST/query surfaces (0.7.1e/0.7.1f);
add If-Match/ETag or consistency classes (0.7.1e); classify safe metadata categories
(0.7.1f); add IAM/RBAC; select providers or policy from workload identity; change
registration lifecycle/admission semantics; touch the dashboard; introduce a new run
lifecycle authority or a separate `RunAttributionStore`.

## Verification

- `./gradlew :tramai-orchestration:test` — checkpoint attribution round-trip + supervisor
  continuity (InMemory/File/Markdown/JDBC runners).
- `./gradlew :tramai-server:test` — start/idempotency/resume attribution.
- `./gradlew :tramai-scheduler:test` — continuation attribution.
- `./gradlew verifyPr` — primary local gate.
- Exact-head CI — final certification.
