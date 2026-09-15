# Task 0.7.1e — Control-Plane Authority Contract

**Parent:** [Epic 0.7.1 — Control-Plane Authority & Workload Identity](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md)

**Branch:** `task/0.7.1e-control-plane-authority-contract` → `epic/0.7.1-control-plane-authority`

**Baseline:** epic head `ecadc4b5` (0.7.1d merged, #407)

**Change class:** `runtime-behaviour` (primary) + `public-api` (new public control-plane surface)

**Status:** Specified; implementation not started.

## Objective

Give an external control-plane client a safe way to read and mutate authoritative workload state:
one mutable authority, explicit read semantics, version-guarded commands, and no path by which a
projection or query can become a second source of truth.

Central invariant:

```text
commands  ->  authoritative state
queries   ->  authoritative state, or an explicitly classified read projection
query/projection path MUST NOT mutate runtime authority
mutation MUST carry the expected authoritative state version
STALE expected version -> reject, never overwrite
```

Derivation: the 0.7.1a authority audit recorded two remaining gaps — no general command precondition
across control-plane mutations, and no defined projection boundary (a controller can currently expose
a store view with no contract stating that it is read-only or how stale it may be).

## What already exists (audit before invention)

Most of the concurrency machinery landed in 0.7.1c and is **reused, not rebuilt**:

- `WorkloadStateVersion` — monotonic value type with `next()`, `INITIAL = 1`, overflow guard.
- `WorkloadRegistrationStore.compareAndSet(expected, updated): Boolean` — atomic, deployment scope +
  state version is the concurrency token, and the immutable witness (identity + configuration
  fingerprint) must also match, so a fabricated record cannot gain mutation authority by guessing a
  version. Returns false on absent scope, moved version, or mismatched witness.
- `WorkloadRegistrationAuthority` — already returns **typed** outcomes and already reports the
  *current* authoritative version on a lost race:
  `MetadataUpdateOutcome.{Applied, Stale(currentVersion, expectedVersion), Unchanged, NotFound}`,
  `LifecycleTransitionOutcome.{Applied, Stale, Unchanged, NotFound, InvalidTransition}`,
  `RegisterOutcome.{Created, AlreadyRegistered, Rejected}`.
- `tramai-server` — already constructs the store/authority (`ServerGovernance`,
  `ServerGovernanceConfiguration`) and already answers with `ProblemDetail` (RFC 7807) through
  `@ExceptionHandler` methods.
- `tramai-persistence-jdbc` — `JdbcWorkloadRegistrationStore` implements the atomic store contract.

Consequence for scope: 0.7.1e does **not** build concurrency control, a CQRS framework, or an event
projection engine. It builds the *external contract* around machinery that exists, and proves the
separation holds.

## Contract

### A. Command authority

A public command surface over the existing authority. It exposes commands, never the store:

```kotlin
interface WorkloadControlPlaneCommands {
    suspend fun register(...): RegisterOutcome            // existing authority operation
    suspend fun suspend(...): LifecycleTransitionOutcome  // expectedVersion required
    suspend fun activate(...): LifecycleTransitionOutcome // expectedVersion required
    suspend fun retire(...): LifecycleTransitionOutcome   // expectedVersion required
    suspend fun updateMetadata(...): MetadataUpdateOutcome // expectedVersion required
}
```

```text
Command -> command boundary -> WorkloadRegistrationAuthority -> WorkloadRegistrationStore.compareAndSet
```

- The command layer delegates lifecycle rules to `WorkloadRegistrationAuthority`; it re-implements
  no lifecycle rule, no fingerprint check and no CAS.
- Every state-dependent mutation carries `expectedVersion`. No unconditional save exists anywhere on
  this path — "read, modify, unconditional write" is structurally unavailable, not merely discouraged.
- The outcome types stay exactly as the authority defines them. 0.7.1e adds no parallel result type
  and no generic persistence exception.

### B. Stale-precondition semantics

Already typed at the authority; 0.7.1e makes it externally consumable and guarantees:

- `Stale(currentVersion, expectedVersion)` reports the **current** authoritative version (the value a
  retrying client needs), never the version the caller guessed. On a lost race the authority
  re-reads authoritative state before reporting, so this holds under concurrency, not just in the
  sequential case.
- `NotFound` is distinct from `Stale`; `Unchanged` is distinct from `Applied`; `InvalidTransition` is
  distinct from both. None of these may be collapsed into `false`, `null` or a 500.
- `RETIRED` stays terminal through the command surface: the authority's transition rules are the only
  authority for that, and the command layer must not add a bypass.
- Concurrency is resolved by the store, never by the command layer: two commands carrying the same
  `expectedVersion` can both reach `compareAndSet`, and exactly one can win.

### C. Query / projection contracts

Reads are split structurally, with the minimum contract:

```kotlin
enum class QueryConsistency { AUTHORITATIVE, PROJECTION }
```

- **Authoritative query** — reads the authority/store; returns the current authoritative record,
  including its `stateVersion`.
- **Projection query** — read-only derived state; may lag; **must** report the authoritative version
  it observed so a client can compare (e.g. authoritative `stateVersion = 18`, projection
  `observedVersion = 16`), rather than asserting a vague "eventually consistent: true".
- Projection results are immutable and cannot be fed back as a mutation input: a projection value is
  not a valid `expected` witness for a command.

No generic CQRS framework, no event bus, no arbitrary metadata query language.

### D. HTTP concurrency contract

0.7.1c deferred REST query/mutation APIs and `If-Match`/ETag to this slice; nothing in the repository
implements `If-Match` today, so this defines the contract rather than extending one.

- `GET workload` → `RegisteredWorkload` with `ETag` derived from `WorkloadStateVersion`. The ETag is a
  version token: `"<stateVersion>"` (strong, since it changes on every authoritative mutation).
- mutation → requires `If-Match: <etag>`; matching version executes the command, stale version is a
  precondition failure, missing required precondition is a precondition-required response.
- Placement: the HTTP surface lives in **`tramai-server`**, which already owns governance wiring and
  the `ProblemDetail` error convention. `tramai-control-plane` stays framework-agnostic (its only
  `api` dependency is `tramai-core`) and must not gain a web dependency; its module doc currently
  states "no REST/query surfaces … here (later candidates)" and is reconciled in this slice.
- Failure mapping (final codes to be confirmed against the existing handlers before implementation):

  | Condition | Status | Notes |
  |---|---|---|
  | stale `If-Match` | 412 | body carries current authoritative version |
  | missing `If-Match` on a required mutation | 428 | never silently last-write-wins |
  | unknown deployment scope | 404 | |
  | invalid lifecycle transition (e.g. RETIRED is terminal) | 409 | distinct from precondition failure |
  | malformed/absent `If-Match` value | 400 | not reinterpreted as "no precondition" |

  Errors are `ProblemDetail` with the authoritative version as an extension property, matching the
  existing handler style.

- Critically: `Controller -> command/query contract -> authority`. Never
  `Controller -> JdbcWorkloadRegistrationStore`.

## Invariants

1. Exactly one mutable authority for workload registration state.
2. Every state-dependent mutation carries an expected version; no unconditional write path exists.
3. A stale expected version is rejected and never overwrites newer state, including across restart and
   across instances.
4. A query or projection value is never accepted as mutation authority.
5. Projection values are immutable and self-describe their consistency class and observed version.
6. Authoritative reads return the authority's current version; projections cannot claim authority.
7. Lifecycle rules (including RETIRED terminality) live only in `WorkloadRegistrationAuthority`.
8. No persistence implementation type is reachable through the public query/command contract.

## Test matrix

Focused verification (async/TCK style matching the existing control-plane and JDBC suites):

1. Matching `expectedVersion` → mutation applied, version advances exactly once.
2. Stale `expectedVersion` → rejected with `Stale(current, expected)`, state unchanged.
3. Two concurrent commands with the same `expectedVersion` → exactly one succeeds, one reports `Stale`
   with the winner's version.
4. `RETIRED` remains terminal through the command surface.
5. A query path cannot invoke authoritative mutation (compile-time separation, not a runtime check).
6. A projection result exposes its `QueryConsistency` and observed version.
7. Projection lag does not authorize a stale command (a lagging projection version is rejected as
   stale).
8. No store implementation type is reachable through the public command/query contract.
9. Metadata/lifecycle mutation cannot replace immutable identity or configuration fingerprint.
10. Stale rejection survives JDBC persistence, restart and cross-instance execution.

### Adversarial discriminator

The strongest test is the one the Epic names: **mutation through a projection/read-model path must be
rejected**, and a **stale precondition against newer state must be rejected**. Both are proven by
attempting them through the public surface, not by asserting a type is absent. Additional
discriminators: `If-Match` present but stale; `If-Match` absent on a required mutation; `If-Match`
malformed (must not degrade to last-write-wins); two same-version commands racing.

## Failure semantics

Unknown scope → `NotFound`. Stale version → rejected with the current authoritative version.
Same-state command → `Unchanged`, no version advance, no `next()` (at `Long.MAX_VALUE` this must not
overflow). Invalid transition → `InvalidTransition`, distinct from stale. Lost CAS race → re-read and
report the authoritative version. Missing precondition → rejected, never implicit.

## Compatibility

- New public surface in `:tramai-control-plane` (preview) changes its API dump → exact hash-bound
  entries in `config/quality/api-migrations.yml` with `targetVersion: "0.7.0"` (the release the
  `0.7.0-SNAPSHOT` line targets), derived from the gate's own diagnostics on the exact base.
- `tramai-server` gains endpoints → additive; no existing endpoint's contract changes.
- Existing store/authority signatures must remain compatible: 0.7.1e consumes `compareAndSet` and the
  outcome types as they are. A signature change there would be a breaking change to a preview module
  and requires an explicit migration entry and justification.

## Non-goals

Not in 0.7.1e: policy/trust zones (0.7.2); provider-selection projections (0.7.3); evidence query API
(0.7.4); timeline reconstruction (0.7.5); IAM/OIDC authorization (0.7.6); persisted run cancellation
(0.7.7); Dashboard work (0.7.8); a generic projection framework; an arbitrary metadata query language;
a CQRS/event-sourcing framework; classifying safe metadata categories or restricting protected
payload exposure (0.7.1f); carrying `GovernedRunIdentity` through `ApprovalGatewayPersistenceRequest`
(tracked separately — see below).

## Socratic clause — must not disappear

`ApprovalGatewayPersistenceRequest` still does not carry `GovernedRunIdentity`. 0.7.1d deliberately
kept the affected governed approval creation paths **fail-closed** rather than silently dropping
attribution. This is not a query/command-authority concern, so it does not derail 0.7.1e — but it must
be resolved before 0.7.1 closes, either as a small 0.7.1d follow-up slice or inside 0.7.1g once the
carriage contract itself is implemented. Tracked as its own issue.

## Implementation boundary

- Touches: `tramai-control-plane` (command/query contract types), `tramai-server` (HTTP surface +
  handlers), `docs/modules/tramai-control-plane.md`, this spec.
- Does not touch: `tramai-core` identity types, persistence implementations, scheduler, engine,
  dashboard module code.

## Verification

- `./gradlew :tramai-control-plane:test` — command outcomes, projection contract, invariants.
- `./gradlew :tramai-server:test` — HTTP `ETag`/`If-Match` mapping, error DTO shape, no-store-leak.
- `./gradlew :tramai-persistence-jdbc:test` — stale rejection across restart/cross-instance.
- `./gradlew verify060Architecture -PchangePolicyBase=<exact epic base>` — API transitions authorized
  by migration entries.
- `./gradlew verifyPr` — primary local gate (JDK 21 locally).
- Exact-head CI + Sovereign Runtime RC — final certification.

## Definition of done

All ten test-matrix items implemented and green; the two Epic-named adversarial cases proven through
the public surface; no store type reachable from the public contract; API transitions authorized with
exact entries; spec, module doc and PR body consistent with the implementation; exact-head CI + RC
green on a single SHA.
