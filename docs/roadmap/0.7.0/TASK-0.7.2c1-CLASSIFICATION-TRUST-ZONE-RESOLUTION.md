# TASK 0.7.2c1 — Classification → Trust-Zone Resolution Boundary

**Epic:** [EPIC-0.7.2-POLICY-TRUST-ZONES.md](EPIC-0.7.2-POLICY-TRUST-ZONES.md)  
**Branch:** `task/0.7.2c1-classification-trust-zone` → `epic/0.7.2-policy-trust-zones`  
**Status:** Implemented

## What this slice establishes

One deterministic, side-effect-free governance boundary:

```text
WorkloadDeploymentIdentity
        │
        ▼
DataClassification      (+ ClassificationSource)
        │
        ▼
ProviderTrustZone
```

A governed workload that already has authoritative identity resolves to exactly
one classification and exactly one trust zone, or is refused with a stable
reason. Refusal is a first-class result, not an exception and not a `null`.

## Contract

`dev.tramai.security.governance` (reuses existing vocabulary; introduces only
what the invariant needs):

- `WorkloadClassificationSignal(classification, source)` — one authoritative
  classification claim about a workload. A workload may carry several at once.
- `WorkloadGovernanceResolver.resolve(identity, signals, deploymentZone, rules)`
  — pure function, no provider/tool/network/filesystem access.
- `WorkloadGovernanceResolution.Resolved(identity, classification, source, trustZone)`
  — the value downstream policy consumes without recomputing either.
- `WorkloadGovernanceResolution.Refused(identity, failure)` — both cases name the
  same `WorkloadDeploymentIdentity`, so a refusal is attributable to exactly one
  governed workload.
- `WorkloadGovernanceFailure` — `NOT_CLASSIFIED`, `NO_RESOLVABLE_TRUST_ZONE`,
  `DEPLOYMENT_ZONE_NOT_PERMITTED`. Enum only: no raw values, no free-form text,
  so a refusal is safe to surface in evidence.

Vocabulary reused as-is, not redefined: `DataClassification`,
`ClassificationSource`, `ProviderTrustZone`, `ClassificationRoutingRule`.

## Invariants

```text
same identity + same signals + same zone + same rules => same resolution
strongest classification claim wins; a weaker claim never downgrades it
equally strong claims keep the LEAST authoritative source
a classification with no rule, or a rule permitting no zone, refuses
a deployment zone outside the permitted set refuses; so does an unknown one
no path falls back to a wider or more permissive trust zone
```

### On "conflicting inputs must fail closed"

The requirement is satisfied structurally rather than by a branch: `rank` is a
total, injective order over `DataClassification`, so two signals can only be
"equally strong" by being the *same* classification. A same-strength
disagreement is therefore not representable, and no conflict branch exists to
be wrong. What *is* representable — and refused — is a conflict between the
resolved classification and the deployment zone, and between the rules and the
deployment zone.

## Boundary of this slice

Deliberately not implemented (later 0.7.2 candidates): provider selection,
provider-input data release or minimization, approval flows, policy DSL
extension, org/environment/workload composition, named organization-defined
zones, persistence, and any rule-engine or taxonomy infrastructure.

No provider is contacted, selected, or exposed by this slice.

## Verification

- `WorkloadGovernanceResolverTest` — 15 tests, 0 failures: determinism across
  repeated and reordered inputs; no silent downgrade; least-authoritative
  provenance; all three refusal paths; no widening from absent configuration;
  no widening from a fallback-permitted zone; positive resolution for LOCAL,
  EU_CLOUD, and GLOBAL_CLOUD.
- `tramai-core` and `tramai-engine` compile clean after the ranking move.

The classification ordering moved from `tramai-engine` (where it was
`internal`) to `tramai-core` because two modules must now agree on it; a second
copy would be a second meaning. Engine behaviour is unchanged.
