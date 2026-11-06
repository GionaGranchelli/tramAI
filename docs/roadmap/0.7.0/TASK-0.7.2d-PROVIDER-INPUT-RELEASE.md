# TASK 0.7.2d — Provider-Input Release Boundary

**Epic:** [EPIC-0.7.2-POLICY-TRUST-ZONES.md](EPIC-0.7.2-POLICY-TRUST-ZONES.md)  
**Branch:** `task/0.7.2d-provider-input-release` → `epic/0.7.2-policy-trust-zones`  
**Status:** Implemented

## What this slice establishes

One question:

```text
Given an ALLOW trust-zone decision, may this specific provider-bound input be released?
```

One answer, and it needs both existing authorities to agree for the same input:

```text
workload zone -> provider deployment zone      TrustZonePolicy          (0.7.2c)
classification -> provider deployment zone     ClassificationRoutingRule.allowedZones
                        |
                        v
                 released only if both agree
```

## Contract

`dev.tramai.security.governance.ProviderInputRelease`:

```kotlin
class ProviderInputRelease(
    trustZonePolicy: TrustZonePolicy = TrustZonePolicy(),
    rules: Map<DataClassification, ClassificationRoutingRule> = emptyMap(),
)

fun releases(
    workloadZone: ProviderTrustZone,
    classification: DataClassification,
    providerZone: ProviderTrustZone,
): Boolean
```

## Invariants

```text
both authorities agree                       -> released
zone pair compatible, no rule for the class  -> withheld
zone pair compatible, rule excludes the zone -> withheld
classification permits the zone, zones deny  -> withheld
unconfigured boundary                        -> withholds everything
```

A compatible trust zone is necessary and never sufficient. **Neither authority can widen the other**: a compatible zone pair cannot release a classification that has no release rule, and a classification that permits a zone cannot release across an incompatible zone. A classification absent from the rules releases nothing, which is the fail-closed default.

## Reuse, and one deliberate omission

The classification side reuses `ClassificationRoutingRule.allowedZones` — the existing rule for which provider trust zones may handle a classification — rather than restating it. No new vocabulary was introduced: `DataClassification`, `ProviderTrustZone`, `ClassificationRoutingRule` and `TrustZonePolicy` are all pre-existing.

`allowedFallbackZones` is deliberately **not** consulted. Fallback is a concession made after a primary provider fails; it is not a release permission for a specific input. The tests pin this: a zone present in `allowedZones` releases even when it is absent from `allowedFallbackZones`.

The existing classification-aware routing check in `DefaultPolicyEngine` is `private fun evaluateProviderRouting` and returns `PolicyDecision.Deny` or `null` meaning *inconclusive, fall back to legacy checks*. It is not a callable release boundary, and it answers a routing question rather than a release question, so this slice does not route through it.

## Boundary of this slice

Not implemented, deliberately: provider selection or invocation; input transformation, minimizing or redaction; per-deployment recomputation; approval flows; policy composition across org/environment/workload sources (0.7.2e); reason or evidence models for a withholding (0.7.2g); missing/unknown/failure semantics beyond the fail-closed default above (0.7.2f).

Deciding only: nothing here transforms an input, contacts a provider, or performs I/O.

## Verification

- `ProviderInputReleaseTest` — 7 tests, 0 failures: both authorities agreeing releases; a compatible zone pair does not release a classification with no rule; a rule excluding the zone withholds despite a compatible zone pair; a permitted classification does not release across incompatible zones; an unconfigured boundary withholds all 36 combinations; the release path consults the zone allow-list and not the fallback allow-list; and across the full zone-pair × classification matrix, release holds exactly when both authorities agree.

Two of those tests failed on first run and both were test defects, not implementation defects: one asserted a withholding for a zone its classification rule actually permitted, and one asserted a release for a zone pair the 0.7.2c policy correctly denied (`LOCAL -> LOCAL` was not listed). The implementation was unchanged by the fixes; the second failure is independent evidence that the same-zone deny-by-default rule holds end to end.
