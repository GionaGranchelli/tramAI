# TASK-0.7.1g1P2 — Authority Model Reconciliation (design record)

**Status:** design only. No rule, code, authority or configuration change is made by this record.
**Endpoint:** AUTHORITY MODEL RECONCILED — IMPLEMENTATION SLICE SPECIFIED (implementation deferred).
**Base:** `689e8b65472d41f240add5bfb4a3cc4bb2b2baf7`.
**Scope guard:** P2 is not performed. No baseline, admissions, classifications, evolution or analyzer change.

---

## 1. Empirical evidence — four complete unrestricted campaigns

All four campaigns ran the committed 7-family configuration from the same effective PIT inputs
(identical analyzer semantics, mutators, timeouts, targets, tests, and config hashes). Raw PIT
evidence was preserved, SHA-256 manifest-verified, and independently re-aggregated from the XML —
not read from the generated baseline.

| campaign | digest (current semantics) | KILLED | SURVIVED | NO_COVERAGE | TIMED_OUT | duration |
| --- | --- | --- | --- | --- | --- | --- |
| G6 | `9aebd3202288c82ff006f2db33c95cac0772746fa3c3061569167cd3f45df9b0` | 1836 | 448 | 190 | 70 | 49m03s (session notes; log no longer on disk) |
| failed P2 | `31fc3b0e7c1ed28e6a1e284d36705c713c41cf4efafc2b5002d8544d01cd5f66` | 1836 | 445 | 190 | 73 | 49m27s |
| Run A | `f83b0feb0e1bd00eec7f744b54d38331f49f53f3e1cd099dc5c93dc06f92ef75` | 1836 | 447 | 190 | 71 | 46m04s |
| Run B | `8e00fd7e02ebf078f24aad5a5c2e75fb3a31f0b216571806ba28c3955686e620` | 1836 | 446 | 190 | 72 | 49m21s |

**Stable in all four campaigns:** 2544 identities with an identical identity set in every pairwise
comparison; all seven family/module topology counts exact (918/808/382/209/155/57/15); 1836 KILLED;
190 NO_COVERAGE; canonical outcome totals 1836 KILLED / 708 NON_KILLED; shared 2195; base-only 189;
candidate-only 349; candidate-only KILLED 282; candidate-only NON_KILLED 67.

**Unstable:** only the SURVIVED ↔ TIMED_OUT split, and only ever three identities (SURVIVED +
TIMED_OUT = 518 in every campaign), giving four distinct digests.

| identity | G6 | failed P2 | Run A | Run B |
| --- | --- | --- | --- | --- |
| `b6c0300e1d1662d29d85df7ac1d6302f34c10418d318f79917d66d0637cd9014` | SURVIVED (437) | TIMED_OUT (0) | TIMED_OUT (0) | TIMED_OUT (0) |
| `eb1ef9ebbfaf9a9b91b2f072d593594491322991e88d145f60502ed5d438db2b` | SURVIVED (162) | TIMED_OUT (0) | SURVIVED (162) | SURVIVED (162) |
| `f711226127cd2c308288c897c928258f226de664dc4f55c003198a18ca42f4a8` | SURVIVED (372) | TIMED_OUT (0) | SURVIVED (372) | TIMED_OUT (0) |

(`numberOfTestsRun` in parentheses.) All three are `PolicyEnforcementHelper` on
`:tramai-engine` (two NegateConditionals at line 59 in `logMigrationWarningOnce`, one void-call
removal of `kotlin/ResultKt::throwOnFailure` at line 50 in `enforce`). Every unstable row has
TIMED_OUT on at least one side; SURVIVED always carries the same test count that identity shows in
G6; TIMED_OUT always carries `detected=true` and `numberOfTestsRun=0`; `killingTest` is null
throughout. Confinement to the timeout boundary is an observation, not proof of cause.

### 1.1 The two projections, computed over all four preserved measurements

Raw measurement projection (current semantics):
`identity|status|outcome|family|module`, sorted, plus `topology=<family:modules>` and
`analyzer=<pluginVersion|engineVersion|mutators|timeoutConst|timeoutFactor>`, SHA-256.

This reproduces all four distinct digests above exactly, confirming the recipe.

Proposed canonical authority projection:
`identity|outcome|family|module`, sorted, plus the same topology and analyzer lines, SHA-256.

| campaign | authority projection digest |
| --- | --- |
| G6 | `e6ad01dc1d2966894a6555304bc8ca9a04c8174e3c83ae88760fcfebf1464dad` |
| failed P2 | `e6ad01dc1d2966894a6555304bc8ca9a04c8174e3c83ae88760fcfebf1464dad` |
| Run A | `e6ad01dc1d2966894a6555304bc8ca9a04c8174e3c83ae88760fcfebf1464dad` |
| Run B | `e6ad01dc1d2966894a6555304bc8ca9a04c8174e3c83ae88760fcfebf1464dad` |

**The critical discriminator passes: all four authority digests are identical.** No averaging or
majority vote was used; each digest is an independent single-campaign computation.

Evidence envelopes (raw 14 reports + generated baseline + independent projection + metadata +
verified SHA-256 manifest; `sha256sum -c` fully clean):

| envelope | files | manifest fingerprint (sha256 of manifest.sha256) |
| --- | --- | --- |
| g6 | 134 | `5cb0573511ac4bf02df386ce…` |
| failedp2 | 135 | `78aa41dc376214b2b041c570…` |
| a | 135 | `35988fb702df2581ea0fbb38…` |
| b | 135 | `c73df331e4435e58fa628ed4…` |

---

## 2. The existing contract, from repository code

### 2.1 Record shape

`BaselineModel.kt` `MutationOutcome` carries both `status` (raw PIT:
`SURVIVED|NO_COVERAGE|TIMED_OUT|KILLED|…`) and `outcome` (canonical ratchet state:
`KILLED|NON_KILLED`), defaulting to `SURVIVED` / `NON_KILLED` respectively.

### 2.2 C7 — raw status is diagnostic, never authority

`BaselineModel.kt:348-354`, on `MutationOutcome` (C7):

> `outcome` is the canonical ratchet state: KILLED | NON_KILLED. Raw PIT `status`
> (SURVIVED/NO_COVERAGE/TIMED_OUT/...) is preserved as diagnostic evidence but is NOT authority —
> TIMED_OUT↔SURVIVED scheduler races must not churn the 10.3c2 ratchet (C7).

`MutationRatchetVerifier.kt:15-17`, restating it for the verifier:

> canonical outcomes are exactly KILLED | NON_KILLED; raw PIT statuses are diagnostic evidence and
> never participate in the ratchet (C7), but every persisted row MUST be self-consistent: its
> identity must equal the SHA-256 recomputed over its own fields, and its stored outcome must equal
> `MutationOutcome.canonical` of its raw status.

### 2.3 M01 / M06 / M07 / M21

From the discriminator matrix in `MutationRatchetVerifier.kt`:

- **M01** base KILLED → candidate NON_KILLED = regression (fail).
- **M06** new NON_KILLED identity = new survivor (fail unless exactly base-authorized).
- **M07** new KILLED identity = pass, only if the row is self-consistent.
- **M21** base identity absent from the candidate population = fail by default; a recorded evolution
  invocation may downgrade individually recorded removals to warnings only when the candidate has a
  fresh measured population.
- **M13** unknown/non-canonical outcome or raw status, or a stored outcome contradicting
  `canonical(raw status)` = fail closed. This is the existing guard against a tool-failure status
  becoming authority.
- **M12 / M14 / M16-M19 / M20** duplicates; family narrowing; analyzer/mutator/timeout drift;
  identity-schema drift; malformed or self-inconsistent authority.

### 2.4 M30-M39 — the population-admission ceremony

`MutationPopulationAdmissionCeremony.kt`, the rule set that governs P1/P2:

- **M30** appearing NON_KILLED with an exact base authorization → accepted, authorization consumed
  in the same transition.
- **M31** authorization created by the same transition → fail.
- **M32** authorized identity, different **row** → fail. (`admitsRow` binds exact
  identity + status + outcome + family + module.)
- **M33** authorized row, different **analyzer semantics** → fail.
- **M34** authorized row, **different population digest** → fail:
  *"An authorization binds the complete measured population."*
- **M35** new authorization bound to another base SHA → fail at mint.
- **M36** retained authorization **rewritten** → fail.
- **M37** authorization removed without a valid consumption → fail.
- **M38** authorization retained after being consumed → fail (single use).
- **M39** obsolete authorization → warning, never forced into an admission.

The ceremony's own trust statement is explicit that the digest binds the *neighbours*:

> The digest binds the *whole* transition, not just the identity's row: … if any other identity in
> the population changes between minting and consumption, the digest no longer matches and the
> authorization must be re-minted against the new measurement. A candidate can never construct that
> measurement, because the hash compared is never read from the ledger.

`MutationPopulationEvolutionProof.projectionHash` (M34's input) is the **raw** projection, i.e. the
same recipe as `1.1` above.

### 2.5 The contradiction, reconciled

The three statements are each individually coherent and were each written for a different purpose:

- **(A) C7** governs the *ratchet*: whether a passing/failing transition is caused by raw status
  churn. It declares raw status non-authority for that purpose, and explicitly anticipates
  TIMED_OUT↔SURVIVED scheduler races.
- **(B) M32** governs *one authorized identity*. It binds the exact persisted row, status included.
  That is deliberate and correct: the adjudications are status-specific. G5b TOOLING_LIMITATION
  asserts that PIT *times out* on a suspension sentinel; G5d UNREACHABLE asserts the mutated
  instruction *cannot execute* (a SURVIVED reading would mean it executed and falsify the proof);
  G5c EQUIVALENT asserts a structural equivalence proven against a *surviving* observation. Removing
  status from M32 would let a `NO_COVERAGE → SURVIVED` transition silently invalidate an UNREACHABLE
  adjudication.
- **(C) M34** governs *whole-population context*. It intends to bind the adjudication's neighbours so
  an authorization cannot be replayed onto a materially different population.

**The defect is not a deliberate override of C7.** M34 was built on the measurement-proof projection
(`projectionHash`), whose raw-status content is *correct for its own purpose* — trust question A
below requires it to stay raw-exact. Using that same hash as the authority digest imported raw
status into the authority path by construction, without a decision that raw status should be
authority. So C7 and M34 both hold; the reconciliation is that **one hash was reused for two
different trust questions**, and only one of them may contain a diagnostic.

The empirical consequence is severe because M34's binding is *whole-population*: 3 identities of
2544 (0.118%) oscillating between two raw statuses — a variation C7 explicitly declares
non-authoritative — make all 67 P1 authorizations unconsumable, while every authorized row, every
canonical outcome, every family/module, the analyzer semantics, and the identity set remain
byte-identical.

---

## 3. Three independent trust questions

### A. Fresh measurement ↔ committed candidate baseline — keep raw-exact

`MutationPopulationEvolutionProof.exactComparison()` must continue to prove the candidate baseline
equals the verifier's actual fresh measurement including raw status. **No relaxation.** A fresh
measurement that differs from the committed candidate in any persisted field must fail. Raw status
belongs here.

### B. Individual P1 admission row — keep exact, status included

Measured over the four campaigns: the 67 authorized rows are **exactly stable in every one** — 0
status mismatches, 0 exact-row mismatches, 0 missing, including raw statuses (36 TIMED_OUT / 27
SURVIVED / 4 NO_COVERAGE). Exact-row binding therefore costs nothing in practice and protects real
adjudication content. Assessed transitions:

- **TIMED_OUT → SURVIVED** — invalidates G5b TOOLING_LIMITATION (which asserts a timeout mechanism)
  and G5d UNREACHABLE (a surviving mutant executed). **Must keep failing.**
- **SURVIVED → TIMED_OUT** — weakens G5c EQUIVALENT's observation and turns a "ran and was not
  detected" claim into "did not complete". **Must keep failing.**
- **NO_COVERAGE → SURVIVED** — directly falsifies an UNREACHABLE proof. **Must keep failing.**
- **SURVIVED → NO_COVERAGE** — contradicts the recorded observation and hides a genuinely
  undetected survivor behind a coverage claim. **Must keep failing.**

**Conclusion: retain exact-status binding for individual authorizations (M32 unchanged).** The
principle is that an adjudication's rationale is admitted only for the observation it was made
against; that is precisely what C7 permits, because it constrains the *ratchet*, not the
authorization payload.

### C. P1 whole-population context — the actual defect

Should unrelated neighbouring raw-status changes invalidate P1 authority when identity, canonical
outcome, family/module, analyzer semantics and the authorized rows are all unchanged? Measured
answer: the neighbours in question are 3 non-authorized identities (0.118% of the population), each
NON_KILLED in all four campaigns with family/module preserved, oscillating only between two raw
statuses that C7 declares non-authoritative. **No — not on raw status.** The context binding should
reject only changes that are authority-relevant.

---

## 4. Threat model of the proposed split

The correction must keep rejecting, with the rejecting rule named. "Existing" = no change required;
"M32 + artifact" = M32 retains raw-status binding for the authorized row, while the whole-population
digest uses the authority projection.

| # | attack | rejecting rule |
| --- | --- | --- |
| 1 | previously KILLED → NON_KILLED regression | **M01** (existing) |
| 2 | unauthorized appearing NON_KILLED identity | **M06** (existing); malformed/self-inconsistent rows via **M20** |
| 3 | identity substitution | identity recomputation over the row's own fields + **M12** duplicates + **M19** schema drift (existing) |
| 4 | family/module re-homing | **M32** exact-row (`admitsRow` binds family + module) + **M14** narrowing (existing) |
| 5 | analyzer/mutator/timeout semantic drift | **M33** + **M16/M17/M18**, and the analyzer line stays **inside** the authority digest (existing + proposed) |
| 6 | disappearance without M21 custody | **M21** (existing) |
| 7 | candidate baseline differing from the verifier's exact fresh measurement | `exactComparison()`, still raw-exact — trust question A (existing, unchanged) |
| 8 | candidate-created same-transition admission | **M31** (+ **M23** for classifications) (existing) |
| 9 | retained consumed authority | **M38** (existing) |
| 10 | re-use of consumed authority | **M37** + **M38** single-use (existing) |
| 11 | authorized identity changing its adjudication-significant row | **M32**, retained status-inclusive — trust question B (existing, deliberately unchanged) |
| 12 | unknown/tool-failure PIT status becoming NON_KILLED authority | **M13** fail-closed on non-canonical status/outcome + `MutationOutcome.canonical` mapping, unchanged (existing) |

No attack in the list becomes admissible under the split. Attacks 1-12 above keep their current
rejections; the only relaxation is that a *non-authorized neighbour's* raw status no longer
invalidates whole-population context, while its canonical outcome, identity, family/module and
analyzer semantics still do. The migration-certificate design adds its own discriminators, T13-T19
in §7, covering its full lifecycle.

---

## 5. Is M34 redundant or merely over-specified?

### Option A — canonical authority digest (retain M34, correct its projection)

M34 keeps its function; its digest binds `identity|outcome|family|module` + topology + analyzer.
Raw status stays inside the measurement proof and inside individual admission rows.

- **Property preserved:** the authorization is still bound to the population context it was
  adjudicated against, so an authorization cannot be replayed onto a materially different
  population; the hash is still computed by the verifier from its own fresh measurement and never
  read from the candidate (anti-self-authorization intact).
- **Defect removed:** diagnostic raw status no longer carries authority, matching C7.

### Option B — remove whole-population digest binding

Rely on exact individual base authorization, M01, M06/M07, M14+, M21's exact fresh/candidate proof,
analyzer semantics, and the single-use rules.

- Individually, each authorization is still fully checked (M32/M33) and the population is still
  governed (M01/M06/M14-M19/M21).
- **What is lost:** nothing rejects a *stale-context* authorization — one minted against one
  population, then consumed in a transition whose population composition changed in ways that are
  legal for the *other* identities but which no longer resemble the context the adjudication was
  made in. M34 is the only rule that binds the adjudicated context as a whole.

**Recommendation: Option A.** It retains a demonstrable property (context binding) that no other
rule provides, and the correction is confined to which projection the digest hashes — the smallest
model that preserves the property. Option B discards a real property to remove a defect that Option
A removes anyway. Note the property is only worth keeping because the authority projection is
empirically stable across four independent campaigns.

---

## 6. Migration of the existing 67 v1 authorizations

### 6.1 Correction — the earlier revision of this section was wrong

An earlier revision of this section proposed migrating the digest semantics *in place* on the 67
records while claiming that M36 remained unchanged. That claim was false, and the repository's own
code proves it:

- `MutationPopulationAdmissions.kt:75-88` — `enforcedPayload()` = identity, status, outcome, family,
  module, analyzer, `fromBaseSha`, **`populationDigest`**, reason, issue, targetPhase. Its own doc
  comment says base/candidate byte-identity is judged over exactly this list.
- `MutationPopulationAdmissionCeremony.kt:314-317` — `isRetainedRewrite()` is
  `candidate.enforcedPayload() != base.enforcedPayload()`, and M36 fails on it.

Changing `populationDigest` on a retained record is therefore a retained-authority rewrite that M36
rejects by construction. The earlier proposal would have needed a "M36 except during migration"
branch — precisely the kind of exception this track refuses to add. The design record must not claim
an invariant it does not keep, so that proposal is withdrawn and replaced by the design below, which
keeps **M36 and M37 literally unchanged**.

The empirical conclusion, the C7 reconciliation, the authority-v2 projection, Option A and
individual-row binding are unaffected by this correction.

### 6.2 Corrected design — a bounded base-side digest-migration certificate

**Not one byte of the 67 admissions changes.** The semantic upgrade is carried by a separate
base-authoritative artifact: a digest-migration certificate in its own ledger, so M36's payload
comparison never sees it.

Certificate fields:

- `fromAlgorithm: raw-v1`, `fromDigest: 9aebd3202288c82ff006f2db33c95cac0772746fa3c3061569167cd3f45df9b0`
- `toAlgorithm: authority-v2`, `toDigest: e6ad01dc1d2966894a6555304bc8ca9a04c8174e3c83ae88760fcfebf1464dad`
- `admissionSetDigest` — SHA-256 over the sorted exact identities the certificate covers (the 67)
- `fromBaseSha` — mint-time anti-replay binding, enforceable only at introduction, then immutable
  provenance (same semantics as `MutationPopulationAdmission.fromBaseSha`)
- `reason` — the recorded provenance of the supersession

Sequence:

```
P1 merged (67 v1 authorizations, byte-identical and untouched)
    ↓
P1M — mint the bounded digest-migration certificate
      admissions untouched; no population transition; no consumption;
      the transition changes only the certificate ledger
    ↓
certificate exists in BASE
    ↓
P2 — consume the original 67 admissions using:
      exact admission row (M32), analyzer (M33),
      and authority-v2 context certified by the base certificate
      then remove the admissions and the certificate
```

Consumption-time check (M34, generalised): the verifier's own fresh **authority** projection must
equal either the admission's own `toAlgorithm`-semantics digest, or the `toDigest` of a
**base-side** certificate whose `fromAlgorithm`/`fromDigest` match the cited admission's
`populationDigest` and whose `admissionSetDigest` matches the exact set of authorizations present.

Proposed rules, all fail-closed. The certificate's **enforced payload** — the field set over which
retained immutability is judged, mirroring `MutationPopulationAdmission.enforcedPayload()` — is:
`fromAlgorithm, fromDigest, toAlgorithm, toDigest, admissionSetDigest, fromBaseSha, reason`. Audit-only
metadata (who/when) is excluded, exactly as the admissions ledger excludes `authorizedBy`/`authorizedAt`.

Target lifecycle, each step with its own rule:

```
mint against the exact base        (M45)
  → retain byte-identically        (M46)
  → consume only from base         (M43 + M42 + M41 + M40)
  → remove in the valid consuming transition  (M44 + M47)
```

- **M40** consumption citing a certificate whose `toDigest` ≠ the verifier's fresh authority
  projection → fail.
- **M41** certificate whose `fromAlgorithm`/`fromDigest` does not match the cited admission's
  `populationDigest` → fail.
- **M42** certificate whose `admissionSetDigest` ≠ the exact set of base authorizations → fail
  (this is what makes the certificate *bounded*: it cannot cover a different or later set).
- **M43** certificate introduced by the same transition that consumes it → fail (the M31 analogue
  for this new authority: the consuming candidate cannot invent the semantic upgrade it consumes).
- **M44** certificate retained after the consumption it authorised completed → fail (single use,
  the M38 analogue).
- **M45** **mint-time base binding:** a newly introduced certificate whose `fromBaseSha` ≠ the
  authority base the transition is proposed against → fail (the M35 analogue).
- **M46** **retained immutability:** a certificate present in the base whose enforced payload differs
  from the base copy in any field → fail (the M36 analogue). A certificate is immutable from the
  moment it is introduced; correcting a certificate means minting a new one in a later transition,
  never editing a retained one.
- **M47** **removal custody:** a base certificate that is absent from the candidate, unless it was
  validly consumed by that same transition, → fail (the M37 analogue). A certificate may only
  disappear by being consumed; it may not be cancelled silently.

**These rules are explicit and must not be assumed from existing machinery.** The existing loaders
(`MutationPopulationAdmissionLoader`, `MutationClassificationEnrollmentLoader`) validate *shape*
only — required fields, canonical 64-hex identity, 40-hex `fromBaseSha`, duplicate ids,
`schemaVersion` — and cannot enforce mint-time base binding, retained immutability or removal
custody. For the admissions ledger those guarantees come from the ceremony rules themselves
(`MutationPopulationAdmissionCeremony.kt`: mint binding at `:190-213`, retention/removal/single-use
at `:218-281`, `isRetainedRewrite()` at `:314-317`). The certificate needs its own equivalent rules
for the same reason; a new ledger file whose lifecycle is left to a generic loader would have none
of these properties.

### 6.3 Why the certificate design is the right one

- M36 remains **literally** unchanged: no exception, no permitted-field list, no special branch.
- M37 remains **literally** unchanged: the 67 are consumed by the same transition as before; the
  certificate is not an admission and its removal is not a consumption.
- The P1 records keep their exact historical bytes and provenance — the audit trail of what was
  actually minted is preserved, not rewritten.
- The candidate performing P2 cannot invent the semantic upgrade it consumes: the certificate must
  already exist in the base (the same temporal trust rule as every other authority in this track).
- Nothing is grandfathered: the certificate names one exact `fromDigest`, one exact `toDigest`, and
  one exact admission-set digest.
- If the 67 must ever change their own bytes, that is a different problem requiring its own
  ceremony, and this record does not authorise it.

---

## 7. Adversarial discriminator matrix required before implementation

Each attack needs a pure-verifier test *and* a real-task authority-transport test (per
`AGENTS.md`: a verifier-level discriminator cannot detect a missing `Loader.load(rootDir)`).

| # | discriminator | expected |
| --- | --- | --- |
| T1 | neighbour raw status flips SURVIVED↔TIMED_OUT, outcome/family/module unchanged | authority digest **unchanged**, M34 pass (the Case-3 discriminator) |
| T2 | neighbour outcome flips NON_KILLED→KILLED | authority digest **changes**, M34 fail |
| T3 | authorized row's own raw status changes | M32 fail (status-inclusive binding retained) |
| T4 | v1-digest record presented under v2 semantics | fail closed |
| T5 | migration names an identity absent from the v1 ledger | fail |
| T6 | certificate omits or misstates `fromDigest` / `toDigest` / `admissionSetDigest` | fail |
| T7 | authority digest computed from candidate-supplied data rather than the verifier's fresh measurement | fail (transport test: the fresh path is used) |
| T8 | analyzer/mutator/timeout semantics drift under the authority digest | fail (analyzer stays inside the digest) |
| T9 | family/module re-homing of an authorized identity | M32 fail |
| T10 | unknown PIT status or stored outcome contradicting `canonical(status)` | M13 fail closed |
| T11 | KILLED→NON_KILLED regression | M01 fail |
| T12 | unauthorized appearing NON_KILLED alongside an authorized one | M06 fail for the unauthorized identity |
| T13 | migration cannot rewrite admission authority: given an existing v1 admission, `identity/status/outcome/family/module/analyzer/fromBaseSha/reason/issue/targetPhase` must stay **byte-identical**; any modification fails M36; only a separately base-minted digest-migration certificate may translate raw-v1 population context to authority-v2 context | M36 fail on any enforced-payload change (the admitted defect in §6.1) |
| T14 | candidate creates a digest-migration certificate **and** consumes it in the same transition (the M31 analogue for the new authority) | M43 fail |
| T15 | consumption citing a certificate whose `fromDigest` does not match the cited admission's `populationDigest`, or whose `admissionSetDigest` does not match the exact base authorization set | M41 / M42 fail (the certificate is bounded, not a general licence) |
| T16 | certificate retained after the consumption it authorised completed | M44 fail (single use) |
| T17 | newly introduced certificate whose `fromBaseSha` ≠ the authority base the transition is proposed against | M45 fail (mint-time base binding) |
| T18 | certificate present in the base with any enforced payload field rewritten (`fromAlgorithm/fromDigest/toAlgorithm/toDigest/admissionSetDigest/fromBaseSha/reason`) | M46 fail (retained immutability) |
| T19 | base certificate absent from the candidate without a valid consumption in that same transition | M47 fail (removal custody — never silent cancellation) |

---

## 8. Files and rules that would change (implementation slice, not performed here)

- `build-logic/.../quality/MutationRatchetVerifier.kt` — split the projections: keep
  `projectionHash` raw-exact for the measurement proof; add the authority projection used by M34.
- `build-logic/.../quality/MutationPopulationAdmissionCeremony.kt` — M34 compares the authority
  projection; add the certificate-aware consumption check and the M40-M44 fail-closed branches.
  `isRetainedRewrite()` and the M36 path are **not** touched.
- New certificate authority: a ledger file (e.g. `config/quality/mutation-population-digest-certificates.yml`)
  with its own loader following the existing loader pattern, plus its `schemaVersion` and
  fail-closed validation. Separate from the admissions ledger so M36's payload comparison never
  sees it.
- Tests: the T1-T19 matrix, verifier-level and real-task level.
- Docs: this record plus the rule-list updates in the ceremony/verifier headers.

**Not changed:** `MutationPopulationAdmissions.kt` enums/payload (no new enforced field),
`MutationPopulationAdmissionLoader.kt` validation, `config/quality/mutation-population-admissions.yml`
(byte-identical), the committed baseline, classifications, and the evolution ledger.

## 9. Explicitly out of scope

- P2 consumption, baseline transition, M21 records, classification changes.
- PIT timeout values, mutator set, targets, tests, analyzer semantics, canonical outcome mapping
  (`MutationOutcome.canonical`), M06, M13, M36, M37 (weakened by nothing in this design).
- Any change to the 67 admissions' identity, status, outcome, family, module, analyzer,
  `fromBaseSha`, reason, issue or targetPhase.
- Killing the three oscillating mutants, or otherwise seeking to recover the digest `9aebd320…`.
- Treating raw-status nondeterminism as harmless: the nondeterminism is real and unresolved; this
  record only establishes that raw status is the wrong thing for the *authority* digest to bind,
  which is what C7 already says.

## 10. Non-goals honoured by this record

No full campaign rerun; no PIT timeout tuning; no mutant killing; no change to the committed
baseline, P1 admissions, M21 records, classification authority or canonical outcome mapping; no P2.
