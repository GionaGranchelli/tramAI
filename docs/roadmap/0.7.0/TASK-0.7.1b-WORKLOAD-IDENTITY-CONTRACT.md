# Task 0.7.1b — Workload Identity Contract

**Parent:** [Epic 0.7.1 — Control-Plane Authority & Workload Identity](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md)

**Branch:** `task/0.7.1b-workload-identity-contract` → `epic/0.7.1-control-plane-authority`

**Baseline:** epic branch at `f9d2e6b4` (epic head containing the 0.7.1a audit)

**Change class:** `public-api` (additive; no breaking change)

**Status:** Implemented; pending exact-head certification.

## Decision

Create the canonical typed identity vocabulary for a governed TramAI workload
and run — without yet creating lifecycle authority, persistence, registration,
or propagation. 0.7.1a established the required semantic fields
(workload, configuration identity/version, environment, deployment, run, plus
bounded owner/purpose metadata) and explicitly deferred lifecycle versioning
to 0.7.1c/e.

The vocabulary lives in `tramai-core` under `dev.tramai.core.identity` because
orchestration, engine and security already expose `tramai-core` as an API
dependency; later control-plane modules can consume the same types without new
dependency edges. `tramai-server` is rejected (runtime identity does not belong
to Spring/server infrastructure) and a new `tramai-control-plane-core` module
is rejected for six value types before the control-plane boundaries exist.

## Contract

### Atomic ids (opaque, validated, case-preserving)

| Type | Semantics |
|---|---|
| `WorkloadId` | Stable identity of one independently governed workload. Not required to be a UUID; generation policy belongs to 0.7.1c. |
| `ConfigurationId` | Stable identity of a governed configuration family. |
| `ConfigurationVersion` | One immutable revision of that configuration. Opaque — no SemVer assumption. |
| `EnvironmentId` | Logical governance environment (dev/staging/production/tenant). Not a deployment. |
| `DeploymentId` | One independently distinguishable deployment of a workload/configuration in an environment. |
| `RunId` | One immutable identifier for one authoritative execution. Resume/retry/restart/approval-resume reuse it; a new execution gets a new id. The type never generates ids. Distinct from `EngineExecutionIdentity.correlationId`, which remains the diagnostic/cross-operation correlation. |

Shared fail-closed validation: non-blank; no leading/trailing whitespace;
no ISO control characters; bounded length (128). No character grammar is
imposed (`claims`, `org.example.claims`, `production/eu`, `urn:...`, `Payments`
vs `payments` are all legal distinct values). No silent normalization: inputs
that differ must never canonicalize to the same key.

All identity types are plain JVM classes, not `@JvmInline` value classes.
The vocabulary is the canonical public tramai-core contract that 0.7.1d will
push to execution boundaries, and TramAI is Kotlin-first but Java-friendly:
`new WorkloadId("claims")`, getters, and equality must work naturally from
Java. Inline value classes would make the contract Kotlin-only or dependent on
experimental boxed exposure (`@JvmExposeBoxed`) — rejected for a stable public
surface. Enforced by `JavaIdentityInteropTest` (compiles and runs in
`:tramai-core:test`) and by identity usage in the `java-consumer-smoke`
example.

### Composition

```text
WorkloadConfigurationIdentity(configurationId, version)
WorkloadDeploymentIdentity(workloadId, configuration, environmentId, deploymentId)
GovernedRunIdentity(deployment, runId)
```

Invariants encoded by the types:

- distinct deployments never collapse to one identity, even when they share
  workload, configuration and environment (the 0.7.1a finding-2 invariant);
- within an authority domain, the same `(configurationId, version)` must never
  refer to two different governed configurations (declared here; collision
  enforcement belongs to 0.7.1c, which requires an authority/store);
- owner/purpose metadata is NOT part of identity equality. `WorkloadMetadata`
  is a separate bounded type (`owner` ≤ 256, `purpose` ≤ 512, non-blank, no
  control characters); the authoritative association of metadata to identity
  belongs to 0.7.1c. No free-form `Map<String, Any?>` — that would recreate
  the untyped `WorkflowContext.attributes` problem identified by 0.7.1a.

## Non-goals

0.7.1b does NOT:

- register workloads or persist identities;
- modify `WorkflowContext`, `WorkflowCheckpoint`, `EngineExecutionIdentity`,
  `ApprovalBinding`, `RuntimeEvidenceRecord`, or scheduler records;
- add lifecycle state or a lifecycle version;
- add stale-command preconditions, APIs/controllers, or JSON schemas;
- add Dashboard behaviour;
- generate `RunId`s (creation at the execution boundary is 0.7.1d);
- migrate existing runs;
- add a new module or dependency edge.

## Verification

- `./gradlew :tramai-core:test` — passed (identity suites:
  `IdentityIdValidationTest`, `WorkloadIdentityCompositionTest`,
  `WorkloadMetadataTest`, and the Java-source `JavaIdentityInteropTest`).
- `./gradlew spotlessApply` / `spotlessCheck` — no changes required, passed.
- `./gradlew :tramai-core:apiDump` — regenerated; additive only (net +122 lines
  vs epic base), with plain JVM `<init>`/getter surface after the Java-interop
  review fix.
- `./gradlew :examples:java-consumer-smoke:compileJava` — passed with identity
  vocabulary usage.
- `./gradlew verifyPr -PchangeClass=public-api` — see completion report.

## Mutation expectations

- remove blank/whitespace/length validation → rejected by
  `IdentityIdValidationTest`;
- ignore `deploymentId`/`environmentId`/`configuration`/`runId` in composition
  equality → rejected by `WorkloadIdentityCompositionTest`;
- embed metadata into identity equality → rejected by the structural guard in
  `WorkloadMetadataTest`.

Full Epic adversarial/mutation certification remains assigned to 0.7.1g.

## Evidence index

- `tramai-core/src/main/kotlin/dev/tramai/core/identity/IdentityValidation.kt`
  — shared fail-closed validation and length bounds.
- `tramai-core/src/main/kotlin/dev/tramai/core/identity/WorkloadId.kt`,
  `ConfigurationId.kt`, `ConfigurationVersion.kt`, `EnvironmentId.kt`,
  `DeploymentId.kt`, `RunId.kt` — atomic ids.
- `tramai-core/src/main/kotlin/dev/tramai/core/identity/WorkloadConfigurationIdentity.kt`,
  `WorkloadDeploymentIdentity.kt`, `GovernedRunIdentity.kt` — composition.
- `tramai-core/src/main/kotlin/dev/tramai/core/identity/WorkloadMetadata.kt`
  — bounded owner/purpose metadata, separate from identity.
- Tests: `tramai-core/src/test/kotlin/dev/tramai/core/identity/`.

## Candidate definition of done

0.7.1b is complete when TramAI has exactly one canonical typed vocabulary for
workload, configuration revision, environment, deployment and run identity,
with fail-closed validation and documented identity semantics — and no
lifecycle/store/propagation authority has been introduced.
