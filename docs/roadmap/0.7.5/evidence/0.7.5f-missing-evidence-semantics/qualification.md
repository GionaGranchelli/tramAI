# 0.7.5f — Missing Evidence & Historical Semantics

A correctness pass over the merged 0.7.5c–e contracts: missing evidence stays missing, and a result
never claims more than its sources establish. No new architecture; two concrete semantic defects fixed,
the status vocabulary kept, and the guarantees that were only documented are now trapped by tests on
the shipped read path.

## Defects found and fixed

**1. "No attempt recorded" implied "nothing executed".** `EXECUTION_NOT_ESTABLISHED` was raised only
when a governance `Snapshot` carried decisions. So a run whose attempt store was instrumented and found
nothing — with governance unqueried, not configured, or a snapshot with no recognized decisions —
produced a result with no limitation at all, and `SUPPORTED` could be read as "nothing ran". The oracle
is now the observation itself: no recorded attempt means execution is unknown, whatever governance did
or did not say. A recorded decision beside it is still permission, never proof.

**2. A correctly shaped configuration reference looked authenticated.** `Recorded` validates shape only,
and `SUPPORTED` said nothing about provenance, so a reference read from current configuration was
indistinguishable from one read from the run's own evidence. Added
`HISTORICAL_CONFIGURATION_UNVERIFIED`, raised whenever a reference is supplied: nothing in this API can
authenticate an origin, so the doubt is reported instead of being implied away. `SUPPORTED` results now
routinely carry limitations — which is the point of keeping the status and the limitations separate.

## Vocabulary: unchanged, clarified

`SUPPORTED` / `PARTIAL` / `UNSUPPORTED` are unchanged and still sufficient — no counterexample required
a new status.

- `SUPPORTED` — the required sources answered sufficiently for the declared scope. **Not** a
  certificate of authenticity, exhaustive event coverage, successful execution or complete metrics.
- `PARTIAL` — some evidence is available, but one or more required elements or source conditions are
  unresolved.
- `UNSUPPORTED` — the available evidence cannot establish the requested reconstruction at all.

## Semantics, and the test that pins each

All of these run through the shipped path: real `InMemoryAttemptObservationStore` /
`UninstrumentedAttemptObservationStore`, real `SemanticTimelineQuery.of(...)`, real
`EvidenceBackedReconstructor`.

| Situation | Required interpretation | Test |
|---|---|---|
| Attempt store uninstrumented | Execution history unknown | `an uninstrumented store leaves execution unknown rather than empty` |
| Instrumented store, no attempts | No attempts *recorded*, not proof nothing ran | `no attempt recorded is not proof that execution did not occur` |
| Governance not queried | Not evaluated, not absent | `governance that was never queried is unevaluated, not absent` |
| Reference with a valid shape | Unauthenticated identity | `a correctly shaped reference is reported as unauthenticated` |
| Usage reported as zero | The observed zero is preserved | `token usage explicitly reported as zero stays zero` |
| Usage incomplete per component | Component total unknown | `an incomplete component total stays unknown` |
| TTFT / finish-reason provenance | Never fabricated | `finish reason provenance is never fabricated` |
| Retries vs repeated invocations | Distinct observations | `retries and repeated invocations stay distinct observations` |
| Shipped path side effects | None: no write, no provider/tool/approval call | `the shipped read path performs no writes and invokes nothing external` |

Already pinned before this slice and re-verified here: uninstrumented ≠ empty (0.7.5d, 0.7.5e), governance
`NotMaterialized` ≠ no decisions and `UnsupportedFormat`/`InvalidStoredProjection` = refused evidence
(0.7.5c, 0.7.5d), a source read failure propagates rather than becoming `NotRecorded` (0.7.5e).

## 0.7.5e review finding A — read-only collaborator boundary

Documented on both ports, in the honest direction: `SemanticTimelineQuery` and
`HistoricalConfigurationSource` declare what a method returns and **cannot** constrain what an
implementation does. The shipped implementation is verified read-only; a caller-supplied one is
unverified, and no doc or test claims otherwise.

Trapped on the shipped path: `the shipped read path performs no writes and invokes nothing external`
wires a store that **throws on `append`** through the real query and the real reconstructor, and asserts
the reconstruction completes, `append` was never called (zero), and the store and governance query were
each read exactly once. There is no provider, tool, approval or workflow collaborator anywhere in
`tramai-security` — the module does not depend on `tramai-engine` — so the invocation capability is
absent, not merely unused.

## 0.7.5e review finding B — historical configuration provenance

See defect 2. The reference type still validates shape only; the difference is that the result now
*states* that provenance is unauthenticated instead of leaving `SUPPORTED` to imply otherwise.

The distinction the owner asked for is now explicit in the vocabulary and docs:

- **Source-declared** — `HistoricalConfigurationReference.Recorded` means "the evidence source stated
  this identity". Doc'd in those words on the type, on the constructor, on the limitation and on the
  result.
- **Verified provenance** — not expressible. No state, enum constant or subtype claims authentication,
  because no authority in this library can establish it; adding one would be inventing a verifier. A
  supplied reference is always reported as `HISTORICAL_CONFIGURATION_UNVERIFIED`.
- **Trust assumptions** are listed on `ReconstructionResult`: the source read persisted historical
  evidence rather than current configuration; `NotRecorded` means the lookup completed and found none;
  `Recorded` is unauthenticated; a read-only collaborator is a contract on an implementation, not proof
  about it.
- **Source obligations** are listed on `HistoricalConfigurationSource`: return `NotRecorded` only when
  the lookup completed and established no reference was recorded; throw on failure (a failure
  propagates, never becomes absence); never read current configuration as a fallback.

## Whose properties are these? (types, shipped implementation, or collaborators)

The three are different, and the documentation now says which is which:

- **Types** — no prompt/response/payload/reasoning field exists; no state claims authenticated
  provenance; status and limitations are derived, so a result cannot contradict its evidence. These are
  compile-time properties of the vocabulary.
- **Shipped implementation** — `EvidenceBackedReconstructor` makes no external call of its own, and the
  shipped read path is verified read-only by write-trapping tests. That is a property of this
  composition, proven by tests against it.
- **Caller-supplied collaborators** — unverified, and stated as such. An arbitrary
  `SemanticTimelineQuery` may write or call out; an arbitrary `HistoricalConfigurationSource` may read
  current configuration or swallow its own failure. Nothing here can inspect an implementation, so the
  contract reports what it cannot verify (`HISTORICAL_CONFIGURATION_UNVERIFIED`) instead of claiming it.

## Adversarial regression coverage

Mapping of the owner's twelve required cases to tests in this PR. All run through the shipped path
unless noted.

| # | Required case | Test |
|---|---|---|
| 1 | No attempts recorded does not prove no execution | `no attempt recorded is not proof that execution did not occur` |
| 2 | Uninstrumented attempts remain unknown | `an uninstrumented store leaves execution unknown rather than empty` |
| 3 | Zero usage and missing usage stay distinct | `token usage explicitly reported as zero stays zero`, `unknown usage is named rather than zeroed` |
| 4 | Partial usage cannot appear as a complete total | `an incomplete component total stays unknown` |
| 5 | Missing TTFT and unreported finish reasons stay unknown | `no time-to-first-token or provider-reported finish reason is fabricated` |
| 6 | Missing historical configuration never falls back to current | `missing historical configuration never falls back to a current configuration` |
| 7 | A source failure propagates rather than becoming `NotRecorded` | `a source failure propagates instead of becoming NotRecorded` |
| 8 | A source-declared reference does not become authenticated provenance | `a source-declared reference never becomes authenticated provenance` |
| 9 | A refused projection cannot become `SUPPORTED` merely because attempts exist | `a refused projection cannot be supported merely because attempts exist` |
| 10 | Side-effect traps untouched during reconstruction | `the shipped read path performs no writes and invokes nothing external`, `the reconstruction queries only what it was asked for`, and `the reconstructor holds only its two read collaborators` (field shape, not implementation behaviour) |
| 11 | Observed attempts keep non-authoritative provenance | `finish reason provenance is never fabricated`, `no time-to-first-token or provider-reported finish reason is fabricated` |
| 12 | Payload canaries do not appear through the producer path | **PARTIAL — pending 0.7.5g**: `values the source recorded are preserved and no unrecorded canary appears` proves recorded values are carried through and nothing is invented. It does **not** prove end-to-end confidentiality for prompts, responses or tool payloads through a producer path; those fields do not exist in this surface, and that proof belongs to 0.7.5g. |

Additional discriminators recorded for the highest-risk items (mutants applied, then restored):

```
source failure swallowed into NotRecorded
  -> a source failure propagates instead of becoming NotRecorded              FAILS
SUPPORTED granted when only one source answered
  -> a refused projection cannot be supported merely because attempts exist   FAILS
supplied reference presented as recorded-and-fine
  -> a source-declared reference never becomes authenticated provenance       FAILS
```

## Verification

`:tramai-security:test --tests 'dev.tramai.security.reconstruction.*'` — **43 tests** (17 contract +
10 reconstructor + 9 missing-evidence + 7 adversarial), 0 failures. Whole-module and CI results recorded at the
head that opened the PR.

Discrimination recorded (mutants applied to the implementation, then restored):

```
execution oracle narrowed back to "decisions were recorded"
  -> no attempt recorded is not proof that execution did not occur        FAILS
unverified-reference limitation removed
  -> a correctly shaped reference is reported as unauthenticated          FAILS
partial usage summed instead of reported as unknown
  -> an incomplete component total stays unknown                          FAILS
```

## Deliberately not here

- **No new status.** `SUPPORTED`/`PARTIAL`/`UNSUPPORTED` still cover the cases; the missing distinction
  was carried by a limitation, not by a fourth status.
- **No TTFT or reasoning-token field.** Neither exists in the recorded attempt, so neither is invented;
  `finishReasonReported` stays `false` until a provider-reported flag exists (0.7.5b).
- **No attempt to authenticate a source or a reference, and no verifier invented.** Out of scope for a
  library contract: it would need an authority this epic does not own, so the limitation is reported
  instead.
- **Confidentiality proof through the producer path is 0.7.5g**, not here. The canary test above is
  labelled PARTIAL in the coverage table rather than counted as one of twelve demonstrated cases.
- **No `Unavailable` lookup state.** A source that cannot complete must throw; that keeps the port to
  one method and the failure path to the language's own. A source that swallows its own failure and
  returns `NotRecorded` remains undetectable — stated as an obligation and documented, not enforced.
- **No new persistence, endpoint, export or analytics.**
- **Inherited, unchanged:** a default Spring application records no attempts, so its reconstruction is
  `ATTEMPTS_UNINSTRUMENTED` (0.7.5b gap).
