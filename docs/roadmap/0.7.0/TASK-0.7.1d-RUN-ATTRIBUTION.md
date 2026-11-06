# Task 0.7.1d — Authoritative Run Attribution

**Parent:** [Epic 0.7.1 — Control-Plane Authority & Workload Identity](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md)

**Branch:** `task/0.7.1d-run-attribution` → `epic/0.7.1-control-plane-authority`

**Baseline:** epic head `e8ad7136` (0.7.1c authoritative registration, #403; #415 release-line
API/versioning policy)

**Change class:** `runtime-behaviour` (primary)

**Status:** Implementation complete, in review. Mutation/adversarial certification is deferred to
0.7.1g by the Epic (§ Sequencing).

## Decision

A governed run acquires exactly one authoritative workload/configuration context at creation and
that context survives every supported runtime boundary unchanged:

```text
RegisteredWorkload                     (authority, 0.7.1c)
      │ snapshot once, at run creation
      ▼
GovernedRunIdentity = WorkloadDeploymentIdentity + RunId
      │
      ├── GovernedRun                    (typed runtime envelope; NEW in 0.7.1d)
      ├── approval suspension / outbox   (V2 provenance, durable)
      ├── persisted checkpoint           (internal encoding, durable witness)
      ├── resume / worker recovery       (recovered from the persisted witness)
      └── scheduler continuation         (durable deployment binding, delay/wakeup ticks)
```

Every copy MUST represent the same canonical identity. Reuse the 0.7.1b/0.7.1c types; no new
identity type, no parallel attribution store, and no second run identifier.

### How the identity is carried, per boundary

- **Runtime boundary — `GovernedRun`.** A governed execution is represented by the additive
  `GovernedRun(context, identity)` envelope. It carries the canonical identity *alongside* the
  existing `WorkflowContext` instead of widening it: `WorkflowContext(workflowId, attributes)`
  stays the public contract, so ungoverned execution and its ABI are untouched.
  `GovernedRun.start(...)` establishes identity exactly once; `GovernedRun.resume(...)` rebuilds
  the boundary for an existing run and never regenerates the run id.
- **Durable boundary — reserved checkpoint metadata, an internal encoding.** The witness is encoded
  into five framework-owned keys (`checkpoint.identity.workload`,
  `checkpoint.identity.configuration`, `checkpoint.identity.configuration_version`,
  `checkpoint.identity.environment`, `checkpoint.identity.deployment`). This is a **persistence
  encoding, not an authority**: the authority is the canonical identity of the running execution,
  the framework always writes these keys last, and application checkpoint metadata can never
  override them. The keys are deliberately not prefixed `tramai.`, a namespace the architecture
  guard reserves for runtime identifiers and configuration properties.
- **The run id is not persisted a second time.** The checkpoint's own `workflowId` *is* the run id,
  so persistence cannot store an identifier that diverges from the run it describes. Invariant,
  enforced structurally at construction: `governedRunIdentity.runId.value == context.workflowId`.
- **Checkpoint-store schemas are unchanged.** Existing implementations round-trip the reserved
  metadata keys through the schema they already have; no store gains identity columns, and no
  second attribution store appears. Ungoverned checkpoints encode to an empty map, so records
  written before attribution existed remain byte-compatible.
- **Approval suspension and sovereign-ops outbox — V2 provenance.** A governed suspension and a
  governed outbox record carry the complete `GovernedRunIdentity` as one non-optional nested
  object in a V2 payload; V1 keeps exactly its released DTO shape. A malformed or mistyped
  `schemaVersion` declaration is corruption; an unknown integral version is unsupported format.
  Neither case is legacy.
- **Scheduler — durable deployment binding.** Continuation classification and deployment identity
  come from the durable binding store, not from the transient runtime registration, so a schedule
  created before a registration change still recovers the identity it was created with.
- **Continuation — recovered, never rebuilt.** `recoverGovernedRun` reads the witness from the run's
  own checkpoint and returns a `GovernedRun`; it is never reconstructed from whatever registration,
  binding or schedule happens to exist at continuation time.

## Contract

### Identity rules

- **`RunId` is the existing `workflowId`.** The server already creates exactly one `workflowId` per
  run; `RunId(workflowId)` wraps it. A second generated identifier would immediately create two
  competing run identities.
- **Snapshot at creation, then immutable.** At governed run start, resolve the runtime binding
  against the 0.7.1c authoritative registration and snapshot `registration.identity`. The
  registration is never consulted again to reconstruct this run's identity: later
  metadata/lifecycle/version changes cannot rewrite historical attribution.
- **Attribution is typed, never an attribute.** No `attributes["governedRunIdentity"] = …`; that
  would recreate the untyped-metadata problem 0.7.1 exists to remove. The reserved
  `checkpoint.identity.*` keys are the *durable* form of a typed identity, not a public metadata
  contract.
- **The binding is a pointer, not authority.** The runtime binding from an executable workflow to
  `WorkloadDeploymentScope` (workload/environment/deployment) carries no fingerprint, lifecycle or
  configuration semantics. `WorkflowRegistry` keeps owning executable lookup and gains no authority
  over configuration identity.

### Fail-closed rules

- No authoritative registration for the bound scope → **governed run does not start**.
- No authoritative registration record → **admission is rejected** (`WorkloadAdmissionRejectedException`).
- All reserved keys absent → intentionally ungoverned (legacy) run; **a partial set is corruption**
  and throws rather than resuming un-attributed. A partially attributed identity is never returned.
- Checkpoint attributed to deployment B resumed through a binding for deployment A → **fail closed**.
  The identity is compared in full (run id, workload, configuration id and version, environment,
  deployment); comparing the run id alone is explicitly insufficient, because a run id can be
  replayed against another workload or deployment.
- Exactly one of {persisted, requested} attribution present at a reconstruction boundary → **fail
  closed**: a governed run cannot become un-attributed, and an ungoverned run cannot resume as
  governed.
- A governed run reaching an un-attributed approval creation path → **fail closed before the first
  persistence operation** (`GovernedRunContinuityException`); legacy ungoverned execution is
  unchanged.
- Legacy ungoverned workflows/checkpoints remain supported where intentionally un-attributed
  (attribution `null`).

## Surfaces

| Surface | Change |
|---|---|
| `tramai-core` identity types | reuse `GovernedRunIdentity` / `RunId`; adds only `GovernedRunContinuityException` (additive, stable module) |
| `tramai-control-plane` | authoritative registration lookup at run creation; binding → scope → `RegisteredWorkload` snapshot; admission rejection |
| `tramai-orchestration` | `GovernedRun` envelope; `WorkflowContext` **unchanged**; reserved checkpoint metadata encoding + decode; whole-identity continuity gate at every reconstruction boundary; supervisor recovers, never rebuilds |
| checkpoint stores + TCK | unchanged schemas; durable attribution round-trip through the reserved metadata keys in every implementation (InMemory/File/Markdown/JDBC) |
| `tramai-persistence-file`, `-jdbc` | governed suspended-invocation and outbox stores; V1 legacy vs V2 governed codecs with strict schema-version dispatch |
| sovereign ops | approval suspension V2 provenance; the approval mutation carries identity inside its native JDBC transaction; `ApprovalRunAttribution.Governed` |
| `tramai-scheduler` | durable deployment binding; governed schedule continuation |
| `tramai-server` | start path resolves + snapshots; idempotent replay returns the existing attribution; resume loads it, never rebuilds it |

## Test matrix

Mandatory in 0.7.1d (implemented and green on the reviewed head):

1. Governed run start snapshots the exact authoritative `WorkloadDeploymentIdentity`.
2. `governedRunIdentity.runId.value == workflowId` (structurally enforced at envelope construction).
3. Missing authoritative registration prevents a governed run from starting; admission is rejected.
4. Two deployment scopes sharing one executable/workflow name produce distinct identities.
5. Initial execution carries the canonical identity in the typed envelope.
6. Checkpoint round-trip preserves attribution across **every** supported implementation.
7. Continuation receives exactly the same `GovernedRunIdentity` as initial execution.
8. Worker/recovery resume preserves it too.
9. Missing attribution on a checkpoint expected to be governed fails closed.
10. A partial reserved-key set is corruption, never a silent un-attributed resume.
11. A checkpoint attributed to deployment B cannot be resumed through deployment A.
12. Reusing an idempotency key returns the pre-existing run identity.
13. Changing mutable registration metadata after creation cannot change historical attribution.
14. Framework-generated scheduled/delayed continuation cannot drop attribution.
15. Legacy ungoverned workflow/checkpoint compatibility remains intact.
16. V2 payloads whose declared `schemaVersion` is mistyped (text, float, null, out-of-range) are
    corruption — never a bogus V1/V2 decode and never an "unsupported version" report.
17. A governed run reaching an un-attributed approval creation path fails closed with 0 approval /
    0 suspension / 0 continuation rows and 0 mutation-store calls.

Deferred to **0.7.1g** (evidence/compatibility/mutation phase), not claimed here: mutation kill
proof for removal of workload/configuration/run-identity checks, adversarial verification matrices,
and the compatibility/evidence proof set.

## Compatibility

Source compatibility and binary/API signature compatibility are different claims, and this task is
not "purely additive":

- `tramai-core` is **stable**; its only change is additive (a new `GovernedRunContinuityException`),
  which passes under the backward-compatibility rule settled in #415.
- `ApprovalRunAttribution` was promoted `internal` → public `@ExperimentalTramaiInternalApi`.
- `JdbcSovereignOpsApprovalMutationStore.<init>` and its autoconfiguration bean method each gained a
  parameter **with a default**. Source-compatible, but a default parameter does not remove the JVM
  descriptor change: consumers must recompile. This is recorded as an explicit preview transition in
  `config/quality/api-migrations.yml` rather than described as additive.
- Seven further preview/experimental modules change dump additively; every transition is authorized
  by an exact hash-bound migration entry with `targetVersion: "0.7.0"` (the release this
  `0.7.0-SNAPSHOT` development line targets).

## Non-goals

0.7.1d does NOT: expose the new attribution through REST/query surfaces (0.7.1e/0.7.1f);
add If-Match/ETag or consistency classes (0.7.1e); classify safe metadata categories (0.7.1f);
carry governed identity through `ApprovalGatewayPersistenceRequest` (the affected creation paths
fail closed instead); add IAM/RBAC; select providers or policy from workload identity; change
registration lifecycle/admission semantics; touch the dashboard; introduce a new run lifecycle
authority or a separate `RunAttributionStore`; pin cross-deployment idempotency scoping in
`WorkflowRunStore` (recorded as a follow-up before that store becomes durable/shared).

## Verification

- `./gradlew :tramai-orchestration:test` — checkpoint attribution round-trip + continuity gate.
- `./gradlew :tramai-server:test` — start/idempotency/resume attribution.
- `./gradlew :tramai-scheduler:test` — continuation attribution.
- `./gradlew :tramai-persistence-file:test`, `:tramai-spring-boot-starter-sovereign-persistence-file:test`,
  `:tramai-spring-boot-starter-sovereign-persistence-jdbc:test` — V1/V2 codec fail-closed matrix
  including strict schema-version dispatch.
- `./gradlew verify060Architecture -PchangePolicyBase=<exact epic base>` — API architecture,
  authorized by hash-bound migration entries.
- `./gradlew verifyPr` — primary local gate (requires JDK 21 on this machine).
- Exact-head CI + Sovereign Runtime RC — final certification.
