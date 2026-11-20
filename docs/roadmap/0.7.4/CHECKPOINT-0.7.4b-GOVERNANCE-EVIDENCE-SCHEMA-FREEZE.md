# CHECKPOINT 0.7.4b — Governance Evidence Schema Freeze (qualification)

Status: **QUALIFIED — FROZEN WITH EXPLICIT LIMITATIONS — MERGED.** Disposition D1 is **ACCEPTED** by the repository
owner; the freeze is approved for `runtime-evidence.v1` as verified below, with S18 and S19 remaining UNRESOLVED.
Merged as `1837a6b7191c7e68731a225025bc31cdf4fb7567` ([#528](https://github.com/GionaGranchelli/tramAI/pull/528),
squash of `56fb85a0fb5b9e3a9748d6b70634f847622cf013`); **0.7.4b closed** — see [Closure](#closure).
Frozen artifact: `runtime-evidence.v1` **as implemented at `release/0.7.0` = `47b230b199b7c56e4a4e073cfaca6a01a31a22f5`**.
Branch: `task/0.7.4-freeze-qualification`. Change class: `documentation` (contract statement + this record). No
production code, schema, validator, digest, workflow or baseline is changed.

This is a **qualification task with an outcome decided by evidence**, not a declaration prepared in advance. The
verification below was executed against the pinned SHA; every claim carries its evidence class.

**This freeze does not complete XR1, does not authenticate evidence origin, and does not establish a live
`provider.route` producer.** It freezes the schema's *shape* at a known SHA together with the limitations that shape
still carries. Nothing here may be cited as proof of any of the three.

## What is being frozen, and against what authority

- The XR1 checkpoint's **Exit clause** (`docs/roadmap/0.7.0/CHECKPOINT-0.7-XR1-...:88-92`): XR1 is complete when a
  non-TramAI-authored Spring AI workload demonstrates the same authoritative governance meaning as native execution,
  **and the findings are sufficient for 0.7.4 to finalize evidence authority/provenance without assuming TramAI owns
  the agent runtime.**
- The audit's dependency order (`0.7.4a-...:220-231`), step 6: *freeze the 0.7.4b schema only after steps 2–5 are
  demonstrable at a single tree, per the checkpoint's Exit clause*; and the **completion gate** (`:262-270`): every
  requirement maps to a source-level gap or an existing authoritative contract.
- The audit's own carry-forward: **T1**, whether adapter-supplied workload identity is validated against the
  registration authority, is "the most consequential unresolved question for 0.7.4b and should be settled before the
  schema freezes" (`:261-263`).

Steps 2–5 (G1a, G1b, G2, G4/declare-or-wire, plus G3) are all present in this one tree; the verification below is
against their **implementations and tests**, not their task records.

## Verification executed

Environment: JDK 25 (`/usr/lib/jvm/java-25-openjdk-amd64`), Gradle 9.0.0, clean worktree pinned at `47b230b1`.
Raw command output: `evidence/0.7.4b-schema-freeze/verification.txt`.

| Run | Result |
|---|---|
| `:examples:spring-ai-authority-boundary:test --rerun-tasks` | **31 tests, 0 failures, 0 errors, 0 skipped** (6 classes) |
| `:tramai-security:test --tests 'dev.tramai.security.evidence.*' --rerun-tasks` | **209 tests, 0 failures, 0 errors, 0 skipped** (10 classes) |
| `:tramai-sovereign:test --tests 'dev.tramai.sovereign.evidence.*' --rerun-tasks` | **81 tests, 0 failures, 0 errors, 0 skipped** (3 classes) |
| `:tramai-engine:test --tests 'dev.tramai.engine.evidence.*' --rerun-tasks` | **19 tests, 0 failures, 0 errors, 0 skipped** (sequential re-run; first attempt was an invocation artifact, see below) |

Source reads: `PolicyContext.kt`, `RuntimeEvidenceAttribution.kt` (full), `RuntimeBoundaryProvenance.kt` (full),
`ToolPermissionRuntimeEvidenceExporter.kt`, `GovernedToolCallback.kt` (full), `IdentityValidation.kt`,
`ProviderRoutingRuntimeEvidenceExporter.kt`, the export model and bundle map, the audit, the XR1 checkpoint.

**How to read the test citations below.** A bare class name means the class exists once in the tree. Two simple
names are **not** unique, so they are qualified where they appear: `ToolPermissionEvidenceAttributionTest` exists in
both `examples/spring-ai-authority-boundary` (5 tests) and `tramai-security` (13 tests), and the class names in
S7/S12/S16 (`GovernedIdentityCarriageTest`, `EvidenceIntegrityTest`, `GovernanceBoundaryTest`,
`FrameworkDispatchTest`, `AdversarialDiscriminatorTest`) belong to the **example module**. Counts are the ones
recorded in `evidence/0.7.4b-schema-freeze/verification.txt`, not estimates: every `n/n` and `(n tests)` claim in
this record was re-checked against that file by a script after review, and one miscount was found and corrected
there (`RuntimeEvidenceAttributionTest` was cited as 14 tests; it has 8).

## Contract matrix

Status vocabulary: **VERIFIED** (reproduced at this SHA, command recorded) · **DECLARED** (contract statement, no
independent verification of the external fact) · **UNSUPPORTED** (no production producer) · **UNRESOLVED** (not
established; must not be read as solved).

| # | Property | Status | Evidence |
|---|---|---|---|
| S1 | `runtime-evidence.v1` identity: schema version + event types + per-family source component | VERIFIED | `EXPECTED_SOURCE_COMPONENTS` pinning is enforced by the validator; 209 security evidence tests green |
| S2 | Per-family metadata allowlist; unknown non-reserved keys filtered | VERIFIED | exporter tests + G1a correction (unknown-key filtering) |
| S3 | Reserved **identity** keys are atomic: zero = legacy, partial = corruption, full requires non-blank `workflowRunId` | VERIFIED | `RuntimeEvidenceAttribution.validate:55-73`; `RuntimeEvidenceAttributionTest` (8 tests, green) |
| S4 | Caller/event metadata cannot shadow identity (`merge` refuses non-identity attribution keys) | VERIFIED | `RuntimeEvidenceAttribution.merge:40-49`; `GovernanceDecisionEvidenceTest:350` asserts the refusal |
| S5 | Identity participates in `digests.payloadDigest` (overlay strictly before the digest) | VERIFIED | `ToolPermissionRuntimeEvidenceExporter.kt:88` (merge + provenance before digest); `examples/spring-ai-authority-boundary` → `ToolPermissionEvidenceAttributionTest.evidence for two deployments of the same run does not share a payload digest` |
| S6 | `workflowRunId` is the single canonical run identifier, deliberately not duplicated into metadata | VERIFIED | `RuntimeEvidenceAttribution.kt:23`, `GovernedToolCallback.contextFor`; carriage test `the evaluator receives the canonical governed identity` |
| S7 | Identity is carried across the external boundary, both callback overloads, never re-minted | VERIFIED | `GovernedIdentityCarriageTest` 5/5 (both overloads carry the same identity; the boundary re-mints nothing it was given; denial still prevents execution) |
| S8 | Reserved **boundary** keys: strength is framework-derived, never caller-asserted | VERIFIED | `RuntimeBoundaryProvenance.provenanceFor:115-125` refuses a caller-supplied `boundary.enforcementStrength`; `ToolPermissionBoundaryProvenanceTest` green |
| S9 | Boundary registration gates strength; identifier must match the record's declared enforcement point | VERIFIED | `RuntimeBoundaryProvenance.resolve:94-106`; boundary test `every refusal declares the strength the framework resolves in the same call's evidence()` |
| S10 | Unregistered boundary identifier is **refused**, not downgraded | VERIFIED | `resolve:98-100` + `validate:148-159`; rationale documented at `:29-33`. **Supersedes the audit's §5 "may at most report OBSERVED" wording** — see D3 |
| S11 | Strength vocabulary is closed: `AUTHORITATIVE`, `INSTRUMENTED`, `OBSERVED`, `IMPORTED` | DECLARED | `strengths:63`. **Only one boundary is registered and its strength is `AUTHORITATIVE`**, so three of the four values have no reachable producer in-tree |
| S12 | G3 contract: `ALLOW` proves authorization, never execution completion; decision recorded before the effect | VERIFIED | `GovernedToolCallback.govern:78-85` (`recordAllow` then `execute`); `EvidenceIntegrityTest.a failure to record the authorization prevents the action`; contract text in the export model |
| S13 | Legacy compatibility: records with zero reserved keys remain valid | VERIFIED | `validate` zero-key branch (`:61-63`, `:140-142`); legacy fixtures green in the 209-test run |
| S14 | Digest semantics preserved: SHA-256 over canonical subject/payload, stable for unchanged content | VERIFIED | existing digest tests green; no digest function changed by G1–G4 (G1–G4 changed only overlay ordering, which is the digest's documented input) |
| S15 | Bundle writer + verifier behaviour preserved; `provider.route` records still accepted | VERIFIED | `:tramai-security` and `:tramai-sovereign` evidence suites green (writer/loader/validator), including a bundle integration test that writes `provider-routing.jsonl` |
| S16 | XR1 authoritative prevention at the declared boundary | VERIFIED | `GovernanceBoundaryTest` 8/8 incl. `a denied action is never invoked`, `both tool callback overloads are governed so neither is a bypass`, `an action requiring approval cannot execute without one`, `an unavailable authority prevents execution and is never read as permission`; `FrameworkDispatchTest` 2/2 on the real Spring AI dispatch path; `AdversarialDiscriminatorTest` 7/7 proves those tests can fail |
| S17 | `provider.route` production producer | **UNSUPPORTED** | rechecked at this SHA: the only `src/main` mentions are the exporter's own private converters; the only invocations are 2 test files + 1 benchmark. Contract states it (G4) |
| S18 | **Workload-identity authenticity (T1)** | **UNRESOLVED** | `WorkloadRegistrationAuthority` is referenced only from `tramai-control-plane`, `tramai-server` and persistence tests — the boundary never consults it; `GovernedToolCallback` carries the caller's `BoundaryContext` identity. `IdentityValidation` is syntax-only. Contract statement added by this task (see D1) |
| S19 | **Event-origin authenticity** | **UNRESOLVED** | `RuntimeBoundaryProvenance.kt:21-27` states it; contract states it (export model `:206-211`); pinned by the test `the registration does not verify the origin of a declared identifier`. See D2 |

## Divergences from the audit's §5 proposal (recorded, not silently absorbed)

**D3 — "at most OBSERVED" became "refuse".** §5 G2 says an unregistered or unproven boundary "may at most report
`OBSERVED`". The implementation refuses an unregistered identifier outright (`:98-100`), documented as a deliberate
choice of refusal over downgrade because a downgrade would admit an unknown adapter's identifier into evidence as
if it were recognised. The implementation is **stricter** than the proposal; the contract text matches the
implementation ("refused rather than accepted or downgraded"). Recorded so the freeze freezes the implemented rule,
not the superseded wording.

**D4 — typed field became reserved keys.** §5 G1 proposed adding a nullable, defaulted `governedRunIdentity` field to
`PolicyContext`. The implementation instead carries the five `identity.*` reserved keys through
`PolicyContext.attributes`, with a measured ABI justification (`0.7.4b1-G1A-ABI-FINDINGS.md`: adding a property to
the `stable` `PolicyContext` breaks compiled Kotlin consumers). Atomicity is preserved by
`RuntimeEvidenceAttribution.validate`, so the audit's stated reason for rejecting the attributes route (a *validated*
rather than *hoped-for* channel) is satisfied. Sanctioned by the G1a task record's own name
(`OPTION-A-IDENTITY-ATTRIBUTES`).

## Dispositions

**D1 — workload identity is declared, not authenticated. ACCEPTED as an explicit schema limitation (owner decision).**
The contract states that `identity.*` values are producer-supplied and syntax-validated only, are not authenticated
against the workload registration authority, and that a record attests *declared* identity — not the declarer's
entitlement to declare it. The owner accepted this as a limitation of the frozen schema, so the freeze proceeds
**with** the limitation rather than by treating T1 as solved. T1 remains a **tracked security requirement: it must be
resolved before anyone may claim authenticated workload provenance**, and it is not a property this freeze confers.

**D1a — authentication work must not rewrite what a v1 record proves.** A `runtime-evidence.v1` record's meaning is
fixed by the schema version it declares. If workload or event origin is authenticated in a later release, that work
must not retroactively change what existing v1 records attest: a v1 record that carried no authenticity claim still
carries none, and presenting a historical v1 record as origin-authenticated after the fact is a misreading, not an
upgrade. Any change of that meaning requires a new schema version, not a re-interpretation of v1.

**D2 — event origin is declared, not verified.** Already stated in the contract and pinned by a test that asserts the
limitation (the test fails if the limitation is removed). Accepted as a limitation of `runtime-evidence.v1`.

**D5 — no completion attestation is introduced.** `tool.permission` and `provider.route` do not attest execution
completion or a returned response, and nothing in this freeze adds one.

## Verdict

**QUALIFIED — FROZEN WITH EXPLICIT LIMITATIONS.** `runtime-evidence.v1` as implemented at `47b230b1` is frozen, with
D1 accepted and S18/S19 retained as UNRESOLVED.

- **In the machinery: no blocking defect found.** Every schema property that can be verified was verified at this
  SHA with recorded commands; identity carriage, atomic attribution, digest participation, strength derivation,
  legacy compatibility and bundle behaviour all reproduce, and the XR1 prevention proofs are green on the real
  dispatch path.
- **S18 (workload authenticity) and S19 (event-origin authenticity) remain UNRESOLVED.** They are stated in the
  contract as limitations and were **not** reclassified as VERIFIED. T1 stays open as a tracked security
  requirement: necessary before claiming authenticated workload provenance.
- **The freeze finalizes a shape, not an authority.** It may not be cited as completing XR1, as authenticating
  evidence origin, or as establishing a live `provider.route` producer.
- **What must not be read out of this freeze:** that every evidence family has a live producer (S17 UNSUPPORTED);
  that identity or boundary provenance authenticates origin (S18, S19 UNRESOLVED); that an `ALLOW` record attests
  completion or success (S12); that `OBSERVED`/`INSTRUMENTED`/`IMPORTED` strengths are exercised (S11); or that a
  later authentication change reinterprets historical v1 records (D1a — it does not).

## Invocation artifacts

The first attempt at the engine battery failed with a Kotlin `FileAnalysisException` and a missing
`tramai-core/build/...` file. Cause: two Gradle invocations were run concurrently in the same worktree with
`--rerun-tasks`, so one build consumed a module output the other was rewriting. **Invocation artifact, not a project
defect**; re-run sequentially and recorded as such. No gate, baseline or workflow was touched.

## Not run / not established

- No `verifyPr` run: its docs-guard test cannot pass in a linked worktree (`.git` is a file, not a directory) — a
  known environment limitation, unrelated to this change. CI covers it in a real checkout.
- S19's negative is established from source reads plus a dedicated test, not by an exhaustive trace of every
  possible producer.
- The strength vocabulary's unused values (S11) are recorded as declared; no code path exercises them, and no claim
  is made that they are reachable.

## Closure

**0.7.4b — Governance Evidence Schema — is closed** at `release/0.7.0` = `1837a6b7191c7e68731a225025bc31cdf4fb7567`.

Delivered in this programme, each verified at the tree named in its own record:

| Item | Record | State at closure |
|---|---|---|
| G1a — identity carriage across the external boundary | `0.7.4b1-G1A-*`, `0.7.4b2-G1B-*` | delivered; mechanism is OPTION A (reserved `identity.*` keys), ABI-justified |
| G2 — registered enforcement-boundary provenance | `0.7.4b3-G2-*` | delivered; strength derived from registration, unregistered refused |
| G3 — authorization vs completion contract | `0.7.4b4-G3-*` | delivered; `ALLOW` never attests completion |
| G4 — `provider.route` producer or declaration | `0.7.4b6-G4-*` | **declared non-production**; no live producer |
| Merge-gate RC applicability correction | `0.7.4b5-*` | delivered; filters read from the producing workflow |
| Schema freeze qualification | this record | **QUALIFIED — FROZEN WITH EXPLICIT LIMITATIONS** |

**Carried forward — these are open, not closed by the freeze:**

1. **T1 — authenticated workload provenance.** Tracked security requirement; nothing may claim authenticated workload
   provenance until it is resolved. D1 accepted its absence as an explicit limitation.
2. **Event-origin authenticity** (S19). Separate security requirement, unimplemented.
3. **Live `provider.route` producer** (S17). `TASK-0.7.4b7-PROVIDER-ROUTE-LIVE-PRODUCER.md`.

The freeze must not be read as completing XR1, authenticating evidence origin, or establishing a live producer, and
later authentication work must not be read back into existing `runtime-evidence.v1` records (D1a).

## Follow-ups (not part of this freeze)

1. **T1 — workload identity authentication** against the registration authority. **Tracked security requirement**:
   necessary before anyone may claim authenticated workload provenance. D1's acceptance means the freeze proceeds
   *without* it, not that it is satisfied. Resolving it must not change what existing `runtime-evidence.v1` records
   attest (D1a).
2. **Event-origin attribution** — separate security requirement, unimplemented (S19).
3. **Live `provider.route` producer** — `TASK-0.7.4b7-PROVIDER-ROUTE-LIVE-PRODUCER.md` (S17).
