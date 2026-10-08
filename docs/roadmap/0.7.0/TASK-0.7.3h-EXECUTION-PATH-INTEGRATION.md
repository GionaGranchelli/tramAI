# TASK 0.7.3h — Real Execution-Path Integration

**Status:** in progress. Epic 0.7.3 closes only when every acceptance criterion below is supported by
the real execution path and its evidence.

## 1. Base

| | |
|---|---|
| Exact base | `f8af25106ca3430cf6a6b63835b5e961279efba2` |
| Branch | `task/0.7.3h-execution-path-integration` |
| Target | `epic/0.7.3-authorized-selection` |
| Working tree at start | clean |

## 2. The bypass being removed

`ProviderExecutionCoordinator.execute` at the base resolves configured routes and immediately drives
execution from that list:

```text
beforeResolution.beforeResolution(...)
routingPlan.resolveCandidates(operation.operation)      <-- configuration treated as authority
for ((index, route) in candidates.withIndex()) {
    circuitBreaker.beforeCall(route.providerName)        <-- admission for a configured route
    attemptExecutor.execute(routeRequest(route, ...))    <-- invocation
    // on qualifying failure: transition(...); continue  <-- next CONFIGURED route
    // on circuit rejected:   transition(...); continue  <-- next CONFIGURED route
}
```

Both `continue` paths advance to `candidates[index + 1]` — the next *configured* route — so fallback
and circuit-open can reach a provider that `AuthorizedCandidates`, `ViableCandidates` and
`CandidateSelection` never permitted. That is the last hard blocker for the epic.

## 3. Authoritative execution inputs — producer map (verified against the base)

Every value the 0.7.3 chain requires, with the real producer and whether it exists at execution time
today. `resolveCandidates` is the only thing that currently reaches the coordinator.

| Input | Producer (verified) | Ownership | Lifetime | Reaches the coordinator? |
|---|---|---|---|---|
| candidate universe (configured routes + order) | `ProviderRoutingPlan.routes` via `resolveCandidates(operation)`; plan held as `components.providers.routingPlan` | `tramai-core` | engine lifetime, immutable snapshot | **YES** |
| provider registration | the same core `ProviderRoutingPlan.providers` — the object `CandidateAuthorization` already consumes (`registration: ProviderRoutingPlan`) | `tramai-core` | engine lifetime | **YES** (same object) |
| provider → trust zone (configured) | `PolicyConfiguration.providerRouting.providerZones`, read by `DefaultPolicyEngine` at the `BEFORE_PROVIDER_INVOCATION` point | `tramai-security` | engine lifetime | **YES** via the policy engine, but not as a value the coordinator holds |
| classification → allowed zones (rules) | `PolicyConfiguration.providerRouting.rules`; composition via `effectiveRoutingRules(organization, environment, workload)` | `tramai-security` | engine lifetime | **YES** via the policy engine |
| required provider capability set | **no producer.** The engine checks `VISION` ad hoc for image messages (`ProviderAttemptExecutor`) and `STREAMING` in the streaming coordinator | `tramai-engine` | per attempt | **NO** as an input |
| runtime availability | `ProviderCircuitBreaker` — `beforeCall` is a *mutating admission* (may grant a permit, may transition an expired OPEN into HALF_OPEN); the open-state query is read-only | `tramai-engine` | engine lifetime, mutable per provider | **YES** |
| selection preference / order | the `resolveCandidates` route order | `tramai-core` | engine lifetime | **YES** |
| workload identity (`WorkloadDeploymentIdentity`) | core identity types; produced on the approval path (`ApprovalRunAttribution`, `SuspendedInvocationStore.runIdentity`) — **not** carried in `ProviderExecutionRequest` | `tramai-core` / `tramai-engine` approvals | per run | **NO** on the provider path |
| workload classification + source | `ExecutionSecurityContext.fromArguments` (strongest `ClassifiedDocument`, least authoritative source on ties) | `tramai-engine` | per invocation | **YES** |
| workload deployment zone (`ProviderTrustZone?`) | **no producer.** `WorkloadGovernanceResolver.resolve` requires it as an argument | — | — | **NO** |
| workload → provider zone pairs (`TrustZonePolicy`) | **no producer.** Only `ProviderInputRelease`'s empty default exists, which allows nothing | `tramai-security` | — | **NO** |
| authoritative `ProviderDeployment` per candidate | **no producer.** `providerZones` carries a zone per provider id but no deployment identity | — | — | **NO** |

**Conclusion.** Four facts genuinely do not reach the coordinator: the workload deployment identity,
the workload deployment zone, the workload→provider zone pairs, and an authoritative deployment
identity per candidate. Three of the twelve already exist and are reachable.

No value above is inferred or defaulted. In particular, per the task: null classification is not
`PUBLIC`, an unknown workload zone is not `LOCAL`/`GLOBAL`, a provider name is not a deployment zone,
a missing deployment is not synthesised, and a missing capability set is not read off the provider
implementation.

## 4. Minimal integration plumbing (design)

The four missing facts are transported by **one** explicit input, built from configuration, reusing
the existing vocabulary — no new provider registry, no second candidate type, no second trust-zone
vocabulary:

```text
ProviderGovernedExecutionInputs
    workloadIdentity      : WorkloadDeploymentIdentity?        core identity type
    workloadZone          : ProviderTrustZone?                 existing zone vocabulary
    trustZonePolicy       : TrustZonePolicy                    existing security type
    deploymentOf          : (providerId) -> ProviderDeployment? existing security type, keyed by provider id
    requiredCapabilities  : Set<ProviderCapability>            core capability vocabulary
```

A missing or `null` member fails closed: no candidate is authorized, so nothing is viable, so nothing
is selected, so nothing is invoked. There is no permissive default and no legacy bypass mode.

The registered provider id is treated as naming **one registered provider deployment** — the same
granularity as `ProviderRoutingPlan.providers: Map<ProviderId, ModelProvider>` — so two deployments of
one brand are two registrations with distinct deployment ids and zones, and they cannot collapse.

`providerZones` and `rules` continue to be owned by the policy engine; the coordinator consumes the
governance decision, not the matrix itself, so the legacy `BEFORE_PROVIDER_INVOCATION` matrix check
remains as a downstream, narrowing-only defence and its reasoning is not duplicated.

## 5. Target sequence

```text
ProviderRoutingPlan.resolveCandidates()          configuration universe / ordering only
          │
          ▼  exact ProviderCandidate mapping (fail closed if not 1:1)
CandidateAuthorization                            authorized = policy ∩ classification ∩ trust ∩ registration ∩ capability
          │
          ▼
AuthorizedCandidates
          │
          ▼
CandidateViability                                viable = authorized ∩ runtime constraints (read-only availability)
          │
          ▼
ViableCandidates
          │
          ▼
CandidateSelection                                strategy(viable), ordered by configured preference
          │
          ├── NoSelection ──► deterministic fail-closed outcome
          ▼
Selected(candidate) ──► exact ResolvedProviderRoute ──► circuit-breaker admission ──► ProviderAttemptExecutor
```

Retry narrows: `remaining = viable.without(selected)` then `CandidateSelection` again — never
`configuredRoutes[index + 1]`.

## 6. Findings and decisions

- The circuit breaker's `beforeCall` is a mutating admission, so viability uses the read-only
  `openUntilMillis` observation. Actual admission stays downstream of selection. A state change
  between viability and admission narrows authority (`without(candidate)`, reselect) and can never
  widen it.
- `AuthorizedCandidates` has **no public members** and an internal constructor (confirmed against
  `tramai-security/api/tramai-security.api`), while `ViableCandidates` publishes
  `contains`/`isEmpty`/`orderedBy`/`without`. The coordinator therefore cannot inspect or fabricate an
  authorization envelope and reads authorization facts through the public `authorizedSet` view. That
  is a useful property, not an obstacle.
- Route-resolution errors keep reporting first and unchanged: governance is consulted after
  `resolveCandidates`, so a missing or unknown route is still a `ConfigurationException`.
- The strategy-exception question carried from 0.7.3g is resolved as **propagation**: a
  strategy exception aborts execution, triggers no fallback and invokes no provider.

## 7. Where this is blocked — missing authoritative facts

The governed path is implemented and **fails closed**: with no governance inputs, `execute` refuses
before any admission or invocation. That is the correct behaviour and it exposes the blocker rather
than hiding it. Six of the seven existing `ProviderExecutionCoordinatorTest` cases now fail because
they assert that a provider is invoked with only a configured route — precisely the assertion §18
anticipates must change — and the seventh (`no route throws configuration exception unchanged from
resolution`) passes.

The blocker is not the coordinator. It is that **three authoritative facts the chain requires have no
producer anywhere in the repository**, so there is nothing to transport:

| Missing fact | Required by | Verified state |
|---|---|---|
| the **workload's deployment zone** (`ProviderTrustZone?`) | `WorkloadGovernanceResolver.resolve(deploymentZone = …)` | No `ProviderTrustZone` occurs anywhere in `tramai-orchestration/src/main` or `tramai-server/src/main`. The engine has only `ExecutionSecurityContext` (classification + source). |
| the **workload → provider zone relation** (`TrustZonePolicy`) | `CandidateAuthorization` → release predicate | Only `ProviderInputRelease`'s empty default exists, which allows nothing. No configuration surface carries the permitted pairs. |
| the **authoritative `ProviderDeployment` per candidate** (deployment id + zone) | `ProviderCandidate.deployment` | No production code constructs a `ProviderDeployment`. `PolicyConfiguration.providerRouting.providerZones` carries a zone per provider id, but no deployment identity — so it cannot express two deployments of one brand (§16.15), which is the case the epic requires. |

The workload **identity** is different: `GovernedRunIdentity` (carrying `WorkloadDeploymentIdentity`)
*is* produced and threaded in `tramai-orchestration` (`GovernedRun`, `WorkflowRunner`,
`WorkflowPersistenceSession`) and `tramai-server` (`WorkflowController`), but only for approvals. The
engine's provider request never receives it. That one is genuine transport plumbing.

**Why this is reported rather than worked around.** The rules for this slice forbid every shortcut
available here: null classification is not `PUBLIC`, an unknown workload zone is not `LOCAL`/`GLOBAL`,
a provider name is not a deployment zone, a missing deployment is not synthesised, and a missing
capability set is not read off the provider. Wiring the zone from the provider's own name, or minting a
deployment id from the provider id, would make the authorization outcome depend on a fabricated fact —
a governed-looking path whose authority comes from an inference. That is worse than the bypass being
removed, because the bypass is at least visible.

**Options, and the decision needed.**

1. **Extend the existing provider-routing configuration** with the missing facts (a deployment
   identity per registered provider, the workload->provider permitted zone pairs) and thread the
   existing `GovernedRunIdentity` from orchestration/server into the engine's provider request. This
   reuses `ProviderRoutingConfiguration` and `ProviderDeployment`, keeps the sovereign profile's
   "every allowed provider has an explicit zone" invariant, and is the smallest change that produces a
   real authority chain. It does add public configuration surface, so it needs the API-migration
   artifact.
2. **Supply the facts only from tests/fixtures for now**, proving the integration path without wiring
   production. This closes the structural proof (§9-§13 of the task) but **cannot** close the epic,
   because the real path would still have no authoritative zone or deployment and would fail closed.
3. **Reconsider the model**: if a workload deployment zone and a provider-deployment identity are not
   intended to be configurable in 0.7.3, then the chain cannot be wired through the real execution path
   yet and the epic's closure gate cannot be met in this slice.

Option 1 is the one that satisfies §22. It requires a decision on where those two configuration facts
live, because that is a public-surface choice rather than an implementation detail.

## 8. What is proven today (fixtures supply the facts, production wiring pending)

`ProviderGovernedExecutionPathTest` — **18 tests, 0 failures** — drives the real
`ProviderExecutionCoordinator` end to end and asserts on provider invocation counts:

happy path invoked exactly once · unauthorized primary invoked zero times · authorized-but-unavailable
primary invoked zero times · nothing authorized fails closed with no invocation · nothing viable fails
closed with no invocation · fallback narrows and reselects · a configured next route outside the
envelope is never invoked · a preference for a forbidden candidate injects nothing · an unavailable
candidate is skipped and the next comes from selection · a selected candidate that loses admission
narrows and reselects · same-route retry selects nothing else · retry exhaustion narrows then falls
back · an escape attempt invokes nothing and reports `STRATEGY_OUTSIDE_VIABLE_SET` · a throwing
preference aborts with no fallback and no invocation · two deployments of one brand do not collapse ·
a route with no authoritative deployment never executes · a provider missing a required capability
never executes · execution with no governance inputs invokes nothing.

One production defect was found by this suite and fixed: the coordinator originally built
`CandidateAuthorization` with the default `ProviderInputRelease()`, whose empty rule map releases
nothing, so authorization refused every candidate — 13 of 18 cases failed with
`No provider candidate is authorized`. The release predicate is now built from the governed
configuration (`ProviderInputRelease(configuration.trustZonePolicy, configuration.rules)`). Observed
failure count went 13 → 2 → 0.

## 9. h2a/h2b landed (verified)

- `DefaultPolicyEngine` exposes the routing configuration it was built with; `SecurityComponents`
  carries it; `EngineInvocationCoordinator` projects it with `ProviderGovernanceConfiguration.from`.
  A policy engine carrying no topology yields none, so execution fails closed.
- `ToolLoopCoordinator` threads `GovernedRunScope.resolve(currentCoroutineContext())`, the identity the
  approval path already uses, so the workload deployment's zone is looked up rather than handed in.
- Required capabilities are derived from the facts `ModelRequest` is built from: `VISION` iff
  `messages.any { it.hasImage() }`, `TOOL_CALLING` iff `operation.toolDefinitions` is non-empty, both
  resolved **before** authorization. `requiredCapabilities` is deleted from the engine's governed
  configuration so no second surface can disagree with the request. `STRUCTURED_OUTPUT` is not inferred.
- Verified: `ProviderExecutionCoordinatorTest` 7 tests / 0 failures; `ProviderGovernedExecutionPathTest`
  18 tests / 0 failures (the six former RED cases are green through real authority).

## 10. h2c — streaming entry into the same boundary (design pinned, not yet implemented)

`StreamingExecutionCoordinator` (522 lines) is the last production path that can reach a provider
without `CandidateSelection`. Confirmed bypass, with exact anchors:

| line | current |
|---|---|
| 120 | `for ((routeIndex, route) in candidates.withIndex())` — raw configured order |
| 123 | `nextRoute = candidates.getOrNull(routeIndex + 1)` — the next *configured* route |
| 127-130 | circuit `Rejected` ⇒ `lastCircuitOpen` + `continue` to that next configured route |
| 195 | startup-retry event condition also reads `candidates.getOrNull(routeIndex + 1)` |
| 210-227 | pre-token `Stop` ⇒ `enforceStreamingFallbackAfterFailure(nextRoute = candidates.getOrNull(routeIndex + 1))`, then `break` — the outer loop advances to the next configured route |
| 346-358 | `handleCircuitBreakerOpenRoute` performs admission **and** the transition with that `nextRoute` |
| 249 | loop exhaustion ⇒ `noAvailableStreamingRouteChunk(operation, lastFailure, lastCircuitOpen)` |

Target shape, reusing the sync projection and semantics — no second streaming authorization model:

```
configured routes → exact ProviderCandidate mapping → CandidateAuthorization(derived ∪ {STREAMING})
  → AuthorizedCandidates → CandidateViability → ViableCandidates → CandidateSelection
  → Selected(candidate) → beforeCall → stream
```

- **Derivation is shared, not copied:** `VISION` if the effective streaming messages carry images,
  `TOOL_CALLING` if the streaming request exposes tools, plus `STREAMING` unconditionally. The same
  helper the sync path uses must serve here so the two paths cannot drift.
- **Narrow and reselect:** compute `narrowed = remaining.without(candidate)` and the next
  governance-selected route from `narrowed` *before* admission, so `handleCircuitBreakerOpenRoute`
  receives a governance-selected `nextRoute` or `null`. On `Rejected` set `remaining = narrowed` and
  continue; on pre-token `Stop` set `remaining = narrowed` and continue where it currently `break`s.
  An empty envelope yields `NoSelection` and the existing `noAvailableStreamingRouteChunk` terminal.
- **`routeIndex` stays the configured position** of the selected route, so route observation and
  attempt numbering are unchanged.
- **Untouched:** same-route retry budget (148-149), the shared-permit boundary (141-144), the
  `STREAMING_STARTUP_RETRY` emission conditions (194-197), and post-token behavior — the
  `emittedAnyTokens` gate already removes cross-provider fallback authority once output has started.
- **Wiring:** the coordinator is constructed in `InvocationExecutionCoordinator`, which already holds
  `providerGovernance`; the governed run comes from `GovernedRunScope.resolve(currentCoroutineContext())`.

Required proofs before closure: streaming fallback cannot escape viable authority; streaming circuit
rejection cannot advance to a next configured route; a provider without `STREAMING` is never selected
or invoked; invocation counts asserted, not inferred.

## 11. Unclassified, carried

An engine-wide `:tramai-engine:test` run hung after ~45 minutes having completed 13 classes, with a
failure cluster in approval/identity classes (`ApprovalResumeEngineTest` 36, `ApprovalSuspensionEngineTest`
14, `ApprovalEngineEdgeCaseTest` 11, `GovernedRunScopeIdentityTest` 8, `GovernedApprovalAttributionTest` 7,
`EngineIdentityDiscriminatorTest` 6, `EngineMemoryIntegrationTest` 6, `EngineCancellationContractTest` 3).

**Attributed: this is a regression introduced by the governed boundary, not environmental.**

Signature at the head (`3c2d26ea`): `dev.tramai.core.exception.ProviderException at
ProviderExecutionCoordinator.kt:263`, which is `governanceAbsent()` —
`"Provider execution requires governance inputs and none were supplied"`. The affected tests construct
provider execution without any routing topology (`EngineMemoryIntegrationTest` contains no
`ProviderRoutingConfiguration` reference at all), so the governed boundary is entered and refuses.

Reproduction, same test identities, both sides:

- head `3c2d26ea`: `./gradlew :tramai-engine:test --tests '*EngineMemoryIntegrationTest*' --tests
  '*GovernedRunScopeIdentityTest*'` — fails, `ProviderException at ProviderExecutionCoordinator.kt:263`.
  Full `./gradlew verifyPr -PchangePolicyBase=f8af2510` — 100 FAILED lines, plus
  `:tramai-observability:test` 5 of 27 failed.
- base `f8af2510` (detached worktree, identical filters): `BUILD SUCCESSFUL`, rc=0, **0 failed**.

The base passes and the head fails at the same test identity, so the cluster is a consequence of this
change. File ownership played no part in the classification: the same failure could not be shown at the
base, which is exactly the test the earlier note said had not been run.

**The unresolved question is a contract question, not a diagnosis.** The 0.7.3h contract says "no
topology -> no governed provider candidate -> fail closed" and "do not resurrect the raw routing
bypass". Applied unconditionally that breaks engine behaviour which predates the epic (chat-memory
persistence, approval resume, governed-run identity, blocking-proxy dispatch, observability). Either
(a) the boundary must be conditional — a caller that never opted into routing topology keeps the legacy
path, and fail-closed governs a *governed* decision rather than making the engine unusable without
topology — in which case this is a production defect in the wiring; or (b) fail-closed is deliberate
and engine-wide, in which case every existing path and its tests must supply topology, a breaking
migration whose scope the contract does not authorise. The contract does not decide between them, and
the 45-minute engine-wide hang is very likely the same cause seen as non-termination rather than as
failure.

## 12. Option (a): governance is mandatory when governed topology exists

**Ruling.** 0.7.3 governance is mandatory when a governed routing topology/configuration exists for
the execution. It does not retroactively make governance topology mandatory for every legacy engine
execution path.

**Shape, and what is forbidden.** Not exception recovery:

```
no governed topology  ->  the pre-0.7.3h execution path (unchanged)
governed topology     ->  authorization -> viability -> selection -> governed retry/fallback
                          missing/incomplete inputs: FAIL CLOSED
                          never fall back to legacy routing from this branch
```

Entering the governed path and then recovering from `governanceAbsent()` into legacy routing would be
a governance downgrade and is explicitly not the implementation.

**Discriminator.** Authoritative configuration state, not the omission of a nullable request field.
`governance` is derived at the single construction site
(`InvocationExecutionCoordinator`: `components.security.routingConfiguration?.let {
ProviderGovernanceConfiguration.from(it) }`), so `governance == null` means no governed routing
topology is configured for this engine. The branch is taken **before** governed selection.

**Sync — landed and verified** (`ccf5771a`). `ProviderExecutionCoordinator.execute` returns
`executeLegacy(request, resolvedRoutes)` when `governance` is null; the legacy body is restored
verbatim from the pre-0.7.3h implementation (`git show f8af2510:...ProviderExecutionCoordinator.kt`),
not approximated. The previously regressed identities now pass at the head:

- `:tramai-engine:test --tests '*EngineMemoryIntegrationTest*' --tests '*GovernedRunScopeIdentityTest*'`
  — rc=0, 0 failed (these were green at `f8af2510` and red at `3c2d26ea` at the same identities).

**Sync focused suites after the branch**: 90 tests, 0 failures
(`ProviderExecutionCoordinatorTest` 7, `ProviderGovernedExecutionPathTest` 25,
`ProviderRetryFallbackLifecyclePropertyTest` 4, `StreamingExecutionCoordinatorTest` 54) — the branch
did not regress the proven governed semantics.

**Streaming — not yet branched.** `StreamingExecutionCoordinator` still enters the governed envelope
unconditionally (`governance` is already a `ProviderGovernanceConfiguration? = null` constructor
parameter, so the discriminator is available). Its legacy helpers are still present in the file (13
references to `executeStreamingRoute`, `collectStreamingRoute`, `handleCircuitBreakerOpenRoute`,
`enforceStreamingFallbackAfterFailure`, `noAvailableStreamingRouteChunk`), so the legacy body is
spliced inline from `git show f8af2510:...StreamingExecutionCoordinator.kt` into the existing
`lifecycleScope.launch { try { ... } }` scope, alongside the governed block rather than extracted
into a function: sharing that scope keeps `chunks`, the emission state, the breaker permit, retry and
cancellation semantics in scope instead of threading them through new parameters.

**Also outstanding**: the boundary discriminator proofs (legacy succeeds / governed+complete /
governed+missing input fails closed with invocation 0 / governed+ineligible cannot escape), and the
exact-final-head mutation campaign M1–M9 including the new downgrade mutant M9 (governed topology
present but execution routed through the legacy branch — killed by proving a governed execution
cannot opt out of authority). The earlier 8/8 result was obtained against the pre-branch production
shape and is historical evidence only.


## 13. h2c landed, continuation policy enforced, mutation campaign closed

§10 above ("design pinned, not yet implemented") is superseded: the streaming coordinator now enters
the same `GovernedProviderEnvelope` boundary as the synchronous one, and both share one
`deriveRequiredCapabilities`. The tests converted for that change and the new proofs are committed.

**Root cause of the defect this sub-slice removed: viability pre-filter changed branch reachability.**
Snapshot-open circuit-breaker candidates were excluded from viability *before* `beforeCall`, so the
`Rejected -> transition(CIRCUIT_BREAKER_OPEN)` branch still existed in the source but became
unreachable: the fallback/continuation policy was never consulted and the next candidate executed
anyway. Branch presence is not reachability. Both coordinators were affected; the streaming suite's
green did not cover the synchronous path and vice versa.

Two further defects were found by state-transition evidence rather than by reading:

- **Resurrection.** Narrowing subtracted from the envelope *snapshot* (`narrowedAfter(chosen)`), so
  removing one candidate restored an earlier one — attempt traces showed a revisited route. Correct
  narrowing is `remaining.without(chosen)` against the *current* remainder.
- **Backwards transition.** Consulting the gate for *any* availability-excluded candidate produced a
  transition `(1, 0)`: an excluded candidate positioned *after* the one about to run was treated as
  gating it. Only exclusions execution actually advances past may gate a continuation.

**Continuation semantics, both mechanisms.** An availability exclusion that execution advances past
consults the existing fallback/continuation policy exactly once: `DENY` is terminal with
`FallbackDenied(CIRCUIT_OPEN_ONLY)` and the next candidate invocation count is 0; `ALLOW` continues
strictly inside the already-current viable envelope. The gate permits continuation only — it cannot
make an excluded candidate viable, authorize another candidate, restore a removed candidate, widen
the viable envelope, or fall back to raw configured-route iteration. With no continuation candidate
the existing all-circuit-open terminal semantics are preserved: no transition is manufactured to
call the gate. An empty authorized set remains an authorization outcome and is never relabelled as
runtime unavailability.

**Mutation campaign (all eight valid; none invalid or compile-rejected counted as a kill):**

| Mutant | Verdict | Killing test / reason |
| --- | --- | --- |
| M1 drop derived VISION | KILLED | `an incapable configured provider is not authorized so the capable candidate executes` |
| M2 drop derived TOOL_CALLING | KILLED | `tool definitions in the actual request require TOOL_CALLING at authorization` |
| M3 drop derived STREAMING | KILLED | `non streaming provider is refused at authorization not at invocation` (+2) |
| M4 sync executes `resolvedRoutes.first()` | KILLED | `fallback route events carry is_fallback true and shared attempt numbering continues` (+11) |
| M5 streaming executes `configuredOrder.first()` | KILLED | property suite over the deterministic corpus (+15) |
| M6 rejection re-widens `remaining` | KILLED | non-termination: re-widening makes reselection non-terminating (suite does not finish in 600s) |
| M7 fallback re-widens `remaining` | KILLED | non-termination, as M6 |
| M8 strategy ignores the eligible set | KILLED | property suite over the deterministic corpus (+15) |

`M1` and `M2` survived the first pass and both survivors were real evidence gaps, not measurement
artefacts: the existing image test asserted only "nobody was invoked", which the lower-level
*defensive* capability check keeps true even when authorization no longer requires the capability —
so it could not distinguish **where** the refusal happened; and no test drove an operation exposing
tool definitions through the governed path at all. Both proofs assert **which** provider executed.
Each was verified green on the pristine head before the mutant was re-run, so the kills are
attributable to the proof and not to a pre-existing failure.

M6/M7 are killed by non-termination rather than by an assertion. That is a detection, and it is also
a property worth recording: monotonic narrowing is load-bearing for termination, not only for
authority.

**Orders of magnitude.** None of the mutants is a statement about counts or thresholds; every mutant
either removes a derived requirement, substitutes a routing referent, or re-widens a narrowed set.

**Suites at the closure head.** 90 focused tests, 0 failures: `ProviderExecutionCoordinatorTest`
7, `ProviderGovernedExecutionPathTest` 25, `ProviderRetryFallbackLifecyclePropertyTest` 4,
`StreamingExecutionCoordinatorTest` 54. Narrowing/reselection mutants were exercised by plans with
three selection steps; a two-candidate plan cannot expose a revisited route.

**Still required before 0.7.3 can close:** the exact-head repository gates (the focused suites are
not the closure ladder), and the §11 resolution — now **attributed**: the approval/identity/memory
cluster and the engine-wide hang are a regression from the governed boundary being entered without
routing topology, reproduced as base-green/head-red at the same test identity. §11 records the
evidence and the contract question it raises.

## 14. Closure evidence for the conditional boundary

**Implemented on both surfaces.** `ProviderExecutionCoordinator` returns
`executeLegacy(request, resolvedRoutes)` when `governance` is null;
`StreamingExecutionCoordinator` dispatches `if (governance == null) executeLegacy(request) else
executeGoverned(request)`. Both legacy bodies are the pre-0.7.3h code restored verbatim from
`f8af2510` — route walking, retry, fallback, breaker permit, cancellation and emission semantics are
the original code, not a reconstruction. Neither branch can recover a governed refusal into legacy
routing, and the branch is taken before governed selection.

**Boundary discriminator proofs**, on each surface independently:

- no governed topology → the pre-existing path executes and walks configured order after a retryable
failure (asserted by per-provider invocation counts, not by the terminal result);
- topology present, required fact absent → fail closed, provider invocation count 0, no legacy
fallback;
- candidate absent from the topology → executes without one, does not execute with one and does not
reach the legacy path. This pair is also the M9 killing proof.

**Pre-mutation ladder**: engine suites (`ProviderExecutionCoordinatorTest` 7,
`ProviderGovernedExecutionPathTest` 28, `StreamingExecutionCoordinatorTest` 57,
`ProviderRetryFallbackLifecyclePropertyTest` 4, `EngineMemoryIntegrationTest` 6,
`GovernedRunScopeIdentityTest` 9) plus `:tramai-security:test` (41 classes) — rc=0, 0 failed. The two
classes that were base-green/head-red at `3c2d26ea` are green through the legacy branch.

**Mutation campaign on the final production shape `cf5931cd`** — M1–M9 with M9 split across both
surfaces, rerun after the regression fix below changed the shape.
M1 KILLED by the VISION proof; M2 by the TOOL_CALLING proof; M3 by the streaming-capability proof;
M4 by the fallback/attempt-numbering proof; M5 by the property suite; M6 and M7 by non-termination
(re-widening makes reselection non-terminating); M8 by the property suite; M9a by *"a configured next
route outside the envelope is never invoked"* (+13); M9b by the streaming capability proof (+4).
SURVIVED 0, NO_COVERAGE 0, UNDETERMINED 0, and no invalid or compile-rejected entry counted as a kill.
Each verdict names a distinct killing test, which is what the attribution fix was for.

An earlier run of the same campaign at `566d0158` produced identical verdicts, but that head is
historical: the shape changed afterwards (see §15), and mutation evidence is content-addressed.

**Two harness defects were found and fixed during this campaign; both had produced a verdict more
favourable than the evidence.**

1. The failure reader scanned every result XML in the directory, so failures from an earlier mutant's
 run were attributed to a later mutant — every verdict named the same killing test. Attribution is
 now restricted to XMLs written after that run began.
2. Compile errors reach the harness on stderr as well as stdout, and a mutant that fails to compile
 never runs the test task. That produced `SURVIVED` for a mutant that had never compiled. The
 non-zero-rc / no-fresh-results case is now `UNDETERMINED`, and the first M9a substitution (which
 left `configuration` nullable and could not compile) is recorded as compile-rejected, not counted,
 and replaced by a compile-valid mutation that forces the legacy branch.

**Still required before 0.7.3 can close:** the exact-head repository gates (`verifyPr`) and the
reconciliation of the 0.7.3 epic acceptance criteria against this evidence.

## 15. The four example modules: a regression this Epic introduced and then fixed

The conditional boundary broke four example modules — `approval-resume`,
`sovereign-offline-verification`, `sovereign-document-intelligence`, `spring-sovereign-starter` — with
`ProviderException: Provider execution requires governance inputs and none were supplied`. Attribution
is by base/head discriminator, not by appearance: at `f8af2510` those modules pass (fresh result XMLs,
7 and 10 tests, 0 failures); at the head before the fix they fail at the same identities. They were not
environmental. The focused suites and the M1–M9 campaign were all green while these were red, because
every fixture supplies a governed run and the examples are the only code exercising a routing topology
without one.

Two causes, both at a single choke point:

1. `ProviderGovernanceConfiguration.from` projected a **defaulted** routing configuration
   (`enabled = false`, empty zones/deployments/pairs) into a non-null governance configuration, so any
   engine that configures policy at all looked governed. `from` now returns null for a configuration
   that carries no topology. `rules` deliberately does not participate in that predicate: it defaults
   to a non-empty sovereign matrix, so consulting it would make every configuration look governed.
2. The governed topology is **keyed by `WorkloadDeploymentIdentity`**, so it is addressable only for
   an execution that carries an admitted run identity. Both coordinators now branch on authoritative
   state — topology *and* admitted identity — with the identity resolved from the run scope, never from
   a caller-supplied request field, so a governed execution cannot opt out of governance by omitting
   one. Missing facts inside the governed branch still fail closed.

**Decision requiring owner ratification.** The second point makes "a governed topology exists for the
execution" mean "a topology exists *and* is addressable for this execution's admitted identity". The
alternative reading — any configured topology governs every execution, identity or not — fails the four
example modules closed, and satisfying it would require registering workload identities for them, which
is a control-plane change rather than an engine one. Under the chosen reading a governed run is never
softer: it always carries an identity and therefore always takes the governed path.

**Proof consequences.** The three "missing required fact" proofs previously used the absent run
identity as their missing fact; that is now the discriminator, not an input. They were re-expressed to
supply an admitted identity and remove a fact *inside* governance (no workload/deployment mapping), so
they assert the same refusal with invocation count 0 while exercising the envelope's fact checking.
One test identity changed with that re-expression
(`execution without governance inputs invokes no provider` → `a governed execution whose required
governance mapping is absent invokes no provider`).

**Evidence after the fix at `cf5931cd`:** example modules base-equal and green; `:tramai-engine:test`
111 tests and `:tramai-security:test` 1043 tests, 0 failures; M1–M9 rerun 10/10 KILLED, tree clean.


