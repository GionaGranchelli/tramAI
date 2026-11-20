# 0.7.5b — Durable model-attempt observation: qualification

**Scope:** one content-free attempt record, one durable store, one producer on the single native
non-streaming path, one read API. No streaming, no TTFT, no dashboard, no HTTP surface, no cost
analytics, no change to the 0.7.4 governance query.
**Baseline:** [0.7.5a audit](../../AUDIT-0.7.5a-TIMELINE-RECONSTRUCTION.md) · `release/0.7.0` = `ab34b4e7`

## What was built

| Piece | File | Why it is the minimum |
|---|---|---|
| Attempt record + SPI + history states | `tramai-core/.../observation/attempt/ModelAttemptObservation.kt` | the type cannot express prompt, response, tool or reasoning content, so no filter can forget to strip it |
| Recording observer | `tramai-core/.../observation/attempt/AttemptRecordingObserver.kt` | decorator over the existing `OperationObserver`; OpenTelemetry observation keeps working unchanged |
| In-memory store | `tramai-core/.../observation/attempt/InMemoryAttemptObservationStore.kt` | test double with the same uniqueness rule as the database |
| Identity bridge | `OperationCallContext.identity` (`AttemptIdentity`) + `ProviderExecutionCoordinator`/`ProviderAttemptExecutor` | the engine already knew provider, attempt, route index and governed run; it just never handed them to an observer |
| Durable store | `tramai-persistence-jdbc/.../JdbcModelAttemptObservationStore.kt` + `V9__model_attempt_observations.sql` | one table, primary key `(invocation_id, attempt)`; separate from the audit chain |
| Wiring | `SovereignJdbcPersistenceAutoConfiguration.modelAttemptObservationStore` | one bean, conditional on a `DataSource`, overridable |

## Invariants and their evidence

| Invariant | Mechanism | Test |
|---|---|---|
| Two invocations never share an attempt identity | `invocationId` generated once per `ProviderExecutionRequest`; `(invocationId, attempt)` is the key | `repeated invocations never share an attempt identity` |
| A retry is two attempts, not one | one record per terminal callback, keyed by attempt | `retry followed by success persists two distinct attempts of one invocation` |
| Duplicate writes never duplicate observations | PK `ON CONFLICT DO NOTHING` + `written` latch | `a duplicate write is idempotent…`, `repeated terminal callbacks do not duplicate the attempt` |
| Missing usage stays unknown | nullable columns, `null` preserved end to end | `missing usage stays null…`, `unknown values survive the round trip as unknown` |
| Thinking tokens are not double-counted | three separate fields, **no total is ever stored or derived** | `a successful attempt is recorded with usage exactly as reported…` |
| A default finish reason is not a provider claim | `finishReasonReported` is `false` on this path | `missing usage stays unknown and a default finish reason is not a provider claim` |
| Failure and cancellation never become success | outcome derived from what actually happened; `UNKNOWN` when neither | `a failed attempt is never recorded as a successful completion`, `a cancelled attempt is recorded as CANCELLED`, `a completion with neither response nor failure is UNKNOWN` |
| Observation cannot change a governed outcome | callbacks isolated; store and delegate failures both contained | `a store failure neither escapes nor stops the delegate…`, `a throwing delegate still lets the attempt be recorded` |
| No prompt, response or secret is reachable | the type has no such field; asserted anyway | `queries expose no prompt text and no secrets` (canary in the prompt) |
| No configured persistence fabricates nothing | uninstrumented store + absent observer | `with no observation persistence configured nothing is fabricated`, `the uninstrumented store claims nothing` |
| History survives the writing process | fresh store instance per read over the same datasource | `an attempt written by one process is readable by another with every field intact` |

## Identities that must not be conflated

- **requestedModel vs reportedModel** — the operation's model is always recorded; the answering model
  is recorded only when the provider named one. Never merged.
- **`Available(emptyList())` vs `Uninstrumented`** — "recorded and nothing was there" is a finding;
  "nothing is recorded here" is not. Only the second returns `Uninstrumented`.
- **observed vs authoritative** — every row carries `provenance = OBSERVED_IN_PROCESS` and lives
  outside the audit chain. Attempt rows are not evidence that a governance decision was enforced.

## Verification at this head

| Command | Result |
|---|---|
| `:tramai-engine:test --tests …ModelAttemptObservationExecutionTest` | **7 tests, 0 failures** — real engine, real provider, real retry |
| `:tramai-core:test --tests …AttemptRecordingObserverTest` | **11 tests, 0 failures** |
| `:tramai-persistence-jdbc:test --tests …JdbcModelAttemptObservationStoreTest` | **6 tests, 0 failures** — PostgreSQL 17 via testcontainers |
| `:tramai-core:test` (whole module) | **342 tests, 0 failures** |
| `:tramai-engine:test` (whole module) | **1016 tests, 0 failures** |
| `:tramai-persistence-jdbc:test` (whole module) | **490 tests, 0 failures** |
| `./gradlew apiCheck` | PASSED (dumps regenerated for core, persistence-jdbc and the starter) |
| `./gradlew verifyStaticAnalysis -PtramaiStaticAnalysisBaseRef=<release head>` | PASSED — baseline 4792 -> 4792, **0 new findings** |
| `./gradlew spotlessCheck` | PASSED |
| `./gradlew verifyChangePolicy -PchangePolicyBase=<release head>` | PASSED — 28 files, `runtime-behaviour` |
| `./gradlew verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures verifyModuleManifest` | PASSED |

`verifyPr` as a single invocation was not run to completion locally: the process is killed after
several minutes in this environment (exit 143). Its components were run separately, above.

## Declared limits, not solved here

1. **Streaming attempts are not recorded.** Out of scope: `StreamChunk` has no timestamps and a
   partial stream has no terminal usage, so a streaming record would be guesswork. A streaming
   attempt therefore produces *no* observation, which is the honest answer.
2. **TTFT is still absent.** No field exists in the core model, so the record has none rather than a
   fabricated value.
3. **`finishReasonReported` is always `false`.** `ModelResponse` cannot distinguish a
   provider-reported `STOP` from the default; closing this needs a provider-reported flag in the
   model, which is a separate change.
4. **No per-decision correlation.** An attempt is attributable to an invocation and, when one exists,
   a governed run — not to an individual audit position.
5. **No retention or rotation policy** for the new table; rows accumulate until an operator deletes
   them.
6. **No `AttemptObservationStoreTck` family.** The repository's store-enrollment guards
   (`AuditStoreTckEnrollmentArchitectureTest` and siblings) are opt-in per SPI family, and this SPI has
   one durable implementation plus a test double. The behavioural invariants are pinned by the JDBC and
   observer suites listed above. A TCK family becomes warranted when a second durable store appears —
   that is the point at which the same rule would otherwise be re-implemented three ways.
7. S18 (workload identity), S19 (event origin) and T1 (caller authentication) remain unresolved from
   0.7.4: a recorded attempt says an attempt was *observed*, not that the caller was authenticated.

## Review round 1 — four corrections

**1. A refused call is not a provider attempt.** Observation used to start before authorization and
before the `BEFORE_PROVIDER_INVOCATION` gate, so a policy or registry refusal was recorded as an
attempt (and a registry refusal could land as `UNKNOWN`). Recording now begins only after both
pre-call checks pass: the engine asks its observer for a recording observation *after* the gate, so a
refusal leaves no row and a start timestamp describes an actual provider call. Telemetry for the
refusal is untouched — the pre-existing observation still receives its callbacks.

**2. A delegate failure can no longer drop the record.** Recording used to run after the delegate call
and depended on the observation returned by the delegate's `onCallStarted`. Delegate callbacks are now
contained, and the terminal record is written *before* forwarding, so an observer outage cannot erase
attempt history. Cancellation still escapes through the existing containment rule.

**3. The Spring path is wired by the application, and that gap is documented rather than claimed
closed.** The persistence starter publishes an `AttemptObservationStore` bean, but the default
`TramaiAutoConfiguration` never collects an `OperationObserver`, so a default Spring Boot application
gets the store and records no rows. Installing the recorder is one line at the composition point the
application already owns:

```kotlin
Tramai.builder()
    .observer(AttemptRecordingObserver(store, delegate = myObserver))  // myObserver may be NoOp
```

That configuration is what the eight engine execution tests drive: they pass an
`AttemptRecordingObserver` as the engine observer and assert the rows a real provider call produces,
including retry, refusal, cancellation and duplicate-write cases.

**Not fixed here, stated as a limitation:** the auto-configuration was not changed to install the
recorder automatically. `TramaiAutoConfiguration.kt` is a legacy file whose pre-existing
over-long declarations are baselined by exact source snippet; any edit reformats the file, and the
reformatting rule re-joins the very lines detekt then rejects, so the file cannot be touched without
an unrelated, unreviewable rewrite of a dozen legacy declarations. That rewrite is a separate task;
until it happens, automatic Spring recording is not claimed.

**4. In-memory history order matches JDBC.** `InMemoryAttemptObservationStore.findByInvocation` now
orders by `(startedAt, attempt)` exactly as the durable store does, so a test can no longer pass with
an order production never returns.

### Added tests

| Invariant | Test |
|---|---|
| Recording does not begin at call start (a refusal cannot be recorded) | `recording starts only when the engine asks for it, not at call start` |
| A throwing delegate cannot drop the record | `a throwing delegate cannot drop the attempt record` |
| A refused call records nothing end-to-end | `a call refused before the provider is invoked records no attempt` |
| No store bean means no recorder and no history | `with no observation persistence configured nothing is fabricated` |

### Limit, stated rather than implied

Automatic Spring installation is not implemented (see correction 3). The supported path is explicit
observer wiring at the application's composition point, exercised end-to-end by the engine execution
tests; automatic installation is blocked on the separate legacy-file reformat task.

## Review round 2 — the capability refusal

A vision-capability refusal still landed after recording was armed. `requireVisionSupport` lived inside
`callOnce`, which runs after line 94 arms the recorder, so on the legacy routing path an unsupported
provider that was never called could be written as a `FAILED` attempt. The check now runs in the retry
loop immediately after the `BEFORE_PROVIDER_INVOCATION` gate and before recording is armed; only
reachable provider faults remain inside `callOnce`.

Test: `a capability refusal records no attempt because the provider was never called` — a vision-less
provider, a request carrying an image, the capability exception asserted, and the store asserted empty.
