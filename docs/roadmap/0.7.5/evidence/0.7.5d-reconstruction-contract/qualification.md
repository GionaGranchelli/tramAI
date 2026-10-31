# 0.7.5d — Historical Reconstruction Contract

Vocabulary for reconstructing a governed run from evidence that already exists. There is no
reconstruction engine here: no source is read, nothing is inferred, and every classification is
derived from evidence the caller passed in.

## Scope

Added, in `tramai-security` (`dev.tramai.security.reconstruction`):

- `ReconstructionRequest` — which run and governance scope ([TimelineRequest], reused), plus the
  historical configuration/policy identity the reconstruction may cite.
- `HistoricalConfigurationReference` — `Recorded(configuration?, policyVersion?, workflowDigest?)` or
  `NotRecorded`. Required on the request, so a caller cannot omit it and let something else stand in.
- `ReconstructionStatus` — `SUPPORTED` / `PARTIAL` / `UNSUPPORTED`.
- `ReconstructionLimitation` — seven derived statements of what a result must not be read as claiming.
- `ReconstructionResult` — the carried `SemanticTimeline` plus `status` and `limitations` **computed**
  from it.

Unchanged: `SemanticTimeline`, `GovernanceSection`, `AttemptHistoryState`, `CorrelationEvidence`,
`GovernanceProjectionView`, `ModelAttemptObservation`, the governance query, the attempt store. No new
persistence, no engine, no endpoint, no analytics, no duplicate model.

## The four questions, and where each is answered

| Question | Answer |
|---|---|
| What is known | `ReconstructionResult.governance` (a `GovernanceSection`, with its own completeness) and `.attempts` (`AttemptHistoryState`) |
| What is missing | `.limitations` |
| What can be reconstructed | `.status` |
| What must never be inferred | `ReconstructionLimitation` (the vocabulary itself) |

## Classification rules

`status` (in `ReconstructionResult.statusOf`):

- `SUPPORTED` — governance answered (`Snapshot`), attempts answered (`RECORDED` or `EMPTY`), **and** the
  historical configuration is `Recorded`.
- `UNSUPPORTED` — neither governance nor attempts answered. A recorded configuration does not change
  this: configuration identity is not evidence of a run.
- `PARTIAL` — everything else.

`limitations` (in `ReconstructionResult.limitationsOf`):

- `GOVERNANCE_NOT_OBSERVED` — `NotQueried`, `NotConfigured` or `NotMaterialized`.
- `GOVERNANCE_SOURCE_REFUSED` — `UnsupportedFormat` or `InvalidStoredProjection`.
- `ATTEMPTS_UNINSTRUMENTED` — no observation persistence configured.
- `HISTORICAL_CONFIGURATION_NOT_RECORDED` — no configuration identity recorded for the run.
- `EXECUTION_NOT_ESTABLISHED` — decisions were recorded but no attempt was observed.
- `USAGE_UNKNOWN` — attempts recorded, none reported token counts.
- `CORRELATION_UNRESOLVED` — both sides answered, nothing beyond the run identity links them.

`status` and `limitations` are computed properties, not fields: a result cannot say `SUPPORTED` while
carrying an uninstrumented history, because there is no stored classification to disagree with the
evidence.

## Invariants and the test that holds each

| Invariant | Test |
|---|---|
| Complete evidence is supported | `complete evidence supports the declared scope` |
| A missing historical configuration is only partial | `a missing historical configuration can only be partial` |
| An empty `Recorded` cannot pass for evidence | `an empty recorded configuration is refused instead of passing for evidence` |
| Missing governance evidence is partial, not complete | `missing governance evidence stays partial rather than complete` |
| Nothing observed is unsupported, whatever the configuration says | `nothing observed is unsupported and a recorded configuration does not fix that` |
| A refused projection is classified, never converted | `a refused projection is classified and never converted into a snapshot` |
| Uninstrumented is not zero attempts; empty is not uninstrumented | `uninstrumented history is never reported as zero attempts` |
| A recorded `ALLOW` is not proof of execution | `a recorded ALLOW is not proof of execution` |
| Observations stay telemetry, decisions stay authoritative | `observed attempts never become authoritative and decisions never become telemetry` |
| Unknown usage is named, not zeroed | `unknown usage is named rather than zeroed` |
| Shared run identity alone leaves correlation unresolved | `shared run identity alone leaves correlation unresolved` |
| Evidence for another run is refused | `evidence for another run is refused rather than reconstructed` |
| The classification is deterministic | `the classification is derived from the evidence and repeats identically` |
| No dedicated payload field exists | `no exposed type has a dedicated payload field` |
| Blank references are refused | `a blank policy version is refused instead of passing for a reference`, `a blank workflow digest is refused instead of passing for a reference` |
| A caller-supplied reference is not provenance-checked | `a caller-supplied reference is accepted without any provenance check` |

Discrimination recorded (mutants applied to the implementation, then restored). Each mutant fails the
test that names the rule, and only that class of test:

```
UNSUPPORTED -> SUPPORTED for "neither source answered"
  -> nothing observed is unsupported and a recorded configuration does not fix that   FAILS
configuration no longer required for SUPPORTED
  -> a missing historical configuration can only be partial                          FAILS
EXECUTION_NOT_ESTABLISHED no longer raised when decisions were recorded
  -> a recorded ALLOW is not proof of execution                                      FAILS
```

## Verification

| Check | Result |
|---|---|
| `:tramai-security:test --tests 'dev.tramai.security.reconstruction.*'` | 14 tests, 0 failures |
| `:tramai-security:test` (whole module) | see the report at the head that opened the PR |
| `spotlessCheck` | PASSED |
| `verifyStaticAnalysis` | PASSED, 0 new findings |
| `verifyChangePolicy` | PASSED |
| `apiCheck :tramai-security` | PASSED (additive: api dump + hash-bound migration entry) |
| `verify060Architecture` | PASSED |
| `verifyCancellationSafety` | PASSED (no new throwing path outside the existing vocabulary) |
| `verifyJUnitTestSignatures` | PASSED |
| `verifyCompilerWarnings` | PASSED (gate green) |

## Trust boundary of the historical reference

`HistoricalConfigurationReference.Recorded` validates shape only: present, non-blank, at least one
reference. It validates **nothing** about provenance — it cannot distinguish a reference read from the
run's own persisted evidence from one taken out of the current configuration. Consequently:

**0.7.5e must obtain these references from persisted historical evidence, and never from current
configuration.** Reading a `Recorded` value as proof of provenance is precisely the substitution this
type exists to prevent.

The test `a caller-supplied reference is accepted without any provenance check` pins that boundary so
no later reader can mistake the contract for a verifier.

## Deliberately not here

- **No reconstruction engine**: no `Reconstructor`, no reader that walks stores, no report assembly.
  That is 0.7.5e, and it needs this vocabulary first.
- **No decision-token parsing.** `EXECUTION_NOT_ESTABLISHED` is raised for *any* recorded decision when
  no attempt was observed, not just for `ALLOW`. That is broader than the requirement and avoids
  duplicating the 0.7.4 token vocabulary here.
- **No "executed" flag.** The contract has no field that could answer "did it run" from a decision; the
  question is answered only by observed attempts.
- **No current-configuration fallback.** The type has no source for "the configuration now", so the
  substitution the brief forbids is not expressible.
- **No retention, paging, cross-run aggregates, HTTP surface or analytics.**
- **Notes on inherited gaps:** `finishReasonReported` stays `false` on every attempt (0.7.5b); a
  default Spring application records no attempts, so its reconstruction truthfully reports
  `ATTEMPTS_UNINSTRUMENTED` rather than an empty history (0.7.5b limitation, unchanged here).
- **Carried correction:** the redundant `sortedBy` in `SemanticTimeline.attempts()` was reported
  removed in the 0.7.5c disposition but had not actually landed in `f5af5260`. It is removed here, and
  the correction is recorded on PR #546.
