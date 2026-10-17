# TASK 0.7.2b — Provider Deployment & Named Trust-Zone Contract

**Epic:** [EPIC-0.7.2-POLICY-TRUST-ZONES.md](EPIC-0.7.2-POLICY-TRUST-ZONES.md)  
**Branch:** `task/0.7.2b-provider-deployment-trust-zone` → `epic/0.7.2-policy-trust-zones`  
**Status:** Implemented

## What this slice establishes

Ownership of trust by concrete provider deployments, using
organization-defined zone names:

```text
ProviderDeployment
        │
        ▼
NamedTrustZone(name, category)      exactly one per deployment
        │
        ▼
ProviderTrustZone                   LOCAL | EU_CLOUD | GLOBAL_CLOUD
```

A provider brand proves nothing about trust. Residency and locality follow from
where a concrete deployment actually runs, so the zone belongs to the deployment,
never to the vendor name.

This is the ownership model only. It states *where a deployment sits*, not
whether that deployment may be used for a given classification — that decision is
policy, and deliberately absent here.

## Contract

`dev.tramai.security.governance`:

- `TrustZoneName(value)` — an organization-defined zone name, e.g.
  `eu-west-sovereign`. Validated: non-blank, trimmed, no control characters.
- `NamedTrustZone(name, category)` — one name bound to exactly one portable
  `ProviderTrustZone` category. Binding both in one value means a zone cannot be
  defined with two categories.
- `ProviderDeployment(deploymentId, providerId, trustZone)` — one concrete
  deployment of one provider, owning exactly one `NamedTrustZone`.
  `deploymentId` and `providerId` are validated the same way.
- `TrustZoneCatalogue(zones)` with `categoryOf(name): ProviderTrustZone?` — the
  zones an organization defines.

Reused as-is, not redefined: `ProviderTrustZone`.

## Invariants

```text
a deployment owns exactly ONE zone            structural: there is one trustZone field
two deployments of the same brand in          they remain different deployments
  different zones are distinct
deployment identity distinguishes them        same brand, same zone, different id => distinct
a zone name resolves to exactly one category  duplicate name with a different category fails
an unknown name resolves to nothing           categoryOf returns null; trust is never widened
names match exactly                           'EU-WEST' does not resolve 'eu-west'
```

"Exactly one" is enforced by the shape of `ProviderDeployment` rather than by a
validation rule, so a deployment cannot carry two zones or none.

## Relationship to 0.7.2c1

0.7.2c1 resolves a governed workload to one `ProviderTrustZone` **category**.
This slice gives provider deployments a category too, via their named zone. That
is the connection point: a later slice can compare the two — resolved workload
category against deployment category — without either side recomputing anything.
Nothing in this slice performs that comparison.

The existing `ProviderRoutingConfiguration.providerZones: Map<String, ProviderTrustZone>`
(and its reader in `DefaultPolicyEngine`) maps a provider id straight to a
portable category, with no deployment identity and no organization-defined names.
This slice adds the missing expressiveness without changing that path.

## Boundary of this slice

Deliberately not implemented: provider selection, eligibility or authorization;
restrictive org/environment/workload policy composition; provider-input data
release or minimization; approval flows; policy DSL work. A deployment having a
zone and TramAI deciding whether that deployment is authorized are separate
concerns, and keeping them separate is what makes the next policy slice tractable.

## Verification

- `ProviderDeploymentTest` — 11 tests, 0 failures: one-zone ownership; same brand
  in different zones stays distinct; deployment identity distinguishes otherwise
  identical deployments; catalogue resolution; unknown name resolves to nothing;
  exact (case-sensitive) matching; conflicting duplicate name rejected; duplicate
  identical name accepted; empty catalogue resolves nothing; blank/untrimmed
  identity rejected; blank and untrimmed zone name rejected.
