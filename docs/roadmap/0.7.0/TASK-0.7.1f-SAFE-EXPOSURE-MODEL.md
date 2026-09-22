# Task 0.7.1f — Safe Exposure Model

**Parent:** [Epic 0.7.1 — Control-Plane Authority & Workload Identity](EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md)

**Branch:** `task/0.7.1f-safe-exposure-model` → `epic/0.7.1-control-plane-authority`

**Baseline:** epic head `f2650e067320ef12f9503ce3b68c09259522f80e` (0.7.1d1 merged, #423)

**Change class:** `runtime-behaviour` (primary) + `public-api` (control-plane port payloads)

**Status:** Implemented.

## Objective

Epic requirement: *"Define default metadata categories; protected payloads have no generic query
surface."*

Make that requirement true **structurally** on the Kotlin control-plane contract, not by convention:

```text
generic control-plane read
        │
        ▼
explicitly safe operational model

protected workload/runtime payload
        X
        └── NOT reachable through any generic control-plane API
```

The deliverable is one explicit safe exposure type that both the framework-neutral ports and the
HTTP adapter can expose, so a later Dashboard, evidence, policy or administration layer consumes
*one* definition of safe metadata instead of inventing its own.

## Baseline — what the audit found (this is the real work of the slice)

Audited at `f2650e06`: `WorkloadControlPlaneQueries`, `ClassifiedRead`, `RegisteredWorkload`,
`WorkloadMetadata`, `WorkloadRegistrationResponse`, `WorkloadControlPlaneController`,
`WorkloadControlPlaneCommands` + the three outcome hierarchies, `WorkloadRegistrationStore` /
`CreateResult`, `InMemoryWorkloadRegistrationStore`, `JdbcWorkloadRegistrationStore` (+ migration
`V8__control_plane_workload_registration.sql`), and the server surfaces that construct them.

Two findings decide the implementation:

**Finding 1 — the HTTP adapter is already safer than the Kotlin contract.** 0.7.1e built
`WorkloadRegistrationResponse` as a deliberate allowlist that omits `configurationFingerprint`, and
documented why ("a witness has no business being handed back to a client"). But
`WorkloadControlPlaneQueries.authoritative()`/`projection()` return `ClassifiedRead`, which carries
the whole internal `RegisteredWorkload` **including the fingerprint**, and every command outcome
(`RegisterOutcome.*`, `MetadataUpdateOutcome.Applied/Unchanged`, `LifecycleTransitionOutcome.Applied/Unchanged`)
handles a `RegisteredWorkload` too. The generic *programmatic* control-plane boundary therefore
exposes an authority witness that the HTTP boundary deliberately withholds. The epic's own Socratic
clause predicted exactly this asymmetry.

**Finding 2 — nothing protected is reachable today, and that is currently an accident of the type
graph rather than a frozen contract.** No control-plane type has a field for prompts, model
input/output, user/conversation content, tool arguments/results, credentials, approval/suspension/
replay payloads, evidence or storage rows. The safety comes from the fact that `RegisteredWorkload`
happens to be narrow — which means a future slice can widen the generic surface by adding one field
to an internal record and changing nothing else (E9). This slice converts that accident into an
exact, test-enforced contract.

## Current exposure inventory

Classification legend: **SAFE** (approved for generic surfaces) · **AUTHORITY-ONLY** (needed to
establish/validate authority; must not cross a generic surface) · **PROTECTED** (payload content;
must never cross) · **OUT OF SCOPE HERE** (different surface/epic).

### Generic control-plane surfaces (in scope)

| # | field / value | source authority | stored where | currently readable through | class | reason |
|---|---|---|---|---|---|---|
| 1 | `workloadId` | `WorkloadDeploymentIdentity` (`tramai-core`) | `tramai_workload_registration.workload_id` | Kotlin read port + every outcome + HTTP GET/POST/PUT | SAFE | identifies the workload; validated bounded id |
| 2 | `configurationId`, `configurationVersion` | `WorkloadConfigurationIdentity` | `tramai_workload_registration.configuration_id/_version` | same | SAFE | configuration identity/version — what a client must name to operate the workload |
| 3 | `environmentId`, `deploymentId` | `WorkloadDeploymentIdentity` | `tramai_workload_registration.environment_id/deployment_id` | same | SAFE | deployment scope |
| 4 | `owner`, `purpose` | `WorkloadMetadata` | `tramai_workload_registration.owner/purpose` | same | SAFE | bounded operational metadata; validated in `tramai-core` (non-blank, trimmed, ≤256/≤512, no ISO control chars) and bounded again by DB CHECK constraints |
| 5 | `lifecycle` | `WorkloadLifecycleState` | `tramai_workload_registration.lifecycle_state` (CHECK ACTIVE/SUSPENDED/RETIRED) | same | SAFE | registration lifecycle state |
| 6 | `stateVersion` | `WorkloadStateVersion` | `tramai_workload_registration.state_version` (CHECK ≥ 1) | same | SAFE | authoritative state version |
| 7 | `consistency` | `QueryConsistency` (derived) | not stored | HTTP only | SAFE | read classification, not workload content |
| 8 | `observedVersion` | `ClassifiedRead` | not stored | Kotlin read port + HTTP `ETag` | SAFE | the version the read testifies to |
| 9 | **`configurationFingerprint`** | `ConfigurationFingerprint` | `tramai_configuration_revision.fingerprint` | **Kotlin read port (`ClassifiedRead.registration`) and every non-stale command outcome; deliberately NOT on HTTP** | **AUTHORITY-ONLY — see ruling** | the witness the store compares to decide mutation authority (`compareAndSet` rejects a caller whose immutable witness differs) and the value that makes `(configurationId, version)` unrebindable. A client cannot present it back (commands take `expectedVersion`, never a record) and must not be invited to treat it as a checkable credential |
| 10 | `RegisteredWorkload` as a *type* | internal authority record | — | return/parameter-reachable from both ports | AUTHORITY-ONLY | carrying the witness is its job **as a store record**; as a generic contract type it is the leak vector |
| 11 | persisting/runtime records (`JdbcWorkloadRegistrationStore`, `InMemoryWorkloadRegistrationStore`, JDBC `ResultSet` rows) | storage | DB / maps | not reachable through either port | not exposed | E4 holds today; pinned by test |
| 12 | any `Map<String, Any?>`, `Map<String, String>`, `JsonNode`, `Any`, `Object` metadata bag | — | — | does not exist on any control-plane port or type | does not exist | E2 holds today; pinned by test |

### Adjacent generic surfaces (audited, deliberately NOT changed here)

| surface | observation | disposition |
|---|---|---|
| `GET /workflows/{name}/runs/{id}` → `WorkflowRunDetail.history/result/error`, `WorkflowRunResponse.result: Any?` (`tramai-server`) | a generic workflow-run surface that *does* carry unchecked application payload (`Any?`), and SSE/`result` echoes of run output | OUT OF SCOPE HERE: it is the workflow-execution API, not the control-plane workload surface; it does not cross the control-plane boundary and is not reachable from `WorkloadControlPlaneQueries`/`Commands`. Recorded as an adjacent finding for its own slice — 0.7.1f may not silently widen into it. |
| `AuditPage`/`AuditRecord`, `WorkerInfo`, `ScheduleSummary` | operational/audit read surfaces | OUT OF SCOPE HERE; audited, not reachable from the control-plane ports |

### Fields deliberately judged *not* in scope

Narrowly-scoped maps elsewhere in the repository were judged individually against the question
*"does this cross the generic control-plane boundary?"* — none of them does. 0.7.1f therefore adds
no repository-wide classification system and no redaction framework.

## Authority / exposure boundary

```text
                       INTERNAL AUTHORITY
        RegisteredWorkload + WorkloadRegistrationStore (CAS witness)
                         │
                         │ WorkloadExposure.from(record)   ← the explicit allowlist mapper
                         ▼
                WorkloadExposure  (safe control-plane model)
                         │
             ┌───────────┴───────────┐
             ▼                       ▼
     WorkloadControlPlaneQueries  command outcome payloads
     (authoritative | projection)  (register | metadata | lifecycle)
             │                       │
             └───────────┬───────────┘
                         ▼
            WorkloadRegistrationResponse (HTTP, unchanged shape)

PROTECTED PAYLOAD STORES / persistence records
prompts · tool data · approvals · evidence · replay · secrets · storage rows
                         │
                         X   not reachable — no control-plane type can hold them
                         │
               generic control-plane

WorkloadRegistrationStore / CreateResult — the persistence SPI — keeps carrying
RegisteredWorkload: the CAS witness is *required* there. The witness stops at the
storage boundary; it does not continue into the generic ports.
```

## Safe-by-default categories (frozen allowlist)

The complete set of facts a generic control-plane surface may expose is exactly:

```kotlin
WorkloadExposure(
    identity: WorkloadDeploymentIdentity,   // workloadId, configuration (id, version), environmentId, deploymentId
    metadata: WorkloadMetadata,             // owner, purpose — bounded, unchanged, not extensible
    lifecycle: WorkloadLifecycleState,      // ACTIVE | SUSPENDED | RETIRED
    stateVersion: WorkloadStateVersion,     // authoritative state version
)
```

plus the read classification (`QueryConsistency`, `observedVersion`) which travels beside it in
`ClassifiedRead`, never inside it. This is an allowlist of named, typed fields — not permission to
expose "nearby" fields.

## Authority-only / internal categories (frozen)

| value | ruling |
|---|---|
| `configurationFingerprint` | **AUTHORITY-ONLY. Must not appear on any generic control-plane surface** — programmatic or HTTP. Rationale: it is the value `WorkloadRegistrationStore.compareAndSet` compares to decide whether a caller has mutation authority, and the value that makes a `(configurationId, version)` binding immutable. Exposing it lets a generic client read authority evidence it cannot use and must not be taught to present. The HTTP adapter already ruled this way in 0.7.1e; 0.7.1f makes the Kotlin contract agree instead of leaving a documented-but-enforced-nowhere exception. |
| `RegisteredWorkload` as a port payload type | **AUTHORITY-ONLY.** It remains public because `WorkloadRegistrationStore` (the persistence SPI) requires the witness; it ceases to be reachable from `WorkloadControlPlaneQueries` / `WorkloadControlPlaneCommands`, including through their outcome hierarchies. |
| `CreateResult`, store records, `ResultSet` rows | storage-only; never a generic surface (E4). |

**How to state "safe" — post-fix, the fingerprint is still visible in one legitimate place**: a
caller of `WorkloadRegistrationStore` (a persistence adapter, or an application that installs its
own store) sees it. That is the store SPI contract, not the generic control-plane contract, and
0.7.1f does not change it — the CAS witness must be comparable.

## Protected categories (frozen — must remain unreachable)

`prompts` · `model input` · `model output` · `conversation/user content` · `tool arguments` ·
`tool results` · `credentials` · `tokens/secrets` · `PII-bearing payloads` · `approval payload
content` · `suspension/replay payloads` · `raw evidence payload bodies` · `raw provider
requests/responses` · `runtime state containing application payload` · `arbitrary caller-controlled
metadata maps`.

This is a category list, not a request to build an enum. The structural guarantee is that the
control-plane type graph cannot carry any of them: the exposure type declares four properties, all
of whose types are bounded identity/state/metadata values, and the reachable-type test fails closed
the moment anything else becomes reachable.

## Generic query contract (frozen)

```kotlin
data class ClassifiedRead(
    val exposure: WorkloadExposure,      // was: registration: RegisteredWorkload
    val consistency: QueryConsistency,
    val observedVersion: WorkloadStateVersion,
) {
    init { require(exposure.stateVersion == observedVersion) { ... } }   // unchanged invariant
}

interface WorkloadControlPlaneQueries {
    suspend fun authoritative(...): ClassifiedRead?    // unchanged signature
    suspend fun projection(...): ClassifiedRead?       // unchanged signature
}
```

The 0.7.1e guarantees are preserved verbatim: `AUTHORITATIVE` vs `PROJECTION` stay distinct,
`observedVersion` stays the version the read testifies to, the state/version contradiction guard
stays, absence stays `null` on the read path, and no read path can mutate.

## Command outcome contract (frozen)

Every outcome payload that was a `RegisteredWorkload` becomes a `WorkloadExposure`:

| type | before | after |
|---|---|---|
| `RegisterOutcome.Created` / `AlreadyRegistered` | `registration: RegisteredWorkload` | `exposure: WorkloadExposure` |
| `RegisterOutcome.Rejected` | `existing: RegisteredWorkload` + `reason` | `existing: WorkloadExposure` + `reason` (**unchanged** `RegistrationConflictReason`) |
| `MetadataUpdateOutcome.Applied` / `Unchanged` | `registration: RegisteredWorkload` | `exposure: WorkloadExposure` |
| `LifecycleTransitionOutcome.Applied` / `Unchanged` | `registration: RegisteredWorkload` | `exposure: WorkloadExposure` |

`Stale`, `NotFound`, `InvalidTransition` carry no record and are unchanged, as are the typed
`currentVersion`/`expectedVersion` semantics and the command/query split (0.7.1e must not be
weakened).

Why the command path is in scope even though the Epic says *query surface*: an outcome is a generic
control-plane response consumed directly by a replaceable transport adapter (that is literally how
`WorkloadControlPlaneController` uses it), E8 forbids a create/update becoming a backdoor for a
field excluded from GET, and `RegisterOutcome.Rejected(existing)` currently hands the authoritative
fingerprint to a caller whose declaration *did not match* — a genuine disclosure, not a false
positive.

`WorkloadRegistrationStore.create/compareAndSet/find` and `CreateResult` are **unchanged**: the
store SPI is where the witness is required.

## HTTP / serialization contract (frozen)

`WorkloadRegistrationResponse` keeps its exact 10 properties and its omission of the fingerprint;
only its **input** changes:

```kotlin
fun from(exposure: WorkloadExposure, consistency: String = "AUTHORITATIVE"): WorkloadRegistrationResponse
```

`okResponse(read: ClassifiedRead)`, `okResponse(exposure: WorkloadExposure)` and
`createdResponse(exposure: WorkloadExposure)` map the safe model. The controller no longer holds a
`RegisteredWorkload` in any branch, so the adapter *cannot* serialize an internal record even by
accident (E1) — the mapper call is the only construction path.

Status/ETag/`ProblemDetail` semantics are untouched (400/404/409/412/428, `ETag: "<version>"` from
`observedVersion` or the outcome's exposure).

## Invariants

| id | invariant | how it is enforced |
|---|---|---|
| E1 | a generic control-plane response is constructed from an explicit safe schema, never from the internal record | the only construction path is `WorkloadExposure.from(record)`; HTTP maps the exposure; `WorkloadRegistrationResponse.from` no longer accepts a `RegisteredWorkload` |
| E2 | no generic public API widens the surface with `Map<*,*>`, `JsonNode`, `Any`, `Object` | exact input/output type-set tests over both ports |
| E3 | no generic path can return prompts, model/user/tool/approval/evidence/secret payloads | reachable-type test (exact set) + exact exposure property set |
| E4 | no persistence record is returned by the public contract | reachable-type test asserts nothing from `dev.tramai.persistence` is reachable |
| E5 | `WorkloadMetadata` stays bounded | exact property-set test on `WorkloadMetadata` (owner, purpose only); no bag can be added silently |
| E6 | query consistency/version semantics preserved | existing 0.7.1e contract tests, unchanged and green |
| E7 | authority witnesses get an explicit ruling | fingerprint ruled AUTHORITY-ONLY; exclusion asserted on the type graph *and* on HTTP JSON |
| E8 | command responses cannot widen reads | outcome payload type-set test + HTTP exact-shape tests on POST and PUT |
| E9 | additions fail closed | every shape assertion is exact (`==` approved set), so a new field/type/JSON property reddens the build and forces an explicit decision |

## Adversarial test matrix

`tramai-control-plane` (new `WorkloadExposureModelTest`):

1. `WorkloadExposure` declares exactly `{identity, metadata, lifecycle, stateVersion}` with exactly
   the approved types.
2. The mapper is the allowlist: an exposure built from a record whose fingerprint carries a unique
   sentinel contains no trace of it (`toString`, `equals`/`hashCode` inputs), and no exposure field
   type can hold a `ConfigurationFingerprint`.
3. Port **output** type graph == approved set (exact) — `RegisteredWorkload` and
   `ConfigurationFingerprint` are absent; nothing from `dev.tramai.persistence` is present.
4. Port **input** type graph == approved set (exact) — the witness may be *submitted* (`register`)
   but no input bag type exists; `RegisteredWorkload` is not an input.
5. `ClassifiedRead.exposure` is the safe type and the state/version contradiction guard still fires.
6. `WorkloadMetadata` declares exactly owner/purpose (no `Map`, no `Any`).
7. Read-path classifiers (`authoritative`, `projection`) yield the same exposure shape — a
   projection cannot widen the surface relative to an authoritative read.
8. Query-consistency behaviour is unchanged (existing 0.7.1e assertions re-run against the new
   payload type).

`tramai-server` (extended `WorkloadControlPlaneControllerTest`):

9. GET/POST/PUT JSON property set == the approved 10-property set **exactly** (`readTree().fieldNames()`),
   for authoritative GET, 201 create, 200 duplicate-register, 200 metadata update, 200 lifecycle update.
10. A registration whose fingerprint is a unique sentinel produces no response body containing that
    sentinel (GET and both mutations).
11. 412 body still carries `expectedVersion`/`currentVersion`, and 404/409/428 bodies are unchanged.

Preferred discriminator: `assertThat(jsonProperties) == approvedSet` (not
`assertFalse(body.contains("password"))`, which proves nothing about the next field).

## Compatibility / API impact

- `:tramai-control-plane` is `maturity: preview`, `apiStability: preview`, published → the dump
  changes and requires an **ACTIVE** entry in `config/quality/api-migrations.yml` with
  `fromSha256 = 0c656c8654f45c0d8fc33b25c8e8935e3bcc6bf1d6ff6d1f395b4a19725db7e8` (the committed
  dump at the epic baseline), the post-`apiDump` `toSha256`, `targetVersion: "0.7.0"`, a rationale
  naming the 0.7.1f invariant, and the consumer-facing migration note.
- `:tramai-server` is `apiStability: internal` (`applicable()` is false in
  `ApiCompatibilityVerifier`, so dumps of internal modules authorize nothing and need no entry —
  0.7.1e changed `tramai-server.api` with entries for `:tramai-control-plane` only).
- No consumer-visible behaviour change is required: this is a preview contract, additive in intent
  (the safe model replaces a strictly wider payload), and no signature *name* other than the payload
  property (`registration` → `exposure`) changes.
- No `tramai-core` identity type, no `WorkloadMetadata` bound, no store SPI and no persistence
  implementation changes.

## Non-goals

Not in 0.7.1f: DLP/PII/secret scanning; field-level RBAC; OIDC/OAuth/SAML; an authorization policy
system; an encryption framework; a policy DSL; an evidence query API (0.7.4); an approval reviewer
UI; Dashboard work (0.7.8); a generic CQRS framework; a generic data taxonomy/ontology; arbitrary
metadata schemas; a redaction framework; data-residency work; and the adjacent workflow-run payload
surface recorded above. No speculative fields for clients that do not exist.

## Mutation expectations

`:tramai-control-plane` is **not** in the mutation population: `config/quality/test-quality.yml`
`mutation.targetFamilies.*.modules` covers `:tramai-core`, `:tramai-engine`, `:tramai-security`,
`:tramai-sovereign`, `:tramai-orchestration`, `:tramai-persistence-file`,
`:tramai-persistence-jdbc` — and `config/quality/mutation-baseline.json` contains no
`:tramai-control-plane` record. Onboarding the module is a quality-config + baseline change, which
`AGENTS.md` forbids riding inside a runtime-behaviour PR (and `mutation-baseline.json` is a
canonical-baseline artifact). 0.7.1f therefore proves the semantic mutations with the exact-shape
discriminators instead, and records the gap as a **deferred concern** rather than building a second
mutation mechanism:

| mutation | killed by |
|---|---|
| safe response starts returning an internal field | test 1 / 9 (exact property sets) |
| a protected value becomes default-safe | test 3 (exact reachable-type set) |
| the explicit mapper is bypassed by the internal record | test 5 + `from(exposure)` only accepting the safe type (compile-time) |
| metadata bounds removed / bag introduced | test 6 (exact property set on `WorkloadMetadata`) |
| an authority witness becomes generically exposed | tests 2, 3, 9, 10 |

## Implementation boundary

- Touches: `tramai-control-plane` (`WorkloadExposure`, `ClassifiedRead`, the three outcome
  hierarchies, `WorkloadRegistrationAuthority` mapping), `tramai-server`
  (`WorkloadRegistrationResponse.from`, `WorkloadControlPlaneResponses`, controller branches),
  their tests, `docs/modules/tramai-control-plane.md`, this spec, and the API migration entry.
- Does **not** touch: `tramai-core` identity/metadata types, `WorkloadRegistrationStore`/`CreateResult`,
  persistence implementations, migration SQL, engine/orchestration/security/sovereign, workflows,
  dashboard, or the adjacent workflow-run surface.

## Verification

- `./gradlew :tramai-control-plane:test` — exposure model, type graph, outcomes, 0.7.1e consistency re-run.
- `./gradlew :tramai-server:test` — HTTP exact-shape + sentinel + unchanged status/ETag semantics.
- `./gradlew :tramai-persistence-jdbc:test` — the store SPI still carries the witness (no regression).
- `./gradlew verify060Architecture -PchangePolicyBase=<epic base>` — the API transition is authorized
  by the ACTIVE migration entry.
- `./gradlew verifyPr` (JDK 21 locally), then `verifyStaticAnalysis`, `verifyCompilerWarnings`,
  `verifyCancellationSafety -PtramaiCancellationBaseSha=<epic base>`, `verifyChangePolicy
  -PchangePolicyBase=<epic base>`, `spotlessCheck`, plus exact-head CI as the certifying signal.
- A gate is green only when it was actually run on the reported head.

## Definition of done

- [x] exposure inventory recorded and frozen (this document)
- [x] safe / authority-only / protected categories frozen (this document)
- [x] `configurationFingerprint` ruled AUTHORITY-ONLY and structurally excluded from every generic surface
- [x] generic query port, command outcomes and HTTP all expose only the approved model
- [x] projection reads cannot widen the surface
- [x] no arbitrary metadata bag reachable; `WorkloadMetadata` stays bounded
- [x] no persistence record reachable from the public contract
- [x] exact-shape/type-graph discriminators implemented and proven to fail when the boundary moves
- [x] API transition authorized by an ACTIVE migration entry
- [ ] focused module suites green; repository quality gates green on the reported head
- [ ] task doc, module doc and PR body agree with the final implementation
- [ ] working tree clean

## Socratic clause / unresolved risks

1. **This slice does not prove absence of a leak into unrelated payload surfaces.** The adjacent
   workflow-run surface (`WorkflowRunDetail.result: Any?`) is a real generic-payload exposure on the
   *workflow execution* API. It is out of 0.7.1f's boundary, and this document records it rather than
   claiming the repository has no such surface.
2. **Mutation coverage for the boundary is deferred, not delivered.** Onboarding
   `:tramai-control-plane` into `test-quality.yml` requires a canonical mutation baseline; that must
   be its own PR. Until then the boundary survives because the tests are exact, not because a
   mutation engine proved them.
3. **A safe model is only as safe as its mapper.** `WorkloadExposure.from` is a hand-written
   allowlist; nothing prevents a future slice from adding a *protected* property to the exposure if
   the reviewer allows the corresponding test update. The protection is that the update is visible
   and deliberate (E9), not that it is impossible.
4. **Endpoint gating is unchanged.** `tramai.control-plane.http.enabled` still defaults to off; this
   slice hardens the contract behind it, and does not add authentication. 0.7.6 owns auth.
