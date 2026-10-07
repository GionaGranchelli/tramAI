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

