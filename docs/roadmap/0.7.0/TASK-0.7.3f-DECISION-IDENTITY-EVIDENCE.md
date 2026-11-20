# TASK 0.7.3f — Decision Identity / Evidence

Status: in progress. Base: `d594023bd95f43d0d3333209a9e10ffd4b1affc2` (epic tip after #500).
Epic: [EPIC-0.7.3-AUTHORIZED-SELECTION.md](./EPIC-0.7.3-AUTHORIZED-SELECTION.md)

## Goal

Bind an existing governance decision to canonical governed-run identity and the minimum
policy/authority context required to explain it later, without creating a second decision model and
without assuming TramAI owns the surrounding runtime.

```
canonical governed identity
  + policy / authority context
  + existing typed decision
  + deterministic safe candidate identity
  + structured reason
        ↓
historically attributable governance decision
```

## §4 audit — existing seam (answers)

**1. Which existing type is authoritative for workload/configuration/run identity?**

`GovernedRunIdentity(deployment: WorkloadDeploymentIdentity, runId: RunId)` in
`tramai-core/.../core/identity/`, where `WorkloadDeploymentIdentity(workloadId, configuration:
WorkloadConfigurationIdentity, environmentId, deploymentId)` and
`WorkloadConfigurationIdentity(id: ConfigurationId, version: ConfigurationVersion)`.

It already nests exactly the tuple required, and it lives in **`tramai-core`** — the module an
external runtime can depend on without orchestration. Reused as-is. `runId` is the per-run
discriminator; no new run/workload tuple was created.

**2. Which existing field identifies the policy/configuration revision actually consulted?**

`PolicyContext.policyVersion` (non-null `String`), with `PolicyContext.workflowDigest` (nullable
`String`) as the existing configuration digest when the enforcement point supplies one.

No `policyDigest` / `configurationDigest` / `authorityDigest` exists in the repository, and the
combination `WorkloadConfigurationIdentity(id, version)` + `policyVersion` already disambiguates
"which governing state applied". Per §8 no digest was invented; the only new digests are the two
that close concrete ambiguities: the **candidate subject** (§10) and the **evidence payload**
(required by `RuntimeEvidenceDigests`).

**3. Which existing evidence representation is safe for historical export?**

`RuntimeEvidenceRecord` (`schemaVersion = "runtime-evidence.v1"`) plus
`RuntimeEvidenceAttribution`, both in `tramai-security/.../security/evidence/`.

`RuntimeEvidenceRecord` already carries `eventId`, `eventType`, `workflowRunId`,
`correlationId`, `actor`, `createdAt`, `source`, `decision(kind, reasonCode)`,
`digests(subjectDigest, payloadDigest)` and an allowlisted `metadata` map.
`RuntimeEvidenceAttribution` already defines the governed-identity metadata keys and already
**fails closed** on partial attribution (`merge` rejects unknown keys; `validate` rejects a partial
key set, and rejects attribution without the canonical `workflowRunId`).

**4. Which existing provider-route evidence is execution/routing telemetry rather than the new
governance decision?**

`ProviderRoutingRuntimeEvidenceExporter` in `tramai-engine`, which records routing/execution
outcomes (`SELECTED` / `FALLBACK` / `BLOCKED`) from the execution path. It is engine/routing
telemetry. It is **not** the governance decision evidence and it cannot retroactively create
governance authority. The `policy.decision` records produced by
`PolicyDecisionRuntimeEvidenceExporter` are likewise policy-enforcement records, not the typed
candidate decision chain.

**5. What minimum new seam is required so Spring AI can later supply the same identity/context
without recreating TramAI policy?**

One reusable envelope binding an existing typed decision to `GovernedRunIdentity`, the preserved
correlation identity, the captured policy context and a decision identity — plus one pure projection
from that envelope into the existing `RuntimeEvidenceRecord` shape. Everything else (identity,
reason vocabularies, digest utility, evidence record, attribution guard, writer) already exists and
is reused.

## Canonical run identity reused

`GovernedRunIdentity` — not re-created, not wrapped in a parallel tuple. The envelope holds the
canonical type itself, so any consumer already holding a `GovernedRunIdentity` can bind a decision
without translation. No `RoutingWorkloadId`, `SelectionRunId`, `DecisionWorkflowId` or
`ProviderRunIdentity` is introduced.

## Decision model reused, not duplicated

`CandidateAuthorizationDecision`, `CandidateViabilityDecision` and `CandidateSelectionDecision`
remain authoritative. The envelope is generic over an existing decision type; it does not
reinterpret, re-wrap into an `Evidence*Decision` hierarchy, or restate the reason vocabularies.
`AuthorizationRefusal`, `ViabilityRefusal` and `SelectionRefusal` stay the only reason families.
The projection maps a typed outcome/reason to the existing `RuntimeEvidenceDecision(kind,
reasonCode)` **strings** — a mapping, not a second vocabulary.

## Policy / authority identity actually captured

`policyVersion` (required) and `workflowDigest` (optional, captured when the enforcement point
provides it), taken from the consulted `PolicyContext` and bound into the envelope at decision time.
They are recorded, never recomputed at read time.

## Candidate identity / digest — canonical form

The candidate subject digest is a deterministic SHA-256 over an explicitly ordered, length-delimited
canonical input built with the existing `CanonicalDigestBuilder`, covering:

1. `providerId`
2. `modelId`
3. `deployment` (its own canonical rendering)

`deployment` is included deliberately: two deployments of the same provider/model pair can live in
different trust zones or authority domains, and omitting them would collapse materially distinct
candidates into one evidence subject. Field changes alter the digest; map/set ordering cannot.
Raw provider/model/deployment values are not written into exported evidence.

## Reason mapping (all outcomes)

| Typed outcome | evidence `kind` | `reasonCode` |
| --- | --- | --- |
| `CandidateAuthorizationDecision.Authorized` | `governance.authorization` | (none) |
| `NotAuthorized(IDENTITY_DEPLOYMENT_MISMATCH)` | `governance.authorization` | `IDENTITY_DEPLOYMENT_MISMATCH` |
| `NotAuthorized(ZONE_PAIR_NOT_ALLOWED)` | `governance.authorization` | `ZONE_PAIR_NOT_ALLOWED` |
| `NotAuthorized(CLASSIFICATION_ZONE_NOT_PERMITTED)` | `governance.authorization` | `CLASSIFICATION_ZONE_NOT_PERMITTED` |
| `NotAuthorized(PROVIDER_NOT_REGISTERED)` | `governance.authorization` | `PROVIDER_NOT_REGISTERED` |
| `NotAuthorized(REQUIRED_CAPABILITY_NOT_SUPPORTED)` | `governance.authorization` | `REQUIRED_CAPABILITY_NOT_SUPPORTED` |
| `CandidateViabilityDecision.Viable` | `governance.viability` | (none) |
| `NotViable(AVAILABILITY)` | `governance.viability` | `AVAILABILITY` |
| `CandidateSelectionDecision.Selected(c)` | `governance.selection` | (none) |
| `NoSelection(NO_VIABLE_CANDIDATES)` | `governance.selection` | `NO_VIABLE_CANDIDATES` |
| `NoSelection(STRATEGY_DECLINED)` | `governance.selection` | `STRATEGY_DECLINED` |
| `NoSelection(STRATEGY_OUTSIDE_VIABLE_SET)` | `governance.selection` | `STRATEGY_OUTSIDE_VIABLE_SET` |

The authorization and viability families never share a `kind`, so a non-viable candidate can never be
read as not-authorized, and a selection escape refusal can never be read as a strategy decline.

## Historical interpretation rule

Evidence is interpretable from the record alone: identity, correlation, policy context, outcome and
structured reason are all bound at decision time. Interpreting a historical record must never load
current registration, availability, preference strategy or policy. This task records facts, not a
replay recipe.

## runtime-evidence.v1 relationship

Preferred direction: typed governance decision → envelope → narrow safe projection → existing
writer. `RuntimeEvidenceRecord`'s shape is sufficient: `decision.kind`/`reasonCode` carry the
outcome and reason family; `digests.subjectDigest` carries the candidate subject digest;
`digests.payloadDigest` carries the canonical payload; `metadata` carries the governed-identity
attribution through the existing `RuntimeEvidenceAttribution` guard; `eventId` carries the decision
identity; `correlationId` is preserved, not regenerated. **No schema change was required.** If the
projection had needed a fact the schema cannot carry, this task would stop and report that fact
before touching the schema; 0.7.4 owns evidence/projection contract freezing.

## External-runtime independence

`GovernedRunIdentity`, `PolicyContext` and the three decision types all live in `tramai-core` /
`tramai-security` and require no workflow, no `ProviderExecutionCoordinator`, no scheduling, no
orchestration, no workflow definition and no provider invocation. An external runtime (XR1) can
construct the envelope from governance inputs it already has — identity, correlation, policy
context, candidate facts — receive the same typed decision, and project the same evidence. The
envelope carries no engine-only type.

## 0.7.3h execution-path obligation remains OPEN

`ProviderExecutionCoordinator` can still resolve and execute routing/fallback independently of the
viable-selection boundary. Epic 0.7.3 cannot be declared complete while that holds. This task does
not close it and does not integrate with the execution path.

## Non-goals (unchanged)

No Spring AI adapter, no XR1, no `ProviderExecutionCoordinator` integration, no execution-path
selection enforcement (0.7.3h), no dashboard/query/projection API, no search/indexing, no
runtime-evidence v2, no policy simulation, no decision replay, no adaptive routing, no provider
invocation, no audit database, no event sourcing, no signing, no retention policy.
