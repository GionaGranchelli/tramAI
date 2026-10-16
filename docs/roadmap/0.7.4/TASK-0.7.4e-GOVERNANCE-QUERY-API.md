# TASK-0.7.4e — Headless Governance Query API

Status: **IN PROGRESS.** Phase 0 decision recorded below before implementation, per the task's
"derive → characterize → implement the smallest legal seam" ordering.

Base: `release/0.7.0` at `3bc94891482948d9f1289937dc6a3ad9e925873d`, verified live with `git fetch` +
`git rev-parse origin/release/0.7.0` before branching. The task's stated starting commit and the live
branch tip agree; recorded, not assumed.

## 1. Phase 0 — baseline and architecture decision

### Measured baseline

| Fact | Value | How measured |
|---|---|---|
| Module | `:tramai-security` | `config/quality/module-catalog.yml` |
| Layer | `governance-security` | same |
| Publishability | `published` | same |
| API stability | **`preview`** | same |
| Committed API dump | `tramai-security/api/tramai-security.api` (70,042 bytes) | `ls` |
| Existing surface | public policy/routing/authorization types beside `internal` projection types | `grep '^internal\|^class\|^data class'` over `governance/` |

The projection surface is entirely `internal`: `GovernanceProjectionReadModel`, `StoredGovernanceProjection`,
`GovernanceProjectionSnapshot`, `SnapshotFact`, `SnapshotDecision`, `SnapshotCoverage`, `ProjectionIdentity`,
`SourceProvenance`, `SourceCurrency`, `JdbcGovernanceProjectionStore`,
`UnsupportedProjectionFormatException`, `ProjectionCorruptionException`. None of these can be referenced
from a consumer outside the module, which is exactly the problem this task exists to solve.

### The decision: Option A

**A narrow public query interface and immutable public response types in `:tramai-security`, implemented
by an `internal` adapter over the existing read-only projection contract.**

Why this is the smallest legal approach:

- The module is `preview`, and the compatibility verifier's own contract states it: a `stable` module can
  never change its dump; a `preview`/`experimental` change is admissible **only** through an exact,
  hash-bound ACTIVE entry in `config/quality/api-migrations.yml` (`fromSha256` = base dump,
  `toSha256` = new dump, `targetVersion` == the current release). That procedure exists and is completed
  in this PR rather than bypassed.
- No new module edge: consumers already depend on `:tramai-security` (`module-boundaries.yml` carries
  `:tramai-bom → :tramai-security` and `:tramai-spring-sovereign → :tramai-security`), and
  `:tramai-persistence-jdbc` already declares `api(project(":tramai-security"))`.
- No internal type becomes public: the projection, snapshot, fact, decision, coverage, identity and store
  types stay `internal`; only the new contract and its response types are public, and the adapter that
  bridges them lives inside the module.
- Precedent: this module already publishes an intentional public surface (routing, policy, authorization)
  next to `internal` governance machinery, and the change guides document the store/API conventions the
  new types follow.

### Option B, and why it is not required

A dedicated query-contract module would add a new module plus two boundary edges to serve a surface that
is already legal in place, and it would force either a second copy of the response types or a public
re-export of the ones defined here. There is no dependency rule that requires it: the module graph,
`module-catalog.yml` and `module-boundaries.yml` all permit the contract to live in `:tramai-security`.
It remains the fallback only if the ABI admission procedure cannot be satisfied.

### Non-negotiable design constraints carried into implementation

- Querying never publishes, rebuilds, updates or deletes a snapshot.
- An identity-scoped query can never return another scope's projection; scope comes from the authoritative
  identity, not from caller labels.
- Format checks and digest validation stay mandatory in the query path.
- The two coverage claims stay independent: raw verified stream range and decision-record completeness are
  reported separately and neither is derived from the other.
- Authority and freshness classifications survive translation; a missing family cannot become an
  affirmative "no event occurred"; an authorization record never becomes execution authority; persisted
  routing evidence does not become authoritative by being persisted.
- Freshness is evidence-driven: a persisted snapshot cannot declare itself current, a caller-supplied
  version or hash is not an authenticated observation, and without a fresh observation the source is
  `UNKNOWN`.
- No protected payloads: no prompts, completions, tool arguments or results, credentials, arbitrary audit
  metadata, document bodies or raw provider error text.
- No mutation, authorization, approval, rejection, routing or registration operation is reachable through
  the query port.

## 2. Open items recorded for review

- Remote exposure is out of scope: this is a headless library contract. Any future endpoint needs its own
  access-control boundary (no HTTP authentication is added here).
- Live control state is not reconstructible from historical evidence; existing authoritative control-state
  queries remain the source of truth.

## 3. Verification, measured

All runs below are at the implementation head, with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.

| Command | Result |
|---|---|
| `./gradlew spotlessCheck` | clean |
| `./gradlew verifyCompilerWarnings` | clean (0 uncovered) |
| `./gradlew verifyStaticAnalysis` | 0 new findings (no baseline entry, no deviation change) |
| `./gradlew :tramai-security:test --tests 'dev.tramai.security.governance.*'` | 262 tests, 0 failures, 0 errors, 2 skipped (the opt-in restart tests skip without their harness) |
| `./gradlew verifyChangePolicy -PchangeClass=public-api -PchangePolicyBase=3bc94891…` | PASSED |
| `./gradlew verify060Architecture --no-daemon -PchangePolicyBase=3bc94891…` | **PASSED, 10/10 checks** |
| `./gradlew verify060Architecture` (without the base property) | FAILED — invocation artifact, see below |

### The architecture gate needs its base property, and this was measured rather than assumed

Run without `-PchangePolicyBase`, `verify060Architecture` fails on `api-architecture` with
`API_COMPATIBILITY_FAILED` for three modules and a long list of "migration entry is stale, orphaned,
hash-mismatched, or not landed" diagnostics for `:tramai-security` — including a base value of
`e3b0c442…`, the sha256 of the empty string, for `:tramai-control-plane`. That invocation has no base, so
every registry entry necessarily reads as stale.

The same non-canonical invocation was run at the pristine base commit in a throwaway checkout and failed
identically (`base_architecture_exit=1`), with the same diagnostic family, including pre-existing
`:tramai-platform` stability inversions and `API_MODULE_UNCLASSIFIED` findings that this change does not
touch. The canonical CI invocation — `.github/workflows/architecture-authority.yml`, which runs
`./gradlew verify060Architecture --no-daemon -PchangePolicyBase="${{ steps.base.outputs.sha }}"` — passes
at this head with 10/10 checks.

Recorded conclusion: the non-canonical failure is an invocation artifact, established by base/head
comparison at identical invocation, not by the apparent unrelatedness of its diagnostics.

## 4. The API admission, proved

`:tramai-security` is `apiStability: preview`, so its committed dump may change only through an exact
hash-bound entry in `config/quality/api-migrations.yml`. The entry added by this change was verified
against the dumps themselves rather than trusted:

| Value | Independent measurement |
|---|---|
| `fromSha256` = `613e6673…` | `git show 3bc94891:tramai-security/api/tramai-security.api \| sha256sum` — identical |
| `toSha256` = `9f401168…` | `sha256sum tramai-security/api/tramai-security.api` — identical |
| `targetVersion` | `"0.7.0"`, matching every other entry in the registry |

The dump diff contains additions only — no removed line — and the only pre-existing files this change
touches are the dump itself and the migration registry. No `internal` type became public: the projection,
snapshot, fact, decision, coverage, identity, store and materialization types are unchanged.

## 5. Review findings from the orchestration round

**F1 (P2, fixed).** The adapter caught `IllegalArgumentException` and reported it as
`InvalidStoredProjection`. The store's own load already maps contract-guard failures *on stored content* to
`ProjectionCorruptionException`, so an IAE reaching the adapter can only come from the mapper or from the
public view types' own construction guards — this change's own code. Reporting that as "invalid stored
projection" blames the persisted row for a translation defect and hides a real bug behind a
plausible-looking answer.

**F2 (withdrawn).** An early reading suggested that `matchesRequestedScope` comparing `auditStreamId` —
which the request cannot supply — would address a real projection under the wrong key and report
`NotMaterialized`. Checked against the code before acting:
`GovernanceProjectionMaterializer` requires `identity.auditStreamId == null`, so a persisted identity never
carries a stream and the comparison is null against null. The claim is withdrawn; the scope check is
defensive for rows this build cannot produce.

**F3 (P1, fixed).** The package exported the interface and every response type but no way to obtain an
implementation: the adapter is `internal` and no public factory existed, so both consumer tests could only
build a stub. A consumer outside the module could not query anything, which leaves the definition of done
unmet. A public factory taking public types only is added, the Java consumer test uses it, and a test
asserts the factory's declared types are all public so a later edit cannot quietly widen it to an internal
type.

**F4 (P3, fixed).** `query` blocks the calling thread (`runBlocking`). That is a deliberate trade for Java
interoperability, and it is now documented on the contract along with the fact that a suspending variant
is deliberately not part of this slice.

## 6. What a consumer sees

```kotlin
// Obtain the service from public API only (the factory is the entry point; the adapter stays internal).
val service: GovernanceQueryService = governanceQueryService(dataSource, controlPlaneQueries, auditStore)

when (val result = service.query(GovernanceQueryRequest("wl-1", "env-1", "dep-1", workflowRunId = "run-1"))) {
    is GovernanceQueryResult.Found -> {
        val view = result.view
        view.identity.workloadId                 // canonical scope, never a caller label
        view.provenance.registrationVersion      // registration evidence and its source version
        view.declaredRunIdentity                 // PRODUCER_DECLARED: declared, not authenticated
        view.currency.registration               // CURRENT / STALE / UNKNOWN, per source, never global
        view.currency.audit
        view.decisionFor(DecisionKind.POLICY_DECISION)?.let { decision ->
            decision.rawCoverage.verifiedRange   // what stream range was verified
            decision.recordCoverage.completeness // what was recognized inside it - independent claim
        }
    }
    GovernanceQueryResult.NotMaterialized -> Unit            // no snapshot for this scope
    is GovernanceQueryResult.UnsupportedFormat -> Unit       // refused, not upgraded
    is GovernanceQueryResult.InvalidStoredProjection -> Unit // refused, never a partial view
}
```

A caller cannot publish, rebuild, update or delete through this port: `GovernanceQueryService` declares a
single `query` method, and the only implementation reachable from public API is the read adapter.


## 7. Phase 5 — adversarial mutation battery

Seven mutations against the most dangerous behaviours, a green control recorded before each, exact text
anchors requiring a single occurrence, and every mutated source restored byte-identically (pre/post sha256
compared). Result: **5 killed, 1 survived, 1 invalid, 0 undetermined.**

Full transcript, per-mutation discriminators and the two harness defects found during the battery are in
`docs/roadmap/0.7.4/evidence/0.7.4e-governance-query-api/mutation-battery.txt`.

Two results are reported as findings rather than as passes:

- **SURVIVED** — deriving `accountedPositions` from the verified range span was not detected, because every
  fixture asserts an accounting where the recorded count equals the span, so the two are
  indistinguishable. A discriminating fixture (positions inside the range that are not accounted for)
  and its test were added; the requalification result is recorded in
  `evidence/0.7.4e-governance-query-api/gates.txt`.
- **INVALID** — the first form of the unsupported-format mutation did not compile, so it is not a kill and
  not evidence either way. It was requalified with a form that compiles, and that result is in the same
  evidence file.

## 8. Epic status

0.7.4e is the public-query slice of Epic 0.7.4, delivered on top of the merged 0.7.4d materialized read
model. It does not reopen 0.7.4d: the projection, snapshot, materializer and store are untouched, and the
adapter reads them through the existing read-only contract.

It does not close the epic either. Still open after this slice: the live `provider.route` producer
(`TASK-0.7.4b7`), the automated cross-process CI gate, identity authentication (T1), event-origin
authenticity (S19), and crash recovery under abrupt termination. Not delivered here, by design: dashboards,
REST/GraphQL/streaming endpoints, OIDC/RBAC, a policy engine, projection-side authorization, incremental
materialization or background refresh, a generic ontology, semantic timeline reconstruction (0.7.5), new
evidence schema versions, privileged runtime controls, and historical completion inference.
