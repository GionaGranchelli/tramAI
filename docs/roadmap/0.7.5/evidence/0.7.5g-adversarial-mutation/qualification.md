# 0.7.5g — Adversarial & Mutation Proof

Proof, not new architecture. Every claim below is backed by a test that fails when the property it guards
is deliberately violated, and the violations are recorded in the mutation ledger.

| | |
|---|---|
| Base | `release/0.7.0` @ `8ed97f3f` (0.7.5f squash-merge) |
| Branch | `task/0.7.5g-adversarial-mutation-proof` |
| Head | the commit that adds this file (see PR, pinned in the PR body) |
| #545 (0.7.5b) | merged, merge commit `924e6cb3`, `c27c5eb0` is an ancestor of `release/0.7.0` — verified via merge metadata and `git merge-base --is-ancestor` |
| #549 (0.7.5f) | merged as `8ed97f3f` |

## What was tested, and what was not

Three properties are kept apart on purpose, because they are different claims:

- **Types** — what the public contracts can and cannot express. Proven by compile-time structure and
  reflection over the published surface: no prompt/response/tool-payload/reasoning field exists; the
  correlation vocabulary has exactly `SAME_WORKFLOW_RUN` and `NONE`, so a decision→attempt edge is not
  expressible; no reconstruction type references a provider, tool, approval or network capability.
- **Shipped implementation** — `AttemptRecordingObserver`, `SemanticTimelineQuery.of(...)`,
  `EvidenceBackedReconstructor`, `OpenTelemetryOperationObserver`. Proven by tests that execute those
  implementations.
- **External collaborators** — an arbitrary `SemanticTimelineQuery` / `HistoricalConfigurationSource`
  implementation is **unverified**, and the adversarial test below demonstrates why the interface cannot
  certify it. No guarantee is claimed about it.

**Tested boundary (confidentiality):** `provider → ProviderAttemptExecutor → AttemptRecordingObserver →
AttemptObservationStore → (PostgreSQL columns) → SemanticTimelineQuery → EvidenceBackedReconstructor →
public rendering`, plus the OTel span the same observer writes.

**Not tested, and not claimed:** application logging, exporter back-ends, third-party observers, the
worker/workflow observers (which still use `recordException`, outside this surface), and any collaborator
supplied by a caller.

## Gate 1 — end-to-end confidentiality

| Origin of the canary | Test | Where it must be absent |
|---|---|---|
| Operation input (prompt) | `ProducerPathConfidentialityTest` | attempt fields, timeline, reconstruction, renderings |
| Provider response content | same | same |
| Provider exception message | same + `ProducerPathTelemetryPrivacyTest` | same, plus span attributes/events/status |
| Tool call arguments | `ProducerPathConfidentialityTest` | attempt fields, timeline, reconstruction |
| Tool result content | same | same |
| Persisted columns | `JdbcAttemptObservationConfidentialityTest` | every column, scanned with `SELECT *` |

Non-vacuity is proven in each test: the operation really ran, the provider really was invoked (twice, once
failing), the tool really executed (`tool.execute.start`/`.success` in the fixture trace), the returned
answer really contained the response canary, the span count is exactly 2, the attempt rows are exactly 2,
and `reportedModel`/token counts survive. A test that passed because nothing happened could not satisfy
those assertions.

Error categories carry the class name only: `failureCategory == "ProviderException"`, never the message.

### Production defect found, characterized, fixed

`OpenTelemetryOperationObserver.onProviderFailure` exported `span.recordException(error)` and used
`error.message` as the span status description. The engine sanitizes provider failures that travel
through `providerTransportFailure`, but an exception a provider throws **directly** reaches the observer
unsanitized — so an upstream error message (which routinely carries response bodies, URLs or request
fragments) was exported into telemetry. The pre-existing privacy test only exercised the sanitized
factory path, which is why the gap survived: it proved the sanitized case, not the general one.

Characterization: `ProducerPathTelemetryPrivacyTest` with a provider that throws
`ProviderException("CANARY-FAILURE-9b02: upstream said no")` — failed with the canary visible in
`exception.stacktrace` and `exception.message`.

Fix (bounded, in `tramai-observability` only): export `exception.type` and `exception.escaped=false`, and
set a fixed status description `"Provider call failed"`. No message, no stack trace, for any provider
failure. `OpenTelemetryOperationObserverTest` was updated to assert the *stronger* property (only those
two attributes exist) instead of the old sanitized-message expectation. Mutant `M13` re-introduces the leak
and is killed by the canary test.

## Gate 2 — zero external execution during reconstruction

`ReconstructionSideEffectTrapTest`:

- store whose `append` throws → reconstruction completes, so no write was attempted;
- exact read counts: 1 run read, 0 invocation reads, 1 governance query (and 0 when no scope is given);
- five repeat reconstructions: identical status, identical limitations, identical attempts, and the stored
  evidence list is equal to what it was before;
- no provider, tool, approval, workflow or `java.net` type is reachable from the reconstruction surface
  (reflection over the published types and their method parameter types);
- adversarial collaborator: a `SemanticTimelineQuery` that writes before reading really does write
  (`append` observed once) and the reconstructor cannot detect it — recorded as the trust boundary, not as
  a defect.

Network egress is unreachable rather than merely unused: `tramai-security` has no network dependency, and
the reflection assertion above fails if one is ever introduced through the reconstruction surface.

## Gates 3 and 4 — identity, causal truth, accounting

`TimelineEvidenceIntegrityTest` (read path) and `ProducerPathProvenanceTest` (recording path):

| Required case | Test |
|---|---|
| Shared run identity is not a decision→attempt edge | `shared run identity is not a decision-to-attempt edge` |
| Status and limitations judged per source | `status and limitations are judged from each source independently` |
| Skewed / concurrent timestamps never prove causality | `skewed and concurrent timestamps never establish causality` |
| Explicit zero vs missing usage | `explicit zero usage is preserved as zero and missing usage stays unknown` |
| Partial component → unknown, not a smaller total | `a partial component is unknown rather than a smaller total` |
| Thinking tokens never added to output | `thinking tokens are never added into output tokens` |
| Duplicates counted once, retries separate | `duplicate observations are counted once and retries stay separate attempts` |
| Missing TTFT never becomes measured; default STOP is not a report; requested model does not stand in | `unreported provenance is never upgraded to a provider claim`, `no time-to-first-token or provider-reported finish reason is fabricated` |
| Same, on the real recorder | `usage and provenance are recorded exactly as the provider reported them`, `an unreported provider model stays unknown instead of borrowing the requested one`, `a provider that reports no usage leaves every component unknown` |
| One write per attempt | `AttemptRecordingWriteCountTest` (2 tests) |

Exact numbers used: input `12`, output `5`, thinking `400` (never `405`); retry totals `14`/`7` over two
attempts; duplicates give `attemptsCounted=1, duplicatesIgnored=1`; partial usage gives
`inputTokens=null, attemptsWithoutUsage=1, attemptsCounted=2`.

## Gate 5 — mutation campaign

Method: for each mutant the anchor was required to match exactly once, the file's sha256 was changed by
the patch (verified), the detector test class was run, the failing test names were captured from the JUnit
XML, the file was restored from a backup, and the restored bytes were verified to hash back to the
original. No test was modified to kill a mutant.

**15 admissible behavioural mutants, 15 killed, 0 survived, 0 inadmissible, 0 undetermined.**

| ID | Family | Mutation | Detector | Result |
|---|---|---|---|---|
| M01 | confidentiality | record the exception message instead of its class name | `ProducerPathConfidentialityTest` | KILLED — `prompt, response and provider failure messages never reach recorded evidence` |
| M02 | provenance | promote the default `STOP` to a provider-reported finish reason | `ProducerPathProvenanceTest` | KILLED — `usage and provenance are recorded exactly as the provider reported them` |
| M03 | provenance | let the requested model stand in for an unreported provider model | `ProducerPathProvenanceTest` | KILLED — `an unreported provider model stays unknown instead of borrowing the requested one` |
| M04 | duplication | drop the single-write guard | `AttemptRecordingWriteCountTest` | KILLED — `one attempt is written exactly once whatever terminal callbacks arrive`, `cancellation after a response still writes once` |
| M05 | accounting | fold reasoning tokens into the completion count | `ProducerPathProvenanceTest` | KILLED — `usage and provenance are recorded exactly as the provider reported them` |
| M06 | accounting | sum the reporting attempts instead of reporting an incomplete total as unknown | `TimelineEvidenceIntegrityTest` | KILLED — `a partial component is unknown rather than a smaller total`, `explicit zero usage is preserved as zero and missing usage stays unknown` |
| M07 | correlation | drop null correlation ids so mixed evidence looks like agreement | `SemanticTimelineQueryTest` | KILLED — `mixed correlation evidence is unresolved instead of picking the id` |
| M08 | correlation | claim a same-run link when no attempt was observed | `SemanticTimelineQueryTest` | KILLED — `a snapshot for the same run claims no link when no attempt was observed` |
| M09 | missing-evidence | report execution as established for an uninstrumented history | `MissingEvidenceSemanticsTest` | KILLED — `an uninstrumented store leaves execution unknown rather than empty` |
| M10 | provenance | present a source-declared reference as if nothing were unverified | `AdversarialSemanticsTest` | KILLED — `a source-declared reference never becomes authenticated provenance` |
| M11 | status | call a result `SUPPORTED` when only the attempt source answered | `AdversarialSemanticsTest` | KILLED — `a refused projection cannot be supported merely because attempts exist` |
| M12 | missing-evidence | turn a historical source failure into `NotRecorded` | `AdversarialSemanticsTest` | KILLED — `a source failure propagates instead of becoming NotRecorded` |
| M13 | confidentiality | export the raw provider throwable and message into the span again | `ProducerPathTelemetryPrivacyTest` | KILLED — `provider exception messages and response content never reach a span or an attempt row` |
| M14 | missing-evidence | drop the uninstrumented-history limitation | `MissingEvidenceSemanticsTest` | KILLED — `an uninstrumented store leaves execution unknown rather than empty` |
| M15 | accounting | count duplicate observations instead of ignoring them | `TimelineEvidenceIntegrityTest` | KILLED — `duplicate observations are counted once and retries stay separate attempts` |

### The campaign's most valuable result: four first-pass survivors

The first pass killed 11 and **survived 4** (M02, M03, M04, M05). They survived because the read-path
tests build observations by hand — a defect in the *recorder* is invisible to a test that never runs the
recorder. That is the "fixture silently selects a different branch" failure mode named in the repository's
evidence protocol, caught by mutation rather than by review. Two detectors were written on the real path
(`ProducerPathProvenanceTest`; `AttemptRecordingWriteCountTest`, which counts `append` calls because
`InMemoryAttemptObservationStore` is idempotent and hides a second write), and the four were re-run:
all four killed. Both passes are recorded, and the surviving verdicts were not hidden or reclassified.

## Gate 6 — verification

Module suites (JDK 21, `--no-daemon`):

```
:tramai-core:test              tests=346  failures=0   (1 skipped: JMH benchmark)
:tramai-engine:test            tests=1028 failures=0   (6 skipped: JMH benchmarks)
:tramai-security:test          tests=1330 failures=0   (3 skipped: DB-harness-conditioned governance tests)
:tramai-persistence-jdbc:test  tests=491  failures=0
:tramai-observability:test     tests=55   failures=0
```

Skips are pre-existing and environment-conditioned (7 JMH benchmarks, 3 two-phase/docker governance
qualification tests). No 0.7.5g test is skipped.

Gates: `spotlessCheck`, `verifyStaticAnalysis` (0 non-baselined findings), `verifyChangePolicy`
(10 files, `runtime-behaviour`, no violations), `:tramai-security:apiCheck` (no API change: the fix is
behavioural), `verify060Architecture`, `verifyCancellationSafety` (324 findings, no new critical/high),
`verifyJUnitTestSignatures`, `verifyCompilerWarnings` (gate green).

## Guarantees established vs assumptions that remain

**Established:**

1. Through the qualified producer path, prompts, response content, tool arguments/results, and provider
   exception messages do not appear in recorded attempt fields, persisted columns, timeline results,
   reconstruction results, their renderings, or exported spans.
2. A provider failure is exported to telemetry as a type name only.
3. The shipped reconstruction composition performs zero writes, an exact number of reads, and returns
   stable results across repetitions.
4. No reconstruction type can reach a provider, tool, approval or network capability.
5. Attempt records keep usage and provenance exactly as reported: zero ≠ unknown, partial ≠ total,
   thinking ≠ output, requested ≠ reported, default ≠ provider-reported, duplicates ≠ two attempts,
   observed ≠ authoritative.
6. Status and limitations are derived from evidence and cannot contradict it.
7. Fifteen deliberate violations of these properties are each detected by a named test.

**Remaining assumptions (not guarantees):**

1. **Caller-supplied collaborators are unverified.** An implementation of `SemanticTimelineQuery` or
   `HistoricalConfigurationSource` can write, call out, or read current configuration. Demonstrated in
   Gate 2, documented on the ports since 0.7.5f.
2. **A provider exception message may still reach places outside this boundary** — application logs, a
   caller's own `OperationObserver`, and the worker/workflow OTel observers, which still use
   `recordException`. Not claimed confidential; a follow-up if those surfaces matter.
3. **JDBC column confidentiality is proven for the current schema.** The test scans every column of every
   row, so a new column is covered automatically, but only for the attempt-observation table.
4. **No TTFT or reasoning-token measurement exists**, so nothing can fabricate one; that is an absence,
   not a verified measurement.
5. **The engine relies on `providerTransportFailure` for sanitization.** A future path that notifies
   observers with an unsanitized throwable is now harmless only because the observer exports types, not
   messages.
