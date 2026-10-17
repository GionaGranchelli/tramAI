# TASK 0.7.2 — Review Findings Follow-Up

**Epic:** [EPIC-0.7.2-POLICY-TRUST-ZONES.md](EPIC-0.7.2-POLICY-TRUST-ZONES.md)  
**Branch:** `task/0.7.2-review-findings` → `epic/0.7.2-policy-trust-zones`  
**Status:** Implemented

## Why this exists

Copilot's review workflow reports `success` whenever the reviewer runs, which is not the same as "no findings". Its findings were read after the fact, across the five 0.7.2 PRs, and two merged slices plus the open one needed correction. This documents each finding and where it was addressed, so the response is attributable rather than silent.

## Findings

### #485 — 0.7.2c1 (merged), twice

1. **`ClassificationRanking.kt`** — naming the rank constants to satisfy the magic-number rule inserted four `private const val` declarations between the KDoc and `DataClassification.rank`, so the KDoc attached to `RANK_PUBLIC` and the public property lost its documentation.
   **Fixed here:** the constants now sit **below** the property they rank, with a comment saying why, so a future edit does not move them back. A gate fix must not cost the API its documentation.
2. **`WorkloadGovernanceResolverTest.kt:205`** — `assertTrue(result.classification == DataClassification.PUBLIC)` where the file's convention is `assertEquals`, which also yields a less informative failure.
   **Fixed here:** `assertEquals(DataClassification.PUBLIC, result.classification)`.

### #487 — 0.7.2b (merged), once

3. **`ProviderDeploymentTest`** — `TrustZoneName` enforces three rules (non-blank, trimmed, no control characters) but the test exercised only the first two. The control-character rejection was documented and unproven.
   **Fixed here:** a BEL case was added. BEL is not whitespace, so `trim()` cannot catch it and the case isolates the control-character rule from the other two.

### #490 — 0.7.2e (open), twice

4. **`EffectiveRoutingPolicy.kt` and the task doc** — both attributed the `allowedFallbackZones ⊆ allowedZones` requirement to `ClassificationRoutingRule` and claimed "the constructor would reject a violation". It is a plain `data class` with no validation; the `require(...)` lives in `ProviderRoutingConfiguration.init`, which validates *configured* rules.
   **Fixed in #490:** the comments now state what is true — composition never *introduces* the violation, because intersection preserves the relation, and composition does not validate its inputs. A new test pins it: a rule whose fallback set exceeds its allowed set composes through unchanged, which also proves the type has no validation, since the construction would throw otherwise.

### #488 and #489 — no findings

#488: none, "approval recommended". #489: none, "changes recommended" only because the reviewer cannot verify the SHA ledger from its environment; those hashes are verified programmatically against `sha256sum` and against the previous entry's `toSha256`.

## Common cause

Two of the three code findings share one shape: **an assertion or a comment that was true of the outcome but wrong about the mechanism.** `assertTrue(x == y)` does pass when equal; the documentation claim did describe a real guarantee. Both were weaker than the thing they stood in for, and both were invisible while every gate was green.

## Verification

```
ProviderDeploymentTest            11 tests, 0 failures
WorkloadGovernanceResolverTest    15 tests, 0 failures
TrustZonePolicyTest                8 tests, 0 failures
ProviderInputReleaseTest           7 tests, 0 failures
ExecutionSecurityContextTest       5 tests, 0 failures
```

41 tests across the four governance suites on this branch. `ProviderDeploymentTest` stays at 11: the control-character case was added to an existing test rather than as a new one. `ExecutionSecurityContextTest` lives in `tramai-engine` and was run because it exercises the ranking this slice moves; the move is comment-only and the suite confirms behaviour is unchanged.

`EffectiveRoutingPolicyTest` (10 tests) is **not** listed here: the 0.7.2e slice that owns it is still unmerged in #490, so the file does not exist on this branch. It was measured on that branch, and is recorded there.

Plus the standard gates at the committed revision: `spotlessCheck`, `verifyStaticAnalysis`, `verify060Architecture` and `verifyChangePolicy`.

## Boundaries

No production behaviour changes. The two merged-PR fixes are a comment relocation and test-only edits: one added test case, three assertion-style corrections, one import. The `ClassificationRanking` edit moves declarations without changing them — the compiled behaviour and the API dump are unchanged, which `verify060Architecture` confirms.
