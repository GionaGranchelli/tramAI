# TASK 0.7.3c — Authorized-Set Derivation

**Epic:** [EPIC-0.7.3-AUTHORIZED-SELECTION.md](EPIC-0.7.3-AUTHORIZED-SELECTION.md)  
**Branch:** `task/0.7.3c-authorized-set` → `epic/0.7.3-authorized-selection`  
**Status:** Implemented

## What this slice establishes

```text
candidate + existing governance facts
              ↓
      AUTHORIZED | DENIED
```

One invariant: **a candidate is authorized only when every required restriction
permits it, and nothing becomes authorized through fallback or the absence of
policy.**

## Contract

`dev.tramai.security.governance.CandidateAuthorization`, and the candidate it
reasons over:

```kotlin
data class ProviderCandidate(
    val providerId: String,
    val modelId: String,
    val deployment: ProviderDeployment,
)

class CandidateAuthorization(private val release: ProviderInputRelease = ProviderInputRelease()) {
    fun authorizes(candidate, workloadZone, classification): Boolean
    fun authorizedSet(candidates, workloadZone, classification): Set<ProviderCandidate>
}
```

The entire executable body is four identity validations, one conjunction, and a
filter:

```text
deployment.providerId == candidate.providerId
        AND
release.releases(workloadZone, classification, deployment.trustZone.category)
```

`ProviderCandidate` pairs an identity with exactly one `ProviderDeployment`, which
is the minimum this boundary needs: trust follows from where a deployment runs,
and one provider brand has many deployments in different zones, so an identity
alone cannot be authorized.

## Why the invariant holds structurally

- **Deny by default.** An authorization holding no policy and no rules denies
  every candidate. Permission has to be stated to exist.
- **The result is a `Set`.** Ordering cannot influence it and a candidate
  evaluated twice cannot add authority — the same structural move 0.7.2e used
  with set intersection. The type cannot express precedence.
- **No widening path.** Nothing consults another candidate, so one candidate's
  authorization cannot transfer to another and an empty result stays empty.
- **The two authorities are consumed, not restated.** `ProviderInputRelease` is
  already their conjunction (trust-zone compatibility AND the classification's
  permitted zones). A second copy of that predicate would be a second thing to
  keep in agreement, so this slice reuses it.

## Brand is not trust, and identity must agree with its deployment

Two deployments of one provider in different zones are different candidates;
only the authorized zone's deployment enters the set. Brand grants nothing.

A candidate whose identity disagrees with its deployment is **denied**, not
rejected at construction. Throwing would let a caller convert a malformed
candidate into an authorization by catching the exception; denial is a decision,
and it is testable. Blank or untrimmed identities are the construction-time
rejections, because they name nothing usable or name something ambiguous;
nothing else about a candidate throws.

## Boundary of this slice

Not here, and not implied: ranking, scoring, preference, viability or health
filtering, selection, fallback, retry, invocation, provider or model resolution,
and any I/O. `authorizedSet` has no default — an empty result means no candidate
may be used, not that the caller should look elsewhere.

Authorization currently coincides with the release predicate, because
provider-input minimization does not exist yet. When it does, release becomes
strictly narrower than authorization, and this boundary must consult the
authorization facts directly instead of the release decision.

Nothing consumes this boundary yet. Wiring it into an invocation path belongs
with 0.7.3e, where selection and fallback are fenced.

## Verification

| check | result |
|---|---|
| `:tramai-security:test --tests '*CandidateAuthorizationTest'` | 15 tests, 0 failing |
| governance suite in full (7 suites) | 71 tests, 0 failing |
| `spotlessCheck` | rc=0 |
| `verifyStaticAnalysis` | BUILD SUCCESSFUL — Detekt baseline 4792 → 4792, 0 added |
| `verify060Architecture` | PASSED — report `10/10` |
| `verifyChangePolicy` | PASSED — change class `runtime-behaviour`, no violations |
| api migration chain | 17 entries; declared `fromSha256` == the computed pre-change dump hash == the previous entry's `toSha256`, and declared `toSha256` == the dump's actual hash |

Acceptance conditions, each with a test that fails if it is violated:

| condition | test |
|---|---|
| explicitly authorized candidates enter the set | `an explicitly authorized candidate enters the authorized set` |
| denied candidates do not | `a candidate whose zone no policy pair allows is denied`, `a candidate the classification rule forbids is denied even on an allowed zone pair`, `a candidate for a classification with no rule is denied on an allowed zone pair` |
| incomplete candidates do not | `a candidate whose identity disagrees with its deployment is denied` |
| ordering does not change the set | `candidate ordering does not change the authorized set` |
| duplicates cannot widen authority | `duplicate candidates do not widen the authorized set`, `repeated evaluation of the same candidate is stable` |
| no candidate is selected | `an empty candidate list yields an empty set rather than a default`, `a denied candidate never appears in the authorized set` |
| nothing widens through absence or through another candidate | `absent policy and rules deny every candidate`, `an authorized candidate cannot authorize a denied one`, `two deployments of one brand are not interchangeable` |
| same-zone is still not implicitly authorized | `same-zone compatibility is not implicitly authorized` |

The new code contains no selection, ranking, fallback, I/O, clock, or randomness
token — verified by stripping comment lines and searching the executable body.
A first search reported matches that were only KDoc prose describing what the
boundary does *not* do.

### Local gate note

`./gradlew verifyPr` — the primary local gate — reports 15 failing tests at this
head, all in `build-logic`, plus `:build-logic:canonicalProbeIntegrationTest`
rc=1. The same test identities fail at the pristine epic base `655e607a`,
reproduced in a clone (these suites need a real `.git` directory, so a worktree
distorts them):

```text
CancellationWiringTest.C1                 daemon contention: "1 busy Daemon could not be reused"
ReleaseVerificationPluginTest             x 10   TestKit fixtures / mavenLocal
TramaiDocsGuardsPluginTest                x 4
CanonicalProbeFunctionalTest              separate task, excluded from :build-logic:test
```

CI is green on all of them, and they fail identically without this slice, so this
is a local environment condition rather than a defect here. It is recorded so the
next run is not re-diagnosed from scratch, and it is deliberately **not** added as
a promotion blocker: CI passing on them means they do not block promotion.

The component gates above are the ones that reflect this slice, and all pass.
