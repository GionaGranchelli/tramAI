# Task 0.7.1d1 — Governed Approval Identity Carriage

**Parent:** [Epic 0.7.1 — Control-Plane Authority & Workload Identity](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md)

**Branch:** `task/418-governed-approval-identity-carriage` → `epic/0.7.1-control-plane-authority`

**Baseline:** epic head `08a78745` (0.7.1e control-plane authority contract, #420; release-closure
example-guide guard repair, #422)

**Issue:** #418 — carried forward from the 0.7.1d review (#407)

**Change class:** `runtime-behaviour` (primary)

**Status:** Frozen — ownership/trust model ruled; implementation in progress.

## Decision

0.7.1d established the canonical governed identity and its continuity rules, but deliberately left
the approval *creation* path un-attributed: `ApprovalGatewayPersistenceRequest` carried no
identity, so `DefaultApprovalGateway` and `SovereignOpsTransactionalApprovalGateway` had to reject
a governed run before the first persistence operation rather than durably record an un-attributed
suspension.

The capability that is actually missing is **not** durable governed-suspension storage. That
already exists and is wired:

- `GovernedSuspendedInvocationStore.createGoverned(GovernedSuspendedInvocation(metadata, identity), envelope)`
  writes ONE durable record: metadata + replay envelope + canonical identity;
- `ApprovalSuspensionCoordinator.resolveGovernedSuspension()` already fails closed with
  `ConfigurationException` when the configured store does not implement the governed interface;
- `SovereignJdbcPersistenceAutoConfiguration` and its file counterpart already wire the governed
  stores.

What is missing is (1) getting the two gateway creation paths onto that existing governed path,
(2) carrying attribution onto the approval lifecycle so an approval stays attributable while
pending and after it is decided, and (3) enforcing continuity when that lifecycle is
reconstructed. This slice does those three things and nothing else.

```text
creation authority
    GovernedRunScope
          │
          ▼
GovernedSuspendedInvocation  ← canonical suspension identity
          │
          ├──────── full equality ────────┐
          │                               ▼
          └──────────────────── approval-row attribution snapshot
                                         │
                                         ▼
                             survives approval lifecycle/history
```

## Authority model (frozen)

```text
CREATION
  GovernedRunScope is authoritative.
  Application/factory supplied identity is never trusted.

DURABLE SUSPENSION
  GovernedSuspendedInvocation is the canonical full governed identity.

APPROVAL ROW
  ApprovalBinding.workflowRunId stores the run id.
  Reserved framework metadata stores the five remaining identity components.
  Together they form an immutable attribution snapshot, not an independently
  mutable identity authority.

RECONSTRUCTION
  Identity is decoded from durable state, never synthesized from correlation IDs,
  workflow IDs alone, caller input, or ambient scope.

CONTINUITY
  Where canonical suspension identity and approval-row attribution both exist,
  all components must match exactly.

AMBIENT SCOPE AT RECONSTRUCTION
  May corroborate persisted identity but may never override it.
  If it disagrees, abort as an identity-substitution/continuity failure.

LEGACY
  A legacy approval has no reserved governed-attribution keys.
  A partial governed-attribution key set is corruption, never legacy.
```

Terminology: the approval-row copy is **not** a second authority. It is a framework-owned immutable
attribution snapshot whose only job is to keep the approval attributable throughout and after its
lifecycle. Where both representations exist, exact equality is required. Persisted identity is
authoritative; disagreement **aborts** — it does not "win" and continue under the persisted value
while a conflicting scope is active.

## Trust boundary (owner of the identity)

```text
factory
  └── produces persistence payload          (identity-blind)

gateway
  ├── resolves canonical runtime identity   (GovernedRunScope)
  ├── validates the caller's workflowRunId
  └── produces resolved payload + attribution

stores / mutation boundary
  └── accepts only resolved attribution for governed creation
```

`ApprovalGatewayRequestFactory` currently receives high-level request data plus `WorkflowRunId?`.
It has no canonical governed-identity input and **stays that way**. At creation the gateway:

1. resolves `GovernedRunScope`;
2. if governed, the ambient `GovernedRunIdentity` is authoritative;
3. if the caller supplied `workflowRunId`, require it to equal `identity.runId`;
4. if absent, derive the effective `WorkflowRunId` from the canonical identity for the existing
   factory call;
5. lets the factory construct the ordinary persistence payload;
6. adds trusted resolved attribution **after** the factory boundary;
7. only then allows persistence to begin.

A caller or factory must never be able to nominate *run A + workload/configuration/deployment
identity B* and have the gateway accept it.

### Structural distinction (not a nullable field)

The factory-produced `ApprovalGatewayPersistenceRequest` MUST NOT gain a
`governedRunIdentity: GovernedRunIdentity?` field — with or without a default. That would force the
factory to manufacture an attribution state the factory is explicitly not trusted to supply.

```kotlin
sealed interface ApprovalRunAttribution {
    data object Ungoverned : ApprovalRunAttribution

    data class Governed(
        val identity: GovernedRunIdentity,
    ) : ApprovalRunAttribution
}

interface GovernedSovereignOpsApprovalRequestMutationStore : SovereignOpsApprovalRequestMutationStore {
    suspend fun createGovernedApprovalRequest(
        request: ApprovalGatewayPersistenceRequest,
        identity: GovernedRunIdentity,
        auditIntent: SovereignOpsAuditOutboxRecord? = null,
        inboxMetadata: ApprovalInboxMetadata? = null,
        resumeCredential: ApprovalResumeCredentialRecord? = null,
    ): SovereignOpsApprovalRequestMutationResult
}
```

As built, the outer wrapper above is a **governed capability on the store boundary**, not a DTO: the
illustrative `ResolvedApprovalPersistenceRequest` shape was deliberately not introduced, because the
attribution is derived from the canonical identity inside the store that already owns the single
transaction. The engine-side equivalent is `GovernedApprovalStore`, mirrored for suspensions by
`GovernedSuspendedInvocationStore`. Names/package follow the existing architecture; the invariant is
what matters: the factory produces a payload, the gateway resolves identity and hands it over as the
canonical value, and the store boundary accepts only a whole identity for governed creation.

The invariant is **carriage through the gateway persistence boundary**, not allegiance to one DTO.
If introducing the wrapper at the lower mutation-store API changes a Preview public surface, that is
acceptable: let the architecture/API gate report the required migration. Do not contort the
ownership model to avoid an API hash, and do not manufacture a migration entry if the gate reports
no change.

## Contract

### Reserved attribution metadata

- Reserved keys carry exactly the five remaining identity components:
  `approval.identity.workload`, `approval.identity.configuration`,
  `approval.identity.configuration_version`, `approval.identity.environment`,
  `approval.identity.deployment`.
- Deliberately not prefixed `tramai.`: `RuntimeEventCatalogueArchitectureTest` reserves that
  namespace for runtime identifiers and configuration properties, and a persisted metadata key is
  neither.
- `ApprovalBinding.workflowRunId` remains the single stored run-id source. Decode reconstructs
  `GovernedRunIdentity.runId` from it; another run-id copy is never encoded.
- The reserved values are framework-owned. Application metadata can never determine them, and an
  application attempt to supply a reserved key is **rejected** as a collision rather than silently
  overwritten, so an attribution-injection attempt is observable instead of hidden.
- The reserved values are immutable across `pending → approved/denied/expired` transitions and are
  preserved verbatim by any decision/update path.

### Fail-closed rules

- No reserved keys → legacy approval, intentionally un-attributed; unchanged behavior.
- Partial reserved key set → **corruption**, never legacy, never a partial identity.
- Reserved values invalid (blank/unparseable) → corruption.
- When persisted approval attribution is checked against the canonical
  `GovernedSuspendedInvocation` identity, the canonical suspension run id must equal
  `ApprovalBinding.workflowRunId`; disagreement → **fail closed**. The run id decoded from the
  approval row itself is derived from `ApprovalBinding.workflowRunId`, so it cannot be the
  independent authority in this comparison.
- Persisted approval attribution and canonical `GovernedSuspendedInvocation` identity differ in any
  component → **fail closed**: run-id-only comparison is explicitly insufficient.
- An active ambient scope that disagrees with persisted attribution at reconstruction → abort as an
  identity-substitution/continuity failure. Ambient scope may corroborate, never override.
- A governed run whose configured suspension store does not implement
  `GovernedSuspendedInvocationStore` → `ConfigurationException` before any write (unchanged, now a
  configuration failure rather than an unconditional rejection).
- Nothing is written, and the mutation store is never called, before attribution is resolved and
  validated.

### Identity is never synthesized

At reconstruction, identity comes from durable state only. It is never rebuilt from
`WorkflowRunId`, correlation IDs, caller input, or ambient `GovernedRunScope`. A non-authoritative
decoy run id supplied to a reconstruction path must not produce an identity. Where a reconstruction
path *does* use a run id as an authoritative lookup/correlation key, disagreement with
`ApprovalBinding.workflowRunId` fails closed rather than being ignored.

## Surfaces

| Surface | Change |
|---|---|
| `tramai-engine` — `ApprovalGatewayPersistenceRequest` | **unchanged**; factory payload stays identity-blind |
| `tramai-engine` — `ApprovalRunAttribution` (sealed) + `GovernedApprovalStore` / `GovernedSuspendedInvocationStore`; ops — `GovernedSovereignOpsApprovalRequestMutationStore` | NEW: governed capabilities carrying the canonical identity into the store that owns the transaction (no `ResolvedApprovalPersistenceRequest` DTO was introduced) + reserved-key codec (encode/decode/collision) |
| `tramai-engine` — `DefaultApprovalGateway` | resolve ambient identity, validate `workflowRunId`, route governed suspensions to `createGoverned`; reject → carry |
| `tramai-spring-boot-starter-sovereign-ops` — `SovereignOpsTransactionalApprovalGateway` | same resolution/validation/carriage; inbox/audit artifacts carry only continuity-required attribution |
| approval-row persistence (JDBC + file) | write/read the reserved keys in existing `sanitized_metadata`; immutability across lifecycle transitions |
| `tramai-persistence-jdbc`, `tramai-persistence-file` | governed suspension route already exists; no new durable authority |
| approval reconstruction boundaries | whole-identity continuity gate |

## Test matrix

**Carriage**

1. Governed suspend through `DefaultApprovalGateway` → durable suspension is governed and carries
   all five components; zero fail-closed rejection.
2. Governed suspend through `SovereignOpsTransactionalApprovalGateway` → same.
3. Caller `workflowRunId` equal to `identity.runId` → accepted; caller `workflowRunId` different →
   fail closed, nothing written.
4. Caller `workflowRunId` absent while governed → effective run id derived from the identity; the
   persisted binding's `workflowRunId` equals `identity.runId`.

**Reconstruction without synthesis**

5. Reconstruct in a fresh store with **no ambient scope and no correlation id**; a decoy run id is
   supplied as a non-authoritative input → identity equals the original (proves it is read back,
   not rebuilt).
6. Where a run id *is* authoritative at the reconstruction boundary: disagreement with
   `ApprovalBinding.workflowRunId` → fail closed.
7. Ambient scope present at reconstruction pointing at a different identity → abort (persisted
   identity is authoritative; the scope never overrides).

**Corruption / substitution**

8. Persisted identity vs canonical suspension identity differ in one component with an identical
   run id → fail closed (run-id-only comparison proven insufficient).
9. Partial reserved key set → corruption exception; never a partial identity.
10. Reserved values present but run id disagrees with the binding → fail closed.

**Injection / immutability**

11. Application metadata supplying one or more reserved keys with a different but valid identity →
    rejected as a collision before durable mutation; the application value never reaches
    persistence.
12. Reserved-key mutation preservation: create a governed approval, transition it through at least
    one decision/update path, then verify the five reserved values are byte/semantically identical
    — D1 is not merely proven at creation.

**Boundary / legacy**

13. Governed run + non-governed suspension store → `ConfigurationException`, nothing written.
14. Legacy ungoverned approval → zero reserved keys, behavior unchanged.

## Compatibility

- Legacy un-attributed approvals remain fully supported and byte-compatible (no reserved keys).
- No schema migration: reserved keys ride the existing `sanitized_metadata` carrier.
- `ApprovalGatewayPersistenceRequest` is not widened; the factory contract does not change.
- API/migration bookkeeping is produced from the architecture gate's own diagnostics on the final
  rebase — never speculatively, and not at all if the gate reports no ABI change.

## Non-goals

0.7.1d1 does NOT: add approval-table columns or a schema migration; introduce a second identity
type, a parallel attribution store, or a second run identifier; redesign the audit/inbox schema or
expose new audit/query surfaces; widen the application-facing factory contract; add IAM/RBAC or
policy selection from workload identity; perform 0.7.1f exposure classification or 0.7.1g evidence
work; touch the dashboard; change approval lifecycle semantics.

## Verification

- `./gradlew :tramai-engine:test` — codec round-trip, collision rejection, corruption matrix,
  gateway carriage and fail-closed seams.
- `./gradlew :tramai-spring-boot-starter-sovereign-ops:test`,
  `:tramai-spring-boot-starter-sovereign-persistence-jdbc:test`,
  `:tramai-spring-boot-starter-sovereign-persistence-file:test` — governed approval row persistence,
  immutability across decisions, restart/cross-instance reconstruction.
- `./gradlew verify060Architecture -PchangePolicyBase=08a78745…` — API architecture, authorized by
  hash-bound migration entries taken from its own diagnostics.
- `./gradlew verifyStaticAnalysis` (0 new findings), `spotlessCheck`, `verifyChangePolicy`.
- Exact-head CI + Sovereign Runtime RC — final certification.

## Implementation order

```text
P0 freeze ownership + trust model        (this spec)
P1 reserved attribution codec + corruption/collision discriminators
P2 approval-row immutable attribution persistence
P3 gateway-resolved attribution + governed store routing
P4 reconstruction continuity gates + inbox/audit continuity
P5 file/JDBC restart + adversarial proofs
P6 docs/API bookkeeping last
```
