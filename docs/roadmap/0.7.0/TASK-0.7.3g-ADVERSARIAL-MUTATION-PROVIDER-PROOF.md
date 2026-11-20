# TASK 0.7.3g — Adversarial / Mutation / Provider Proof

**Status:** complete as a proof slice. **Epic 0.7.3 is not complete; 0.7.3h remains open.**

## 1. Base

| | |
|---|---|
| Exact base | `bef845ab0f0a4c8a4d4509d28a196195b70948ff` |
| Branch | `task/0.7.3g-adversarial-mutation-provider-proof` |
| Target | `epic/0.7.3-authorized-selection` |
| Working tree at start | clean |

## 2. The one invariant

```text
authorized = policy ∩ classification ∩ trust ∩ registration ∩ capability
viable     = authorized ∩ runtime constraints
selected   = strategy(viable)
```

The slice exists to prove **no path after authorization may widen authority**. Every case below
attacks one edge of that chain and then asserts the candidate is absent from every downstream
authority-bearing value — `AuthorizedCandidates`, the viability decisions, `ViableCandidates` and
`CandidateSelectionDecision` — rather than asserting only that a refusal reason was produced.

## 3. Proof slice, not a production change

**Production code changed: none.** The diff is tests and this document.

No defect was discovered that required a production fix. Two behaviours were found where the
implementation is *stricter* than the slice's first expectation, and both were corrected on the test
side, not the production side:

1. an empty viable set is refused with `NO_VIABLE_CANDIDATES` **before the strategy is consulted**,
   where the first draft asserted `STRATEGY_OUTSIDE_VIABLE_SET`;
2. `ProviderRoutingPlan` **refuses to build** a route naming an unregistered provider, so a tamper
   fixture that removed a registration produced an invalid plan rather than a refused candidate.

Neither is a defect: (1) is the stronger guarantee, and (2) is a fail-closed builder.

## 4. Real stages, real facts

The proof flows through the production boundaries only. No `AuthorizedCandidates` or
`ViableCandidates` value is constructed directly in any 0.7.3g test — both have module-internal
constructors, and the fixtures deliberately do not use that access.

```text
ProviderCandidate                     (real)
  ↓ ProviderDeployment + NamedTrustZone/TrustZoneName            (real)
  ↓ ProviderInputRelease(TrustZonePolicy, ClassificationRoutingRule)   (real)
  ↓ ProviderRoutingPlan.providers → ModelProvider.supportsCapability   (real registration)
  ↓ CandidateAuthorization        → AuthorizedCandidates
  ↓ CandidateViability            → ViableCandidates
  ↓ CandidateSelection            → CandidateSelectionDecision
  ↓ GovernanceDecisionEnvelope    → RuntimeEvidenceContractValidator → RuntimeEvidenceBundleWriter
```

### Provider fixtures

Real `ProviderRoutingPlan` registration entries backed by real `ModelProvider` implementations. The
stub counts invocations and throws if `complete` is ever reached:

| Candidate | Registered | Capability | Zone | Other | Expected |
|---|---|---|---|---|---|
| A | yes | complete | governed (EU_CLOUD) | available | authorized → viable → selectable |
| B | yes | complete | governed | **unavailable at runtime** | authorized → not viable → not selectable |
| C | yes | **missing TOOL_CALLING** | governed | available | not authorized → viability never evaluated |
| D | **no** | — | governed | available | not authorized → viability never evaluated |
| E | yes | complete | **ineligible zone (GLOBAL_CLOUD)** | available | not authorized → viability never evaluated |
| F | yes, **identity ≠ deployment provider** | complete | governed | available | not authorized → viability never evaluated |

A second fully governed provider (`eta`) exists so the viable set can hold more than one candidate
for the retry and ranking attacks. `theta` is added to the registration in the retry test so
narrowing can be repeated twice.

### Viability-call attribution

`CandidateViability` takes its evaluator as a required function, so the fixture wraps it in a probe
that records every candidate evaluated. For C, D, E and F the proof asserts the probe never saw
them, which is what makes "never evaluated for viability" a measurement rather than an inference
from a missing map entry.

## 5. Adversarial scenarios

### Fallback (configured routing is not authority)

| Case | Setup | Result |
|---|---|---|
| fallback outside authorization | viable = {A}; strategy asks for D (unregistered) | `STRATEGY_OUTSIDE_VIABLE_SET`; A untouched |
| fallback authorized but unavailable | viable = {A}; strategy asks for B | `STRATEGY_OUTSIDE_VIABLE_SET` |
| fallback genuinely viable | viable = {A, A2}; strategy asks for A2 | `Selected(A2)` |
| empty viable set with a configured default | plan names a default provider; A and B both unavailable | `NO_VIABLE_CANDIDATES` and **the strategy is never consulted** |

There is no try-the-configured-fallback-anyway path: the empty guard precedes the strategy call, and
the case asserts the strategy was not invoked at all.

### Retry (narrowing only)

`{A, A2, theta}` → `without(A)` → `{A2, theta}` → `without(A2)` → `{theta}`. At every step an
attempted candidate re-requested by the strategy is refused, a candidate outside the original
universe stays outside, and removal is proven never to admit a candidate (`without(D)` on a set that
never contained D leaves membership unchanged).

### Preference and ranking (order only)

A preference list of `[D, B, A2, A]` — forbidden candidates first — yields `orderedBy == [A2, A]`,
and both the authorized and viable membership are asserted identical under the hostile and the
reversed preference. A strategy that ignores the filtered ordering and returns D or B is fenced for
both. Ordering may change *which* member is chosen; membership is asserted fixed by the stages.

### Strategy mutation (the 0.7.3e attack retained and strengthened)

A strategy that casts the supplied set to `MutableSet`, adds an outside candidate and returns it is
refused with `STRATEGY_OUTSIDE_VIABLE_SET`, and the authority envelope is asserted unchanged. Run
against a multi-element viable set, against a single-element envelope, and with a completely
invented unregistered candidate.

Measured detail for the single-element case: `toSet()` on a one-element set yields an immutable JDK
set, so the mutation attempt (`add`) throws `UnsupportedOperationException` rather than reaching the
fence. The test accepts either mechanism and asserts the invariant that matters: the outside
candidate is never selected and the envelope is unchanged. Both are a refusal to widen.

### Duplicates and ordering

`[A, A, A]` grants exactly the authority of `[A]`; `[A, B]` and `[B, A]` produce identical
authorized and viable membership. A duplicate cannot increase authority and source ordering cannot
alter membership.

Ordering is asserted at the level of individual refusals as well, not only aggregate membership: two
**independent** worlds are walked in opposite orders, each verdict is read from the pipeline composed
over that order, and the two per-candidate verdict maps are compared whole. Reading one world twice
would prove only that a pure call is pure, so the comparison deliberately crosses worlds to close the
shared-state loophole. The case also asserts the resulting map is not trivially all-refusals, since two
maps of identical refusals would be a weak proof.

### Refusal precedence

| Combination | Reported |
|---|---|
| identity mismatch + ineligible zone | `IDENTITY_DEPLOYMENT_MISMATCH` |
| zone pair denied + classification denied | `ZONE_PAIR_NOT_ALLOWED` |
| classification denied + unregistered | `CLASSIFICATION_ZONE_NOT_PERMITTED` |
| unregistered + missing capability | `PROVIDER_NOT_REGISTERED` |

Input ordering is asserted not to change any refusal.

### Full conjunction

Starting from a candidate that passes everything, exactly one authoritative fact is changed at a
time — zone pair removed, classification rule's zone removed, registration removed, one required
capability removed, identity made inconsistent — and each case asserts four things: the specific
refusal, absence from the authorized set, viability never evaluating the candidate, and
non-selectability. Two of these tamper the policy for every candidate at once, so the honest
downstream result is an empty viable set and the case asserts `NO_VIABLE_CANDIDATES`: a missing
authority cannot be compensated by the strategy.

### Stage separation

* unavailable candidate: `Authorized` **and** `NotViable(AVAILABILITY)` — never `NotAuthorized`;
* capability-incompatible candidate: `NotAuthorized(REQUIRED_CAPABILITY_NOT_SUPPORTED)` — never
  `NotViable`, and viability is never evaluated for it;
* the empty outcome is never reported with the other stage's reason, which is the stage swap that
  would produce a safe-looking selection while lying about why.

## 6. Provider non-invocation

Every 0.7.3g proof suite runs with stubs whose `complete` throws, and the whole decision path
(authorization, viability, selection, evidence, and their assembly) is asserted to record **zero
invocations**. Nothing in this slice performs generation, transport or provider I/O. This is the
boundary the epic draws between decision authority and execution runtime, and it is why wiring
nothing into `ProviderExecutionCoordinator` is a requirement rather than an omission.

## 7. Evidence proof

Decisions taken from the adversarial matrix — not hand-written fixtures — are bound through
`GovernanceDecisionEnvelope` and pushed through the canonical `RuntimeEvidenceContractValidator` and
`RuntimeEvidenceBundleWriter`:

| Decision | Kind | Reason |
|---|---|---|
| `NotAuthorized` | `governance.authorization` | `PROVIDER_NOT_REGISTERED` |
| `NotAuthorized` | `governance.authorization` | `REQUIRED_CAPABILITY_NOT_SUPPORTED` |
| `NotViable` | `governance.viability` | `AVAILABILITY` |
| `Selected(A)` | `governance.selection` | — |
| `NoSelection` | `governance.selection` | `STRATEGY_OUTSIDE_VIABLE_SET` |
| `NoSelection` | `governance.selection` | `STRATEGY_DECLINED` |

Every decision above is produced by the real stages. In particular the ordinary decline is the real
selection stage's own outcome for a strategy that returns nothing — not a decision value constructed
in the test — so the escape-versus-decline contrast is between two stage outcomes rather than between
a stage outcome and a hand-written value.

Proven: each record validates and persists to `governance-decisions.jsonl` in the writer's reported
runtime-evidence directory (one record per decision); the subject digest equals
`CandidateSubjectDigest.of` the candidate concerned; two candidates with the identical `Authorized`
outcome carry **different subject digests** (so the 0.7.3f Blocker-1 collapse stays closed) while
legitimately sharing a payload digest, because `payloadDigest` covers the decision payload — event
type, kind, reason, policy version, workflow digest, correlation and attribution — and not the
candidate, which is carried separately in `subjectDigest`; a capability refusal is never emitted in
the viability family and non-viability is never emitted as a governance denial; a selection escape
(`STRATEGY_OUTSIDE_VIABLE_SET`) stays distinguishable from an ordinary decline
(`STRATEGY_DECLINED`); and no raw provider, model, deployment or trust-zone value appears in the
exported JSONL.

## 8. Artifacts under mutation

Production files, frozen before the campaign and restored byte-identically after every mutant, with
the post-campaign hashes compared back to this table:

| File | SHA-256 |
|---|---|
| `evidence/GovernanceDecisionEvidence.kt` | `38f443b7f73694c0ec8ff644f9ec50670798f7c0083a27f3e50c841044c35973` |
| `evidence/RuntimeEvidenceBundleWriter.kt` | `02dc6f6769f1a3264543253e80ae897a3aa5bc23736ca03a0e4130939c37374a` |
| `evidence/RuntimeEvidenceContractValidator.kt` | `ddd989dc2c7027138bf8b996fa8bb7892cfcf7d6fc57b8a167f85057facf222e` |
| `governance/CandidateAuthorization.kt` | `d7c411948d223909418dfe16949012e4f05116b9659348b022b5bd6d82ebf9c1` |
| `governance/CandidateViability.kt` | `0fc1951ed338b15754da72904c3948f483554884bcd16e0e4a91c67024af309e` |
| `governance/ViableCandidates.kt` | `44bcd092218ec5537ab48c0b69aec96fdee2851624c6dfd8c47281088276bb3b` |
| `governance/CandidateSelection.kt` | `65440a5b99f1a175d8e1d5719c3b8d24e9a70179004c13060783f9bfd2511eb0` |

Test files, hashed to prove the counts come from one revision (unchanged across the campaign):

| Test file | SHA-256 |
|---|---|
| `governance/AdversarialGovernanceFixtures.kt` | `a6db612f8cc71dc52047244d3e7161ad431f64a1d8dce965c83fb9797811ef3f` |
| `governance/GovernanceAdversarialCompositionTest.kt` | `0e02f3bb89c1887b61ec6ab25224375f12529cee7234286c177a946f2b4c48f1` |
| `governance/GovernanceSelectionBoundaryAttackTest.kt` | `f05e2985fe4244c1a2aa5a2b5b134ea7987bad975aaef1a6c05ddc6a4d325d51` |
| `evidence/GovernanceAdversarialEvidenceTest.kt` | `7dfe17b5a6cfc189158cf7adc8f5e3862c7bed8f60e48c20bae756053069c1ec` |

## 9. Mutation population and disposition

**Disposition: 26 KILLED · 0 SURVIVED · 0 NO_COVERAGE · 0 UNDETERMINED.**

Baseline before the campaign: **206 tests, 0 failed**. The driver aborts the whole campaign if the
baseline is not green, if any anchor fails to match, or if a mutation does not change a file's hash;
it reports `NO_TESTS_RAN` rather than a verdict when a mutant fails to compile. Counts below are
`failures of 206 executed`, verbatim from this revision's run.

### Authorization — `CandidateAuthorization`

| Mutant | Failure count | Killing evidence |
|---|---|---|
| `D01` identity/deployment check removed | 6 | `identity refusal takes precedence over the other restrictions`; `a candidate whose identity disagrees with its deployment is NOT_AUTHORIZED`; `C through F are refused authorization and viability never evaluates them`; `refusal precedence is deterministic and ordered` |
| `D02` zone-pair refusal removed | 19 | `a candidate whose zone no policy pair allows is NOT_AUTHORIZED for that reason`; `two deployments of one brand are not interchangeable`; `the zone-pair refusal takes precedence over the classification refusal`; `every refusal carries a member of the stable reason family` |
| `D03` classification refusal removed | 5 | `a candidate for a classification with no rule is NOT_AUTHORIZED`; `a candidate the classification rule forbids is NOT_AUTHORIZED for that reason`; `every refusal carries a member of the stable reason family`; `refusal precedence is deterministic and ordered` |
| `D04` registration refusal removed | 25 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `a selection escape stays distinguishable from an ordinary strategy decline`; `the adversarial matrix decisions pass the canonical validator and the canonical writer` |
| `D05` required-capability refusal removed | 8 | `refused and non viable candidates are distinguishable by reason and by stage`; `one unsupported required capability refuses authorization`; `a capability incompatible candidate is refused authorization and never reported non viable`; `candidate input ordering never changes a refusal or the authorized set` |
| `D06` conjunction replaced by unconditional authorization | 34 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `a selection escape stays distinguishable from an ordinary strategy decline`; `the adversarial matrix decisions pass the canonical validator and the canonical writer` |
| `D07` refusal precedence reordered | 3 | `identity refusal takes precedence over the other restrictions`; `a candidate whose identity disagrees with its deployment is NOT_AUTHORIZED`; `refusal precedence is deterministic and ordered` |
| `D08` refused candidate admitted as authorized | 25 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `a selection escape stays distinguishable from an ordinary strategy decline`; `the adversarial matrix decisions pass the canonical validator and the canonical writer` |

### Viability

| Mutant | Failure count | Killing evidence |
|---|---|---|
| `F01` refusal reported as viable | 17 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `a selection escape stays distinguishable from an ordinary strategy decline`; `the adversarial matrix decisions pass the canonical validator and the canonical writer` |
| `F02` availability filter removed | 10 | `selection never reclassifies a non-viable candidate as governance-denied`; `a configured fallback that is authorized but not viable cannot be selected`; `candidate ordering does not change the viability results`; `B a governed but unavailable provider is authorized then not viable then not selectable` |

### `ViableCandidates`

| Mutant | Failure count | Killing evidence |
|---|---|---|
| `H01` retry narrowing removed | 3 | `retry after an attempted candidate stays inside the original viable set`; `retry narrows the viable set and a removed candidate cannot reappear`; `narrowing can never add a candidate` |
| `H02` `orderedBy` retains outside candidates | 3 | `a preference may reorder viable candidates but cannot add one`; `a preference listing forbidden candidates first cannot resurrect them`; `preference orders but cannot widen membership` |
| `H03` membership always true | 27 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `a selection escape stays distinguishable from an ordinary strategy decline`; `the adversarial matrix decisions pass the canonical validator and the canonical writer` |

### `CandidateSelection`

| Mutant | Failure count | Killing evidence |
|---|---|---|
| `I01` membership check removed | 23 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `a selection escape stays distinguishable from an ordinary strategy decline`; `the adversarial matrix decisions pass the canonical validator and the canonical writer` |
| `I02` empty-viable guard removed | 2 | `an empty viable set selects nothing and never consults a strategy`; `an empty viable set is refused before the strategy is consulted even with a configured default` |
| `I03` backing set handed to the strategy | 3 | `a strategy cannot widen the envelope by mutating the set it is given` (both suites); `a mutating strategy cannot add a completely unregistered candidate` |
| `I04` outside answer accepted as a selection | 23 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `a selection escape stays distinguishable from an ordinary strategy decline`; `the adversarial matrix decisions pass the canonical validator and the canonical writer` |

### Evidence

| Mutant | Failure count | Killing evidence |
|---|---|---|
| `A01` bound subject substituted | 6 | `the subject digest names the candidate concerned and separates equal outcomes`; `candidate scoped outcomes bind the candidate subject`; `a selection cannot be bound to a subject it does not name`; `current preference changes cannot alter historical selection evidence` |
| `A02` `Selected` subject mismatch ignored | 1 | `a selection cannot be bound to a subject it does not name` |
| `A03` viability family collapsed into authorization | 9 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `the adversarial matrix decisions pass the canonical validator and the canonical writer`; `the canonical validator accepts every governance decision outcome` |
| `A04` partial attribution emitted | 40 | `refused and non viable candidates are distinguishable by reason and by stage`; `no raw provider model or deployment value reaches exported evidence`; `a selection escape stays distinguishable from an ordinary strategy decline`; `the subject digest names the candidate concerned and separates equal outcomes` |
| `B01` unknown governance kind permitted | 1 | `the canonical family table knows the governance decision family` |
| `B02` non-allowlisted governance metadata permitted | 3 | `the governance evidence family carries no family metadata of its own`; `the canonical family table knows the governance decision family`; `non-allowlisted metadata is rejected by the canonical validator` |
| `C01` cross-family reason accepted | 1 | `a reason outside its own closed family is rejected` |
| `C02` unknown decision kind accepted | 2 | `an unknown governance decision kind is rejected by the canonical validator`; `invalid decision kind fails` |
| `C03` foreign source component accepted | 2 | `a foreign source component is rejected by the canonical validator`; `wrong source component is rejected` |

### 9.1 Correction record — four measurements that were not verdicts

Recorded because a mutation result that was never measured must never be reported as a kill, and a
void measurement must never be reported as a survivor.

1. **`A04`, first attempt: the mutant never applied.** The insertion anchor embedded a literal
   backslash-`n` instead of a newline, so the replacement matched nothing. The driver printed
   `ANCHOR MISSING` and the entry was recorded as void.
2. **`A04`, second attempt: a false `SURVIVED`.** The corrected anchor used 20 leading spaces against
   a line that has 12, so the guard fired and the suite ran green **on an unmutated file** — reported
   as `SURVIVED`. That report was wrong in the direction that hides a gap. The driver now proves the
   mutation landed before measuring (anchor matched, file hash changed, target string absent) and
   aborts the campaign otherwise. The final attempt produced `KILLED`, 40 of 206.
3. **`D04`, first attempt: no test executed.** Removing the null check outright
   (`provider == null -> { … }` → `false -> { … }`) also removes the smart cast the following
   capability branch depends on, so the module did not compile and **zero** tests ran. Reported as
   `NO_TESTS_RAN`, re-derived as `provider == null -> null`: `KILLED`, 24 of 206.
4. **The first campaign ran against a test revision that no longer exists.** Formatting and the
   removal of two test indirections changed the test sources after those counts were taken. Under
   the rule that a test-only edit invalidates a mutation count, the campaign was re-run on the
   frozen revision, and the test-file hashes in §8 were captured before and after to prove it did not
   change during the run. Every count in §9 is from that re-run.
5. **Two proof-hygiene defects were found on review of the first pushed head, and both were fixed
   rather than argued.** The input-ordering case compared `decision(candidate)` with
   `decision(candidate)` — the same call, same candidate, same authorization instance — so it proved
   `x == x` while its name and this document claimed that input ordering does not change a refusal.
   It now compares two independent worlds walked in opposite orders. And the escape-versus-decline
   case constructed `NoSelection(STRATEGY_DECLINED)` by hand inside a class whose stated claim is
   that every decision comes from the real stages; the decline is now the selection stage's own
   outcome. Neither finding involved a production defect. Both are test-revision changes, so the
   campaign and every count in this document were re-derived once more, and the hashes in §8 are the
   post-fix revision.

`SURVIVED` entries in the mutation tables: none. No mutant was classified as equivalent, and none was
classified from inspection alone.

## 10. Verification ladder

| Check | Result |
|---|---|
| 0.7.3g cross-stage / adversarial classes | 36 tests, 0 failed |
| focused suite used for the campaign (10 classes) | 206 tests, 0 failed — baseline and restored |
| full `:tramai-security` | 1040 tests, 0 failed |
| `spotlessKotlinCheck` | 0 |
| `verifyCompilerWarnings` | 0 — 129 baseline-covered warning identities, gate green |
| `verifyStaticAnalysis` | 0 — Detekt baseline 4792 → 4792, 0 removed, 0 added |
| `verify060Architecture` | 0 |
| `verifyChangePolicy` | PASSED — 5 changed files, change class `runtime-behaviour`, no policy violations |
| API compatibility | production API unchanged: `apiDump` leaves `tramai-security.api` byte-identical (`ce5c4bbb…` before and after, 0 diff lines) |

No baseline entry was edited and no quality finding was suppressed. The `@Suppress("UNCHECKED_CAST")`
occurrences in the boundary-attack file are the deliberate ones the shipped 0.7.3e attack already
uses, on a cast the attacker performs on purpose; they suppress no analyzer finding.

## 11. Scope boundary — 0.7.3h remains open

This slice proves the decision machinery. It does **not** prove the execution path respects it.

* `ProviderExecutionCoordinator` is **not** wired to `ViableCandidates` or `CandidateSelection` here.
* No execution integration, retry scheduling, fallback routing or provider invocation is added.
* Epic 0.7.3 must not be marked complete while the real execution path can execute routing or
  fallback independently of the viable-selection boundary. That is the carried 0.7.3h obligation:
  forcing the actual execution path through the viable-selection boundary.
* **Carried question for 0.7.3h.** This slice proves a caller-supplied strategy cannot widen
  authority; in the single-element case the malicious strategy's attempt fails by throwing before it
  can be fenced. A refusal to widen is not the same property as a safe execution runtime: once a real
  execution coordinator supplies strategies, an exception thrown through `CandidateSelection.select`
  becomes an availability concern. 0.7.3h should decide explicitly whether strategy exceptions
  propagate or become a deterministic, auditable execution refusal. 0.7.3g does not widen scope to
  settle it.
