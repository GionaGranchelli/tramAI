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
