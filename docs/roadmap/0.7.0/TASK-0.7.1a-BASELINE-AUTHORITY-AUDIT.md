# Task 0.7.1a — Baseline Authority Audit

**Parent:** [Epic 0.7.1 — Control-Plane Authority & Workload Identity](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md)

**Branch:** `epic/0.7.1-control-plane-authority`

**Baseline:** `release/0.7.0` at `3a7d9a4b46e967e2953dca8d64edf25412778361`

**Change class:** `documentation`

**Status:** Audit complete; implementation deliberately deferred to 0.7.1b.

## Decision

TramAI already has strong authorities for workflow execution, checkpoint
persistence, approval binding, policy evaluation, provider routing, and typed
runtime evidence. It does not yet have one authoritative control-plane
identity or lifecycle contract joining those authorities.

The minimum next contract is therefore a shared identity context which binds:

```text
workload identity + configuration/version identity + environment/deployment
    + one run identity
```

That context must be created at the supported execution boundary, propagated
unchanged through resume and evidence, and owned by runtime/control-plane
contracts rather than by the dashboard or a projection store.

No new runtime type, persistence schema, endpoint, or UI surface is justified
by this audit alone.

## Authority map

| Concern | Current authority | Durable? | Current identity | Finding for 0.7.1 |
|---|---|---:|---|---|
| Workflow definition | `tramai-orchestration` `Workflow` and `WorkflowDefinitionCompatibility` | Definition only | `name`, `definitionVersion`, definition digest | Usable configuration anchor, but not a workload/deployment identity. |
| Workflow registration | `tramai-server` `WorkflowRegistry` | No; process-local map | workflow name | Duplicate names are rejected within one process only; deployments can silently reuse the same name. |
| HTTP run record | `tramai-server` `WorkflowRunStore` | No; in-memory map | `workflowId` UUID, workflow name | Current server query/cancel authority is separate from durable checkpoint authority. |
| Orchestration context | `tramai-orchestration` `WorkflowContext` | Only through checkpoint metadata where supplied | `workflowId` plus untyped attributes | Carries run identity, but no workload/configuration/environment/deployment identity. |
| Worker execution | `WorkflowExecutionSupervisor`, leases, checkpoint store | Checkpoint/lease stores can be durable | checkpoint `workflowName` + `workflowId`, definition-version metadata | Worker fencing and revision protection exist; control-plane workload identity is absent. |
| Scheduling | `WorkflowSchedulerStore` with JDBC/in-memory scheduler stores | Yes where JDBC-backed | `scheduleId`, `workflowName`, tick IDs, run IDs, delay-wakeup run/step IDs | Schedule/tick identity is not bound to authoritative workload/configuration/deployment identity; scheduled runs must inherit the workload identity contract rather than create a parallel identity. |
| Engine invocation | `tramai-engine` `EngineExecutionIdentity` | Suspended invocation stores may be durable | `workflowRunId`, correlation ID, workflow digest, policy version, actor | Strong invocation/resume identity, but it is not linked to a registered workload or deployment. |
| Approval authority | `ApprovalStore` and `ApprovalContinuationStore`; `ApprovalBinding` | Implementations vary; file/JDBC exist | workflow run, tool, argument digest, policy version, workflow digest | Binding is fail-closed and version-aware, but has no workload/configuration identity. |
| Policy authority | `DefaultPolicyEngine` over `PolicyConfiguration` | Configuration only | model/provider/tool and classification context | Policy is runtime-authoritative, but scope is not workload/environment composable yet. |
| Provider routing | `ProviderRoutingPlan` and engine provider coordinators | Immutable process configuration | provider/model route | Registry routing is explicit; no control-plane identity or decision projection contract exists. |
| Runtime evidence | `RuntimeEvidenceRecord` and exporters | Bundle/store dependent | optional run/correlation/actor plus digests | Safe typed evidence exists, but workload/configuration identity and lifecycle events are not required. |
| Audit persistence | `AuditStore`, file/JDBC stores, audit outbox | File/JDBC implementations exist | audit stream, optional run/correlation/actor | Durable audit is not the same as authoritative workload/run state. |
| Control/query surface | `WorkflowController`, sovereign-ops REST/controllers, dashboard client | Mostly process/store dependent | endpoint-specific IDs | Read models and commands are not yet one typed control-plane contract; query and mutation authority must be separated. |

## End-to-end identity findings

### Existing strengths

- `Workflow` validates a non-blank name and definition version and computes a
  deterministic definition digest.
- `WorkflowContext.workflowId` is propagated into orchestration observers and
  persisted checkpoint identity.
- `EngineExecutionIdentity` explicitly distinguishes run identity from
  correlation identity and is designed not to regenerate on resume.
- Approval bindings include run identity, policy version, workflow digest, and
  argument digest; the approval coordinator checks these values.
- Checkpoints use revisions, generations, leases, and fenced stores to reject
  stale writers.
- Runtime evidence uses typed decisions and SHA-256 digests; the suspended
  invocation contract excludes raw prompts, tool arguments, tokens, and other
  sensitive payloads from safe metadata.

### Blocking gaps

1. **No workload identity.** There is no authoritative identifier for the
   independently governed application/workload that owns a workflow.
2. **No deployment identity.** Environment, deployment, or instance context is
   not bound to workflow registration or run identity. Same-named workflows
   in distinct deployments can look identical to a control-plane consumer.
3. **No canonical configuration identity.** `definitionVersion` and workflow
   digests identify a workflow definition, but there is no stable contract for
   the complete governed configuration and its owner/purpose metadata.
4. **Split run authorities.** The HTTP server's in-memory `WorkflowRunStore`,
   orchestration checkpoints, engine suspended invocations, and approval
   continuations each own a slice of lifecycle state. None is the canonical
   control-plane run authority.
5. **No general command precondition.** Checkpoint and approval stores have
   concurrency/version checks, but control-plane commands do not share a
   workload/run version and expected-state precondition contract.
6. **Evidence identity is optional.** `RuntimeEvidenceRecord` can carry
   `workflowRunId` and `correlationId`, but does not require workload,
   configuration, environment, or deployment identity.
7. **Projection boundary is not defined.** Existing controllers expose store
   views directly; there is no explicit consistency category or rule that a
   read projection cannot mutate runtime authority.

## Authority boundary for the next candidates

The following boundary is the smallest one consistent with Epic 0.7.1:

```text
runtime execution + authoritative stores
        own identity, lifecycle, commands, versions, and evidence

control-plane query layer
        reads typed projections and declares consistency/lag

dashboard / REST client
        renders projections and submits typed commands
        never mutates stores directly and never decides policy
```

The identity contract should be consumed by orchestration, engine, approval,
evidence, and later projection modules. It should not be implemented inside
the dashboard or by copying the server's current `WorkflowRunRecord`.

## Required 0.7.1b derivation

Define and validate one minimal contract with these semantic fields:

| Field | Requirement |
|---|---|
| `workloadId` | Stable and non-blank; unique within the supported authority domain. |
| `configurationId` / version | Identifies the governed configuration, not only a display name. |
| `environmentId` | Logical governance environment (e.g. dev/staging/production or tenant-specific environment). |
| `deploymentId` | Identity of one independently distinguishable deployment of a workload/configuration in that environment; distinct deployments must not collapse to one identity even within the same environment. |
| `runId` | One immutable run identity; resume reuses it. |
| owner/purpose metadata | Safe, bounded, non-sensitive metadata required by the supported profile. |

The monotonic lifecycle version required by 0.7.1 for stale-command protection is
a property of the authoritative mutable state record, not of immutable run
identity. It must therefore be derived only after 0.7.1c establishes the
authoritative state owner and finalized through 0.7.1e — it is not part of the
0.7.1b identity contract. Whether the lifecycle version lives on the identity
record or on a separate authoritative lifecycle state is an 0.7.1c/e design
decision; keeping identity immutable and versioned state separate avoids
polluting evidence, approvals, and checkpoints with an unrelated concurrency
counter.

The exact names and module placement are deliberately left to 0.7.1b after
checking public API and module-boundary impact. A nullable or arbitrary
`attributes` map is not sufficient to satisfy this contract.

## Deferred findings

These are real gaps but belong to later candidates or Epics:

- durable persisted-run cancellation and resume fencing: Epic 0.7.7;
- policy classification/trust-zone composition: Epic 0.7.2;
- authorized/viable/selected route projection: Epic 0.7.3;
- typed evidence projection/query API: Epic 0.7.4;
- semantic timeline and side-effect-free reconstruction: Epic 0.7.5;
- OIDC and capability authorization: Epic 0.7.6;
- dashboard integration: Epic 0.7.8.

## Evidence index

- `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/Workflow.kt`
  — workflow name, definition version, digest, run/resume entry points.
- `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowObservation.kt`
  — `WorkflowContext` run identity and untyped attributes.
- `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowPersistence.kt`
  — checkpoint identity, optimistic revisions, generations, and persistence SPI.
- `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowExecutionSupervisor.kt`
  — worker ownership, lease fencing, checkpoint resume, and active execution map.
- `tramai-scheduler/src/main/kotlin/dev/tramai/scheduler/WorkflowSchedulerStore.kt`
  — schedule records, tick claims, delay wakeups, and the JDBC/in-memory store boundary.
- `tramai-server/src/main/kotlin/dev/tramai/server/WorkflowRegistry.kt`
  — process-local registration and definition-version lookup.
- `tramai-server/src/main/kotlin/dev/tramai/server/WorkflowRunStore.kt`
  — process-local run records, query, cancel, resume, and SSE state.
- `tramai-server/src/main/kotlin/dev/tramai/server/ScheduleController.kt`
  — exposed schedule query/control surface over the scheduler store.
- `tramai-engine/src/main/kotlin/dev/tramai/engine/EngineExecutionIdentity.kt`
  — engine run/correlation/policy/actor identity.
- `tramai-engine/src/main/kotlin/dev/tramai/engine/SuspendedInvocationStore.kt`
  — safe suspension metadata and trusted replay boundary.
- `tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalBinding.kt`
  — approval-to-run/version/digest binding.
- `tramai-security/src/main/kotlin/dev/tramai/security/evidence/RuntimeEvidenceRecord.kt`
  — typed, digest-based runtime evidence.
- `tramai-security/src/main/kotlin/dev/tramai/security/DefaultPolicyEngine.kt`
  — deny-by-default provider/tool/workflow-resume decisions.

## Candidate definition of done

- this audit is reviewed against the 0.7.1 Epic acceptance criteria;
- 0.7.1b derives the identity contract from this map;
- no implementation is started from an invented parallel authority;
- no baseline, analyzer, deviation, or CI gate changes are expected for this
  documentation-only candidate.
