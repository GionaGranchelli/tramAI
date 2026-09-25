# TASK-0.7.1i — Integration Closure / Master Promotion Authority

**Status:** ⛔ Recorded as a mandatory 0.7.1 closure gate (blocking)
**Owner:** maintainer
**Depends on:** 0.7.1 content freeze (0.7.1a–0.7.1h plus the mutation-population work merged into the Epic, and no further Epic commits planned)
**Class:** docs-only slice to record the requirement. The remediation work it describes is its own slice, later.

## Why this exists

Every 0.7.1 task so far is verified as an **Epic-targeted PR**: each one is small, separately reviewed, and green against its own base. The Epic→master promotion (PR #440) is judged by a different set of verifiers, which evaluate the **cumulative** transition. Those verifiers are currently red for three reasons, and none of them is a flake or a consequence of any individual PR.

Observed on #440 at head `66cb3c13e9464bb31becb6726f9fe9fa33a8a24f`:

| Verifier | Result |
|---|---|
| CI | passed |
| Architecture Authority | **failed** |
| Maintainability / change policy | **failed** on `analyzer-runtime-separation` |
| release-sovereign lane | **failed** — population pin `expected 179, discovered 181` |

The consequence is the reason this record exists: **nothing else in the repository prevents us from finishing the mutation work and declaring 0.7.1 complete while #440 remains structurally impossible to merge.** Epic-level green does not imply promotion-level green, and no 0.7.1 task owns the difference.

## Gate 1 — API migration closure

The API migration ledger (`config/quality/api-migrations.yml`) records, per module, the `fromSha256 → toSha256` pair a consumer must be told about. On the Epic it accumulates **intermediate hops** because each task appends its own entry as it lands.

Independently re-derived for this record at `305f1f8e`:

- 12 entries across 10 modules;
- two modules carry **chains**: `:tramai-control-plane` and `:tramai-orchestration`;
- `config/quality/api-migrations.yml:103-108` — `:tramai-control-plane`, `e3b0c44298… → 0c656c8654…` (rationale: 0.7.1e control-plane authority contract);
- `config/quality/api-migrations.yml:110-115` — `:tramai-control-plane`, `0c656c8654… → 71acccdcab…` (rationale: 0.7.1f safe exposure model);
- one entry is anchored on `e3b0c44298…`, which is the SHA-256 of the empty string — a module with no `master` API dump at all.

The promotion transition is `master → final-0.7.1`, so the ledger must carry that pair, not a path through intermediate Epic states.

**Required result:** once the Epic is frozen, bind the migration evidence to the actual `master → final-0.7.1` API hashes for each affected module — `:tramai-control-plane`, `:tramai-engine`, `:tramai-persistence-file`, `:tramai-persistence-jdbc`, `:tramai-spring-boot-starter-sovereign-ops`, `:tramai-spring-boot-starter-sovereign-persistence-jdbc` and any others the verifier names — and prove it by running the architecture gate at the frozen head. Rebinding before the freeze is premature churn: every subsequent Epic commit invalidates the binding.

## Gate 2 — Final population certification

The `release-sovereign` lane in `.github/workflows/maintainability-baseline.yml` pins `expected: 179` for its six filter sets. Measured for this record with those six filters (local, JDK 21):

| Tree | tests | failing locally |
|---|---|---|
| `origin/master` `41ac9151` | **179** — pin-accurate | 47 |
| Epic `305f1f8e` | **181** | 14 |

The delta is fully accounted for, and it is **not** general drift: `TramaiDocsGuardsPluginTest` is the only class whose count differs (17 → 19), and the two additions are version-alignment cases:

- `verifyVersionAlignment fails when the promoted release is no longer named current`
- `verifyVersionAlignment fails when the promoted release date drifts`

Every other class in the lane is count-identical between `master` and the Epic.

Two consequences for closing this gate:

1. **The pin change is a 179 → 181 edit backed by that accounting** — two named tests, one class, no unexplained residue.
2. **A local run cannot certify it.** The same filters fail locally on *both* trees — 14 on the Epic and 47 on `master`, sharing the same ten `ReleaseVerificationPluginTest` TestKit fixture failures — so those failures are environmental rather than Epic drift. And because `maintainability-baseline.yml` triggers only for master-targeted PRs, **this lane has never been exercised on the Epic line in CI at all**. The corrected pin must therefore be verified in the lane's real environment at the frozen head before it counts as certified.

**Required result:** bind the 179 → 181 correction to the two named tests, and show the lane green with the new pin, in CI, at the frozen head.

## Gate 3 — Master-promotion separation

`analyzer-runtime-separation` fails because the cumulative Epic→master diff contains **both** `build-logic`/analyzer changes **and** runtime production changes. This is by design, not a defect in the gate: the rule exists so that an analyzer can never change in the same governed transition as the runtime it judges. An Epic roll-up breaks exactly that property, even though every constituent change was separately reviewed — the final master transition carries no encoded proof of the separation.

Note what does **not** fix it: `baseline-migration`. That change class permits analyzer and baseline changes together; it still forbids runtime production changes. So the roll-up fails under it too, and reaching for the label is the wrong instinct.

**Required result:** a promotion sequence in which analyzer/build-logic changes and runtime production changes do not appear in the same governed transition. The expected shape is ordered transitions — land the independently valid tooling/analyzer transition first, then reconstruct the runtime promotion so that its master diff no longer contains analyzer changes.

## Non-goal (binding)

> **Do not weaken `verifyChangePolicy` merely to permit an Epic roll-up PR.**

No exemption, no widened allowlist, no `epic-integration` carve-out, no analyzer+runtime exception, no "provenance is implied by the constituent PRs" argument invented to obtain a green. If the promotion needs a path, it must be a sequence that satisfies the rule **as written**.

## Trigger

After the 0.7.1 content freeze. Until then this task stays recorded and unstarted, and `#440` stays open as the integration canary — not merged, not closed, not "temporarily waived".

## Exit

- the promotion PR is green at its exact head with **no workflow exempted, weakened or bypassed**;
- each of the three gates above has recorded evidence, including the lane re-measurement behind any pin change;
- no authority file was changed to accommodate the transition;
- the promotion remains a certification event rather than a first-time integration.
