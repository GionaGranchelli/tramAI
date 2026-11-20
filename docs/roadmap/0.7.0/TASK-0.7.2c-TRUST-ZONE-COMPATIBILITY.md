# TASK 0.7.2c — Restrictive Trust-Zone Compatibility

**Epic:** [EPIC-0.7.2-POLICY-TRUST-ZONES.md](EPIC-0.7.2-POLICY-TRUST-ZONES.md)  
**Branch:** `task/0.7.2c-trust-zone-compatibility` → `epic/0.7.2-policy-trust-zones`  
**Status:** Implemented

## What this slice establishes

Exactly one question:

```text
TrustZonePolicy.allows(workloadZone, providerZone): Boolean

listed pair      -> true
everything else  -> false
```

Both sides are the portable `ProviderTrustZone` category: 0.7.2c1 already resolves
a governed workload to one, and 0.7.2b gives a provider deployment one through its
named zone. This slice is the comparison the previous two were built for, and
nothing more.

## Contract

`dev.tramai.security.governance.TrustZonePolicy`:

```kotlin
class TrustZonePolicy(allowedPairs: Set<Pair<ProviderTrustZone, ProviderTrustZone>> = emptySet())
fun allows(workloadZone: ProviderTrustZone, providerZone: ProviderTrustZone): Boolean
```

An explicit allow-list of **ordered** pairs.

## Invariants

```text
listed pair                       -> allowed
pair listed in the opposite order -> denied
same zone on both sides           -> denied unless that pair is listed
policy with no pairs              -> denies every pair
caller mutating the set it passed -> cannot widen the policy afterwards
```

Denial is total and is the default. Permission has to be stated to exist, which is
why the constructor's default is the empty allow-list: an unconfigured policy
denies everything rather than allowing anything, and no omission can be read as
permission.

The allow-list is copied at construction so a policy cannot be widened after the
fact by whoever supplied the set — a rule that can change under a caller is not a
rule.

Only the portable category is compared. Organization-defined names describe one
organization's topology; the category is what two sides can agree on.

## Boundary of this slice

Deliberately not implemented: provider selection, eligibility or authorization;
classification-aware policy composition; provider-input data release or
minimization; approval flows; policy DSL work; any reason or evidence model for a
denial (that is 0.7.2g, and a boolean is all this question needs).

Failing closed is expressed as a value, not an exception: an incompatible pair
returns `false`, and the caller decides what to do about it. Nothing here throws,
persists, or touches a provider.

## Verification

- `TrustZonePolicyTest` — 8 tests, 0 failures: an allowed pair passes; the reversed
  pair is denied; unlisted pairs are denied; same-zone is denied unless listed and
  allowed when listed; an empty policy denies all nine zone pairs; a policy with two
  listed pairs allows exactly those over the full matrix; and a policy is not
  widened by mutating the caller's set after construction.
