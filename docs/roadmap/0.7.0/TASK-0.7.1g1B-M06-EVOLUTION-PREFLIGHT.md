# TASK-0.7.1g1B — M06 Evolution Preflight

Stop-and-report compatibility gate for 0.7.1g1B *Mutation Authority Evolution*. No mutation
authority was modified: this document is the only artifact created.

## 1. Base, scope, and a defect in the base SHA as stated

| Item | Value |
|---|---|
| Repository | `GionaGranchelli/tramAI` |
| Base branch | `epic/0.7.1-control-plane-authority` |
| Base SHA as given in the task | `e38ce9f9dbb3519947ffe692932c934eb3f17` (37 characters) |
| Base SHA actually resolved on the Epic branch | `e38ce9f9dbb3519947ffe3ea692932c934eb3f17` (40 characters) |
| Task branch created from it | `task/0.7.1g1b-mutation-authority-evolution` |
| Epic tip subject | `docs(0.7.1g1a): canonical mutation population drift audit (#430)` |

**The SHA in the task description is not a valid git object.** It has 37 characters, and
`git rev-parse e38ce9f9dbb3519947ffe692932c934eb3f17^{commit}` resolves to nothing. The Epic branch
resolves to the 40-character SHA above. The two strings are identical except that the resolvable one
carries three extra characters (`3ea`) after the 20-character prefix:

```
as given:  e38ce9f9dbb3519947ffe 692932c934eb3f17
resolved:  e38ce9f9dbb3519947ffe 3ea692932c934eb3f17
```

The base has **not moved**: the branch tip is the audit merge commit from the previous stage of this
same workstream. I proceeded on the reading that the task means the current Epic head, and I state it
here because a SHA is the one thing in a task like this that must not be repaired silently. If the
intent was a different commit, every conclusion below is void. I did not substitute a different SHA:
the branch was created from the resolved tip, and that tip is what every artifact was read at.

Verification of the preconditions:

```
$ git fetch origin && git rev-parse origin/epic/0.7.1-control-plane-authority
e38ce9f9dbb3519947ffe3ea692932c934eb3f17
$ git status --porcelain            # empty
$ git checkout -b task/0.7.1g1b-mutation-authority-evolution e38ce9f9dbb3519947ffe3ea692932c934eb3f17
$ git rev-parse HEAD                # e38ce9f9dbb3519947ffe3ea692932c934eb3f17
```

### 1.1 Sources of truth used

- `config/quality/mutation-baseline.json` at the base SHA (2384 rows) — the base authority.
- `/tmp/tramai-071g1a-population-A.json` and `/tmp/tramai-071g1a-population-B.json` — the raw canonical
  populations from the g1A campaigns (each 2544 rows). Both present; no campaign was re-run.
- `docs/roadmap/0.7.0/TASK-0.7.1g1A-MUTATION-POPULATION-DRIFT-AUDIT.md` — the merged audit, used only
  for cross-checking, never as the partition input.
- `MutationRatchetVerifier.kt`, `MutationPopulationEvolution.kt`, `MutationRatchetAuthority.kt`,
  `MutationIdentity.kt`, `.github/workflows/ci.yml` at the base SHA.

## 2. Current verifier semantics (Phase 1)

Read from source at the base SHA. Line references are to
`build-logic/src/main/kotlin/dev/tramai/build/quality/MutationRatchetVerifier.kt` unless stated.

### 2.1 `RECORDED_EVOLUTION` changes M21 handling only

- `MutationPopulationEvolution.kt:11-31` — the enum and its entry point: property
  `tramaiMutationEvolution`, and `fromProperty` maps `""`/`forbid` to `FORBID`, `recorded-evolution` to
  `RECORDED_EVOLUTION`, and **throws** on any other non-blank value. Absent means `FORBID`.
- `MutationRatchetVerifier.kt:69` — `evolution: MutationPopulationEvolution = MutationPopulationEvolution.FORBID`.
- `MutationRatchetVerifier.kt:319-324` — M21 is delegated to `populationEvolutionDiagnostics(...)`, which
  is the only place the evolution mode is consulted for removals.
- `MutationRatchetVerifier.kt:893-947` — for each `baseById.keys - candidateIds`: `FORBID` emits a
  **failure** (L914-925); `RECORDED_EVOLUTION` with no record for that identity emits a **failure**
  (L927-935); `RECORDED_EVOLUTION` with a record emits a **warning** (L937-944). So recorded evolution
  converts exactly one class of diagnostic — M21 disappearance — from failure to warning.
- `MutationRatchetVerifier.kt:950-996` — even then it is conditioned: a proof from an exact fresh
  measurement must exist and match the committed candidate (L958-972), every record must name an identity
  that actually disappears in this transition (L973-983), and every record's `fromBaseSha` must equal the
  transition base (L984-993).

### 2.2 `outcomeRatchet` still applies M06 to every candidate-only NON_KILLED identity

`MutationRatchetVerifier.kt:299-313`:

```kotlin
for (id in candidateIds - baseIds) {
    val candidate = candidateById.getValue(id)
    if (candidate.outcome == NON_KILLED) {
        diagnostics += VerificationDiagnostic.failure(
            DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR,
            "M06: ${describe(candidate)} (${short(id)}) is a NEW NON_KILLED identity absent from the " +
                "base authority. New mutants must be killed; a PR cannot certify its own survivors.",
            findingId = id, modulePath = candidate.module)
    }
}
```

The set is exactly `candidateIds - baseIds` with `outcome == NON_KILLED` — no family filter, no module
filter, no budget, no score threshold. `outcomeRatchet` receives the evolution context
(`MutationRatchetVerifier.kt:262-266`) but passes it only to `populationEvolutionDiagnostics` at L320;
the M06 loop itself never reads it. `AUTHORITY_EXCLUDED_IDENTITIES` — the only hard-coded exemption set
(`MutationRatchetVerifier.kt:530-533`, two identities) — is consulted for M01 at L278 and for M21 at
L905/L973, never for M06.

### 2.3 No record, label, or property exempts M06

`MutationEvolutionRecord` (`MutationPopulationEvolution.kt:34-42`) carries `id`, `fromBaseSha`, `reason`,
`issue`, `targetPhase`, `authorizedAt`, `authorizedBy`. `recordsById` is built at
`MutationRatchetVerifier.kt:902` and read only inside `populationEvolutionDiagnostics` (L906) and
`evolutionEvidenceDiagnostics` (L974/L984). No field of a record is read anywhere in the M06 path, and
no record shape includes anything but an identity — a record cannot describe a rekey, an inheritance, or
a replacement.

### 2.4 Candidate-added classifications cannot be a shortcut

`MutationRatchetVerifier.kt:376-430`: **every** classification a candidate adds is a failure.
`addedClassificationDiagnostic` (L400-430) returns M09 (fabricated, no mutant anywhere), M08 (mutant
absent from the base authority — explicitly *"A PR cannot approve its own new survivors"*), or M09
(mutant exists in base but was never classified). Retained classifications must stay byte-identical
(M03, L361-372); removing one while the survivor remains is M11 (L384-394). There is no path in which a
candidate's own classification record resolves an M06 finding: the added set is computed from the same
`candidateIds` that M06 rejects.

### 2.5 CI label wiring

`.github/workflows/ci.yml:215-221` — the pull-request gate passes exactly one property:

```yaml
./gradlew verifyMutationRatchet \
  -PtramaiMutationEvolution=${{ contains(github.event.pull_request.labels.*.name, 'mutation-population-evolution') && 'recorded-evolution' || 'forbid' }} \
  -PtramaiMutationBaseSha="${{ github.event.pull_request.base.sha }}"
```

The label only selects `recorded-evolution` over `forbid`; it cannot express anything else, and
`.github/workflows/ci.yml:223-242` (push path) auto-selects `recorded-evolution` only when the pushed
range changed both `config/quality/mutation-baseline.json` and `config/quality/mutation-evolution.yml`.

**Phase 1 conclusion:** recorded evolution is a strictly M21-scoped affordance. M06 is unconditional,
independent of evolution mode, and cannot be discharged by an evolution record or a candidate
classification.

## 3. A/B raw-artifact equality (Phase 2 gate)

| Check | A | B | Equal |
|---|---|---|---|
| rows | 2544 | 2544 | yes |
| sha256 (bytes) | `06549e8a21e199b3f4c94fed…` | `06549e8a21e199b3f4c94fed…` | True |
| canonical projection sha256 | `ced818dd63cd5050dd5a219f…` | `ced818dd63cd5050dd5a219f…` | True |
| full row-object equality | — | — | True |

Identity self-consistency (SHA-256 recomputed over the eight serialized fields per
`MutationIdentity.kt:26-41`, including `block`/`index`) was re-derived for every row:

| Population | rows | rows whose stored identity ≠ recomputed identity |
|---|---|---|
| committed authority | 2384 | 0 |
| Campaign A | 2544 | 0 |
| Campaign B | 2544 | 0 |

No duplicate identities were found in any population (the analysis fails closed on duplicates). A and B
are byte-identical, so the descriptor-aware partition is computed once and holds for both.

## 4. The exact M06 set

Computed from the raw JSON only (identity sets, no Markdown tables):

| Quantity | Value | Matches stated expectation |
|---|---|---|
| committed authority total | 2384 | yes (2384) |
| fresh population total | 2544 | yes (2544) |
| shared identities | 2195 | yes (2195) |
| base-only identities (M21 set) | 189 | yes (189) |
| candidate-only identities | 349 | yes (349) |
| candidate-only KILLED | 154 | — |
| **candidate-only NON_KILLED (M06 set)** | **195** | **yes (195)** |
| candidate-only with any other outcome | 0 | — |

- sha256 over the sorted 195 identities: `1b066deb78c9a1a7fe25fc5a0e7813867102732f7ef575b18673bce6409cbcf0`
- sha256 over the sorted 189 base-only identities: `8caaed09b1d660c0d5604465f8b3ea794f7d1ba086d158022ad0eb4526140d66`

M06 total by raw PIT status:

| raw status | count |
|---|---|
| NO_COVERAGE | 109 |
| SURVIVED | 40 |
| TIMED_OUT | 46 |

### 3.1 Artifact validity across the audited SHA → current base

The A/B campaigns were measured at `0f36ab87…`; this preflight reasons about the Epic tip `e38ce9f9…`. Three
commits landed in between, so the measurements are inherited evidence only if none of the inputs that
determine a mutation population changed. That is verified here rather than assumed.

```
$ git diff --name-only 0f36ab8744c14ebbdb3b10964311d0416e012ab2 e38ce9f9dbb3519947ffe3ea692932c934eb3f17
build-logic/src/main/kotlin/dev/tramai/build/docs/DocsContractVerifierTask.kt
build-logic/src/main/kotlin/dev/tramai/build/docs/PromotedReleaseVerifier.kt
build-logic/src/main/kotlin/dev/tramai/build/docs/VersionAlignmentVerifier.kt
build-logic/src/test/kotlin/dev/tramai/build/docs/TramaiDocsGuardsPluginTest.kt
build-logic/src/test/kotlin/dev/tramai/build/quality/CanonicalProbeFunctionalTest.kt
build-logic/src/test/kotlin/dev/tramai/build/quality/ResidualQualityVerifierTasksTest.kt
docs/roadmap/0.7.0/TASK-0.7.1g1A-MUTATION-POPULATION-DRIFT-AUDIT.md
tramai-orchestration/src/test/kotlin/dev/tramai/orchestration/WorkflowMcpStepTest.kt
```

Eight files. Four checks decide whether they can move a measurement:

1. **No mutation-target input changed.** `config/quality/test-quality.yml:19-77` configures seven families
   over five modules — `:tramai-engine`, `:tramai-security`, `:tramai-sovereign`, `:tramai-core`,
   `:tramai-structured`. Filtering the diff above by those five module prefixes returns **nothing**. The
   only test change in the range is `WorkflowMcpStepTest.kt` (#432, the MCP fixture teardown fix), and
   `:tramai-orchestration` is not a configured mutation target module: it contributes neither a target
   class nor a test input to any family's PIT run. The `routing`, `retry` and `tools` families additionally
   restrict themselves with `targetTests` (`:40-44`, `:52-58`, `:65-68`); none of those test classes is in
   the diff either.
2. **The analyzer, configuration and authority are byte-identical.** `git diff --exit-code` over
   `config/quality/test-quality.yml`, `config/quality/mutation-baseline.json`,
   `config/quality/mutation-classifications.yml`, `config/quality/mutation-evolution.yml` and
   `build-logic/src/main/kotlin/dev/tramai/build/quality` exits **0 with empty output**. The one
   build-logic package the range does change is `dev.tramai.build.docs` (release/version doc guards), and
   no file in the mutation path under `build/quality` references it — `git grep dev.tramai.build.docs --
   build-logic/src/main/kotlin/dev/tramai/build/quality` returns nothing.
3. **Analyzer semantics, identity schema and topology all match**, measured vs committed: `pluginVersion`
   1.19.0, `engineVersion` 1.22.1, the same eleven mutators, `timeoutConst` 4000, `timeoutFactor` 1.25,
   `identitySchemaVersion` 2, and identical family→module topology.
4. **The delta is confined to the two families the Epic work touched.** Family totals, committed → fresh:
   `approval` 768 → 918, `evidence` 798 → 808, and `policy`, `retry`, `routing`, `structuredOutput`, `tools`
   unchanged. That is exactly the growth the g1A audit attributed to `SOURCE_REFACTORED`
   (`ApprovalResumeCoordinator`, `ApprovalSuspensionCoordinator`, `DefaultApprovalGateway`,
   `RuntimeEvidenceContractValidator`) — not a measurement artifact.

Recorded caveat, not a defect: the committed authority's `measuredCommit` is `5856530e…`, older than the
audited `0f36ab87…`. That gap is the pre-existing drift the g1A audit exists to measure; it is why the fresh
population is 2544 rather than 2384. The claim made here is narrower and is what #433 needs: **the 2544-row
A/B population measured at `0f36ab87…` is valid evidence at `e38ce9f9…`, because no configured mutation
target's production source, tests, analyzer, or configuration changed between them.** A test change inside a
target module *would* have invalidated it; none exists in this range.

No fresh campaign is required for this conclusion, and none was run.

## 5. Descriptor-aware source-point partition (Phase 3)

### 5.1 Key definition

```
source-level key = (module, className, method, methodDescription, mutator, description)
block and index are ignored
```

Classification rule, per candidate-only NON_KILLED identity: **A** if base-only identities exist at that
key and every one of them is NON_KILLED; **B** if base-only identities exist at that key and they contain
both KILLED and NON_KILLED; **C** if no base-only identity carries that key.

### 5.2 Result

| Category | Meaning | Total |
|---|---|---|
| A | UNAMBIGUOUS_INHERITED_NON_KILLED | 37 |
| B | AMBIGUOUS_INHERITED (mixed predecessor outcomes) | 51 |
| C | GENUINELY_NEW_NON_KILLED | 107 |
| | **total** | **195** |

| Set | sha256 of sorted identities |
|---|---|
| A | `e6c6868d802db9f75327d7733a97358ca871296244e716f78da761858f35e788` |
| B | `93cdb79cd5f91fa0779bc03ea8fb5ea3c397fc0f6ab59a968220834ce68b81ff` |
| C | `e2db016d87cad1d8eb8cadd84c671f99871e88b640bbe96018ccfa47ec04baf9` |

The audit Markdown suggested A≈37, B≈51, C≈107 (A+B=88). The raw JSON gives exactly that, so the
audit's approximation is confirmed rather than superseded — but the numbers above are the authoritative
ones, and every claim in this document rests on them.

### 5.3 Per-category summaries

#### Category A — 37 identities

| raw status | count |
|---|---|
| NO_COVERAGE | 30 |
| SURVIVED | 4 |
| TIMED_OUT | 3 |

| family | count | | module | count |
|---|---|---|---|---|
| approval | 37 | | :tramai-engine | 37 |

| class | count |
|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator` | 16 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | 15 |
| `dev.tramai.engine.approval.DefaultApprovalGateway` | 6 |

| class#method | count |
|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#executeClaimedResume` | 6 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#resume` | 9 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#revealAndValidateReplayPayload` | 1 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#suspendToolExecution` | 14 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#validateRenewedApprovalRequirement` | 1 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#requestApproval-Atj0Sqo` | 6 |

#### Category B — 51 identities

| raw status | count |
|---|---|
| NO_COVERAGE | 26 |
| SURVIVED | 4 |
| TIMED_OUT | 21 |

| family | count | | module | count |
|---|---|---|---|---|
| approval | 40 | | :tramai-engine | 40 |
| evidence | 11 | | :tramai-security | 11 |

| class | count |
|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator` | 22 |
| `dev.tramai.engine.approval.DefaultApprovalGateway` | 12 |
| `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | 11 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | 6 |

| class#method | count |
|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#executeClaimedResume` | 3 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#resume` | 17 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#revealAndValidateReplayPayload` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#suspendToolExecution` | 6 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#requestApproval-Atj0Sqo` | 12 |
| `dev.tramai.security.evidence.RuntimeEvidenceContractValidator#validateMetadata` | 11 |

#### Category C — 107 identities

| raw status | count |
|---|---|
| NO_COVERAGE | 53 |
| SURVIVED | 32 |
| TIMED_OUT | 22 |

| family | count | | module | count |
|---|---|---|---|---|
| approval | 105 | | :tramai-engine | 105 |
| evidence | 2 | | :tramai-security | 2 |

| class | count |
|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator` | 29 |
| `dev.tramai.engine.approval.DefaultApprovalGateway` | 28 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | 23 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1` | 5 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2` | 5 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3` | 5 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2` | 4 |
| `dev.tramai.engine.approval.GovernedApprovalStoreKt` | 3 |
| `dev.tramai.engine.approval.ApprovalRunAttributionKt` | 2 |
| `dev.tramai.engine.approval.GovernedContinuationContinuityKt` | 1 |
| `dev.tramai.security.evidence.RuntimeEvidenceAttribution` | 1 |
| `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | 1 |

| class#method | count |
|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#authorizeResume` | 13 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#prepareResume` | 16 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2#invokeSuspend` | 4 |
| `dev.tramai.engine.approval.ApprovalRunAttributionKt#decodeApprovalAttribution` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#compensateStep` | 6 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#compensateSuspension` | 11 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#persistSuspendedInvocation` | 6 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1#invokeSuspend` | 5 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2#invokeSuspend` | 5 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3#invokeSuspend` | 5 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#persistGoverned` | 11 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#persistUngoverned` | 11 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#requireExistingAttributionMatches` | 6 |
| `dev.tramai.engine.approval.GovernedApprovalStoreKt#requireAttributionMatchesBinding` | 3 |
| `dev.tramai.engine.approval.GovernedContinuationContinuityKt#requireGovernedContinuity` | 1 |
| `dev.tramai.security.evidence.RuntimeEvidenceAttribution#getMetadataKeys` | 1 |
| `dev.tramai.security.evidence.RuntimeEvidenceContractValidator#validateMetadata` | 1 |

### 5.4 The key is not an identity: fan-in and fan-out

This is the finding that decides Phase 4. Descriptor keys are not a bijection between old and new
identities. Over the 195 M06 identities:

| old identities at key | new identities at key | keys |
|---|---|---|
| 0 | 1 | 11 |
| 0 | 2 | 12 |
| 0 | 3 | 5 |
| 0 | 4 | 9 |
| 0 | 5 | 3 |
| 0 | 6 | 1 |
| 1 | 1 | **5** |
| 2 | 1 | 2 |
| 3 | 3 | 2 |
| 4 | 3 | 2 |
| 5 | 6 | 1 |
| 6 | 6 | 1 |
| 7 | 6 | 3 |
| 12 | 6 | 1 |
| 13 | 8 | 1 |
| 14 | 8 | 1 |
| 21 | 9 | 1 |
| 37 | 8 | 1 |

- Base-only descriptor keys: 33. Candidate-only NON_KILLED descriptor keys: 62. Keys present on both
  sides: 21.
- **Only 5 of the 195 new identities are a clean one-old-to-one-new rekey.**
- 83 of the 195 sit at a key occupied by two or more base-only identities; the extreme case is a single
  descriptor key with 37 base-only identities collapsing onto 8 new ones.
- 44 keys carry more than one new identity, so a key can expand as well as collapse.

Category B is unanimous in the way that matters: all 51 of its identities have predecessors containing
**both** a KILLED and a NON_KILLED outcome at the same descriptor key. Every B predecessor group is
mixed. That is a property of the data, not of a threshold: there is no B identity whose key could be
read as unambiguously inherited.

### 5.5 Cross-check: does the enclosing method exist in the base authority?

Independent of the descriptor key, and computed against the *whole* base population (not just the
base-only subset):

| Category | new identities whose (module, className, method) the base authority already measured |
|---|---|
| A | 37 / 37 |
| B | 51 / 51 |
| C | 1 / 107 |

106 of the 107 category-C identities live in methods the authority never measured at all — 16 distinct
methods, including `ApprovalResumeCoordinator#authorizeResume`, `#prepareResume`, four
`invokeSuspend` synthetic-lambda bodies, `ApprovalSuspensionCoordinator#compensateSuspension`,
`#compensateStep`, `#persistSuspendedInvocation`, `DefaultApprovalGateway#persistGoverned`,
`#persistUngoverned`, `#requireExistingAttributionMatches`, and `ApprovalRunAttributionKt#decodeApprovalAttribution`.
Category C is therefore new source, not a shifted coordinate — the descriptor key and the method-level
check agree there.

For A and B the two readings disagree in the way that makes inheritance undecidable from the descriptor
key alone: the enclosing methods were measured before, but the identities are new and the key that would
justify inheritance is shared with up to 36 other old identities, including killed ones.

### 5.6 Category A against the whole base authority, not just the disappearing rows

Category A is defined over *base-only* predecessors — the identities that disappeared. That is the task's
definition, and it leaves a question open that matters for any future lineage design: a key could carry a
**retained** base identity with a KILLED outcome and still look "unambiguous" under the disappearing-only
rule, because a retained row is not a base-only row. So the check was repeated against all 2384
base-authority identities.

Result: **all 11 keys are uniform NON_KILLED across the whole authority**, covering 51 base-authority
identities (43 that disappeared plus 8 retained siblings), and every one of the 37 category-A identities
sits at such a key.

| # | class#method | mutator | description | base-only (disappearing) | whole authority at key | outcomes | raw statuses | uniform NON_KILLED |
|---|---|---|---|---|---|---|---|---|
| 1 | `ApprovalResumeCoordinator#executeClaimedResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 3 | 5 | NON_KILLED=5 | NO_COVERAGE=4 / SURVIVED=1 | yes |
| 2 | `ApprovalSuspensionCoordinator#suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 8 | NON_KILLED=8 | NO_COVERAGE=7 / SURVIVED=1 | yes |
| 3 | `ApprovalSuspensionCoordinator#suspendToolExecution` | `NullReturnValsMutator` | replaced return value with null | 7 | 7 | NON_KILLED=7 | NO_COVERAGE=7 | yes |
| 4 | `ApprovalResumeCoordinator#resume` | `VoidMethodCallMutator` | removed call to `CancellationKt::rethrowIfCancellation` | 1 | 1 | NON_KILLED=1 | SURVIVED=1 | yes |
| 5 | `ApprovalSuspensionCoordinator#suspendToolExecution` | `VoidMethodCallMutator` | removed call to `Intrinsics::checkNotNull` | 1 | 1 | NON_KILLED=1 | SURVIVED=1 | yes |
| 6 | `ApprovalResumeCoordinator#resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 13 | 14 | NON_KILLED=14 | NO_COVERAGE=13 / SURVIVED=1 | yes |
| 7 | `DefaultApprovalGateway#requestApproval-Atj0Sqo` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 5 | 6 | NON_KILLED=6 | NO_COVERAGE=5 / SURVIVED=1 | yes |
| 8 | `ApprovalResumeCoordinator#executeClaimedResume` | `NegateConditionalsMutator` | negated conditional | 3 | 4 | NON_KILLED=4 | TIMED_OUT=4 | yes |
| 9 | `ApprovalResumeCoordinator#revealAndValidateReplayPayload` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 1 | 3 | NON_KILLED=3 | NO_COVERAGE=2 / SURVIVED=1 | yes |
| 10 | `ApprovalSuspensionCoordinator#validateRenewedApprovalRequirement` | `ConditionalsBoundaryMutator` | changed conditional boundary | 1 | 1 | NON_KILLED=1 | SURVIVED=1 | yes |
| 11 | `ApprovalSuspensionCoordinator#suspendToolExecution` | `VoidMethodCallMutator` | removed call to `Intrinsics::checkNotNullExpressionValue` | 1 | 1 | NON_KILLED=1 | SURVIVED=1 | yes |

The 11 descriptor keys in full:

```
1.  :tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure
2.  :tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure
3.  :tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution
4.  :tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to dev/tramai/core/coroutines/CancellationKt::rethrowIfCancellation
5.  :tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/jvm/internal/Intrinsics::checkNotNull
6.  :tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure
7.  :tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure
8.  :tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional
9.  :tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|revealAndValidateReplayPayload|(Ldev/tramai/engine/approval/ResumeUncertainOutcome;Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure
10. :tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|validateRenewedApprovalRequirement|(Ldev/tramai/engine/tool/ToolExecutionRequest;Ldev/tramai/core/policy/PolicyDecision$RequireApproval;Ljava/lang/String;)V|org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator|changed conditional boundary
11. :tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue
```

So the name `UNAMBIGUOUS_INHERITED_NON_KILLED` survives the stronger test and does not need weakening: at
every category-A key, *no* base-authority identity — disappearing or retained — is KILLED. The word
"unambiguous" is scoped to one thing only: the outcomes at that key. It is **not** a claim of lineage, and
Section 9.1 shows why the key still cannot carry one. Categories A and B remain separated by exactly the
property that matters: B's keys carry KILLED predecessors, A's do not.

## 6. Complete inventories

### 6.1 Base-only predecessor groups by descriptor key (all 189 identities)

Every base-only identity, grouped by the descriptor key it occupies. The categories in 6.2–6.4 reference
these keys.

**P1** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 3 base-only identities, uniform (NON_KILLED=3)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `949c8ad8dfd437397bb1938ed16ced78b35521bc1e81770a35d3f0b91a03a245` | NON_KILLED | TIMED_OUT | 19 | 159 |
| 2 | `062c0e53f177bc67178766900107469b2751614ddbbdc486618320ac6746b6d3` | NON_KILLED | TIMED_OUT | 39 | 313 |
| 3 | `dc0810d0cc145bf0e3745796d66a9175a03676369e161f328dc8cd87fb3512a7` | NON_KILLED | TIMED_OUT | 54 | 430 |

**P2** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` — 3 base-only identities, uniform (NON_KILLED=3)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `6371b23fe46996857beba836eb604bbad25d658c8b84c3be0ed90554c9b6f381` | NON_KILLED | NO_COVERAGE | 21 | 194 |
| 2 | `4e1e2df1c11eae41ce0876811804019e367164d932eb485d4eee2734d245f3e2` | NON_KILLED | NO_COVERAGE | 41 | 368 |
| 3 | `527a99cc5db7370b85b52164d27928486931a7f3c5247b7a67004fbf9f1ef5f9` | NON_KILLED | NO_COVERAGE | 56 | 489 |

**P3** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::executeClaimedResume` — 4 base-only identities, MIXED (KILLED=1, NON_KILLED=3)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `950b0b5ac5a964b4a53962a1e439caca8a7943474f1919be4eaf164159577778` | NON_KILLED | NO_COVERAGE | 20 | 163 |
| 2 | `be663ad795d6885d7609aca076dfc7e1502fe28e063cd7ff2c21d7f5d281f3e6` | NON_KILLED | NO_COVERAGE | 40 | 317 |
| 3 | `4948fdbaf262d7c72c4f3f2c6a29ed7219c62cb0f1d820a9d8f0be6a3e7e49c1` | NON_KILLED | NO_COVERAGE | 55 | 434 |
| 4 | `56b12a836fef73fbc3184febbffbd2824b0357c966758422fb2d7c042052dac8` | KILLED | KILLED | 58 | 496 |

**P4** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resolveAndValidateResumeTool|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Ldev/tramai/core/approval/ApprovalContinuation;)Ldev/tramai/core/model/ResolvedTool;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 5 base-only identities, uniform (KILLED=5)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `e3c33ca72888706f68d059899580e3275695bb1ed3ead44e0cc1469d7a5afd08` | KILLED | KILLED | 9 | 34 |
| 2 | `e21521614f27a6f3c0cce2315a108667da6ff1bac8d9a29bd085ef6e7b21a96e` | KILLED | KILLED | 17 | 58 |
| 3 | `057b79621cabf73bf2880c446d83ddf31daba59eaa5bfa4a6d128683f273c632` | KILLED | KILLED | 25 | 84 |
| 4 | `f5c7739fa85d69bddd1f37d79efba20426d4f721499383d5ff64006dc9c0606e` | KILLED | KILLED | 32 | 109 |
| 5 | `80aacc22c2e7948c37e5ba8474219e0aa24f18acef8e19daf7b3ff3575354763` | KILLED | KILLED | 39 | 134 |

**P5** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resolveAndValidateResumeTool|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Ldev/tramai/core/approval/ApprovalContinuation;)Ldev/tramai/core/model/ResolvedTool;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resolveAndValidateResumeTool` — 1 base-only identities, uniform (KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `6ced1d5fb8f4a1d8376837e4722f24b7032a4f1ddce619325992ad743c337a40` | KILLED | KILLED | 43 | 155 |

**P6** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 21 base-only identities, MIXED (KILLED=7, NON_KILLED=14)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `bd46cba9bba932b8ccd3c2559fb736144c4a0ec61c301c69cba3fd573f14a6be` | NON_KILLED | TIMED_OUT | 11 | 68 |
| 2 | `130b60fa7f074f598b0bf133ef3eb4107a6508e011a1abd4bf5fef96aea13c34` | KILLED | KILLED | 15 | 95 |
| 3 | `59bbf214663690ff40abf6695e8508b450b1ba104299445761c6dd3945e91102` | KILLED | KILLED | 17 | 101 |
| 4 | `85ae74cb2ffc3e0a9b39a47a55f090adbdc94804bfd3a14000e8a6e8dcd2f711` | NON_KILLED | TIMED_OUT | 24 | 134 |
| 5 | `2155f6a06a7837e9d35bc4d2318d244639ff8f6095564243149e0b8918557325` | KILLED | KILLED | 28 | 163 |
| 6 | `e200bda99ea856d765bdebe57b8fd5b0371f9d948c31eed3b4b3fb39000be406` | NON_KILLED | TIMED_OUT | 36 | 216 |
| 7 | `319df689ce2b58ca09f69673e63be5d46ca210a4306f5ca057d6780dacd6768c` | NON_KILLED | TIMED_OUT | 43 | 298 |
| 8 | `8c909e76359d54e15ed4f892d5fd3763402b9a2c967c4a38d8d0612954358dfc` | NON_KILLED | TIMED_OUT | 49 | 380 |
| 9 | `f43a1391235fa3af0911ecc49adf3d9556deb3c4d273a74c4d23cd2b9b001403` | KILLED | KILLED | 53 | 432 |
| 10 | `9e05d5b73798f8e90a50de3460dfb21a4fb853fec8bd1bededcbab6f42df19da` | NON_KILLED | NO_COVERAGE | 64 | 480 |
| 11 | `216bc036a8ad39df78010e2a47317c344aeed1eceb26a9f02baa86bd8efcd628` | KILLED | KILLED | 71 | 543 |
| 12 | `0c6a0f5da99ccc30abfb217561fae68d69dc66fbc9ecb83af70a25b4ccc4d554` | NON_KILLED | NO_COVERAGE | 81 | 588 |
| 13 | `cfa7fa1e346a7cec464cfb674699b00f1ad7bfc4be02815700f5656fd6f6148a` | KILLED | KILLED | 87 | 650 |
| 14 | `05abfbc4e98099ec67da3968eac4760f3d3e3d450951a03bed5a3c680ae61774` | NON_KILLED | TIMED_OUT | 93 | 695 |
| 15 | `bb7c63ab70f627db63573fb75cab2e3f899ae3eb1ba8cbbd8699c65b6d737b51` | NON_KILLED | TIMED_OUT | 106 | 796 |
| 16 | `a9e240548004e6069badf22c734284702c95dbc463696a949eadc578f8fab267` | NON_KILLED | TIMED_OUT | 121 | 927 |
| 17 | `e52fd6ae714d4313ce487f557d2a39b7423478038f3ffc1e7755eea81ccce667` | NON_KILLED | TIMED_OUT | 138 | 1064 |
| 18 | `692b201e3aa17a96282b2540feca325a10e9f599a825bf6646eab69a41bdfcc6` | NON_KILLED | SURVIVED | 146 | 1154 |
| 19 | `1fb81d5d3133a74314590ac5dca4d3e3c9d5648a7e33d3f64420ccf9a3676e55` | NON_KILLED | TIMED_OUT | 160 | 1214 |
| 20 | `995ddfdf0973289bd2aaae3c7ac6d5d193bddb254942c68a214532601f15ad38` | KILLED | KILLED | 170 | 1317 |
| 21 | `f2f3de5d9785c42b69876a9d63a22a7cbfcb84a7b2412ff6e16d65fcfc6db83d` | NON_KILLED | TIMED_OUT | 184 | 1377 |

**P7** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to dev/tramai/core/coroutines/CancellationKt::rethrowIfCancellation` — 1 base-only identities, uniform (NON_KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `0a1c3c1c58bbd3fbc81a661140302ed7924b88eabc3f4cb13ba994b81ec09691` | NON_KILLED | SURVIVED | 166 | 1305 |

**P8** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to dev/tramai/engine/approval/ReplayAuthorizationService::emitAuthorizationReplayed` — 1 base-only identities, uniform (KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `665f7e153ec5c9a5183074ecce43b947aa673ecc7ea783888fcebffe8a692533` | KILLED | KILLED | 98 | 751 |

**P9** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` — 13 base-only identities, uniform (NON_KILLED=13)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `82fe089c156d3cab018d2c369c1d9a579b3437dcb4f60ad52a00c2e7b5395fcd` | NON_KILLED | NO_COVERAGE | 13 | 85 |
| 2 | `546a615f4436ae3c4551bac83587debf19f41acb30f77ddf50ac1a032a908b39` | NON_KILLED | NO_COVERAGE | 26 | 156 |
| 3 | `ef0c447154578eb65b0eab3c9a5eb7d6dae5fdb9a973000c141659b3fc7fee12` | NON_KILLED | NO_COVERAGE | 38 | 248 |
| 4 | `1d9dc7a8c9b27ecacc1ce9a793dd858006fa2756f9af04459e7f0ee5344bb5e4` | NON_KILLED | NO_COVERAGE | 45 | 340 |
| 5 | `ac0be306eb26093e449960234cc23ae4360f2a9f24ce7755548d91c41a8cf0e2` | NON_KILLED | NO_COVERAGE | 51 | 422 |
| 6 | `30fe02f32b41cb86a7cc26fc2c9ca8daae09d0f876a1640261c3bcfe8b8d1985` | NON_KILLED | NO_COVERAGE | 66 | 527 |
| 7 | `c4c37ffff62b410b50781206c508dd2116afe304d0b6352711342bbdfb8a5b6a` | NON_KILLED | NO_COVERAGE | 83 | 635 |
| 8 | `ba6ebc3e7076aad611c1b8b8e1a2c8bb32fb8e9023083786e0cb9aa11b10683d` | NON_KILLED | NO_COVERAGE | 95 | 737 |
| 9 | `ec7d1ab598d682932fc840658003cc3de493213cb7f7bec10ef1e9fa2b57043f` | NON_KILLED | NO_COVERAGE | 108 | 843 |
| 10 | `ebf31b386086133eb9870daff2229bd15e1d1950dfade56d84ce97ccd2a1008f` | NON_KILLED | NO_COVERAGE | 123 | 991 |
| 11 | `f2d34adda4b651689c1314670d1c4c24f767f4e0a4e5f25c69d0f27519203a9c` | NON_KILLED | NO_COVERAGE | 140 | 1131 |
| 12 | `cf1441ce7d467171fecd593d5b3396dae9e746cca078d995de70022c95c6d024` | NON_KILLED | NO_COVERAGE | 162 | 1281 |
| 13 | `f05dac1eeae60233c8063f5e5d6d51936c4160d819cd58b1bc072e03793e685f` | NON_KILLED | NO_COVERAGE | 186 | 1444 |

**P10** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` — 14 base-only identities, MIXED (KILLED=1, NON_KILLED=13)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `083b220893a987e60174fe419f93fed1a68054923ea384effe2dc1fb5fd3fa1d` | NON_KILLED | NO_COVERAGE | 12 | 72 |
| 2 | `b7b2dfec2fa09b6eb182fea85d233cc87faac535cc65c56da85825d1a80b68c7` | NON_KILLED | NO_COVERAGE | 25 | 138 |
| 3 | `efacd2b1e0f485e273560d796700fa9827d03c93e51328b758068d34f4ace86a` | NON_KILLED | NO_COVERAGE | 37 | 220 |
| 4 | `c52b419c90e9aeaf63ea6a1d8c7cba7eea201ad5aca14a2fad28107c219f3de9` | NON_KILLED | NO_COVERAGE | 44 | 302 |
| 5 | `3555704187bd3a2f436216918a2c486828d59193985ee03b9de3f58638a3d9d7` | NON_KILLED | NO_COVERAGE | 50 | 384 |
| 6 | `246350bcbd765bab30f01bd098a4ac4343dca32a12b62313fb86886269547f11` | NON_KILLED | NO_COVERAGE | 65 | 484 |
| 7 | `976f65569f36d2e3edd8a235b9ff9ebfaaa571be0c3acd960b1df205ef0b7197` | NON_KILLED | NO_COVERAGE | 82 | 592 |
| 8 | `32df7c11c787780f1f4858c17032291bca4c707c06e677736a5d1b7f47231811` | NON_KILLED | NO_COVERAGE | 94 | 699 |
| 9 | `0e8a40e624e101b8387b3e5657c69280b6b6b5c5f9c395a1129aed7c23fb8dd5` | NON_KILLED | NO_COVERAGE | 107 | 800 |
| 10 | `b1e34f861946bca679216e4b049070608e98c66fab5f766f229d3c3671b640bb` | NON_KILLED | NO_COVERAGE | 122 | 931 |
| 11 | `319b7bee9207f52fc24858a6c755ed014c79ab39a252c7c3292f1e8799c49d3a` | NON_KILLED | NO_COVERAGE | 139 | 1068 |
| 12 | `d64b7292b499228c8682560ed181e1067cff585542f5d1af25caf413e37f232d` | NON_KILLED | NO_COVERAGE | 161 | 1218 |
| 13 | `f0cf0add6920df8153ee3ae07249fb86b8f771a5879482a900addd12af1faf96` | NON_KILLED | NO_COVERAGE | 185 | 1381 |
| 14 | `0110d7f7665b47ebc3bf1cea5a362b545205a50795ab96652968a21ae4677f22` | KILLED | KILLED | 189 | 1457 |

**P11** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|revealAndValidateReplayPayload|(Ldev/tramai/engine/approval/ResumeUncertainOutcome;Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 2 base-only identities, MIXED (KILLED=1, NON_KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `c479cc1c69a1d68182be1c0c3a69714d5700105821024efb7ef1419fe1a87808` | KILLED | KILLED | 23 | 130 |
| 2 | `26ff0a87eb1ba6c06a77256c3a16d075d9db7d7fb107ed4077380a3f5686dc56` | NON_KILLED | TIMED_OUT | 31 | 169 |

**P12** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|revealAndValidateReplayPayload|(Ldev/tramai/engine/approval/ResumeUncertainOutcome;Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` — 1 base-only identities, uniform (NON_KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `189b579940ae63f9aba4a4be8f21e4c9e6e043e4186d838cb76bce1a8410494e` | NON_KILLED | NO_COVERAGE | 33 | 204 |

**P13** — `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|revealAndValidateReplayPayload|(Ldev/tramai/engine/approval/ResumeUncertainOutcome;Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::revealAndValidateReplayPayload` — 2 base-only identities, MIXED (KILLED=1, NON_KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `83cb636cae4b11deeaf924238c4227f9df48436ece99b3b35337cdab42a897ef` | NON_KILLED | NO_COVERAGE | 32 | 173 |
| 2 | `e2ccc920c43c3a0337efc61a8ba030edac2840deb59ea80ebeaa0f38c0fe6796` | KILLED | KILLED | 37 | 222 |

**P14** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 12 base-only identities, MIXED (KILLED=5, NON_KILLED=7)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `5e13cf15e29cb717376bada0008b5a705dcee0a70341f96df2b19f625de33678` | KILLED | KILLED | 8 | 50 |
| 2 | `acb3b3ec95bc51217b7214b5893cb46dc2a9982d6b88789f86ab2fd5d5db0a42` | KILLED | KILLED | 11 | 71 |
| 3 | `73418182fa85726be12904f6164e3ecf6fe9b86519aece34982bc6306719fa5a` | NON_KILLED | TIMED_OUT | 35 | 190 |
| 4 | `97b1be0758dead1ecc207615a95d60a8943690305890679308f2978fa69848aa` | NON_KILLED | TIMED_OUT | 60 | 363 |
| 5 | `4a35eba5008e2026f17ec0991f08de3057f69dc2ff375d77f7d1058384de247f` | KILLED | KILLED | 66 | 429 |
| 6 | `350a94d49928183879837011ca2899707ec297b2f4eb9cd31dde17253bda0534` | NON_KILLED | TIMED_OUT | 107 | 618 |
| 7 | `88dec573bedb6aaa046b85e47990e5cdeb03e8b3be04d20713037e6efd347ef3` | NON_KILLED | TIMED_OUT | 128 | 786 |
| 8 | `4fc4c332ed039bc77619dd96bb5ed5a7d7993c39939d3d739da525222fc67fd8` | KILLED | KILLED | 143 | 911 |
| 9 | `6d79865a905314b94c87f8c173cf629a32c85ac57f37157a9031ee80c30481a3` | KILLED | KILLED | 145 | 919 |
| 10 | `5a8ca9067f040c03972d7dceb0e2137b79b1c91d1899d2f22f8f6aaa49c2b163` | NON_KILLED | TIMED_OUT | 151 | 986 |
| 11 | `b0e724190b292778c4e4903adeda1dfa5201ebb94ed6167d3f493069b4799698` | NON_KILLED | TIMED_OUT | 165 | 1135 |
| 12 | `1f025dec8de783d77bca435fe618c6a819fec0be91caeba7b3c6aa8e298d5d53` | NON_KILLED | TIMED_OUT | 181 | 1286 |

**P15** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to dev/tramai/core/coroutines/CancellationKt::rethrowIfCancellation` — 4 base-only identities, MIXED (KILLED=1, NON_KILLED=3)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `65778562d1a90086389a488009846170d75153ff4bf10485ef62dcbb37988fec` | KILLED | KILLED | 142 | 906 |
| 2 | `8b266859888e6063a44fcf410f2e9e208ac54739ee2317ebc090961534afd9eb` | NON_KILLED | NO_COVERAGE | 157 | 1068 |
| 3 | `a3aa225ceecd19dec371f1904fe6b60481f3cebdeaae4dbc55f3f219ce43ad5d` | NON_KILLED | NO_COVERAGE | 171 | 1217 |
| 4 | `d68d2a536541e1b4f1de71ca49b5b34aa6a7a8ce25006496cf0e2c3ad8ac82e7` | NON_KILLED | NO_COVERAGE | 187 | 1368 |

**P16** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` — 7 base-only identities, uniform (NON_KILLED=7)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `0aacdd75008dc597dfc796387bc7138d3efdde59a54f7abce241ee5013ce4581` | NON_KILLED | NO_COVERAGE | 37 | 232 |
| 2 | `d0bb3df5d0eafe76663fb617ea2bcaacad24af2b38bccfd85a961180039d5672` | NON_KILLED | NO_COVERAGE | 62 | 412 |
| 3 | `5afb3c677c23a861ed8eafdbcaf88cb0bd467c2bcf57f49a2af1026b770556ef` | NON_KILLED | NO_COVERAGE | 109 | 692 |
| 4 | `5a41f3ffc662fa55d35dba1909b94c1848dedc1146268ab889f7df98ac9abd34` | NON_KILLED | NO_COVERAGE | 130 | 860 |
| 5 | `364e06b64a99a1b4a086b6d003d62eae024d6c38e42ab21c25129df101fd635d` | NON_KILLED | NO_COVERAGE | 153 | 1044 |
| 6 | `e0f4a6a950eb63f0639eec3c497921fa703d01b12def70603dbc0d4b56fbd110` | NON_KILLED | NO_COVERAGE | 167 | 1193 |
| 7 | `261e4a81cca1c5cd532e953501c99d467fed7993b8b35f4bc27eb046bf809640` | NON_KILLED | NO_COVERAGE | 183 | 1344 |

**P17** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/jvm/internal/Intrinsics::checkNotNull` — 1 base-only identities, uniform (NON_KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `14d9afc3f3883a5228acb5af40dfb349e68f18c47d101d3c002d7381a5775a17` | NON_KILLED | SURVIVED | 31 | 155 |

**P18** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue` — 1 base-only identities, uniform (NON_KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `d8fb778015c5a217e1a7baac454ec8e153d4019cb664750a99ce924aa9fdfe05` | NON_KILLED | SURVIVED | 54 | 297 |

**P19** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution` — 7 base-only identities, uniform (NON_KILLED=7)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `5ecf004235db47c42fbba875f40c8bada66e1714a0c4cf3804787bfd94fd2b82` | NON_KILLED | NO_COVERAGE | 36 | 194 |
| 2 | `8d7e1fa01d045eeba98fd6f90840468e02875757e3c7270f150d812a47298895` | NON_KILLED | NO_COVERAGE | 61 | 367 |
| 3 | `72555a80f9468d15109776a23d4b48105afd7dae4c688b774353706a3dd8f7fb` | NON_KILLED | NO_COVERAGE | 108 | 622 |
| 4 | `b7ddd5aaa42259755c26e7d4d36f66abfebc82de7108278f0e5ac5e85c13acd3` | NON_KILLED | NO_COVERAGE | 129 | 790 |
| 5 | `cfe2ccb893ca48be3894ee6d61957c9b732a16a8830edb66f4aac1ae41d308cc` | NON_KILLED | NO_COVERAGE | 152 | 990 |
| 6 | `3aa7e614aa6ac84aaecf1a4fb0d54e670bddc881b07f59a35b50f1cc9f1b62cd` | NON_KILLED | NO_COVERAGE | 166 | 1139 |
| 7 | `35c0b8afec49c52d4f4e939cbb0fb5bbd9e4aeb65a06ebabf3c432bb03d2e36a` | NON_KILLED | NO_COVERAGE | 182 | 1290 |

**P20** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|validateInitialApprovalRequirement-zjKNo-Q|(Ldev/tramai/engine/tool/ToolExecutionRequest;Ldev/tramai/core/policy/PolicyDecision$RequireApproval;Ljava/lang/String;)Ljava/lang/String;|org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator|changed conditional boundary` — 2 base-only identities, uniform (KILLED=2)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `f7d77ffa48e3b779d4b3bdd325816b513aa52f37f44361d0daf5aac4abff5b46` | KILLED | KILLED | 19 | 70 |
| 2 | `35a85692107c57e4ca7bb4ae19e3824dabcf2276a70a70e01afb72e0ed061de2` | KILLED | KILLED | 31 | 110 |

**P21** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|validateInitialApprovalRequirement-zjKNo-Q|(Ldev/tramai/engine/tool/ToolExecutionRequest;Ldev/tramai/core/policy/PolicyDecision$RequireApproval;Ljava/lang/String;)Ljava/lang/String;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 5 base-only identities, uniform (KILLED=5)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `8e8f88d468af58cc0a5533980fb74d476432d9e90cd71aa826714ca6e769828c` | KILLED | KILLED | 19 | 70 |
| 2 | `38958541295a62e3daa09e3781f9eb466a913b8a68c0cc09d58c2e033e25cb6b` | KILLED | KILLED | 22 | 79 |
| 3 | `87dcca678e077eb7f49987f8bd0ec806a643f6bbba7f203b2d3a8326772adcd3` | KILLED | KILLED | 26 | 88 |
| 4 | `47b75ca5bb946fce6a09beebbebf9912d83d4946a9d3a6e313ebdf462222d93a` | KILLED | KILLED | 31 | 110 |
| 5 | `909339da34dde55b76a7b1043465b145c79b0889d2e360bde0756791c58ae253` | KILLED | KILLED | 34 | 118 |

**P22** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|validateInitialApprovalRequirement-zjKNo-Q|(Ldev/tramai/engine/tool/ToolExecutionRequest;Ldev/tramai/core/policy/PolicyDecision$RequireApproval;Ljava/lang/String;)Ljava/lang/String;|org.pitest.mutationtest.engine.gregor.mutators.returns.EmptyObjectReturnValsMutator|replaced return value with "" for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::validateInitialApprovalRequirement-zjKNo-Q` — 1 base-only identities, uniform (KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `a5a4c02eda08ced6c11f295e09b7a01670bdd737d853db22248198667e3c354c` | KILLED | KILLED | 38 | 139 |

**P23** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|validateRenewedApprovalRequirement|(Ldev/tramai/engine/tool/ToolExecutionRequest;Ldev/tramai/core/policy/PolicyDecision$RequireApproval;Ljava/lang/String;)V|org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator|changed conditional boundary` — 1 base-only identities, uniform (NON_KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `b5217c93437c82fa7f6516f742eb044f23d19f9f5f6fd022d09d15afb66d2eb2` | NON_KILLED | SURVIVED | 43 | 147 |

**P24** — `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|validateRenewedApprovalRequirement|(Ldev/tramai/engine/tool/ToolExecutionRequest;Ldev/tramai/core/policy/PolicyDecision$RequireApproval;Ljava/lang/String;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 7 base-only identities, uniform (KILLED=7)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `780c644a8598c145eb32f407bd7bfe9cff29be63b081db0752103dd7f9a9a718` | KILLED | KILLED | 19 | 72 |
| 2 | `83d1ef42f35ca1df37706e5b40916522be7b46303e0b77452bb0c2f671dace71` | KILLED | KILLED | 28 | 99 |
| 3 | `b33d3e8cc0a1eeba34b1b4220ffe8cf736ab882bfc7816046a303c46654e0bf6` | KILLED | KILLED | 31 | 108 |
| 4 | `3523d4890c1d86eb5799b72af98aa2b10e732db3877faaacd8af5236a68772b5` | KILLED | KILLED | 35 | 115 |
| 5 | `c869df82a567053d34ba7a512a4418c6a8ac0cfcfe0caf8303badc0924ecace8` | KILLED | KILLED | 38 | 125 |
| 6 | `142d3419d52f38a97243c03b42ab2f3fdb463f282d2e4ff1978fdbed1284fdfa` | KILLED | KILLED | 43 | 147 |
| 7 | `56fb3e2cb039aab55a0c0cd6348b13dac467e1f9de7ef1a526edd6f85fae9738` | KILLED | KILLED | 46 | 155 |

**P25** — `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 6 base-only identities, MIXED (KILLED=1, NON_KILLED=5)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `947f05488df35965fa51bf4673beb3e92a14e4406ec31c6f66bca3bb7e8ca4e1` | NON_KILLED | TIMED_OUT | 13 | 86 |
| 2 | `5ffadc16dab2dcfa5c8b310d7c7631633e1ca3094981733343926fff913b1731` | NON_KILLED | TIMED_OUT | 24 | 153 |
| 3 | `e236ea3f276e38d9e26dd11dd5c20a1bdbbe4a0f52cbf7e890051d0abd095339` | KILLED | KILLED | 28 | 193 |
| 4 | `afba54a83e116a798977913ddae1941d5c76f01827322f4c3af6e6f49ea6a1f5` | NON_KILLED | TIMED_OUT | 38 | 241 |
| 5 | `a76865f870dc0c2ec6a1b4feb5a304a3374b6470104178b235273b64283d72fd` | NON_KILLED | TIMED_OUT | 50 | 326 |
| 6 | `1bfda558c38bac3cde86dd8bc03fbf4253f2029d968311d650df2d72087c781c` | NON_KILLED | TIMED_OUT | 62 | 411 |

**P26** — `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` — 5 base-only identities, uniform (NON_KILLED=5)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `3db41731fa0393fe43aaa0288207c8ebf692b552b0c98379514bc6f9929a1551` | NON_KILLED | NO_COVERAGE | 15 | 110 |
| 2 | `7d5515ebf1454998b4df3ccf0f10795dda859f3ef4fc8af24f725fafb01b4b42` | NON_KILLED | NO_COVERAGE | 26 | 184 |
| 3 | `02c365c59375551744b483b46d39794b8e93491e53bfdf339a4cff1e4ea59c51` | NON_KILLED | NO_COVERAGE | 40 | 277 |
| 4 | `d91f6922ebfe647243fb65df46a7557d087d830be640be56629bd4dd8b5e65f2` | NON_KILLED | NO_COVERAGE | 52 | 362 |
| 5 | `5c2c8235fab20ded12284021db2aae1fcddf3144441d95dea0a9d02ed8b22e8f` | NON_KILLED | NO_COVERAGE | 64 | 447 |

**P27** — `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo` — 7 base-only identities, MIXED (KILLED=2, NON_KILLED=5)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `4681686f2f267cb553f68d413c5d1d0ffdda484edb42584dcaa1cf839e0fd537` | NON_KILLED | NO_COVERAGE | 14 | 90 |
| 2 | `46159f4c1a0f9c057292497389e25cbbe69d792126307ae5667a45a2069682e1` | NON_KILLED | NO_COVERAGE | 25 | 157 |
| 3 | `b91468eee9d536c3d229a8ed57ec7513ce52c450b0824e6f004dc4b7ce1318cb` | KILLED | KILLED | 30 | 202 |
| 4 | `6e45cd86eec24bafbc7b2b6b78b0a5145167b3199d2cf62c4c9f7c6025f2720c` | NON_KILLED | NO_COVERAGE | 39 | 245 |
| 5 | `fefd169fd4b29701e21b49169db1d6ce9546fdcd91c132d9b2f3587d089330a0` | NON_KILLED | NO_COVERAGE | 51 | 330 |
| 6 | `246b6fba6aabb668aa580497138761695987f6d3d10ec41f3613842cd74abb77` | NON_KILLED | NO_COVERAGE | 63 | 415 |
| 7 | `37470da71000d7f3ffed6b39c5cb491e0c411a15cfe80d66c6f5a3ba9ed6df82` | KILLED | KILLED | 80 | 500 |

**P28** — `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|toGatewayResult|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ljava/time/Clock;)Ldev/tramai/core/approval/gateway/ApprovalRequestResult;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 8 base-only identities, uniform (KILLED=8)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `793981f98769edc8e547086de3566bbdcafdd72785b0cbec1e38f5546ed073bd` | KILLED | KILLED | 6 | 30 |
| 2 | `83509ebce4e0058761aa64637f20762b99bcb779272d6bbacf8b8dff4721288d` | KILLED | KILLED | 11 | 56 |
| 3 | `0439bb0607903bc6f27d61149dea58b6c4e2bb8dd7688ecba4108c4234ea3fb2` | KILLED | KILLED | 20 | 115 |
| 4 | `e1fcc10b0931789c0c691229bbf439436ae323ed91ee4e3557c0214a3af20508` | KILLED | KILLED | 22 | 125 |
| 5 | `0cbf1d2df2070824efe130be900a5a4b791286d6ddede16c07c669e79451319a` | KILLED | KILLED | 27 | 151 |
| 6 | `9be7727f6dc42ae7165113c44d2c7a7bbcd080721fa941a1f0d9e36716674c75` | KILLED | KILLED | 32 | 180 |
| 7 | `a56c34f5b7ff9ed9ddb56ac2d09de44c737792ccbfd6ed140939aa72e18a4f9e` | KILLED | KILLED | 38 | 221 |
| 8 | `e62f20c11017a5691291ed7bbd2370b8c90cda0651f701eef711b5edd8636230` | KILLED | KILLED | 41 | 226 |

**P29** — `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|toGatewayResult|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ljava/time/Clock;)Ldev/tramai/core/approval/gateway/ApprovalRequestResult;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::toGatewayResult` — 1 base-only identities, uniform (KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `ef4a38a37da4f0accf5e3d5119d9947c209a0448d0ba7f719bcf39d88ccaa57e` | KILLED | KILLED | 54 | 280 |

**P30** — `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateDecisionKind|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 1 base-only identities, uniform (KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `b8d57f3a8cfa01752a4a2f21d17c7dd2df0e3f981a5fd0da4f15484c53cc301a` | KILLED | KILLED | 11 | 41 |

**P31** — `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator|changed conditional boundary` — 4 base-only identities, MIXED (KILLED=1, NON_KILLED=3)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `9da71de67474488109161790ab792973e3422d1bf5dfec8f2b3f1fd665470769` | NON_KILLED | SURVIVED | 57 | 204 |
| 2 | `12dd45b419d9cea7f66740220df91700c04dd5b7bb6ef7364d059431912684e0` | NON_KILLED | SURVIVED | 70 | 252 |
| 3 | `03fe978066fab6ab5d9bb78942e45cb880aa526340e0cebb71fe9bfed6d368b3` | KILLED | KILLED | 98 | 385 |
| 4 | `a9745066815e3b7cf055ffa8924ae4afb7faf61f59ccf2ec1085c8fe28e71b2b` | NON_KILLED | SURVIVED | 111 | 433 |

**P32** — `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 37 base-only identities, MIXED (KILLED=29, NON_KILLED=8)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `ca0bada2431c7ecdbd1a9ffb4c012addb0e37423e5f7aacad00d26ba2c70f9de` | KILLED | KILLED | 13 | 45 |
| 2 | `abcb69cf55a048b99298691762a51399a8baeb1eb5d3837bd35d51d9fd41d98f` | KILLED | KILLED | 16 | 55 |
| 3 | `477068444b350a2e226b5084e9645910308be0e4fa0bc6659952bd90f8abe60c` | NON_KILLED | NO_COVERAGE | 25 | 92 |
| 4 | `4288c9ca4e8d2eb4d9c48c96adac8d0f500bc787c2b283db6bb04dc9bba4e09f` | KILLED | KILLED | 28 | 99 |
| 5 | `aff15f3550cd791ef2acbd1d2212086a16c88909fb625420e1e097208d895020` | KILLED | KILLED | 31 | 106 |
| 6 | `7165c3227c30f421f2a6c21d97fa9a75c3ff5762aa1d678ff18176daba836bf9` | KILLED | KILLED | 35 | 120 |
| 7 | `75833d2d554ecf815439ce2b859ac1edef77e21ad3437f29aef612ccb184ae1b` | KILLED | KILLED | 38 | 128 |
| 8 | `4b373fdebbcea1fb830db688fa8e6bdcbeb0b40618e3eaef1d254de9f64e44bd` | KILLED | KILLED | 44 | 156 |
| 9 | `7d907ad74364b799b4f4e25a08dabea840396230a76c160859c34163a0781df1` | KILLED | KILLED | 47 | 164 |
| 10 | `26e44096428c398823df0c11ce34b731f450bb3200975bf8b06e88d4470d3790` | KILLED | KILLED | 53 | 192 |
| 11 | `53697b2d077eb7f8a97e57a484ecc2aab4d1d3e1d9906fa0d2fdd45a8a5125c0` | KILLED | KILLED | 55 | 201 |
| 12 | `8e228ad7ea2de8b0d228514e80bb75751d4035a62b95b3a22c007c50c15be08c` | KILLED | KILLED | 57 | 204 |
| 13 | `ac83ce2e9393996022e1ee3893d986230d9958ce30681a9da19cc56eaa955b98` | KILLED | KILLED | 60 | 212 |
| 14 | `7dda948fc68f908e39fa404895f1ecc40db43eb61934a37db5b64c3e686518f3` | KILLED | KILLED | 66 | 240 |
| 15 | `fafed97d42fd45c1506cefc9366a245205388b7a0c6161be0d4ca8b936a6168a` | KILLED | KILLED | 68 | 249 |
| 16 | `46184b1a9fabe9897daaf9a97aa03a59913d34294700f705589e3b02c20a48bf` | KILLED | KILLED | 70 | 252 |
| 17 | `d9c41d45bb176b283d0d39baf8894ae8ff967188553fd30d841fbc631e5f9a31` | KILLED | KILLED | 73 | 260 |
| 18 | `e0f8eeb27938e0d7af7efedf900468efd48365d9112db42738502eaea8e29306` | KILLED | KILLED | 81 | 320 |
| 19 | `b02d448cd873c73cf74b6b5390f54e564adab46fe09a5adc7300526bd2dad1f4` | KILLED | KILLED | 85 | 336 |
| 20 | `51abee92922e3ad7b7f87997cc2a5f853e05d0e78f6ae88e98c183dc50e4337c` | KILLED | KILLED | 88 | 344 |
| 21 | `55e523c4e3033a325d53843dbdbd94f28912d108701154a7d0f6b7dae032b8ef` | KILLED | KILLED | 94 | 373 |
| 22 | `8c5fcb2064c4f0ea482a072ba706a3ea9b5be3a6b0f9eea40d88d201c2274ae0` | KILLED | KILLED | 96 | 382 |
| 23 | `e6c50edec0cc1af56b952f0ad029ee551cce5ab99993023ddab63cad67ece9d5` | KILLED | KILLED | 98 | 385 |
| 24 | `da7de7326bbe60e83053559e8084d634ba34393609fdff1d3172db8ff03804f9` | KILLED | KILLED | 101 | 393 |
| 25 | `14e6955eae1ddeab6d7d79a008799fdaadc581ca57ac4b4ec1377e7325fdd495` | KILLED | KILLED | 107 | 421 |
| 26 | `4143d82d74783fd4a1a85977e537dd881dbccc99af532924bcff5c9def7d618c` | KILLED | KILLED | 109 | 430 |
| 27 | `6a5baa99db06765b80b13d14473214bb35a51ab808672dd9f26ffd797d9f91ef` | KILLED | KILLED | 111 | 433 |
| 28 | `1082270f835b3e30e90bbf29f7d5e2d3a6bccbe9b00cb762f166b87d820cf73f` | KILLED | KILLED | 114 | 441 |
| 29 | `b2527301e99ae9ec14f83a75f174fa18ede119c136c8d125e32fee05b9609dea` | KILLED | KILLED | 120 | 469 |
| 30 | `d8540bd51f41b9f72ba4e0f27779a3440109b55a5d72b8e3505aec99dd47aa34` | KILLED | KILLED | 122 | 475 |
| 31 | `0cfa472a9557aa62c1b1c1b54d9932cbfb05700b1e908e26c8a5f4f43bf906fc` | NON_KILLED | NO_COVERAGE | 128 | 507 |
| 32 | `d3d159737c1734ff7859ff865f2fbf00f5991c62aba4cba08fe69bc7b928dfab` | NON_KILLED | NO_COVERAGE | 130 | 510 |
| 33 | `0745e3647ebf1461f19be9d959396415cc71379a9481ff83431539fe4a3220a6` | NON_KILLED | NO_COVERAGE | 133 | 521 |
| 34 | `fa4e11a4ecb4ae8c1654cf8e53cb65e0045af41ff5bc62e6074dc9cfa355ea1d` | NON_KILLED | NO_COVERAGE | 136 | 529 |
| 35 | `b1e44ec991236dfb2e879ad8d510c923e493e5d8da377fcae62907fa2cb688e7` | NON_KILLED | NO_COVERAGE | 144 | 559 |
| 36 | `4e9834c5b54021afc27e8a86417cc3404f0bcf8d144ce757e99ce93cdef06366` | NON_KILLED | NO_COVERAGE | 151 | 590 |
| 37 | `86e1f7ed67ea39312b9fae991a44369f62f4e1181c29aecf8796b82ab1139d68` | NON_KILLED | NO_COVERAGE | 153 | 596 |

**P33** — `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateSourceComponent|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` — 1 base-only identities, uniform (KILLED=1)

| # | base-only identity | outcome | raw status | block | index |
|---|---|---|---|---|---|
| 1 | `9d9e99ebd21cb86472bf128efbb919be11d36784987c38acb14179e5bc118e95` | KILLED | KILLED | 11 | 41 |

### 6.2 Category A — UNAMBIGUOUS_INHERITED_NON_KILLED (37 identities)

| # | candidate identity | raw status | family | module | class | method | mutator | description | block | index | source-level key | predecessors at key |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `021b3c457a5dcdc319f98c4c3154904799439c329a704355633fbbad00891eef` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 41 | 378 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `6371b23fe469`(NON_KILLED/NO_COVERAGE), `4e1e2df1c11e`(NON_KILLED/NO_COVERAGE), `527a99cc5db7`(NON_KILLED/NO_COVERAGE) |
| 2 | `04c2e1c3f1b8d7c2e9a6e9aa61c87d699cb90b432de6729730f87d302357cb28` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 134 | 936 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `0aacdd75008d`(NON_KILLED/NO_COVERAGE), `d0bb3df5d0ea`(NON_KILLED/NO_COVERAGE), `5afb3c677c23`(NON_KILLED/NO_COVERAGE), `5a41f3ffc662`(NON_KILLED/NO_COVERAGE), `364e06b64a99`(NON_KILLED/NO_COVERAGE), `e0f4a6a950eb`(NON_KILLED/NO_COVERAGE), `261e4a81cca1`(NON_KILLED/NO_COVERAGE) |
| 3 | `1843d3a31bd38b547f6615d450d411c3a1810340d4160797d653db7efd1e734a` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution | 36 | 210 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution` | `5ecf004235db`(NON_KILLED/NO_COVERAGE), `8d7e1fa01d04`(NON_KILLED/NO_COVERAGE), `72555a80f946`(NON_KILLED/NO_COVERAGE), `b7ddd5aaa422`(NON_KILLED/NO_COVERAGE), `cfe2ccb893ca`(NON_KILLED/NO_COVERAGE), `3aa7e614aa6a`(NON_KILLED/NO_COVERAGE), `35c0b8afec49`(NON_KILLED/NO_COVERAGE) |
| 4 | `1ce5967ee60b6f4380d69dead598dde89212eeb17c2b9be6348bf9dfc3928981` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to dev/tramai/core/coroutines/CancellationKt::rethrowIfCancellation | 91 | 641 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to dev/tramai/core/coroutines/CancellationKt::rethrowIfCancellation` | `0a1c3c1c58bb`(NON_KILLED/SURVIVED) |
| 5 | `30f9bb41fa82566d6965603b16558d9b8c3943309ddb3240ba6462fca25d7173` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 159 | 1118 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `0aacdd75008d`(NON_KILLED/NO_COVERAGE), `d0bb3df5d0ea`(NON_KILLED/NO_COVERAGE), `5afb3c677c23`(NON_KILLED/NO_COVERAGE), `5a41f3ffc662`(NON_KILLED/NO_COVERAGE), `364e06b64a99`(NON_KILLED/NO_COVERAGE), `e0f4a6a950eb`(NON_KILLED/NO_COVERAGE), `261e4a81cca1`(NON_KILLED/NO_COVERAGE) |
| 6 | `3658ba45eaedbf6a946036d210a91578d65fda8b0f0f295137393d3454e9a5e0` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/jvm/internal/Intrinsics::checkNotNull | 31 | 168 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/jvm/internal/Intrinsics::checkNotNull` | `14d9afc3f388`(NON_KILLED/SURVIVED) |
| 7 | `3b12d1a55c6b59c5906de2128d72e8df33f8df56ba26c33d432312e2e012a64a` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution | 133 | 857 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution` | `5ecf004235db`(NON_KILLED/NO_COVERAGE), `8d7e1fa01d04`(NON_KILLED/NO_COVERAGE), `72555a80f946`(NON_KILLED/NO_COVERAGE), `b7ddd5aaa422`(NON_KILLED/NO_COVERAGE), `cfe2ccb893ca`(NON_KILLED/NO_COVERAGE), `3aa7e614aa6a`(NON_KILLED/NO_COVERAGE), `35c0b8afec49`(NON_KILLED/NO_COVERAGE) |
| 8 | `441d33435645798ccbb4599430cd3cd44ba0429f94eff009d3ec2bdd00d893d0` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 16 | 112 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `82fe089c156d`(NON_KILLED/NO_COVERAGE), `546a615f4436`(NON_KILLED/NO_COVERAGE), `ef0c44715457`(NON_KILLED/NO_COVERAGE), `1d9dc7a8c9b2`(NON_KILLED/NO_COVERAGE), `ac0be306eb26`(NON_KILLED/NO_COVERAGE), `30fe02f32b41`(NON_KILLED/NO_COVERAGE), `c4c37ffff62b`(NON_KILLED/NO_COVERAGE), `ba6ebc3e7076`(NON_KILLED/NO_COVERAGE), `ec7d1ab598d6`(NON_KILLED/NO_COVERAGE), `ebf31b386086`(NON_KILLED/NO_COVERAGE), `f2d34adda4b6`(NON_KILLED/NO_COVERAGE), `cf1441ce7d46`(NON_KILLED/NO_COVERAGE), `f05dac1eeae6`(NON_KILLED/NO_COVERAGE) |
| 9 | `453e4aaf232b4d80c83653952228658084fe9e3cb56fb91b49071b115aaaa45b` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution | 158 | 1071 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution` | `5ecf004235db`(NON_KILLED/NO_COVERAGE), `8d7e1fa01d04`(NON_KILLED/NO_COVERAGE), `72555a80f946`(NON_KILLED/NO_COVERAGE), `b7ddd5aaa422`(NON_KILLED/NO_COVERAGE), `cfe2ccb893ca`(NON_KILLED/NO_COVERAGE), `3aa7e614aa6a`(NON_KILLED/NO_COVERAGE), `35c0b8afec49`(NON_KILLED/NO_COVERAGE) |
| 10 | `4f99ba33ee70bdc6f023171d803dc5438e88cb16170b27516865e56fb355cbb4` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution | 110 | 672 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution` | `5ecf004235db`(NON_KILLED/NO_COVERAGE), `8d7e1fa01d04`(NON_KILLED/NO_COVERAGE), `72555a80f946`(NON_KILLED/NO_COVERAGE), `b7ddd5aaa422`(NON_KILLED/NO_COVERAGE), `cfe2ccb893ca`(NON_KILLED/NO_COVERAGE), `3aa7e614aa6a`(NON_KILLED/NO_COVERAGE), `35c0b8afec49`(NON_KILLED/NO_COVERAGE) |
| 11 | `5397c6bf591a7da840a173f7ef0d751fca2db96db65a57b0de194c6d2e1b2930` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 56 | 395 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `82fe089c156d`(NON_KILLED/NO_COVERAGE), `546a615f4436`(NON_KILLED/NO_COVERAGE), `ef0c44715457`(NON_KILLED/NO_COVERAGE), `1d9dc7a8c9b2`(NON_KILLED/NO_COVERAGE), `ac0be306eb26`(NON_KILLED/NO_COVERAGE), `30fe02f32b41`(NON_KILLED/NO_COVERAGE), `c4c37ffff62b`(NON_KILLED/NO_COVERAGE), `ba6ebc3e7076`(NON_KILLED/NO_COVERAGE), `ec7d1ab598d6`(NON_KILLED/NO_COVERAGE), `ebf31b386086`(NON_KILLED/NO_COVERAGE), `f2d34adda4b6`(NON_KILLED/NO_COVERAGE), `cf1441ce7d46`(NON_KILLED/NO_COVERAGE), `f05dac1eeae6`(NON_KILLED/NO_COVERAGE) |
| 12 | `564517b2fdab899eceefac4eb9dbb40f3a60139a5c6f917f5fe7c1b172fc9d67` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 13 | 96 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `0aacdd75008d`(NON_KILLED/NO_COVERAGE), `d0bb3df5d0ea`(NON_KILLED/NO_COVERAGE), `5afb3c677c23`(NON_KILLED/NO_COVERAGE), `5a41f3ffc662`(NON_KILLED/NO_COVERAGE), `364e06b64a99`(NON_KILLED/NO_COVERAGE), `e0f4a6a950eb`(NON_KILLED/NO_COVERAGE), `261e4a81cca1`(NON_KILLED/NO_COVERAGE) |
| 13 | `577c07309b70f484aa98cc6b07eb93c132e82198f86397d69963a2a95399afef` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 21 | 202 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `6371b23fe469`(NON_KILLED/NO_COVERAGE), `4e1e2df1c11e`(NON_KILLED/NO_COVERAGE), `527a99cc5db7`(NON_KILLED/NO_COVERAGE) |
| 14 | `5a7edac3a982221ce692e032184362a1db8efc49292d9ea0b5716824303ecef1` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 11 | 92 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `3db41731fa03`(NON_KILLED/NO_COVERAGE), `7d5515ebf145`(NON_KILLED/NO_COVERAGE), `02c365c59375`(NON_KILLED/NO_COVERAGE), `d91f6922ebfe`(NON_KILLED/NO_COVERAGE), `5c2c8235fab2`(NON_KILLED/NO_COVERAGE) |
| 15 | `5d700610726248849d6141d8f8159c688932d1759e3f3d20bbb6fe966cb670e4` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `NegateConditionalsMutator` | negated conditional | 19 | 167 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `949c8ad8dfd4`(NON_KILLED/TIMED_OUT), `062c0e53f177`(NON_KILLED/TIMED_OUT), `dc0810d0cc14`(NON_KILLED/TIMED_OUT) |
| 16 | `62897c202ffe147b902f030a11767832f6d273bbb92f9276db1e5d956e1662a6` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 62 | 441 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `0aacdd75008d`(NON_KILLED/NO_COVERAGE), `d0bb3df5d0ea`(NON_KILLED/NO_COVERAGE), `5afb3c677c23`(NON_KILLED/NO_COVERAGE), `5a41f3ffc662`(NON_KILLED/NO_COVERAGE), `364e06b64a99`(NON_KILLED/NO_COVERAGE), `e0f4a6a950eb`(NON_KILLED/NO_COVERAGE), `261e4a81cca1`(NON_KILLED/NO_COVERAGE) |
| 17 | `62911071da6c7a9fe85f76a41c5a23d56b1156e2d1447dd96ccce962aabeff3b` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `revealAndValidateReplayPayload` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 33 | 205 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|revealAndValidateReplayPayload|(Ldev/tramai/engine/approval/ResumeUncertainOutcome;Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `189b579940ae`(NON_KILLED/NO_COVERAGE) |
| 18 | `65f7e7cc271623407239ce9b2d935ac0a2b3ea438c87600ee86b54fa21f96425` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `validateRenewedApprovalRequirement` | `ConditionalsBoundaryMutator` | changed conditional boundary | 43 | 148 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|validateRenewedApprovalRequirement|(Ldev/tramai/engine/tool/ToolExecutionRequest;Ldev/tramai/core/policy/PolicyDecision$RequireApproval;Ljava/lang/String;)V|org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator|changed conditional boundary` | `b5217c93437c`(NON_KILLED/SURVIVED) |
| 19 | `66c311cf4f0ae36396880146f9059264308168b0286cdee72b852076560df61e` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution | 12 | 78 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution` | `5ecf004235db`(NON_KILLED/NO_COVERAGE), `8d7e1fa01d04`(NON_KILLED/NO_COVERAGE), `72555a80f946`(NON_KILLED/NO_COVERAGE), `b7ddd5aaa422`(NON_KILLED/NO_COVERAGE), `cfe2ccb893ca`(NON_KILLED/NO_COVERAGE), `3aa7e614aa6a`(NON_KILLED/NO_COVERAGE), `35c0b8afec49`(NON_KILLED/NO_COVERAGE) |
| 20 | `6a33ba5d985ae4e67fc8f6f06b740880318078558c3973bf2c30f67c37a692c6` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 25 | 189 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `3db41731fa03`(NON_KILLED/NO_COVERAGE), `7d5515ebf145`(NON_KILLED/NO_COVERAGE), `02c365c59375`(NON_KILLED/NO_COVERAGE), `d91f6922ebfe`(NON_KILLED/NO_COVERAGE), `5c2c8235fab2`(NON_KILLED/NO_COVERAGE) |
| 21 | `6cadd3397125ba1bdc4b72d2267c73f6b9ab4fdcd77205691f0d10adc07e5101` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `NegateConditionalsMutator` | negated conditional | 54 | 441 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `949c8ad8dfd4`(NON_KILLED/TIMED_OUT), `062c0e53f177`(NON_KILLED/TIMED_OUT), `dc0810d0cc14`(NON_KILLED/TIMED_OUT) |
| 22 | `6dcb09ca1cc8e2bb404aa0b4e8345aa185d10b00f019b81a268355d7c2ee83ef` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 62 | 467 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `3db41731fa03`(NON_KILLED/NO_COVERAGE), `7d5515ebf145`(NON_KILLED/NO_COVERAGE), `02c365c59375`(NON_KILLED/NO_COVERAGE), `d91f6922ebfe`(NON_KILLED/NO_COVERAGE), `5c2c8235fab2`(NON_KILLED/NO_COVERAGE) |
| 23 | `8210e97a204f70645bc2df52a4af2859a972a5dd3cbd24400c32f61284c72dc8` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 48 | 369 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `3db41731fa03`(NON_KILLED/NO_COVERAGE), `7d5515ebf145`(NON_KILLED/NO_COVERAGE), `02c365c59375`(NON_KILLED/NO_COVERAGE), `d91f6922ebfe`(NON_KILLED/NO_COVERAGE), `5c2c8235fab2`(NON_KILLED/NO_COVERAGE) |
| 24 | `87fbd8e6ed4176baf39df68250f43fbabc8082b0407e4aac1f2b95953ca27782` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 111 | 751 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `0aacdd75008d`(NON_KILLED/NO_COVERAGE), `d0bb3df5d0ea`(NON_KILLED/NO_COVERAGE), `5afb3c677c23`(NON_KILLED/NO_COVERAGE), `5a41f3ffc662`(NON_KILLED/NO_COVERAGE), `364e06b64a99`(NON_KILLED/NO_COVERAGE), `e0f4a6a950eb`(NON_KILLED/NO_COVERAGE), `261e4a81cca1`(NON_KILLED/NO_COVERAGE) |
| 25 | `8dc069d5a0f38543a188b36d41cc8d112c332188626cd816eb4a7c1c7cd28c82` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 74 | 556 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `3db41731fa03`(NON_KILLED/NO_COVERAGE), `7d5515ebf145`(NON_KILLED/NO_COVERAGE), `02c365c59375`(NON_KILLED/NO_COVERAGE), `d91f6922ebfe`(NON_KILLED/NO_COVERAGE), `5c2c8235fab2`(NON_KILLED/NO_COVERAGE) |
| 26 | `9026b993fd997eb85a94ed6bde3b111ec4e411a59c6362b6fb0af2e99bca27bf` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 107 | 746 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `82fe089c156d`(NON_KILLED/NO_COVERAGE), `546a615f4436`(NON_KILLED/NO_COVERAGE), `ef0c44715457`(NON_KILLED/NO_COVERAGE), `1d9dc7a8c9b2`(NON_KILLED/NO_COVERAGE), `ac0be306eb26`(NON_KILLED/NO_COVERAGE), `30fe02f32b41`(NON_KILLED/NO_COVERAGE), `c4c37ffff62b`(NON_KILLED/NO_COVERAGE), `ba6ebc3e7076`(NON_KILLED/NO_COVERAGE), `ec7d1ab598d6`(NON_KILLED/NO_COVERAGE), `ebf31b386086`(NON_KILLED/NO_COVERAGE), `f2d34adda4b6`(NON_KILLED/NO_COVERAGE), `cf1441ce7d46`(NON_KILLED/NO_COVERAGE), `f05dac1eeae6`(NON_KILLED/NO_COVERAGE) |
| 27 | `92b019e1bae11d828ffdc6b850e818f5b5986b7a078a2c29defba79bc0a39791` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 44 | 303 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `82fe089c156d`(NON_KILLED/NO_COVERAGE), `546a615f4436`(NON_KILLED/NO_COVERAGE), `ef0c44715457`(NON_KILLED/NO_COVERAGE), `1d9dc7a8c9b2`(NON_KILLED/NO_COVERAGE), `ac0be306eb26`(NON_KILLED/NO_COVERAGE), `30fe02f32b41`(NON_KILLED/NO_COVERAGE), `c4c37ffff62b`(NON_KILLED/NO_COVERAGE), `ba6ebc3e7076`(NON_KILLED/NO_COVERAGE), `ec7d1ab598d6`(NON_KILLED/NO_COVERAGE), `ebf31b386086`(NON_KILLED/NO_COVERAGE), `f2d34adda4b6`(NON_KILLED/NO_COVERAGE), `cf1441ce7d46`(NON_KILLED/NO_COVERAGE), `f05dac1eeae6`(NON_KILLED/NO_COVERAGE) |
| 28 | `ac7940cfd29db06c06381a310e485889b22bdc156808208d2725d8d299e34027` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue | 54 | 319 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue` | `d8fb778015c5`(NON_KILLED/SURVIVED) |
| 29 | `b280cd7e98348884d2ed52b40a25057122bf434410994caa0db380fb68f656a4` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 56 | 500 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `6371b23fe469`(NON_KILLED/NO_COVERAGE), `4e1e2df1c11e`(NON_KILLED/NO_COVERAGE), `527a99cc5db7`(NON_KILLED/NO_COVERAGE) |
| 30 | `c2be62debe23ffe52f88f679c18778a3822f08312f112a698e418884d7c9a770` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution | 61 | 392 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::suspendToolExecution` | `5ecf004235db`(NON_KILLED/NO_COVERAGE), `8d7e1fa01d04`(NON_KILLED/NO_COVERAGE), `72555a80f946`(NON_KILLED/NO_COVERAGE), `b7ddd5aaa422`(NON_KILLED/NO_COVERAGE), `cfe2ccb893ca`(NON_KILLED/NO_COVERAGE), `3aa7e614aa6a`(NON_KILLED/NO_COVERAGE), `35c0b8afec49`(NON_KILLED/NO_COVERAGE) |
| 31 | `ceba69cd43f170d2b3eebe5b7e0e74e3503eab3e80ceb4eed1a7f91659d3e64e` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 11 | 71 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `82fe089c156d`(NON_KILLED/NO_COVERAGE), `546a615f4436`(NON_KILLED/NO_COVERAGE), `ef0c44715457`(NON_KILLED/NO_COVERAGE), `1d9dc7a8c9b2`(NON_KILLED/NO_COVERAGE), `ac0be306eb26`(NON_KILLED/NO_COVERAGE), `30fe02f32b41`(NON_KILLED/NO_COVERAGE), `c4c37ffff62b`(NON_KILLED/NO_COVERAGE), `ba6ebc3e7076`(NON_KILLED/NO_COVERAGE), `ec7d1ab598d6`(NON_KILLED/NO_COVERAGE), `ebf31b386086`(NON_KILLED/NO_COVERAGE), `f2d34adda4b6`(NON_KILLED/NO_COVERAGE), `cf1441ce7d46`(NON_KILLED/NO_COVERAGE), `f05dac1eeae6`(NON_KILLED/NO_COVERAGE) |
| 32 | `d77706fde05eb954a18854bdc62e90721dca630d7edd3b681c1ca599046c0eb9` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 37 | 279 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `3db41731fa03`(NON_KILLED/NO_COVERAGE), `7d5515ebf145`(NON_KILLED/NO_COVERAGE), `02c365c59375`(NON_KILLED/NO_COVERAGE), `d91f6922ebfe`(NON_KILLED/NO_COVERAGE), `5c2c8235fab2`(NON_KILLED/NO_COVERAGE) |
| 33 | `ecb1e83af59393d1b5232a58ff15d691a148b163c854160584245a09ca6b31a5` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 87 | 617 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `82fe089c156d`(NON_KILLED/NO_COVERAGE), `546a615f4436`(NON_KILLED/NO_COVERAGE), `ef0c44715457`(NON_KILLED/NO_COVERAGE), `1d9dc7a8c9b2`(NON_KILLED/NO_COVERAGE), `ac0be306eb26`(NON_KILLED/NO_COVERAGE), `30fe02f32b41`(NON_KILLED/NO_COVERAGE), `c4c37ffff62b`(NON_KILLED/NO_COVERAGE), `ba6ebc3e7076`(NON_KILLED/NO_COVERAGE), `ec7d1ab598d6`(NON_KILLED/NO_COVERAGE), `ebf31b386086`(NON_KILLED/NO_COVERAGE), `f2d34adda4b6`(NON_KILLED/NO_COVERAGE), `cf1441ce7d46`(NON_KILLED/NO_COVERAGE), `f05dac1eeae6`(NON_KILLED/NO_COVERAGE) |
| 34 | `ece7d1190f97b351afdc5e431e888fd7e24aabf29ca1ab41d356e6dd490aa787` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 28 | 177 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `82fe089c156d`(NON_KILLED/NO_COVERAGE), `546a615f4436`(NON_KILLED/NO_COVERAGE), `ef0c44715457`(NON_KILLED/NO_COVERAGE), `1d9dc7a8c9b2`(NON_KILLED/NO_COVERAGE), `ac0be306eb26`(NON_KILLED/NO_COVERAGE), `30fe02f32b41`(NON_KILLED/NO_COVERAGE), `c4c37ffff62b`(NON_KILLED/NO_COVERAGE), `ba6ebc3e7076`(NON_KILLED/NO_COVERAGE), `ec7d1ab598d6`(NON_KILLED/NO_COVERAGE), `ebf31b386086`(NON_KILLED/NO_COVERAGE), `f2d34adda4b6`(NON_KILLED/NO_COVERAGE), `cf1441ce7d46`(NON_KILLED/NO_COVERAGE), `f05dac1eeae6`(NON_KILLED/NO_COVERAGE) |
| 35 | `ef13cbec0d70fb8ec493cc6ed0709275f5ea9d9d73b2a661ef9c4d782f531af3` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 69 | 501 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `82fe089c156d`(NON_KILLED/NO_COVERAGE), `546a615f4436`(NON_KILLED/NO_COVERAGE), `ef0c44715457`(NON_KILLED/NO_COVERAGE), `1d9dc7a8c9b2`(NON_KILLED/NO_COVERAGE), `ac0be306eb26`(NON_KILLED/NO_COVERAGE), `30fe02f32b41`(NON_KILLED/NO_COVERAGE), `c4c37ffff62b`(NON_KILLED/NO_COVERAGE), `ba6ebc3e7076`(NON_KILLED/NO_COVERAGE), `ec7d1ab598d6`(NON_KILLED/NO_COVERAGE), `ebf31b386086`(NON_KILLED/NO_COVERAGE), `f2d34adda4b6`(NON_KILLED/NO_COVERAGE), `cf1441ce7d46`(NON_KILLED/NO_COVERAGE), `f05dac1eeae6`(NON_KILLED/NO_COVERAGE) |
| 36 | `f1a19c075b2903dca52266897ef06706740455acdeed3a843dbe51de24236213` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 37 | 252 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | `0aacdd75008d`(NON_KILLED/NO_COVERAGE), `d0bb3df5d0ea`(NON_KILLED/NO_COVERAGE), `5afb3c677c23`(NON_KILLED/NO_COVERAGE), `5a41f3ffc662`(NON_KILLED/NO_COVERAGE), `364e06b64a99`(NON_KILLED/NO_COVERAGE), `e0f4a6a950eb`(NON_KILLED/NO_COVERAGE), `261e4a81cca1`(NON_KILLED/NO_COVERAGE) |
| 37 | `f23b4d0921a0e5daca1b271f41357982ca9e52de09f0e1e5d72933b1b87f3ac6` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `NegateConditionalsMutator` | negated conditional | 39 | 323 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `949c8ad8dfd4`(NON_KILLED/TIMED_OUT), `062c0e53f177`(NON_KILLED/TIMED_OUT), `dc0810d0cc14`(NON_KILLED/TIMED_OUT) |

### 6.3 Category B — AMBIGUOUS_INHERITED (51 identities)

| # | candidate identity | raw status | family | module | class | method | mutator | description | block | index | source-level key | predecessors at key |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `04c94b48909a32fc160f6a6a6983f183656ff9dc7d4506faf675765d6df690eb` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::executeClaimedResume | 40 | 327 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::executeClaimedResume` | `950b0b5ac5a9`(NON_KILLED/NO_COVERAGE), `be663ad795d6`(NON_KILLED/NO_COVERAGE), `4948fdbaf262`(NON_KILLED/NO_COVERAGE), `56b12a836fef`(KILLED/KILLED) |
| 2 | `0bf3201b50780da27f483eb575458877d047d34c6152438bf8f27e99e68eaeaa` | SURVIVED | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `ConditionalsBoundaryMutator` | changed conditional boundary | 116 | 459 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator|changed conditional boundary` | `9da71de67474`(NON_KILLED/SURVIVED), `12dd45b419d9`(NON_KILLED/SURVIVED), `03fe978066fa`(KILLED/KILLED), `a9745066815e`(NON_KILLED/SURVIVED) |
| 3 | `1016c93dbbc9b196b9a4a530989b2410096601826884b69cf63f9445547f97e8` | NO_COVERAGE | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `NegateConditionalsMutator` | negated conditional | 133 | 533 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `ca0bada2431c`(KILLED/KILLED), `abcb69cf55a0`(KILLED/KILLED), `477068444b35`(NON_KILLED/NO_COVERAGE), `4288c9ca4e8d`(KILLED/KILLED), `aff15f3550cd`(KILLED/KILLED), `7165c3227c30`(KILLED/KILLED), `75833d2d554e`(KILLED/KILLED), `4b373fdebbce`(KILLED/KILLED), `7d907ad74364`(KILLED/KILLED), `26e44096428c`(KILLED/KILLED), `53697b2d077e`(KILLED/KILLED), `8e228ad7ea2d`(KILLED/KILLED), `ac83ce2e9393`(KILLED/KILLED), `7dda948fc68f`(KILLED/KILLED), `fafed97d42fd`(KILLED/KILLED), `46184b1a9fab`(KILLED/KILLED), `d9c41d45bb17`(KILLED/KILLED), `e0f8eeb27938`(KILLED/KILLED), `b02d448cd873`(KILLED/KILLED), `51abee92922e`(KILLED/KILLED), `55e523c4e303`(KILLED/KILLED), `8c5fcb2064c4`(KILLED/KILLED), `e6c50edec0cc`(KILLED/KILLED), `da7de7326bbe`(KILLED/KILLED), `14e6955eae1d`(KILLED/KILLED), `4143d82d7478`(KILLED/KILLED), `6a5baa99db06`(KILLED/KILLED), `1082270f835b`(KILLED/KILLED), `b2527301e99a`(KILLED/KILLED), `d8540bd51f41`(KILLED/KILLED), `0cfa472a9557`(NON_KILLED/NO_COVERAGE), `d3d159737c17`(NON_KILLED/NO_COVERAGE), `0745e3647ebf`(NON_KILLED/NO_COVERAGE), `fa4e11a4ecb4`(NON_KILLED/NO_COVERAGE), `b1e44ec99123`(NON_KILLED/NO_COVERAGE), `4e9834c5b540`(NON_KILLED/NO_COVERAGE), `86e1f7ed67ea`(NON_KILLED/NO_COVERAGE) |
| 4 | `10512d6c6e0078e866ab5926dcb17a5b3d77edf65a3e0bb77df3820bc8ba65e1` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `revealAndValidateReplayPayload` | `NegateConditionalsMutator` | negated conditional | 31 | 170 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|revealAndValidateReplayPayload|(Ldev/tramai/engine/approval/ResumeUncertainOutcome;Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `c479cc1c69a1`(KILLED/KILLED), `26ff0a87eb1b`(NON_KILLED/TIMED_OUT) |
| 5 | `13586a6301d3fa8dbe85981352dc08c0616e953c4f285810b48f4fb05904fe31` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume | 55 | 360 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` | `083b220893a9`(NON_KILLED/NO_COVERAGE), `b7b2dfec2fa0`(NON_KILLED/NO_COVERAGE), `efacd2b1e0f4`(NON_KILLED/NO_COVERAGE), `c52b419c90e9`(NON_KILLED/NO_COVERAGE), `3555704187bd`(NON_KILLED/NO_COVERAGE), `246350bcbd76`(NON_KILLED/NO_COVERAGE), `976f65569f36`(NON_KILLED/NO_COVERAGE), `32df7c11c787`(NON_KILLED/NO_COVERAGE), `0e8a40e624e1`(NON_KILLED/NO_COVERAGE), `b1e34f861946`(NON_KILLED/NO_COVERAGE), `319b7bee9207`(NON_KILLED/NO_COVERAGE), `d64b7292b499`(NON_KILLED/NO_COVERAGE), `f0cf0add6920`(NON_KILLED/NO_COVERAGE), `0110d7f7665b`(KILLED/KILLED) |
| 6 | `16f71d24dc95007e1a903b98c660c2bf0ec975915a1e9c41ed727689f2ccd5a2` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NegateConditionalsMutator` | negated conditional | 132 | 853 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `5e13cf15e29c`(KILLED/KILLED), `acb3b3ec95bc`(KILLED/KILLED), `73418182fa85`(NON_KILLED/TIMED_OUT), `97b1be0758de`(NON_KILLED/TIMED_OUT), `4a35eba5008e`(KILLED/KILLED), `350a94d49928`(NON_KILLED/TIMED_OUT), `88dec573bedb`(NON_KILLED/TIMED_OUT), `4fc4c332ed03`(KILLED/KILLED), `6d79865a9053`(KILLED/KILLED), `5a8ca9067f04`(NON_KILLED/TIMED_OUT), `b0e724190b29`(NON_KILLED/TIMED_OUT), `1f025dec8de7`(NON_KILLED/TIMED_OUT) |
| 7 | `18ac9e9b9ded332a5d862841a5db3d9f650201fc4c749f56b11bdebe1657289d` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume | 15 | 99 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` | `083b220893a9`(NON_KILLED/NO_COVERAGE), `b7b2dfec2fa0`(NON_KILLED/NO_COVERAGE), `efacd2b1e0f4`(NON_KILLED/NO_COVERAGE), `c52b419c90e9`(NON_KILLED/NO_COVERAGE), `3555704187bd`(NON_KILLED/NO_COVERAGE), `246350bcbd76`(NON_KILLED/NO_COVERAGE), `976f65569f36`(NON_KILLED/NO_COVERAGE), `32df7c11c787`(NON_KILLED/NO_COVERAGE), `0e8a40e624e1`(NON_KILLED/NO_COVERAGE), `b1e34f861946`(NON_KILLED/NO_COVERAGE), `319b7bee9207`(NON_KILLED/NO_COVERAGE), `d64b7292b499`(NON_KILLED/NO_COVERAGE), `f0cf0add6920`(NON_KILLED/NO_COVERAGE), `0110d7f7665b`(KILLED/KILLED) |
| 8 | `20ca1a1d8d14f6b6be78f7f5d7170c216ff8f9e225bbff006c11104dca0b0411` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `revealAndValidateReplayPayload` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::revealAndValidateReplayPayload | 32 | 174 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|revealAndValidateReplayPayload|(Ldev/tramai/engine/approval/ResumeUncertainOutcome;Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::revealAndValidateReplayPayload` | `83cb636cae4b`(NON_KILLED/NO_COVERAGE), `e2ccc920c43c`(KILLED/KILLED) |
| 9 | `252596080a3a8e97a347ef98bc08ae9af6fa6c3db5d8c432744148d5109f9848` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 54 | 356 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 10 | `2c5adc165ef6fb1d3199ff178a0e14f3b95289c6aae29d322a54a1f1d57f667f` | NO_COVERAGE | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `NegateConditionalsMutator` | negated conditional | 156 | 616 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `ca0bada2431c`(KILLED/KILLED), `abcb69cf55a0`(KILLED/KILLED), `477068444b35`(NON_KILLED/NO_COVERAGE), `4288c9ca4e8d`(KILLED/KILLED), `aff15f3550cd`(KILLED/KILLED), `7165c3227c30`(KILLED/KILLED), `75833d2d554e`(KILLED/KILLED), `4b373fdebbce`(KILLED/KILLED), `7d907ad74364`(KILLED/KILLED), `26e44096428c`(KILLED/KILLED), `53697b2d077e`(KILLED/KILLED), `8e228ad7ea2d`(KILLED/KILLED), `ac83ce2e9393`(KILLED/KILLED), `7dda948fc68f`(KILLED/KILLED), `fafed97d42fd`(KILLED/KILLED), `46184b1a9fab`(KILLED/KILLED), `d9c41d45bb17`(KILLED/KILLED), `e0f8eeb27938`(KILLED/KILLED), `b02d448cd873`(KILLED/KILLED), `51abee92922e`(KILLED/KILLED), `55e523c4e303`(KILLED/KILLED), `8c5fcb2064c4`(KILLED/KILLED), `e6c50edec0cc`(KILLED/KILLED), `da7de7326bbe`(KILLED/KILLED), `14e6955eae1d`(KILLED/KILLED), `4143d82d7478`(KILLED/KILLED), `6a5baa99db06`(KILLED/KILLED), `1082270f835b`(KILLED/KILLED), `b2527301e99a`(KILLED/KILLED), `d8540bd51f41`(KILLED/KILLED), `0cfa472a9557`(NON_KILLED/NO_COVERAGE), `d3d159737c17`(NON_KILLED/NO_COVERAGE), `0745e3647ebf`(NON_KILLED/NO_COVERAGE), `fa4e11a4ecb4`(NON_KILLED/NO_COVERAGE), `b1e44ec99123`(NON_KILLED/NO_COVERAGE), `4e9834c5b540`(NON_KILLED/NO_COVERAGE), `86e1f7ed67ea`(NON_KILLED/NO_COVERAGE) |
| 11 | `2d5928356b93e422214cdfb78956b5392c45e52e94cb45b91d1216ea59a40eb7` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume | 68 | 463 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` | `083b220893a9`(NON_KILLED/NO_COVERAGE), `b7b2dfec2fa0`(NON_KILLED/NO_COVERAGE), `efacd2b1e0f4`(NON_KILLED/NO_COVERAGE), `c52b419c90e9`(NON_KILLED/NO_COVERAGE), `3555704187bd`(NON_KILLED/NO_COVERAGE), `246350bcbd76`(NON_KILLED/NO_COVERAGE), `976f65569f36`(NON_KILLED/NO_COVERAGE), `32df7c11c787`(NON_KILLED/NO_COVERAGE), `0e8a40e624e1`(NON_KILLED/NO_COVERAGE), `b1e34f861946`(NON_KILLED/NO_COVERAGE), `319b7bee9207`(NON_KILLED/NO_COVERAGE), `d64b7292b499`(NON_KILLED/NO_COVERAGE), `f0cf0add6920`(NON_KILLED/NO_COVERAGE), `0110d7f7665b`(KILLED/KILLED) |
| 12 | `2fa16c9fd834bab14481c3dd937b1bb150988ee0cd86b7ecb457ffa7c7108181` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NegateConditionalsMutator` | negated conditional | 72 | 515 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `947f05488df3`(NON_KILLED/TIMED_OUT), `5ffadc16dab2`(NON_KILLED/TIMED_OUT), `e236ea3f276e`(KILLED/KILLED), `afba54a83e11`(NON_KILLED/TIMED_OUT), `a76865f870dc`(NON_KILLED/TIMED_OUT), `1bfda558c38b`(NON_KILLED/TIMED_OUT) |
| 13 | `33ea83e53a8418523ccc33aa5bea657b6d4d2bbd4aed708d8dcf89b7f53decf1` | NO_COVERAGE | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `NegateConditionalsMutator` | negated conditional | 135 | 536 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `ca0bada2431c`(KILLED/KILLED), `abcb69cf55a0`(KILLED/KILLED), `477068444b35`(NON_KILLED/NO_COVERAGE), `4288c9ca4e8d`(KILLED/KILLED), `aff15f3550cd`(KILLED/KILLED), `7165c3227c30`(KILLED/KILLED), `75833d2d554e`(KILLED/KILLED), `4b373fdebbce`(KILLED/KILLED), `7d907ad74364`(KILLED/KILLED), `26e44096428c`(KILLED/KILLED), `53697b2d077e`(KILLED/KILLED), `8e228ad7ea2d`(KILLED/KILLED), `ac83ce2e9393`(KILLED/KILLED), `7dda948fc68f`(KILLED/KILLED), `fafed97d42fd`(KILLED/KILLED), `46184b1a9fab`(KILLED/KILLED), `d9c41d45bb17`(KILLED/KILLED), `e0f8eeb27938`(KILLED/KILLED), `b02d448cd873`(KILLED/KILLED), `51abee92922e`(KILLED/KILLED), `55e523c4e303`(KILLED/KILLED), `8c5fcb2064c4`(KILLED/KILLED), `e6c50edec0cc`(KILLED/KILLED), `da7de7326bbe`(KILLED/KILLED), `14e6955eae1d`(KILLED/KILLED), `4143d82d7478`(KILLED/KILLED), `6a5baa99db06`(KILLED/KILLED), `1082270f835b`(KILLED/KILLED), `b2527301e99a`(KILLED/KILLED), `d8540bd51f41`(KILLED/KILLED), `0cfa472a9557`(NON_KILLED/NO_COVERAGE), `d3d159737c17`(NON_KILLED/NO_COVERAGE), `0745e3647ebf`(NON_KILLED/NO_COVERAGE), `fa4e11a4ecb4`(NON_KILLED/NO_COVERAGE), `b1e44ec99123`(NON_KILLED/NO_COVERAGE), `4e9834c5b540`(NON_KILLED/NO_COVERAGE), `86e1f7ed67ea`(NON_KILLED/NO_COVERAGE) |
| 14 | `379f2e6d296b1693899e84e358c73328a9679c4cb6c023d16bddb50bdb5a9bdc` | SURVIVED | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `ConditionalsBoundaryMutator` | changed conditional boundary | 75 | 270 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator|changed conditional boundary` | `9da71de67474`(NON_KILLED/SURVIVED), `12dd45b419d9`(NON_KILLED/SURVIVED), `03fe978066fa`(KILLED/KILLED), `a9745066815e`(NON_KILLED/SURVIVED) |
| 15 | `3883b3c127aed30401c7b826e0e5131aca1167c49145f1dc5e9706f319b4ed3e` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo | 24 | 164 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo` | `4681686f2f26`(NON_KILLED/NO_COVERAGE), `46159f4c1a0f`(NON_KILLED/NO_COVERAGE), `b91468eee9d5`(KILLED/KILLED), `6e45cd86eec2`(NON_KILLED/NO_COVERAGE), `fefd169fd4b2`(NON_KILLED/NO_COVERAGE), `246b6fba6aab`(NON_KILLED/NO_COVERAGE), `37470da71000`(KILLED/KILLED) |
| 16 | `4245a1aa59c91bc59bd4fe50b0a42bcef26b9c5b4c39e10771b3f6e3602de63d` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NegateConditionalsMutator` | negated conditional | 35 | 243 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `947f05488df3`(NON_KILLED/TIMED_OUT), `5ffadc16dab2`(NON_KILLED/TIMED_OUT), `e236ea3f276e`(KILLED/KILLED), `afba54a83e11`(NON_KILLED/TIMED_OUT), `a76865f870dc`(NON_KILLED/TIMED_OUT), `1bfda558c38b`(NON_KILLED/TIMED_OUT) |
| 17 | `43dd0cdff07a1d862bc17951e50cbb1b7d8a5d77d91348744b2910467c01f204` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NegateConditionalsMutator` | negated conditional | 109 | 668 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `5e13cf15e29c`(KILLED/KILLED), `acb3b3ec95bc`(KILLED/KILLED), `73418182fa85`(NON_KILLED/TIMED_OUT), `97b1be0758de`(NON_KILLED/TIMED_OUT), `4a35eba5008e`(KILLED/KILLED), `350a94d49928`(NON_KILLED/TIMED_OUT), `88dec573bedb`(NON_KILLED/TIMED_OUT), `4fc4c332ed03`(KILLED/KILLED), `6d79865a9053`(KILLED/KILLED), `5a8ca9067f04`(NON_KILLED/TIMED_OUT), `b0e724190b29`(NON_KILLED/TIMED_OUT), `1f025dec8de7`(NON_KILLED/TIMED_OUT) |
| 18 | `45475202eecb348909e43024a9d2482a72f676b0c1ac6d42b48b482d23de3567` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NegateConditionalsMutator` | negated conditional | 60 | 388 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `5e13cf15e29c`(KILLED/KILLED), `acb3b3ec95bc`(KILLED/KILLED), `73418182fa85`(NON_KILLED/TIMED_OUT), `97b1be0758de`(NON_KILLED/TIMED_OUT), `4a35eba5008e`(KILLED/KILLED), `350a94d49928`(NON_KILLED/TIMED_OUT), `88dec573bedb`(NON_KILLED/TIMED_OUT), `4fc4c332ed03`(KILLED/KILLED), `6d79865a9053`(KILLED/KILLED), `5a8ca9067f04`(NON_KILLED/TIMED_OUT), `b0e724190b29`(NON_KILLED/TIMED_OUT), `1f025dec8de7`(NON_KILLED/TIMED_OUT) |
| 19 | `4865b74caf50c74ee8bfb7994960e3bf640e3a8c6ecc32cd9a99eef5bafc94bf` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 9 | 59 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 20 | `4b1bdb9fc7c4170b0202d36c5ea3a35302209e4595ced39281591b45467fbc3e` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::executeClaimedResume | 55 | 445 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::executeClaimedResume` | `950b0b5ac5a9`(NON_KILLED/NO_COVERAGE), `be663ad795d6`(NON_KILLED/NO_COVERAGE), `4948fdbaf262`(NON_KILLED/NO_COVERAGE), `56b12a836fef`(KILLED/KILLED) |
| 21 | `4dec4f43d11411f42c02624208840d47be3c1e1059e83d7505776e4ce4df70d8` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo | 47 | 332 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo` | `4681686f2f26`(NON_KILLED/NO_COVERAGE), `46159f4c1a0f`(NON_KILLED/NO_COVERAGE), `b91468eee9d5`(KILLED/KILLED), `6e45cd86eec2`(NON_KILLED/NO_COVERAGE), `fefd169fd4b2`(NON_KILLED/NO_COVERAGE), `246b6fba6aab`(NON_KILLED/NO_COVERAGE), `37470da71000`(KILLED/KILLED) |
| 22 | `5170395637241867d2d38413ee0348f1030fdccb80617e0bb33b2001345f59f0` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume | 86 | 579 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` | `083b220893a9`(NON_KILLED/NO_COVERAGE), `b7b2dfec2fa0`(NON_KILLED/NO_COVERAGE), `efacd2b1e0f4`(NON_KILLED/NO_COVERAGE), `c52b419c90e9`(NON_KILLED/NO_COVERAGE), `3555704187bd`(NON_KILLED/NO_COVERAGE), `246350bcbd76`(NON_KILLED/NO_COVERAGE), `976f65569f36`(NON_KILLED/NO_COVERAGE), `32df7c11c787`(NON_KILLED/NO_COVERAGE), `0e8a40e624e1`(NON_KILLED/NO_COVERAGE), `b1e34f861946`(NON_KILLED/NO_COVERAGE), `319b7bee9207`(NON_KILLED/NO_COVERAGE), `d64b7292b499`(NON_KILLED/NO_COVERAGE), `f0cf0add6920`(NON_KILLED/NO_COVERAGE), `0110d7f7665b`(KILLED/KILLED) |
| 23 | `565b38ef596a44ef3c8a1f9666276dfee5d8f04e233c0ed6ebeea58dd21b0d9c` | NO_COVERAGE | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `NegateConditionalsMutator` | negated conditional | 30 | 110 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `ca0bada2431c`(KILLED/KILLED), `abcb69cf55a0`(KILLED/KILLED), `477068444b35`(NON_KILLED/NO_COVERAGE), `4288c9ca4e8d`(KILLED/KILLED), `aff15f3550cd`(KILLED/KILLED), `7165c3227c30`(KILLED/KILLED), `75833d2d554e`(KILLED/KILLED), `4b373fdebbce`(KILLED/KILLED), `7d907ad74364`(KILLED/KILLED), `26e44096428c`(KILLED/KILLED), `53697b2d077e`(KILLED/KILLED), `8e228ad7ea2d`(KILLED/KILLED), `ac83ce2e9393`(KILLED/KILLED), `7dda948fc68f`(KILLED/KILLED), `fafed97d42fd`(KILLED/KILLED), `46184b1a9fab`(KILLED/KILLED), `d9c41d45bb17`(KILLED/KILLED), `e0f8eeb27938`(KILLED/KILLED), `b02d448cd873`(KILLED/KILLED), `51abee92922e`(KILLED/KILLED), `55e523c4e303`(KILLED/KILLED), `8c5fcb2064c4`(KILLED/KILLED), `e6c50edec0cc`(KILLED/KILLED), `da7de7326bbe`(KILLED/KILLED), `14e6955eae1d`(KILLED/KILLED), `4143d82d7478`(KILLED/KILLED), `6a5baa99db06`(KILLED/KILLED), `1082270f835b`(KILLED/KILLED), `b2527301e99a`(KILLED/KILLED), `d8540bd51f41`(KILLED/KILLED), `0cfa472a9557`(NON_KILLED/NO_COVERAGE), `d3d159737c17`(NON_KILLED/NO_COVERAGE), `0745e3647ebf`(NON_KILLED/NO_COVERAGE), `fa4e11a4ecb4`(NON_KILLED/NO_COVERAGE), `b1e44ec99123`(NON_KILLED/NO_COVERAGE), `4e9834c5b540`(NON_KILLED/NO_COVERAGE), `86e1f7ed67ea`(NON_KILLED/NO_COVERAGE) |
| 24 | `58751c78068df6ef25ed6c64c5eeda6e6600f67f72351812073397c2ba203c6f` | SURVIVED | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `ConditionalsBoundaryMutator` | changed conditional boundary | 62 | 222 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator|changed conditional boundary` | `9da71de67474`(NON_KILLED/SURVIVED), `12dd45b419d9`(NON_KILLED/SURVIVED), `03fe978066fa`(KILLED/KILLED), `a9745066815e`(NON_KILLED/SURVIVED) |
| 25 | `5c2981ad80f33214fb0c6b720cfd3b854c42277f40cd86d2041ea4f36baf2b2b` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo | 61 | 430 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo` | `4681686f2f26`(NON_KILLED/NO_COVERAGE), `46159f4c1a0f`(NON_KILLED/NO_COVERAGE), `b91468eee9d5`(KILLED/KILLED), `6e45cd86eec2`(NON_KILLED/NO_COVERAGE), `fefd169fd4b2`(NON_KILLED/NO_COVERAGE), `246b6fba6aab`(NON_KILLED/NO_COVERAGE), `37470da71000`(KILLED/KILLED) |
| 26 | `611bdacfe9f54230f4c152506c0491fa01445042d992cacdfaad2cee031d2cd4` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 14 | 95 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 27 | `6c077df4e378bce13bd76dac8c0f46b4fdae2c7cd81108157512b61cced8de20` | NO_COVERAGE | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `NegateConditionalsMutator` | negated conditional | 138 | 547 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `ca0bada2431c`(KILLED/KILLED), `abcb69cf55a0`(KILLED/KILLED), `477068444b35`(NON_KILLED/NO_COVERAGE), `4288c9ca4e8d`(KILLED/KILLED), `aff15f3550cd`(KILLED/KILLED), `7165c3227c30`(KILLED/KILLED), `75833d2d554e`(KILLED/KILLED), `4b373fdebbce`(KILLED/KILLED), `7d907ad74364`(KILLED/KILLED), `26e44096428c`(KILLED/KILLED), `53697b2d077e`(KILLED/KILLED), `8e228ad7ea2d`(KILLED/KILLED), `ac83ce2e9393`(KILLED/KILLED), `7dda948fc68f`(KILLED/KILLED), `fafed97d42fd`(KILLED/KILLED), `46184b1a9fab`(KILLED/KILLED), `d9c41d45bb17`(KILLED/KILLED), `e0f8eeb27938`(KILLED/KILLED), `b02d448cd873`(KILLED/KILLED), `51abee92922e`(KILLED/KILLED), `55e523c4e303`(KILLED/KILLED), `8c5fcb2064c4`(KILLED/KILLED), `e6c50edec0cc`(KILLED/KILLED), `da7de7326bbe`(KILLED/KILLED), `14e6955eae1d`(KILLED/KILLED), `4143d82d7478`(KILLED/KILLED), `6a5baa99db06`(KILLED/KILLED), `1082270f835b`(KILLED/KILLED), `b2527301e99a`(KILLED/KILLED), `d8540bd51f41`(KILLED/KILLED), `0cfa472a9557`(NON_KILLED/NO_COVERAGE), `d3d159737c17`(NON_KILLED/NO_COVERAGE), `0745e3647ebf`(NON_KILLED/NO_COVERAGE), `fa4e11a4ecb4`(NON_KILLED/NO_COVERAGE), `b1e44ec99123`(NON_KILLED/NO_COVERAGE), `4e9834c5b540`(NON_KILLED/NO_COVERAGE), `86e1f7ed67ea`(NON_KILLED/NO_COVERAGE) |
| 28 | `6e83174c379e04bed5f19fcf1b87028e3230064cd5c85e570c00e164acf9dada` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NegateConditionalsMutator` | negated conditional | 11 | 74 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `5e13cf15e29c`(KILLED/KILLED), `acb3b3ec95bc`(KILLED/KILLED), `73418182fa85`(NON_KILLED/TIMED_OUT), `97b1be0758de`(NON_KILLED/TIMED_OUT), `4a35eba5008e`(KILLED/KILLED), `350a94d49928`(NON_KILLED/TIMED_OUT), `88dec573bedb`(NON_KILLED/TIMED_OUT), `4fc4c332ed03`(KILLED/KILLED), `6d79865a9053`(KILLED/KILLED), `5a8ca9067f04`(NON_KILLED/TIMED_OUT), `b0e724190b29`(NON_KILLED/TIMED_OUT), `1f025dec8de7`(NON_KILLED/TIMED_OUT) |
| 29 | `6f9609fdbbabaf152fc2ccc4f9728c40bcefc962cd0377592fc6684ae18acb58` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NegateConditionalsMutator` | negated conditional | 23 | 160 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `947f05488df3`(NON_KILLED/TIMED_OUT), `5ffadc16dab2`(NON_KILLED/TIMED_OUT), `e236ea3f276e`(KILLED/KILLED), `afba54a83e11`(NON_KILLED/TIMED_OUT), `a76865f870dc`(NON_KILLED/TIMED_OUT), `1bfda558c38b`(NON_KILLED/TIMED_OUT) |
| 30 | `7cd98a90304d7964683c92622c269f113cd690e65473f8153133d8cfc08fe189` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume | 27 | 159 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` | `083b220893a9`(NON_KILLED/NO_COVERAGE), `b7b2dfec2fa0`(NON_KILLED/NO_COVERAGE), `efacd2b1e0f4`(NON_KILLED/NO_COVERAGE), `c52b419c90e9`(NON_KILLED/NO_COVERAGE), `3555704187bd`(NON_KILLED/NO_COVERAGE), `246350bcbd76`(NON_KILLED/NO_COVERAGE), `976f65569f36`(NON_KILLED/NO_COVERAGE), `32df7c11c787`(NON_KILLED/NO_COVERAGE), `0e8a40e624e1`(NON_KILLED/NO_COVERAGE), `b1e34f861946`(NON_KILLED/NO_COVERAGE), `319b7bee9207`(NON_KILLED/NO_COVERAGE), `d64b7292b499`(NON_KILLED/NO_COVERAGE), `f0cf0add6920`(NON_KILLED/NO_COVERAGE), `0110d7f7665b`(KILLED/KILLED) |
| 31 | `827431d2f104e64e451d5a2c6993ba211604b8ca06048ea4b681fc017ab4815f` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 67 | 459 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 32 | `862220d0ea233a543c5b0ddc516ea15f0d492b868cde8f97ceb44e7608067d36` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo | 73 | 519 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo` | `4681686f2f26`(NON_KILLED/NO_COVERAGE), `46159f4c1a0f`(NON_KILLED/NO_COVERAGE), `b91468eee9d5`(KILLED/KILLED), `6e45cd86eec2`(NON_KILLED/NO_COVERAGE), `fefd169fd4b2`(NON_KILLED/NO_COVERAGE), `246b6fba6aab`(NON_KILLED/NO_COVERAGE), `37470da71000`(KILLED/KILLED) |
| 33 | `8b4d579ff820c98f7f3dac5ee8c86eb942422cf1b8b192ec44992c1881695d39` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 26 | 155 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 34 | `8b52a478cb33035b2af3c59f099290ab6fa8c3812888c33b9893160ce0fa8d22` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo | 36 | 247 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo` | `4681686f2f26`(NON_KILLED/NO_COVERAGE), `46159f4c1a0f`(NON_KILLED/NO_COVERAGE), `b91468eee9d5`(KILLED/KILLED), `6e45cd86eec2`(NON_KILLED/NO_COVERAGE), `fefd169fd4b2`(NON_KILLED/NO_COVERAGE), `246b6fba6aab`(NON_KILLED/NO_COVERAGE), `37470da71000`(KILLED/KILLED) |
| 35 | `9b9bfadffcdc04d8b2b0b8c2a07f6d86863e455634d52cded8e90259765ea318` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 42 | 264 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 36 | `9bc111bcf8e78ae2fe81a4ff243faf5d3b0185fd352d472f6e7fc722b696f19c` | NO_COVERAGE | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `NegateConditionalsMutator` | negated conditional | 149 | 585 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `ca0bada2431c`(KILLED/KILLED), `abcb69cf55a0`(KILLED/KILLED), `477068444b35`(NON_KILLED/NO_COVERAGE), `4288c9ca4e8d`(KILLED/KILLED), `aff15f3550cd`(KILLED/KILLED), `7165c3227c30`(KILLED/KILLED), `75833d2d554e`(KILLED/KILLED), `4b373fdebbce`(KILLED/KILLED), `7d907ad74364`(KILLED/KILLED), `26e44096428c`(KILLED/KILLED), `53697b2d077e`(KILLED/KILLED), `8e228ad7ea2d`(KILLED/KILLED), `ac83ce2e9393`(KILLED/KILLED), `7dda948fc68f`(KILLED/KILLED), `fafed97d42fd`(KILLED/KILLED), `46184b1a9fab`(KILLED/KILLED), `d9c41d45bb17`(KILLED/KILLED), `e0f8eeb27938`(KILLED/KILLED), `b02d448cd873`(KILLED/KILLED), `51abee92922e`(KILLED/KILLED), `55e523c4e303`(KILLED/KILLED), `8c5fcb2064c4`(KILLED/KILLED), `e6c50edec0cc`(KILLED/KILLED), `da7de7326bbe`(KILLED/KILLED), `14e6955eae1d`(KILLED/KILLED), `4143d82d7478`(KILLED/KILLED), `6a5baa99db06`(KILLED/KILLED), `1082270f835b`(KILLED/KILLED), `b2527301e99a`(KILLED/KILLED), `d8540bd51f41`(KILLED/KILLED), `0cfa472a9557`(NON_KILLED/NO_COVERAGE), `d3d159737c17`(NON_KILLED/NO_COVERAGE), `0745e3647ebf`(NON_KILLED/NO_COVERAGE), `fa4e11a4ecb4`(NON_KILLED/NO_COVERAGE), `b1e44ec99123`(NON_KILLED/NO_COVERAGE), `4e9834c5b540`(NON_KILLED/NO_COVERAGE), `86e1f7ed67ea`(NON_KILLED/NO_COVERAGE) |
| 37 | `9f7ce30fc6da401efe187481149db12210e987367a547e032c9cc182d0e187be` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NegateConditionalsMutator` | negated conditional | 60 | 426 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `947f05488df3`(NON_KILLED/TIMED_OUT), `5ffadc16dab2`(NON_KILLED/TIMED_OUT), `e236ea3f276e`(KILLED/KILLED), `afba54a83e11`(NON_KILLED/TIMED_OUT), `a76865f870dc`(NON_KILLED/TIMED_OUT), `1bfda558c38b`(NON_KILLED/TIMED_OUT) |
| 38 | `a9760bea3a92a752255a2becee7edb3ffa0957cf0e88cb75a0a11db850dcc747` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 76 | 533 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 39 | `c3a3a1cf672fe44f75d9cee697e72d78449b353258d8cba49600c62021beaa31` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NegateConditionalsMutator` | negated conditional | 35 | 206 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `5e13cf15e29c`(KILLED/KILLED), `acb3b3ec95bc`(KILLED/KILLED), `73418182fa85`(NON_KILLED/TIMED_OUT), `97b1be0758de`(NON_KILLED/TIMED_OUT), `4a35eba5008e`(KILLED/KILLED), `350a94d49928`(NON_KILLED/TIMED_OUT), `88dec573bedb`(NON_KILLED/TIMED_OUT), `4fc4c332ed03`(KILLED/KILLED), `6d79865a9053`(KILLED/KILLED), `5a8ca9067f04`(NON_KILLED/TIMED_OUT), `b0e724190b29`(NON_KILLED/TIMED_OUT), `1f025dec8de7`(NON_KILLED/TIMED_OUT) |
| 40 | `ce65c0b75a83a7cbaf0ff52ed87aee8fe45d571587e78da263663124ea64326d` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `suspendToolExecution` | `NegateConditionalsMutator` | negated conditional | 157 | 1067 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|suspendToolExecution|(Ldev/tramai/engine/approval/SuspendToolExecutionRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `5e13cf15e29c`(KILLED/KILLED), `acb3b3ec95bc`(KILLED/KILLED), `73418182fa85`(NON_KILLED/TIMED_OUT), `97b1be0758de`(NON_KILLED/TIMED_OUT), `4a35eba5008e`(KILLED/KILLED), `350a94d49928`(NON_KILLED/TIMED_OUT), `88dec573bedb`(NON_KILLED/TIMED_OUT), `4fc4c332ed03`(KILLED/KILLED), `6d79865a9053`(KILLED/KILLED), `5a8ca9067f04`(NON_KILLED/TIMED_OUT), `b0e724190b29`(NON_KILLED/TIMED_OUT), `1f025dec8de7`(NON_KILLED/TIMED_OUT) |
| 41 | `d20dbac385cb8d5a09e313541a2dcf177b7c0f390c6ae0cf9fafe1a5c072bd02` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume | 106 | 708 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` | `083b220893a9`(NON_KILLED/NO_COVERAGE), `b7b2dfec2fa0`(NON_KILLED/NO_COVERAGE), `efacd2b1e0f4`(NON_KILLED/NO_COVERAGE), `c52b419c90e9`(NON_KILLED/NO_COVERAGE), `3555704187bd`(NON_KILLED/NO_COVERAGE), `246350bcbd76`(NON_KILLED/NO_COVERAGE), `976f65569f36`(NON_KILLED/NO_COVERAGE), `32df7c11c787`(NON_KILLED/NO_COVERAGE), `0e8a40e624e1`(NON_KILLED/NO_COVERAGE), `b1e34f861946`(NON_KILLED/NO_COVERAGE), `319b7bee9207`(NON_KILLED/NO_COVERAGE), `d64b7292b499`(NON_KILLED/NO_COVERAGE), `f0cf0add6920`(NON_KILLED/NO_COVERAGE), `0110d7f7665b`(KILLED/KILLED) |
| 42 | `d3f812e883d41cbb8c11e3225d66974589a48842fafdd8a4f9fa41ee8008dcca` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume | 10 | 63 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` | `083b220893a9`(NON_KILLED/NO_COVERAGE), `b7b2dfec2fa0`(NON_KILLED/NO_COVERAGE), `efacd2b1e0f4`(NON_KILLED/NO_COVERAGE), `c52b419c90e9`(NON_KILLED/NO_COVERAGE), `3555704187bd`(NON_KILLED/NO_COVERAGE), `246350bcbd76`(NON_KILLED/NO_COVERAGE), `976f65569f36`(NON_KILLED/NO_COVERAGE), `32df7c11c787`(NON_KILLED/NO_COVERAGE), `0e8a40e624e1`(NON_KILLED/NO_COVERAGE), `b1e34f861946`(NON_KILLED/NO_COVERAGE), `319b7bee9207`(NON_KILLED/NO_COVERAGE), `d64b7292b499`(NON_KILLED/NO_COVERAGE), `f0cf0add6920`(NON_KILLED/NO_COVERAGE), `0110d7f7665b`(KILLED/KILLED) |
| 43 | `e118041e3a76ebc8af348ce6431c9cf24871161652481cfd5bdee100d0829894` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 105 | 704 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 44 | `e3d83f9421a20ebe9993912caa7c52d98e0e157efd276c1a43915b5b1ee15724` | NO_COVERAGE | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `NegateConditionalsMutator` | negated conditional | 141 | 555 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `ca0bada2431c`(KILLED/KILLED), `abcb69cf55a0`(KILLED/KILLED), `477068444b35`(NON_KILLED/NO_COVERAGE), `4288c9ca4e8d`(KILLED/KILLED), `aff15f3550cd`(KILLED/KILLED), `7165c3227c30`(KILLED/KILLED), `75833d2d554e`(KILLED/KILLED), `4b373fdebbce`(KILLED/KILLED), `7d907ad74364`(KILLED/KILLED), `26e44096428c`(KILLED/KILLED), `53697b2d077e`(KILLED/KILLED), `8e228ad7ea2d`(KILLED/KILLED), `ac83ce2e9393`(KILLED/KILLED), `7dda948fc68f`(KILLED/KILLED), `fafed97d42fd`(KILLED/KILLED), `46184b1a9fab`(KILLED/KILLED), `d9c41d45bb17`(KILLED/KILLED), `e0f8eeb27938`(KILLED/KILLED), `b02d448cd873`(KILLED/KILLED), `51abee92922e`(KILLED/KILLED), `55e523c4e303`(KILLED/KILLED), `8c5fcb2064c4`(KILLED/KILLED), `e6c50edec0cc`(KILLED/KILLED), `da7de7326bbe`(KILLED/KILLED), `14e6955eae1d`(KILLED/KILLED), `4143d82d7478`(KILLED/KILLED), `6a5baa99db06`(KILLED/KILLED), `1082270f835b`(KILLED/KILLED), `b2527301e99a`(KILLED/KILLED), `d8540bd51f41`(KILLED/KILLED), `0cfa472a9557`(NON_KILLED/NO_COVERAGE), `d3d159737c17`(NON_KILLED/NO_COVERAGE), `0745e3647ebf`(NON_KILLED/NO_COVERAGE), `fa4e11a4ecb4`(NON_KILLED/NO_COVERAGE), `b1e44ec99123`(NON_KILLED/NO_COVERAGE), `4e9834c5b540`(NON_KILLED/NO_COVERAGE), `86e1f7ed67ea`(NON_KILLED/NO_COVERAGE) |
| 45 | `e44e3a71b7ef3e7a1ac87d6f7c96dfb9d33350285ed9dc9512e3d99a88276a42` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NegateConditionalsMutator` | negated conditional | 46 | 328 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `947f05488df3`(NON_KILLED/TIMED_OUT), `5ffadc16dab2`(NON_KILLED/TIMED_OUT), `e236ea3f276e`(KILLED/KILLED), `afba54a83e11`(NON_KILLED/TIMED_OUT), `a76865f870dc`(NON_KILLED/TIMED_OUT), `1bfda558c38b`(NON_KILLED/TIMED_OUT) |
| 46 | `e52f1c1f657020d5f63b247fbc7fd432c886258cf5059a5e2a9a5fdc4981bc32` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo | 10 | 72 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requestApproval-Atj0Sqo` | `4681686f2f26`(NON_KILLED/NO_COVERAGE), `46159f4c1a0f`(NON_KILLED/NO_COVERAGE), `b91468eee9d5`(KILLED/KILLED), `6e45cd86eec2`(NON_KILLED/NO_COVERAGE), `fefd169fd4b2`(NON_KILLED/NO_COVERAGE), `246b6fba6aab`(NON_KILLED/NO_COVERAGE), `37470da71000`(KILLED/KILLED) |
| 47 | `e78cb26e67aa7d640e9ff5e9e31d267e7aa84b1308357a7312c82c3342bb01cb` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NegateConditionalsMutator` | negated conditional | 85 | 575 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `bd46cba9bba9`(NON_KILLED/TIMED_OUT), `130b60fa7f07`(KILLED/KILLED), `59bbf2146636`(KILLED/KILLED), `85ae74cb2ffc`(NON_KILLED/TIMED_OUT), `2155f6a06a78`(KILLED/KILLED), `e200bda99ea8`(NON_KILLED/TIMED_OUT), `319df689ce2b`(NON_KILLED/TIMED_OUT), `8c909e76359d`(NON_KILLED/TIMED_OUT), `f43a1391235f`(KILLED/KILLED), `9e05d5b73798`(NON_KILLED/NO_COVERAGE), `216bc036a8ad`(KILLED/KILLED), `0c6a0f5da99c`(NON_KILLED/NO_COVERAGE), `cfa7fa1e346a`(KILLED/KILLED), `05abfbc4e980`(NON_KILLED/TIMED_OUT), `bb7c63ab70f6`(NON_KILLED/TIMED_OUT), `a9e240548004`(NON_KILLED/TIMED_OUT), `e52fd6ae714d`(NON_KILLED/TIMED_OUT), `692b201e3aa1`(NON_KILLED/SURVIVED), `1fb81d5d3133`(NON_KILLED/TIMED_OUT), `995ddfdf0973`(KILLED/KILLED), `f2f3de5d9785`(NON_KILLED/TIMED_OUT) |
| 48 | `ef7dc1d6b1d5c625fa2423bce4ce749fb00a5ee310efec28cf791f474c20ebac` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `executeClaimedResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::executeClaimedResume | 20 | 171 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|executeClaimedResume|(Ldev/tramai/engine/approval/ResumeExecutionContext;Ldev/tramai/core/approval/ClaimedApprovalContinuation;Ldev/tramai/core/approval/ApprovalContinuationStore;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::executeClaimedResume` | `950b0b5ac5a9`(NON_KILLED/NO_COVERAGE), `be663ad795d6`(NON_KILLED/NO_COVERAGE), `4948fdbaf262`(NON_KILLED/NO_COVERAGE), `56b12a836fef`(KILLED/KILLED) |
| 49 | `f0effc0ace61dc60a5f7c3950d67dd8d9dd453fe9f848d569d8f1ddd6d962625` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `resume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume | 43 | 268 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|resume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::resume` | `083b220893a9`(NON_KILLED/NO_COVERAGE), `b7b2dfec2fa0`(NON_KILLED/NO_COVERAGE), `efacd2b1e0f4`(NON_KILLED/NO_COVERAGE), `c52b419c90e9`(NON_KILLED/NO_COVERAGE), `3555704187bd`(NON_KILLED/NO_COVERAGE), `246350bcbd76`(NON_KILLED/NO_COVERAGE), `976f65569f36`(NON_KILLED/NO_COVERAGE), `32df7c11c787`(NON_KILLED/NO_COVERAGE), `0e8a40e624e1`(NON_KILLED/NO_COVERAGE), `b1e34f861946`(NON_KILLED/NO_COVERAGE), `319b7bee9207`(NON_KILLED/NO_COVERAGE), `d64b7292b499`(NON_KILLED/NO_COVERAGE), `f0cf0add6920`(NON_KILLED/NO_COVERAGE), `0110d7f7665b`(KILLED/KILLED) |
| 50 | `f5e8c455520a32d816751b0ec4adb9d4619a38afdf67b81256a615a68b779590` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requestApproval-Atj0Sqo` | `NegateConditionalsMutator` | negated conditional | 9 | 68 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requestApproval-Atj0Sqo|(Ljava/lang/String;Ldev/tramai/core/approval/gateway/ApprovalRecommendation;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `947f05488df3`(NON_KILLED/TIMED_OUT), `5ffadc16dab2`(NON_KILLED/TIMED_OUT), `e236ea3f276e`(KILLED/KILLED), `afba54a83e11`(NON_KILLED/TIMED_OUT), `a76865f870dc`(NON_KILLED/TIMED_OUT), `1bfda558c38b`(NON_KILLED/TIMED_OUT) |
| 51 | `fbb04e20399512619fdafc983bfd38f4239bd266d9bd2b8323ba8541450cd17f` | NO_COVERAGE | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `NegateConditionalsMutator` | negated conditional | 158 | 622 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | `ca0bada2431c`(KILLED/KILLED), `abcb69cf55a0`(KILLED/KILLED), `477068444b35`(NON_KILLED/NO_COVERAGE), `4288c9ca4e8d`(KILLED/KILLED), `aff15f3550cd`(KILLED/KILLED), `7165c3227c30`(KILLED/KILLED), `75833d2d554e`(KILLED/KILLED), `4b373fdebbce`(KILLED/KILLED), `7d907ad74364`(KILLED/KILLED), `26e44096428c`(KILLED/KILLED), `53697b2d077e`(KILLED/KILLED), `8e228ad7ea2d`(KILLED/KILLED), `ac83ce2e9393`(KILLED/KILLED), `7dda948fc68f`(KILLED/KILLED), `fafed97d42fd`(KILLED/KILLED), `46184b1a9fab`(KILLED/KILLED), `d9c41d45bb17`(KILLED/KILLED), `e0f8eeb27938`(KILLED/KILLED), `b02d448cd873`(KILLED/KILLED), `51abee92922e`(KILLED/KILLED), `55e523c4e303`(KILLED/KILLED), `8c5fcb2064c4`(KILLED/KILLED), `e6c50edec0cc`(KILLED/KILLED), `da7de7326bbe`(KILLED/KILLED), `14e6955eae1d`(KILLED/KILLED), `4143d82d7478`(KILLED/KILLED), `6a5baa99db06`(KILLED/KILLED), `1082270f835b`(KILLED/KILLED), `b2527301e99a`(KILLED/KILLED), `d8540bd51f41`(KILLED/KILLED), `0cfa472a9557`(NON_KILLED/NO_COVERAGE), `d3d159737c17`(NON_KILLED/NO_COVERAGE), `0745e3647ebf`(NON_KILLED/NO_COVERAGE), `fa4e11a4ecb4`(NON_KILLED/NO_COVERAGE), `b1e44ec99123`(NON_KILLED/NO_COVERAGE), `4e9834c5b540`(NON_KILLED/NO_COVERAGE), `86e1f7ed67ea`(NON_KILLED/NO_COVERAGE) |

### 6.4 Category C — GENUINELY_NEW_NON_KILLED (107 identities)

| # | candidate identity | raw status | family | module | class | method | mutator | description | block | index | source-level key | predecessors at key |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `04ed49ac905fc2abdf7cfdd761e7582bd7ac491aa223a084e13e742d0d09d33f` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.GovernedApprovalStoreKt` | `requireAttributionMatchesBinding` | `NegateConditionalsMutator` | negated conditional | 7 | 25 | `:tramai-engine|dev.tramai.engine.approval.GovernedApprovalStoreKt|requireAttributionMatchesBinding|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/engine/approval/ApprovalRunAttribution;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 2 | `050d2b83c8527c768c73ba7fdaa77021fd04a05638d3b665a724cfccd98f9164` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `NegateConditionalsMutator` | negated conditional | 12 | 74 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 3 | `062ef5efb7ee873d74da6c82da726c85406b3df1c9ae0040aa715081a88a3ef8` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NegateConditionalsMutator` | negated conditional | 24 | 131 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 4 | `080c1a247bb10d6724a005dc6a718e8566dea770b07383aff5f3f04c0c6abdb5` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3` | `invokeSuspend` | `NegateConditionalsMutator` | negated conditional | 4 | 29 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 5 | `09642639c5ec0e9791214208bb1d953a203adfc7ba5bd8fde1ef175d780df17f` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2` | `invokeSuspend` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator$resume$2::invokeSuspend | 6 | 36 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator$resume$2::invokeSuspend` | — |
| 6 | `098258d70a2379a58e39d24f5f85824705998bb6d3e20cf0bec54ffecc4db0a6` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 44 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 7 | `09ea31d4d0e8b35c80543a6c812d206ce3d949fad35e2c5ad14e48e1332f89c0` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2` | `invokeSuspend` | `NegateConditionalsMutator` | negated conditional | 5 | 32 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 8 | `0df4e1e91cdadf04e0f755db04b79fb465972d0f446ffbc9944aab98a8c53512` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `NegateConditionalsMutator` | negated conditional | 53 | 271 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 9 | `10ee55f449883c987fd7019d46d5d32380e072a8b669f78aa614058c6123864f` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateSuspension | 13 | 94 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateSuspension` | — |
| 10 | `15c7def4f5c98cbdea1a90b5b4fd44978a3d90d13c30d2beafccf83997250120` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 27 | 154 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 11 | `1a9e9e35471793c09bcb21e205b59c2a76215adcb1c069794669ab52a0b65f56` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.GovernedContinuationContinuityKt` | `requireGovernedContinuity` | `NegateConditionalsMutator` | negated conditional | 4 | 29 | `:tramai-engine|dev.tramai.engine.approval.GovernedContinuationContinuityKt|requireGovernedContinuity|(Ljava/lang/String;Ldev/tramai/core/identity/GovernedRunIdentity;Ldev/tramai/core/identity/GovernedRunIdentity;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 12 | `1b17d7d30c234dcb3e0e8081542d742c2c5ec6b4716c5ba787b4edf3be5a22a1` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requireExistingAttributionMatches` | `NegateConditionalsMutator` | negated conditional | 14 | 79 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requireExistingAttributionMatches|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 13 | `1b309f9a702a4e6ceecf930f61718bb260539696cfa516ce53ad1897291bb436` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 12 | 73 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 14 | `1b8938aaabc6e8c69890f8c7ac50b50a6d0e01f2bcb3c3d82049d3bba33735b2` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 14 | 122 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 15 | `1c4ca901fd8d56ace18e167d294242db6cfdd8a6f551c7860dfaa934ea26ce54` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2` | `invokeSuspend` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 6 | 37 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 16 | `1e85193edcd55874f3bdb58865290bcb1dc9353f0175bbe17ba1aced419ad902` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistGoverned | 13 | 78 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistGoverned` | — |
| 17 | `2436750ccd7646d3803f335216c2fdecf27a58b90acb8250b5929a634201d5c5` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `NegateConditionalsMutator` | negated conditional | 17 | 101 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 18 | `2b197f5cd3cba2e7f02c6ebfc6701d945e397b4166f04f4218a2e591892b85fe` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateStep` | `NegateConditionalsMutator` | negated conditional | 10 | 63 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateStep|(Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 19 | `2d1f9e373009b20d4462805571e0e9435e599916a76d4bc78d3d2ccd6525f9b2` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::authorizeResume | 12 | 71 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::authorizeResume` | — |
| 20 | `2f92ef797c538ab858fdeca1c5a464a8bba2c633cf74bad2fac99d756f42ca65` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 63 | 470 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 21 | `38af093087fd0acbd846859277155e9155f29f59b79c59dd9a0c8d6bf86c6355` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateStep` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 12 | 77 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateStep|(Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 22 | `39f37feb8af8811303e70d73fea29138e2cc3c866330e3d1f5371e94239a92e8` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume | 62 | 427 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume` | — |
| 23 | `3a5c5213b226f1f86b48c12172489ee618b9af890d750ab3ac1d7d2032854e0a` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateStep` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateStep | 17 | 106 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateStep|(Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateStep` | — |
| 24 | `3fa7e539cb12045632a1d4ed3c9ff9355785c024410c6896c2cd3882b6a0912a` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `NegateConditionalsMutator` | negated conditional | 20 | 164 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 25 | `492f9a132e141f512c5690d8b0d43c6e5925b488de13d80ef71504bcf61b62d4` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2` | `invokeSuspend` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 40 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 26 | `4be603cfac01a955a396208293347fa218b4f0f364a6bea0e851e58e327678cc` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `NegateConditionalsMutator` | negated conditional | 37 | 195 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 27 | `4cca31fe707f13c869b7fa55a507661638e60a62cdb13f1b7cf5cb3899e023ae` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume | 40 | 228 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume` | — |
| 28 | `4e3ec3205822664846f9e727b232647706a9535829fc83bb7a55dbd4fd2baf4b` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NegateConditionalsMutator` | negated conditional | 39 | 224 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 29 | `4fc586ff85c68cfdeca566abb58fcfc7986945efb5540844990d16a75ed127bb` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 32 | 271 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 30 | `56d3366434a6608d7b5ccd59b6c1377be7324ab2078ec7a09b872153968e4466` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requireExistingAttributionMatches` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requireExistingAttributionMatches | 9 | 50 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requireExistingAttributionMatches|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requireExistingAttributionMatches` | — |
| 31 | `57a4c81569e7d75271f721a4f689845a31ca3569b00af2fd980d76a0ae1a2393` | SURVIVED | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | `validateMetadata` | `VoidMethodCallMutator` | removed call to dev/tramai/security/evidence/RuntimeEvidenceAttribution::validate | 12 | 51 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceContractValidator|validateMetadata|(Ldev/tramai/security/evidence/RuntimeEvidenceRecord;)V|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to dev/tramai/security/evidence/RuntimeEvidenceAttribution::validate` | — |
| 32 | `5ac56e0a701ab6fcde19f86e640b34609b0ebeb64437e7ae185479282e7a6c7d` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requireExistingAttributionMatches` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 16 | 100 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requireExistingAttributionMatches|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 33 | `5cae2063c995e0938a94146e5e66b1b173eb4e205fac4339449f644c85deb22d` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::authorizeResume | 54 | 275 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::authorizeResume` | — |
| 34 | `5d4ca8196b51364c881844ac42e5b155040463a57047cb308bafd4cda258af34` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `persistSuspendedInvocation` | `NegateConditionalsMutator` | negated conditional | 7 | 30 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|persistSuspendedInvocation|(Ldev/tramai/engine/approval/ApprovalSuspensionCoordinator$GovernedSuspension;Ldev/tramai/engine/SuspendedInvocationMetadata;Ldev/tramai/engine/SensitiveReplayEnvelope;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 35 | `60f7b020e009cc99a8a80e19e34a956e570614449294444db057993e433b1ac6` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateStep` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 44 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateStep|(Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 36 | `613bf2d479166ff51b50e27060a2442f69fa2d20a9c103b75c5147e8264d3f7f` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 55 | 369 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 37 | `63e5372ba37bc3b097def60c22bddd050404d168b26c2fa73bc94a99a6705c1e` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 55 | 292 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 38 | `64b71d7ed973b9d0863a93fa15b70e0b193f58ccdfc48b2eea575f34f61956be` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::authorizeResume | 24 | 133 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::authorizeResume` | — |
| 39 | `6759437252f37022e139ccaa2ca729226147768a1d7972fcd59317f185d965a4` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::authorizeResume | 38 | 199 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::authorizeResume` | — |
| 40 | `6a1ce2f46d2ad5cdb7fb857a56fcf4ca0d3d87ffc3825279121386e04c3f7c88` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requireExistingAttributionMatches` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requireExistingAttributionMatches | 26 | 141 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requireExistingAttributionMatches|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requireExistingAttributionMatches` | — |
| 41 | `6cef3fd2e23d3df1571d390564b8274596c70c97965bc5719603d39116b74b8c` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2` | `invokeSuspend` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 2 | 12 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 42 | `6dc734145518bb488ac0b6d202711c3d1b0c1d804489ed3d9b09453e75db96a1` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `persistSuspendedInvocation` | `NegateConditionalsMutator` | negated conditional | 12 | 50 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|persistSuspendedInvocation|(Ldev/tramai/engine/approval/ApprovalSuspensionCoordinator$GovernedSuspension;Ldev/tramai/engine/SuspendedInvocationMetadata;Ldev/tramai/engine/SensitiveReplayEnvelope;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 43 | `70d345f72064e7cea4ebb9cff97f74946f00d582ba38a4e62c5498261494b0a0` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3` | `invokeSuspend` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$3::invokeSuspend | 8 | 44 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$3::invokeSuspend` | — |
| 44 | `75ec22b0e703bb8a54ddbde9d6edab92e1e3fbbff3318021b9a6d11eccca21ec` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 33 | 197 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 45 | `7b156f3a3c6265cf6e474c7001a4e6f06d54cbae3d042616889451e5629ba0a6` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistUngoverned | 11 | 65 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistUngoverned` | — |
| 46 | `7d3fc3ab2af8aff687f6b242abb14d98d96af1914e515dda317bb7c38de55767` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistGoverned | 32 | 185 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistGoverned` | — |
| 47 | `7e799e77e8c7396e770465175fde071720cab896296f61dab7fca18d7df87572` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistGoverned | 23 | 136 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistGoverned` | — |
| 48 | `7f06a51c79515d82dcabce9af7ae1d77d8a3691fd2b99528f3e17212204ee512` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `NegateConditionalsMutator` | negated conditional | 30 | 239 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 49 | `84224c763190937a1428a51988461c3f8669d81c7cb2e8476e35aa8e03b15cf9` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 19 | 113 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 50 | `8510790e9203bf585125a30cf5178e0002d129f7580ddc36fd928ed8a9ea1ad5` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 41 | 251 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 51 | `8610dc7e970e4c48ae0d47971626c7769a141837b34d53d5610af7d596bf8593` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `NegateConditionalsMutator` | negated conditional | 12 | 90 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 52 | `887313eb0142a0add13da39b7d198c8bcc351ac8cd2dd1639028d47d7a2fd38e` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 22 | 196 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 53 | `8abddce868884ec7da02fd97518d43894ab3009ec7433721ad4580505f09b4fb` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1` | `invokeSuspend` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$1::invokeSuspend | 6 | 32 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$1::invokeSuspend` | — |
| 54 | `8cdddbe0775a84fda14fe7bb987e3d4caff65dc0c20934acd8abf538a07afb41` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume | 25 | 135 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume` | — |
| 55 | `8d5bb41c2eaa72679422c10b5d20524d7605a23c839b989c83952f93eb0cebcf` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1` | `invokeSuspend` | `NegateConditionalsMutator` | negated conditional | 5 | 28 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 56 | `8e18b4b48968d65f181bd226d6e81d99c6c9abb3427463ae77773eb4c901e658` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalRunAttributionKt` | `decodeApprovalAttribution` | `NegateConditionalsMutator` | negated conditional | 22 | 127 | `:tramai-engine|dev.tramai.engine.approval.ApprovalRunAttributionKt|decodeApprovalAttribution|(Ljava/lang/String;Ljava/util/Map;)Ldev/tramai/engine/approval/ApprovalRunAttribution;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 57 | `9516ca2fa7750c16a31995eaa14e2f651d4e5798089a11f5053e01d141591f0d` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 25 | 150 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 58 | `95f4d3181b61682eafec19984f4bd5ebfac46cde52bacaa5a4d18f21814e78ee` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateStep` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateStep | 11 | 67 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateStep|(Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateStep` | — |
| 59 | `980c70e5ac83cda9c9fd96339e82d200255c2e12afe527b95f998666c1249806` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2` | `invokeSuspend` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$2::invokeSuspend | 5 | 33 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$2::invokeSuspend` | — |
| 60 | `9c4c3454c7f772591a2a5b5f468c2d0f281ddbd5a5d8945aa0577a366d2be1a8` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 14 | 90 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 61 | `9e3d60d7a85525416ee27ca9ce1abac74612ec1ba1134a64f13b3148d85cbd04` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NegateConditionalsMutator` | negated conditional | 11 | 68 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 62 | `a14d63a582a3626d45aae6a2c10f651e5507b6bb2a1c0a3ac38ebace47045f0c` | SURVIVED | evidence | :tramai-security | `dev.tramai.security.evidence.RuntimeEvidenceAttribution` | `getMetadataKeys` | `EmptyObjectReturnValsMutator` | replaced return value with Collections.emptySet for dev/tramai/security/evidence/RuntimeEvidenceAttribution::getMetadataKeys | 0 | 4 | `:tramai-security|dev.tramai.security.evidence.RuntimeEvidenceAttribution|getMetadataKeys|()Ljava/util/Set;|org.pitest.mutationtest.engine.gregor.mutators.returns.EmptyObjectReturnValsMutator|replaced return value with Collections.emptySet for dev/tramai/security/evidence/RuntimeEvidenceAttribution::getMetadataKeys` | — |
| 63 | `a15f9807befc68bd1e00747e77a09e147656195cac0833b68904cb326ccf0a5b` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3` | `invokeSuspend` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 6 | 37 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 64 | `a25dbb8c0826d5df57d94e0003d897c2ac066acefe43d7f1e087414be5fc3feb` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NegateConditionalsMutator` | negated conditional | 61 | 423 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 65 | `a4dbacf1299871940f52d31fa1bad147b674028106db00cf0d32545eafb93303` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistUngoverned | 18 | 105 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistUngoverned` | — |
| 66 | `a542bbe6db3f9f551bbbea6972a28dc670945d1232221bd1d9a3f98814a6defb` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistGoverned | 35 | 204 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistGoverned` | — |
| 67 | `ab78ed8577ff2073f4690730365919999ad7f76ddb68bef5c56a08deeef15c31` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 44 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 68 | `ae609c23ce293d1e7c19723fbfe113c0644d63437e360e323bad136091f7d1e1` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `NegateConditionalsMutator` | negated conditional | 25 | 142 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 69 | `af8cf7ad147d631f613f8f05cbd1a3d3ab2987fbcfb9a0b576fa5399b61639b6` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateSuspension | 31 | 243 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateSuspension` | — |
| 70 | `b1d24bc23a862911a495013126f2713cf5443baeee820ee8da3f66f81a54c645` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 39 | 216 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 71 | `b36fc3ca50a326d7924fe14e278a2c4902307649d2b15ad19aef66d7ab17ed7b` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `NegateConditionalsMutator` | negated conditional | 23 | 129 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 72 | `b4bde9e3e32ec4786c9201e8ed557700e977626dd65a2e5a82893cbc2ca402bc` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `persistSuspendedInvocation` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::persistSuspendedInvocation | 8 | 31 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|persistSuspendedInvocation|(Ldev/tramai/engine/approval/ApprovalSuspensionCoordinator$GovernedSuspension;Ldev/tramai/engine/SuspendedInvocationMetadata;Ldev/tramai/engine/SensitiveReplayEnvelope;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::persistSuspendedInvocation` | — |
| 73 | `b545c2ae147a0ed5c6e743c446f179d2998dea15a0b781fd9131aa3ae18799fd` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 26 | 153 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 74 | `c3509c6487f8933fbec46bb166eba697f54ba5808453016a45978d21953c7e53` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NegateConditionalsMutator` | negated conditional | 53 | 332 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 75 | `c67230adf1097aeef5217c95976e0d6f85dd129a70f205e98ee97acb15bd383e` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.GovernedApprovalStoreKt` | `requireAttributionMatchesBinding` | `NegateConditionalsMutator` | negated conditional | 2 | 12 | `:tramai-engine|dev.tramai.engine.approval.GovernedApprovalStoreKt|requireAttributionMatchesBinding|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/engine/approval/ApprovalRunAttribution;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 76 | `c6784c2618c57ec3eaba846ee2dab13b696e32ebcfcb6a6312d7c550bfa23412` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `persistSuspendedInvocation` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::persistSuspendedInvocation | 14 | 58 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|persistSuspendedInvocation|(Ldev/tramai/engine/approval/ApprovalSuspensionCoordinator$GovernedSuspension;Ldev/tramai/engine/SuspendedInvocationMetadata;Ldev/tramai/engine/SensitiveReplayEnvelope;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::persistSuspendedInvocation` | — |
| 77 | `c7774df8ccdcefaaac0c151f68b24313cc511a6a0800951cd375dfe217d265b6` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateStep` | `VoidMethodCallMutator` | removed call to dev/tramai/core/coroutines/CancellationKt::rethrowIfCancellation | 16 | 101 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateStep|(Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to dev/tramai/core/coroutines/CancellationKt::rethrowIfCancellation` | — |
| 78 | `c98ac92dc495b7162789fc13c1d2318f1f5d2aa07bb3c051df7946fe638d3cff` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `NegateConditionalsMutator` | negated conditional | 22 | 132 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 79 | `cb90dfa953b5b8f3e70f6fcad04d793fe9f952f4d49816dee8be9f3bebf7504d` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 44 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 80 | `ceab2162714ae5159800ad6e1dde34ed859dd7afeb6e64f90f83f48206ea302e` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3` | `invokeSuspend` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$3::invokeSuspend | 5 | 33 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$3::invokeSuspend` | — |
| 81 | `d38ac52f2843448a4df4cba789ae27ddf9df3f73356c9c8ef972f2f03cc180c3` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalRunAttributionKt` | `decodeApprovalAttribution` | `NegateConditionalsMutator` | negated conditional | 24 | 131 | `:tramai-engine|dev.tramai.engine.approval.ApprovalRunAttributionKt|decodeApprovalAttribution|(Ljava/lang/String;Ljava/util/Map;)Ldev/tramai/engine/approval/ApprovalRunAttribution;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 82 | `d5b70d305cec04c7aca39a5a9c629221c88509f4a8ee07d9643b2f8e37d1277f` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 44 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 83 | `d68d0252ca2d0c4e59ad7655508624e507633be914605effb2324b63022ba289` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `NegateConditionalsMutator` | negated conditional | 10 | 61 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 84 | `d8e1290f30007d7296e5509916277dde18d7bf84b9ed568cbebe927dd96a66cb` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 24 | 148 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 85 | `ddf6092edbf61789c9cb31c2d9ce6d98a0a817dc56b587a269ac36125f2ef4d9` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1` | `invokeSuspend` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 36 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 86 | `e09a3c1cc38bcd7ec2214ce9aea881a3b1ffa38ba92f49e8e912e8259d0c2a66` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateSuspension | 21 | 168 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateSuspension` | — |
| 87 | `e0ae69141eabff4855f7acc9e498139795c71ae3aca5a096d468101f3ceca1f0` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2` | `invokeSuspend` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 2 | 12 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 88 | `e22c7dcc1e36b6956165d91403a12a340160639592be69fe070caa3736bd8851` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2` | `invokeSuspend` | `NegateConditionalsMutator` | negated conditional | 4 | 29 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 89 | `e28c35edba409d362d08cd122805137f43a325c9c9a335db1e045b9aca435dfe` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.GovernedApprovalStoreKt` | `requireAttributionMatchesBinding` | `NegateConditionalsMutator` | negated conditional | 5 | 22 | `:tramai-engine|dev.tramai.engine.approval.GovernedApprovalStoreKt|requireAttributionMatchesBinding|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/engine/approval/ApprovalRunAttribution;)V|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 90 | `e2f022edd78a3f2e65a2c14eda3793ca0fa99762aa24513a623d3c1b23cb823a` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume | 54 | 336 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume` | — |
| 91 | `e602fa9990536c07017cc7764ed989526f964e395f82e05d456f7dca523c4610` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistGoverned` | `NegateConditionalsMutator` | negated conditional | 31 | 181 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistGoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 92 | `e8276b8096040f41b037f371b6ca328f710a43c2a1042e37532446a5dcd2be50` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistUngoverned | 26 | 146 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistUngoverned` | — |
| 93 | `eacdfd8c8a0f505292702876fa5450fdd86babd28931cb8600a5894ef07843e3` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 13 | 83 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 94 | `eb21aaed31923633c541c603ebf29c170343b1e822874768f732093a099b3a18` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `persistSuspendedInvocation` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::persistSuspendedInvocation | 9 | 38 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|persistSuspendedInvocation|(Ldev/tramai/engine/approval/ApprovalSuspensionCoordinator$GovernedSuspension;Ldev/tramai/engine/SuspendedInvocationMetadata;Ldev/tramai/engine/SensitiveReplayEnvelope;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::persistSuspendedInvocation` | — |
| 95 | `eb8827c4f4ca1d6b1f90686e5335ffb189d78b4694548a6843f7ebc5d1d95a79` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requireExistingAttributionMatches` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 44 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requireExistingAttributionMatches|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 96 | `ebc96b3e8599f671eb60c470b2a259a474aa994c2599547731b9944800e60b57` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1` | `invokeSuspend` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 2 | 12 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 97 | `ec0280ffb25881b65754a45adb048e4e28f52996812c287c268d8b5cb1c74be0` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `persistSuspendedInvocation` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::persistSuspendedInvocation | 13 | 51 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|persistSuspendedInvocation|(Ldev/tramai/engine/approval/ApprovalSuspensionCoordinator$GovernedSuspension;Ldev/tramai/engine/SuspendedInvocationMetadata;Ldev/tramai/engine/SensitiveReplayEnvelope;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::persistSuspendedInvocation` | — |
| 98 | `eec481d97a434f85884bdc18877d8a54801c8ba289658e0fb49d92e286cad2f7` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3` | `invokeSuspend` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 2 | 12 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 99 | `f175e8698db5459d85e11e56b04155c7b963d148d906f57908c6440dd533fead` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2` | `invokeSuspend` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$2::invokeSuspend | 8 | 44 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$2::invokeSuspend` | — |
| 100 | `f20df64ded8b98458fc68d8583afd170ebbec2fa33a9ed0db1cc349b80225d6d` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 13 | 85 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 101 | `f4463604928886b61038161dfacada30be5fa9dcd62178ac329b83c4568e3ffd` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `persistUngoverned` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistUngoverned | 29 | 161 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|persistUngoverned|(Ldev/tramai/engine/approval/ApprovalGatewayPersistenceRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::persistUngoverned` | — |
| 102 | `f56f28b7d039e3bf8f5790671b10d5202d38555f464e5270aa47c101445b082f` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateSuspension | 36 | 289 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator::compensateSuspension` | — |
| 103 | `f571f62614e6f584322a29fa5ef9b4f9bb0e6bf6e5d48f72181d7778088e3a53` | TIMED_OUT | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `authorizeResume` | `NegateConditionalsMutator` | negated conditional | 11 | 67 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|authorizeResume|(Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/approval/ApprovalResumeCoordinator$ResumePreparation;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator|negated conditional` | — |
| 104 | `f5c807b43f9fe724ccbed38be0c2939820c115361446f18e236fa6e66fb46ac6` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.DefaultApprovalGateway` | `requireExistingAttributionMatches` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requireExistingAttributionMatches | 15 | 83 | `:tramai-engine|dev.tramai.engine.approval.DefaultApprovalGateway|requireExistingAttributionMatches|(Ldev/tramai/core/approval/ApprovalRequest;Ldev/tramai/core/identity/GovernedRunIdentity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/DefaultApprovalGateway::requireExistingAttributionMatches` | — |
| 105 | `f60db12bdb9ea3f1b61d5c01867a18b9ebe7f2a8726a8287f8129dad183a4625` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | `compensateSuspension` | `VoidMethodCallMutator` | removed call to kotlin/ResultKt::throwOnFailure | 7 | 44 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator|compensateSuspension|(Ljava/lang/String;JLdev/tramai/core/approval/ApprovalContinuationStore;Ldev/tramai/core/approval/ApprovalGateCoordinator;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator|removed call to kotlin/ResultKt::throwOnFailure` | — |
| 106 | `f802165800d8737a082118c89e9c5eae4e7d8470cd15c54c57bc19476be9a15c` | SURVIVED | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1` | `invokeSuspend` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$1::invokeSuspend | 9 | 43 | `:tramai-engine|dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1|invokeSuspend|(Ljava/lang/Object;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalSuspensionCoordinator$compensateSuspension$2$1::invokeSuspend` | — |
| 107 | `f8bdda993a7ca9073a0fdc3ad08b52203dc497be50d67c150e7ee094066f5ece` | NO_COVERAGE | approval | :tramai-engine | `dev.tramai.engine.approval.ApprovalResumeCoordinator` | `prepareResume` | `NullReturnValsMutator` | replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume | 12 | 72 | `:tramai-engine|dev.tramai.engine.approval.ApprovalResumeCoordinator|prepareResume|(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;|org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator|replaced return value with null for dev/tramai/engine/approval/ApprovalResumeCoordinator::prepareResume` | — |

## 7. Totals by class for the whole M06 set

| class | total | NO_COVERAGE | SURVIVED | TIMED_OUT | A | B | C |
|---|---|---|---|---|---|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator` | 67 | 44 | 4 | 19 | 16 | 22 | 29 |
| `dev.tramai.engine.approval.DefaultApprovalGateway` | 46 | 26 | 7 | 13 | 6 | 12 | 28 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | 44 | 23 | 11 | 10 | 15 | 6 | 23 |
| `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | 12 | 8 | 4 | 0 | 0 | 11 | 1 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1` | 5 | 2 | 2 | 1 | 0 | 0 | 5 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2` | 5 | 2 | 2 | 1 | 0 | 0 | 5 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3` | 5 | 2 | 2 | 1 | 0 | 0 | 5 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2` | 4 | 2 | 1 | 1 | 0 | 0 | 4 |
| `dev.tramai.engine.approval.GovernedApprovalStoreKt` | 3 | 0 | 3 | 0 | 0 | 0 | 3 |
| `dev.tramai.engine.approval.ApprovalRunAttributionKt` | 2 | 0 | 2 | 0 | 0 | 0 | 2 |
| `dev.tramai.engine.approval.GovernedContinuationContinuityKt` | 1 | 0 | 1 | 0 | 0 | 0 | 1 |
| `dev.tramai.security.evidence.RuntimeEvidenceAttribution` | 1 | 0 | 1 | 0 | 0 | 0 | 1 |

## 8. Path 1 — is pure current recorded evolution viable? No.

Path 1 = replace `mutation-baseline.json` with the exact fresh population, write 189 removal records,
leave the verifier unchanged, apply the `mutation-population-evolution` label.

It fails, and it fails on M06: the loop at `MutationRatchetVerifier.kt:299-313` compares
`candidateIds - baseIds` against the base authority and emits
`MUTATION_RATCHET_NEW_SURVIVOR` for every NON_KILLED member of that set. Evolution mode is not an input
to that loop (Section 2.2), and no record shape can describe a candidate-only identity (Section 2.3).
With the fresh population as the candidate, `candidateIds - baseIds` is the 349-identity candidate-only
set and 195 of those are NON_KILLED, so the transition would report 195 M06 failures. The g1A ratchet
run already showed the coupled shape of this: 384 failure lines = M21 189 + M06 195 + M10 0 + M01 0.

This is an analytic proof from control flow, not an executed transition: producing the executed
counter-example would require creating the candidate baseline, the 189 records and a measurement proof —
exactly the artifacts this preflight forbids. The claim I make is bounded accordingly: under the current
verifier, M06 cannot be satisfied by recorded evolution, so **pure recorded evolution is not
mergeable** as a mechanism for this authority.

## 9. Path 2 — safe identity-rekey inheritance (evaluated, not implemented)

Could category A be treated as pre-existing residual authority without weakening B or C?

### 9.1 Why key equality is not sufficient evidence

The identity schema is the problem, and it is deliberate. `MutationIdentity.kt:6-14` states the design:
`methodDescription` and `index` are included *"so overloaded methods and distinct bytecode mutation
points that share textual fields cannot collapse into one identity"*. The descriptor-aware key removes
`block` and `index` — the two fields the schema added precisely to keep mutation points apart. Treating
key equality as identity continuity reintroduces exactly the collapse the schema refuses, and the data
shows the collapse is real, not theoretical: fan-in up to 37, only 5 clean one-to-one rekeys, 44
expanding keys.

The failure mode to prevent is concrete. In category B every key carries a KILLED predecessor and a
NON_KILLED predecessor. Any rule of the form "the new identity inherits the old authority at this key"
must choose between a killed outcome and a survivor at the same key, and choosing the survivor is
precisely how a killed mutation becomes NON_KILLED under a new PIT coordinate. B is not a corner case:
it is 51 of the 88 identities that the descriptor key would otherwise claim as inherited.

### 9.2 Minimum evidence a rekey mechanism would have to carry

Not a recommendation — the floor below which the mechanism is unsound:

1. **Per-identity lineage, not per-key.** Each new identity names exactly one predecessor identity; a
   key may not carry a bulk mapping. With 37→8 and 21→9 fan-ins, a key-level rule has no single
   defensible answer to "which predecessor?".
2. **Predecessor outcome must be NON_KILLED.** Any KILLED predecessor at the key disqualifies the
   inheritance outright, or the mechanism launders kills. This alone removes all 51 B identities.
3. **The predecessor must actually disappear in this transition**, and be consumed at most once —
   otherwise one approved survivor could authorize several new NON_KILLED identities (the 44 expanding
   keys show this is reachable).
4. **The lineage set must be complete and auditable**: every new NON_KILLED identity either carries a
   lineage record or fails, with no silent default in either direction.
5. **The evidence must not be authored by the transition it authorizes.** This is the binding
   constraint, and it is the same one M08 protects for classifications: *a candidate PR cannot certify
   its own new survivors*. If the PR that contains the new mutation points also writes the records that
   excuse them, the mechanism is a self-approval channel with extra steps, and the 5-identity clean
   subset does not change that — 189 records would be written by the same author in the same commit.
6. **It must remain a strictly narrower authority than the current one.** No rule may apply to a new
   identity that is not at a measured, previously-authoritative source point with a non-killed
   predecessor — which is the only sense in which category A is a candidate at all.

### 9.3 Assessment

Category A is the only category that could be argued for, and Section 5.6 strengthens what can honestly be
said about it: no KILLED identity exists anywhere at its keys, retained or disappearing. That is still not
lineage — it is a statement about outcomes at a key, not about which mutation point the key describes. Nor is
it self-certifying: the 5 clean rekeys are weak evidence in the presence of 83 multi-predecessor identities,
and category A itself is 30 NO_COVERAGE / 4 SURVIVED / 3 TIMED_OUT — that is, 37 new mutants that were
never killed, most of them never even covered. Nothing in the descriptor key distinguishes "the same
mutation point, shifted" from "a new mutation point added inside a method that already had mutations and
whose earlier points have shifted", and those two answers have opposite ratchet consequences. Path 2 can
only be sound as an **authority-side lineage artifact adjudicated outside the candidate PR** — the same
ceremony shape as classifications today — not as a ratchet exception granted by inheritance.

## 10. Path 3 — remaining debt if only category A could inherit

| Quantity | Value |
|---|---|
| M06 identities remaining | **158** |
| of which category B (ambiguous inherited) | 51 |
| of which category C (genuinely new) | 107 |

| raw status | count |
|---|---|
| NO_COVERAGE | 79 |
| SURVIVED | 36 |
| TIMED_OUT | 43 |

| family | count |
|---|---|
| approval | 145 |
| evidence | 13 |

| class | count |
|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator` | 51 |
| `dev.tramai.engine.approval.DefaultApprovalGateway` | 40 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator` | 29 |
| `dev.tramai.security.evidence.RuntimeEvidenceContractValidator` | 12 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1` | 5 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2` | 5 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3` | 5 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2` | 4 |
| `dev.tramai.engine.approval.GovernedApprovalStoreKt` | 3 |
| `dev.tramai.engine.approval.ApprovalRunAttributionKt` | 2 |
| `dev.tramai.engine.approval.GovernedContinuationContinuityKt` | 1 |
| `dev.tramai.security.evidence.RuntimeEvidenceAttribution` | 1 |

Actionable clusters for a later task (class#method [raw status] → identities):

| cluster | count |
|---|---|
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#authorizeResume [NO_COVERAGE]` | 10 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#authorizeResume [SURVIVED]` | 1 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#authorizeResume [TIMED_OUT]` | 2 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#executeClaimedResume [NO_COVERAGE]` | 3 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#prepareResume [NO_COVERAGE]` | 10 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#prepareResume [SURVIVED]` | 1 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#prepareResume [TIMED_OUT]` | 5 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#resume [NO_COVERAGE]` | 8 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#resume [SURVIVED]` | 1 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#resume [TIMED_OUT]` | 8 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#revealAndValidateReplayPayload [NO_COVERAGE]` | 1 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator#revealAndValidateReplayPayload [TIMED_OUT]` | 1 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2#invokeSuspend [NO_COVERAGE]` | 2 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2#invokeSuspend [SURVIVED]` | 1 |
| `dev.tramai.engine.approval.ApprovalResumeCoordinator$resume$2#invokeSuspend [TIMED_OUT]` | 1 |
| `dev.tramai.engine.approval.ApprovalRunAttributionKt#decodeApprovalAttribution [SURVIVED]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#compensateStep [NO_COVERAGE]` | 3 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#compensateStep [SURVIVED]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#compensateStep [TIMED_OUT]` | 1 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#compensateSuspension [NO_COVERAGE]` | 6 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#compensateSuspension [SURVIVED]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#compensateSuspension [TIMED_OUT]` | 3 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#persistSuspendedInvocation [NO_COVERAGE]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#persistSuspendedInvocation [SURVIVED]` | 4 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator#suspendToolExecution [TIMED_OUT]` | 6 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1#invokeSuspend [NO_COVERAGE]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1#invokeSuspend [SURVIVED]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$1#invokeSuspend [TIMED_OUT]` | 1 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2#invokeSuspend [NO_COVERAGE]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2#invokeSuspend [SURVIVED]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$2#invokeSuspend [TIMED_OUT]` | 1 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3#invokeSuspend [NO_COVERAGE]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3#invokeSuspend [SURVIVED]` | 2 |
| `dev.tramai.engine.approval.ApprovalSuspensionCoordinator$compensateSuspension$2$3#invokeSuspend [TIMED_OUT]` | 1 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#persistGoverned [NO_COVERAGE]` | 6 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#persistGoverned [SURVIVED]` | 2 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#persistGoverned [TIMED_OUT]` | 3 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#persistUngoverned [NO_COVERAGE]` | 6 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#persistUngoverned [SURVIVED]` | 2 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#persistUngoverned [TIMED_OUT]` | 3 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#requestApproval-Atj0Sqo [NO_COVERAGE]` | 6 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#requestApproval-Atj0Sqo [TIMED_OUT]` | 6 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#requireExistingAttributionMatches [NO_COVERAGE]` | 2 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#requireExistingAttributionMatches [SURVIVED]` | 3 |
| `dev.tramai.engine.approval.DefaultApprovalGateway#requireExistingAttributionMatches [TIMED_OUT]` | 1 |
| `dev.tramai.engine.approval.GovernedApprovalStoreKt#requireAttributionMatchesBinding [SURVIVED]` | 3 |
| `dev.tramai.engine.approval.GovernedContinuationContinuityKt#requireGovernedContinuity [SURVIVED]` | 1 |
| `dev.tramai.security.evidence.RuntimeEvidenceAttribution#getMetadataKeys [SURVIVED]` | 1 |
| `dev.tramai.security.evidence.RuntimeEvidenceContractValidator#validateMetadata [NO_COVERAGE]` | 8 |
| `dev.tramai.security.evidence.RuntimeEvidenceContractValidator#validateMetadata [SURVIVED]` | 4 |

Reading of the clusters, for whoever picks this up next:

- **TIMED_OUT (43 remaining)** is the smallest actionable group and the one most likely to be test-
  harness debt rather than missing tests: a timeout means the mutant ran and the test process did not
  finish in budget, not that nothing exercised it. Cluster them by class and check the per-mutant
  timeout budget before writing a single new test.
- **SURVIVED (36 remaining)** is real missing protection and needs stronger tests. It cannot be closed by
  this candidate classifying its own survivors: Section 2.4 shows every candidate-added classification is
  an M08/M09 failure, so this path requires a separately designed authority-side adjudication mechanism.
  No current repository mechanism lets this candidate adjudicate its own new survivors.
- **NO_COVERAGE (79 remaining)** is the largest group and the one where a decision is cheapest. It means
  PIT measured no coverage **of that mutation point** — not that the enclosing method or class is never
  entered; a method can run while a specific mutated instruction is not observed. So the question is
  whether the point is reachable and worth covering, or whether it should not be in the target set at all.
  `compensateSuspension`
  and its three synthetic `invokeSuspend` bodies, `persistGoverned`/`persistUngoverned`, and
  `requestApproval-Atj0Sqo` account for most of it.
- Note the shape of the concentration: the remaining debt is dominated by two classes
  (`ApprovalResumeCoordinator`, `ApprovalSuspensionCoordinator`) plus `DefaultApprovalGateway`. This is
  a small number of large coroutine-heavy methods, not 158 unrelated problems.

## 11. Path 4 — explicitly authorizing new residual debt

What repository policy would have to change to permit it, described without recommending it:

- The ratchet would need a candidate-side allowance the base authority does not constrain: either a
  numeric ceiling on new NON_KILLED identities per transition, or an approval artifact the candidate
  authors for its own findings. Both are properties of the *candidate*, while every current mechanism
  (M01–M21) is a property of the *base*, which is the ratchet's whole design.
- The invariant *"a candidate PR cannot certify its own new survivors"* is exactly what M06 and M08
  exist to enforce. Permitting residual debt does not bypass that invariant at the rekey level; it
  contradicts it at the policy level: the candidate would certify its own survivors by policy instead
  of by record. Any ceiling high enough to admit 195 identities (or 158 if A inherits) is not a safety
- margin, it is the absence of one, and it would silently absorb the next transition's growth too.
- If new residual debt must ever be admitted, the only shape consistent with the current design is
  authority-side and out of band: the debt is adjudicated and committed as part of an approval
  ceremony that lands **outside** the PR introducing it, so the PR under review is judged against a
  base that already carries the debt. That is Path 3 plus time, not a ratchet relaxation.
- I am not recommending this path, and nothing in this preflight should be read as authorization for it.

## 12. Proposed minimum next steps (for decision, not started)

1. Decide the lineage question as an **authority-side artifact** or not at all (Section 9.2). If the
   answer is "not at all", category A is debt like B and C and Path 3 is the only road.
2. If Path 3 is chosen: kill the 36 SURVIVED, then triage the 43 TIMED_OUT against their timeout
   budgets, then decide the 79 NO_COVERAGE by reachability. 158 identities remain under the most
   generous reading of category A, so the work is real either way.
3. Land that work as its own transition, then evolve the authority as a plain removal baseline with
   the 189 removal records — at which point M21 is the only mechanism left to exercise and recorded
   evolution is sufficient.
4. Do not touch the verifier to make Path 1 pass. The M06 loop is the ratchet's core statement.

## 13. Explicit non-claims

- I did not modify any mutation authority, verifier, workflow, classification, threshold, family,
  baseline or test. The working tree contains one new file (this document).
- I did not execute an evolution transition, and I did not apply the
  `mutation-population-evolution` label. Path 1's failure is proven from control flow, not from an
- executed run (Section 8), and Path 2/3/4 are analyses only.
- No fresh mutation campaign was run: the A/B artifacts were present and byte-identical.
- The partition is descriptor-aware **and nothing more**. It is not a lineage, not a proof that a
  category-A identity is the same mutation point as any predecessor, and not evidence about which
  source revision introduced either side.
- I did not verify that the 189 base-only identities are *legitimately* removed (refactors, deleted
  code); the g1A audit attributed them `SOURCE_REFACTORED`, and this document inherits that
  attribution without re-deriving it.
- Category C being "genuinely new" means new with respect to the descriptor key and the method-level
  cross-check. I did not diff source revisions to confirm every one of those methods is newly written.
- The whole-authority check in Section 5.6 establishes the outcomes at every category-A key across all 2384
  base identities. It does **not** establish that any category-A identity is the same mutation point as any
  predecessor, and it is not evidence for lineage.
- The 37/51/107 split is not a safety verdict about any individual mutant, and no count here is a
  recommendation to classify, exempt, or waive anything.
- The base SHA defect in Section 1 is unresolved by design: I used the resolved Epic tip and said so.

## 14. Commands run

```
git fetch origin
git rev-parse origin/epic/0.7.1-control-plane-authority        # e38ce9f9dbb3519947ffe3ea692932c934eb3f17
git rev-parse 'e38ce9f9dbb3519947ffe692932c934eb3f17^{commit}'  # no such object (37-char literal)
git status --porcelain                                          # empty
git checkout -b task/0.7.1g1b-mutation-authority-evolution e38ce9f9dbb3519947ffe3ea692932c934eb3f17
sha256sum /tmp/tramai-071g1a-population-A.json /tmp/tramai-071g1a-population-B.json
#   both 06549e8a21e199b3f4c94fed359a3ae783e7796a29b27d6189c0dd261bc8a457
python3 /tmp/tramai-071g1b-partition.py    # Phase 2/3 analysis; writes /tmp/tramai-071g1b-*.json
```

Read for Phase 1 (no writes): `MutationRatchetVerifier.kt`, `MutationPopulationEvolution.kt`,
`MutationIdentity.kt`, `.github/workflows/ci.yml`. The analysis scripts live in `/tmp` and are not
committed; the JSON they produced is `/tmp/tramai-071g1b-{phase2,partition,m06}.json`.

## 15. Working-tree proof

Recorded BEFORE COMMIT, while the analysis ran — at that point the base was the tip and no task commit
existed yet:

```
$ git rev-parse HEAD
e38ce9f9dbb3519947ffe3ea692932c934eb3f17

$ git status --porcelain                       # before this document was written
(empty)

$ git status --porcelain                       # after writing it — one entry, nothing else
?? docs/roadmap/0.7.0/TASK-0.7.1g1B-M06-EVOLUTION-PREFLIGHT.md
```

Re-run AFTER COMMIT, against the PR head rather than pasted here: amending this document moves the SHA, so
any hash quoted in this section would go stale. The expected output is:

```
$ git rev-parse HEAD^        # e38ce9f9dbb3519947ffe3ea692932c934eb3f17 — the task commit sits on the base
$ git show --stat --oneline HEAD   # 1 file changed: this document
$ git status --porcelain     # (empty)
```

Either way, the task commit contains exactly that one file. No authority, verifier, workflow, classification,
threshold, family, baseline, production source or test file appears in it, and the base SHA stays the tip
this branch was cut from.

## 16. Final verdict

```
AUTHORITY_EVOLUTION_BLOCKED_BY_M06
```

Every candidate-only NON_KILLED identity fails M06, and M06 is unconditional: it does not read the
evolution mode, and no evolution record shape can name a candidate-only identity. Recorded evolution
resolves M21 and nothing else, so a pure transition (fresh population + 189 records) leaves 195 M06
failures and is not mergeable. I cannot demonstrate a legitimate resolution of those 195 failures under
the current verifier without a mechanism narrower than the descriptor key and adjudicated outside the
transition it authorizes, which does not exist today. 158 of the 195 remain even under the most
generous reading (category A inheriting), so the path forward is debt repair, not authority evolution.

### 16.1 Durable conclusion

**M21 evolution and M06 debt are separate problems.** The transition in front of us is:

```
189 disappearing identities        → understood by recorded evolution / M21
195 candidate-only NON_KILLED      → rejected independently by M06
```

Even under the most permissive hypothetical reading of category A:

```
195 candidate-only NON_KILLED
- 37 possible inherited-debt candidates
= 158 unresolved M06
```

So the next slice is not a baseline replacement. It is either a mutation-debt closure slice, or — if lineage
is judged worth designing — an authority-side lineage design that lands before any ratification of it. M06
should not be modified: it is currently doing exactly what it was written to do, preventing the candidate
that introduces new mutation identities from certifying those same survivors.

Stop here. No fix implemented, no authority modified.
