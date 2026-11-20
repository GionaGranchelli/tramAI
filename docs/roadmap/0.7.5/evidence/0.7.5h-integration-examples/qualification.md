# Epic 0.7.5 — Integration, Reference Examples & Final Epic Qualification

**Task:** 0.7.5h · **Base:** `release/0.7.0` @ `202e5f09` (0.7.5g squash-merge) · **PR:** #552 against `release/0.7.0` · **Merged:** `83a69734`

This is the epic gate. It adds the executable reference examples the epic promised, qualifies the
reconstruction against real PostgreSQL in all four evidence states, and then states — criterion by
criterion — what the epic actually delivered. Where something was not delivered it says so; no
unimplemented functionality is re-described as delivered.

## 1. Deliverables in this slice

| Deliverable | Where |
|---|---|
| Kotlin integration example over the shipped timeline/reconstruction API | `examples/timeline-reconstruction/src/main/kotlin/dev/tramai/examples/timeline/TimelineReconstructionExample.kt` |
| Java integration example over the same API (Java-consumer proof) | `examples/timeline-reconstruction/src/main/java/dev/tramai/examples/timeline/TimelineReconstructionJavaExample.java` |
| Example acceptance tests (Kotlin 3, Java 2) | `TimelineReconstructionExampleTest`, `TimelineReconstructionLauncherSmokeTest`, `TimelineReconstructionJavaExampleTest` |
| Real persistence-to-reconstruction qualification, PostgreSQL 17 | `TimelineReconstructionPersistenceTest` (5 tests) |
| Module card + catalogue entry + coverage table | `docs/modules/timeline-reconstruction.md`, `config/quality/module-catalog.yml`, `docs/modules/README.md` |
| Acceptance matrix (this document) | below |

The example is deliberately a **reader**: it owns no evidence, writes nothing, and renders from typed
fields only. Prompts, responses, tool payloads and reasoning are not expressible in the types, so the
example has no redaction step to forget.

## 2. Executable examples

```
./gradlew :examples:timeline-reconstruction:run     # prints the Kotlin narrative
./gradlew :examples:timeline-reconstruction:test    # 10 tests, Kotlin + Java + PostgreSQL
```

The `run` task is smoke-tested, not just documented: `TimelineReconstructionLauncherSmokeTest` loads the
exact class name the build configures (passed to the test as a system property, one source of truth),
invokes `main`, and asserts the printed narrative. This closes a real defect in the first head of this PR —
`application.mainClass` named `...MainKt`, a class no source file generates, so `./gradlew run` failed while
every compiling test passed. Re-introducing that name fails the smoke test (verified: 1 test, 1 failure).
`./gradlew :examples:timeline-reconstruction:run` now prints the narrative shown below.

The Kotlin narrative for the reference evidence, as produced by the tests:

```
run run-example-001
status PARTIAL
governance NOT_QUERIED
correlation NONE
history RECORDED
limitations CORRELATION_UNRESOLVED,GOVERNANCE_NOT_OBSERVED,HISTORICAL_CONFIGURATION_UNVERIFIED
usage attempts=2 duplicates=0 withoutUsage=1 input=unknown output=unknown thinking=unknown
invocation inv-example-001 correlation=corr-example attempts=2 final=SUCCEEDED
  attempt 0 FAILED provider=primary requested=logical-model reported=unknown duration=1000ms finish=none (not provider-reported)
  attempt 1 SUCCEEDED provider=primary requested=logical-model reported=logical-model duration=1000ms finish=STOP (not provider-reported)
```

Note what that output refuses to do: `status` is `PARTIAL` because governance was never asked, the token
totals are `unknown` (not `0`) because one of the two attempts reported nothing, and the finish reason is
marked `(not provider-reported)` because `ModelResponse` defaults to `STOP`. The example teaches the
honest reading rather than the convenient one.

**Java parity is a compile-time claim, not a story.** The Java example calls
`SemanticTimelineQuery.Companion.of(store, null)`, `new EvidenceBackedReconstructor(query, source)`,
`reconstruct(runId, null)`, `HistoricalConfigurationReference.Recorded(...)` and the result accessors
directly. Writing it surfaced two Java-consumer facts, both documented in the example source:
`@JvmOverloads` generates overloads by dropping *trailing* parameters, so the single-argument
`Recorded(...)` overload binds `configuration` — pass the explicit three-argument form when you mean a
policy version; and `SemanticTimeline.attempts()` is a function, not a `getAttempts()` property.

## 3. Real persistence to reconstruction

`TimelineReconstructionPersistenceTest` runs against PostgreSQL 17 testcontainers. What is real here,
and what is not, stated exactly:

- **Real persistence, real reader.** The governance projection is produced by the shipped materializer
  (through the `:tramai-security` test fixture) and read through the public
  `GovernanceQueryService.jdbc(dataSource)` port; the observations are persisted through the real
  `JdbcModelAttemptObservationStore` and read back from real `model_attempt_observations` rows.
- **The attempt records themselves are constructed by the example**, not produced by a governed engine
  run in this test. This test therefore qualifies *persistence-to-reconstruction*, not the producer path.
  The producer path — a real governed operation through `ProviderAttemptExecutor` →
  `AttemptRecordingObserver` → store → query → reconstructor, with canaries — is proven separately in
  0.7.5g (`ProducerPathConfidentialityTest`, `ProducerPathProvenanceTest`,
  `JdbcAttemptObservationConfidentialityTest`; see the [0.7.5g qualification](../0.7.5g-adversarial-mutation/qualification.md)).
  Neither proof substitutes for the other.

| State | Test | Asserted |
|---|---|---|
| **SUPPORTED** | `recorded attempts plus a materialized projection reconstruct as SUPPORTED` | status `SUPPORTED`; history `RECORDED`; governance is a `Snapshot` with decisions; 2 attempts; still reports `HISTORICAL_CONFIGURATION_UNVERIFIED` |
| **PARTIAL** (no governance scope) | `recorded attempts without a governance scope reconstruct as PARTIAL` | status `PARTIAL`; governance `NOT_QUERIED`; `GOVERNANCE_NOT_OBSERVED`; 1 attempt really read back |
| **PARTIAL** (empty instrumented history) | `an empty instrumented projection reconstructs as PARTIAL and never as nothing happened` | history `EMPTY`; `EXECUTION_NOT_ESTABLISHED`; no attempts; never `UNSUPPORTED` |
| **UNSUPPORTED** | `an uninstrumented store cannot support a reconstruction` | history `UNINSTRUMENTED`; status `UNSUPPORTED`; `ATTEMPTS_UNINSTRUMENTED` + `EXECUTION_NOT_ESTABLISHED` |
| **Refusal** | `a governance scope naming another run is refused rather than associated` | `IllegalArgumentException` — another run's facts are never placed beside these attempts |

**One honest limit:** `UNSUPPORTED` is reachable only with an *uninstrumented* store, because an
instrumented PostgreSQL store always answers (even with an empty table, it answers "no attempts
recorded"). That case is therefore proven with `UninstrumentedAttemptObservationStore` rather than
pretended against a table whose answer is a different one.

## 4. Acceptance matrix — the eight Epic 0.7.5 criteria

| # | Criterion | Verdict | Evidence and exact gap |
|---|---|---|---|
| 1 | Reference run renders classification, policy, routing, approval/control and execution transitions where evidence exists | **PARTIAL** | Policy decisions render as typed facts (`DecisionKind`, decision, reason code) and execution attempts render fully. **Routing, approval/control and tool transitions are not rendered as timeline events**: no event log exists to read, which 0.7.5c recorded as deferred and 0.7.5d/e did not invent. 0.7.8 owns presentation; the underlying events still have no durable source. |
| 2 | Missing evidence is visible | **MET** | `GovernanceSection.{NotQueried,NotConfigured,NotMaterialized,UnsupportedFormat,InvalidStoredProjection}`, `AttemptHistoryState.{RECORDED,EMPTY,UNINSTRUMENTED}`, `ReconstructionLimitation` (7 values), and derived `status`. Proven by 0.7.5f semantics tests, 0.7.5g adversarial tests and the four-state PostgreSQL qualification above. |
| 3 | Reconstruction causes zero provider/tool/approval/network/workflow side effects | **MET** | `ReconstructionSideEffectTrapTest`: write-trapping store, exact read counts (1 run read, 0 invocation reads, 1 governance query), five repeat runs with byte-identical evidence, and a reflection assertion that no reconstruction type reaches a provider, tool, approval or network capability. Scoped to the shipped composition — a caller-supplied implementation is unverified (0.7.5f/Gate 2). |
| 4 | Historical configuration is used when available; absence is explicit | **MET** | `HistoricalConfigurationReference.Recorded`/`NotRecorded`; the reconstructor builds the request itself so a caller cannot inject a reference; `NotRecorded` stays `NotRecorded` and never falls back to current configuration. Provenance is **not** authenticated: a supplied reference always yields `HISTORICAL_CONFIGURATION_UNVERIFIED`. |
| 5 | A reconstructed external-runtime event retains authoritative / instrumented / observed / imported provenance according to evidence at execution time | **PARTIAL** | Attempts carry `AttemptProvenance.OBSERVED_IN_PROCESS` and are never promoted; governance facts carry `FactAuthority` from the 0.7.4 projection and are reused unchanged. **Not delivered:** an *external-runtime import* path — there is no importer, so an imported event cannot exist and is not claimed. |
| 6 | A supported run exposes a request-to-response attempt timeline correlated to the governing run/operation and the corresponding decision where correlation exists, with retries and fallback as distinct attempts | **PARTIAL** | Retries are distinct attempts of one invocation and repeated invocations stay separate (proven on a real retried governed run in 0.7.5c and again in 0.7.5g). Correlation is **shared run identity only** (`SAME_WORKFLOW_RUN`/`NONE`); there is deliberately no decision→attempt edge, and fallback is recorded as a distinct `routeIndex` attempt but not surfaced as a narrative "fallback transition". |
| 7 | Where the runtime reports them, token counts, thinking tokens, model identity, latency, finish/error status and streaming TTFT retain provenance through a headless safe query; unavailable metrics stay unknown | **PARTIAL** | Non-streaming usage, requested/reported model, duration, outcome, failure category and normalized finish reason are recorded and never fabricated (zero ≠ unknown, partial ≠ total, thinking never folded into output, default `STOP` never a provider report). **Not delivered:** TTFT and streaming attempts — the streaming path records no attempt, and `ModelResponse` carries no provider-reported finish-reason flag, so `finishReasonReported` is permanently `false`. Both are recorded as gaps, not simulated. |
| 8 | Repeated imports and multiple observations of one attempt do not inflate usage; thinking tokens are not added to output | **MET** | One write per attempt (`AttemptRecordingWriteCountTest`), duplicates ignored with `duplicatesIgnored` counted (0.7.5g mutation M15), and thinking tokens stored separately with no total anywhere. |

**Score: 5 MET, 3 PARTIAL, 0 NOT MET, 0 silently re-described.** The three PARTIAL rows are gaps with a
named cause, not redefinitions: (1) no durable event log for routing/approval transitions, (5) no
external-runtime importer, (6)/(7) correlation limited to run identity and no streaming/TTFT
instrumentation.

## 5. Core invariants — held?

| Invariant | Verdict | Evidence |
|---|---|---|
| `reconstruction != re-execution` | **MET** | Zero-write trap tests on the **shipped composition**: a write-trapping store, exact read counts and a reflection assertion that the shipped reconstruction types expose no provider/tool/approval/network capability. This is not a claim about arbitrary caller-supplied implementations of `SemanticTimelineQuery` or `HistoricalConfigurationSource`, which remain unverified — 0.7.5g demonstrates a collaborator that writes before reading (0.7.5e/Gate 2) |
| `missing historical evidence remains missing` | **MET** | Missing/unknown matrix tests; `EXECUTION_NOT_ESTABLISHED` follows the observation, not the decisions (0.7.5f) |
| `current configuration is never silently substituted` | **MET** | The type system has no "current configuration" input; `NotRecorded` has no fields; source must throw on failure rather than return absence |
| `best-effort telemetry remains distinguishable from authoritative evidence` | **MET** | `FactAuthority` preserved from 0.7.4; `AttemptProvenance.OBSERVED_IN_PROCESS` never upgraded; a decision is never proof of execution |
| `reconstruction preserves provenance rather than upgrading it` | **MET** | `HISTORICAL_CONFIGURATION_UNVERIFIED` on every supplied reference; observed attempts stay non-authoritative (0.7.5g Gate 3/4) |

## 6. Task matrix

| Task | State |
|---|---|
| 0.7.5a audit | Delivered (PR #544) |
| 0.7.5b durable attempt observation | Delivered (#545, `924e6cb3`) |
| 0.7.5c timeline projection/query | Delivered (#546, `508a043d`) |
| 0.7.5d reconstruction contract | Delivered (#547, `90d3e6b9`) |
| 0.7.5e side-effect-free reconstructor | Delivered (#548, `ca8dcd3e`) |
| 0.7.5f missing-evidence semantics | Delivered (#549, `8ed97f3f`) |
| 0.7.5g adversarial & mutation proof | Delivered (#550, `202e5f09`) — 15/15 mutants killed; one production telemetry defect found and fixed |
| 0.7.5h integration/docs/epic gate | This slice |

## 7. Verification

```
:examples:timeline-reconstruction:test         tests=10 failures=0  (3 Kotlin example, 2 Java example, 5 PostgreSQL)
:examples:timeline-reconstruction:run          prints the reference narrative, exit 0
```

Repository gates at this head (`-PtramaiStaticAnalysisBaseRef`/`-PchangePolicyBase` pinned to
`origin/release/0.7.0`): spotlessCheck, verifyStaticAnalysis, verifyChangePolicy, `:tramai-security:apiCheck`,
verify060Architecture, verifyModuleDocContract, verifyCancellationSafety, verifyJUnitTestSignatures,
verifyCompilerWarnings. Exact-head CI: see PR #552.

## 8. Release blockers and follow-ups carried forward

1. **Release blocker (tracked, issue [#551](https://github.com/GionaGranchelli/tramAI/issues/551)):
   `OpenTelemetryWorkflowObserver` and `OpenTelemetryTramaiWorkerObserver` still export exception messages
   and stack traces.** `OpenTelemetryOperationObserver` was fixed in 0.7.5g; the same pattern remains on
   those two paths, which are outside this epic's surface. **0.7.0 must not be promoted until that issue is
   merged and verified.**
2. **No TTFT, no streaming attempts, no provider-reported finish-reason flag** (0.7.5b limitation, still
   open): unavailable rather than fabricated.
3. **No durable event log** for routing/approval/tool transitions: criteria 1, 5 and 6 stay PARTIAL until
   one exists.
4. **Caller-supplied collaborators are unverified** (0.7.5f, demonstrated in 0.7.5g): read-only is a
   contract on an implementation, not a proof about it.
5. **No retention policy** for `model_attempt_observations`; R1 remains an accepted temporary risk with
   `R1-action` tracked before release promotion.
