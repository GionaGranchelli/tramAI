# TASK 0.7.2e — Restrictive Effective Policy Composition

**Epic:** [EPIC-0.7.2-POLICY-TRUST-ZONES.md](EPIC-0.7.2-POLICY-TRUST-ZONES.md)  
**Branch:** `task/0.7.2e-effective-policy-composition` → `epic/0.7.2-policy-trust-zones`  
**Status:** Implemented

## What this slice establishes

```text
organization policy
        ∩
environment policy
        ∩
workload policy
        ↓
effective policy
```

One invariant: **a narrower scope may restrict authority, but may never widen it.**

## Contract

`dev.tramai.security.governance.effectiveRoutingRules` — a pure function, not a type:

```kotlin
fun effectiveRoutingRules(
    organization: Map<DataClassification, ClassificationRoutingRule>,
    environment: Map<DataClassification, ClassificationRoutingRule> = emptyMap(),
    workload: Map<DataClassification, ClassificationRoutingRule> = emptyMap(),
): Map<DataClassification, ClassificationRoutingRule>
```

No new types were introduced. The authority being composed is `ClassificationRoutingRule.allowedZones` — the existing rule for which provider trust zones may handle a classification — so composition is set intersection over types that already exist.

## Why the invariant holds structurally

Two independent properties, neither of which depends on a scope behaving well:

```text
effective zones = intersection of the scopes' zones
                  => always a subset of every scope that speaks
                  => no scope can add a zone another scope denied

effective keys  = the organization's classifications only
                  => nothing can be introduced from below
```

A narrower scope that permits a zone the parent denied does not raise an error and does not take effect: the intersection is empty, so that classification is left with no permitted zones and permits nothing. Widening is not forbidden by a check — it is unrepresentable in the result.

The same intersection is applied to `allowedFallbackZones`. When every scope satisfies `allowedFallbackZones ⊆ allowedZones`, the composition does too, because intersection preserves that relation: `∩ fallbacks ⊆ ∩ alloweds`. Composition therefore never introduces a violation.

Composition does **not** validate its inputs, and does not claim to. `ClassificationRoutingRule` is a plain data class with no validation of its own — the subset requirement is enforced where rules are configured, by `ProviderRoutingConfiguration.init`. A rule constructed directly can violate it, and composition preserves that violation rather than repairing it silently.

## Silence and the origin of authority

```text
a scope silent about a classification   -> abstains, imposes no restriction
the organization silent about one       -> that classification is absent from the result
```

Authority originates at the widest scope. A classification the organization never defines cannot appear in the effective policy no matter how many narrower scopes define it, and a classification the organization defines with no permitted zones stays that way.

## Boundary of this slice

This is composition and nothing else. Deliberately not implemented:

- no DSL, no generic policy framework, no configurable rule engine;
- no persistence;
- no routing: `effectiveRoutingRules` is **not** wired into `DefaultPolicyEngine`, and nothing evaluates it implicitly;
- no provider selection;
- no approval logic;
- no reason or evidence model for a restrictive outcome (0.7.2g).

The function is pure: no state, no I/O, and neither input map is modified.

## Verification

- `EffectiveRoutingPolicyTest` — 9 tests, 0 failures, attacking the invariant from both directions:
  - identical scopes leave the policy unchanged;
  - a narrower environment restricts, and a workload scope restricts further;
  - **a narrower scope naming a zone the parent denied collapses to none** — the classification survives with an empty zone set;
  - **a classification the organization never defined is absent from the effective policy** — the result's keys are exactly the organization's keys;
  - a scope silent about a classification imposes no restriction, while an organization silent about all of them authorizes nothing;
  - fallback zones are intersected and stay within the effective allowed zones;
  - a sweep asserting the effective zones and fallback zones stay within **every** scope that defined them.
