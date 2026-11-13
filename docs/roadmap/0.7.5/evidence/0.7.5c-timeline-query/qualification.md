# 0.7.5c — Semantic Timeline Projection & Query

A read-only timeline that shows what governance recorded and what model attempts were observed for
one governed run. It adds no new persistence and no new evidence: it projects two existing sources.

## Scope

| Item | Decision |
|---|---|
| Contract and projection | `tramai-security/.../timeline/SemanticTimeline.kt`, `StoreBackedSemanticTimelineQuery.kt` |
| Sources | `GovernanceQueryService` (Epic 0.7.4) and `AttemptObservationStore` (Epic 0.7.5b) |
| New storage | none — both sources are read through their existing ports |
| Event log / reconstruction | none — 0.7.5d/e |
| HTTP endpoint, dashboard, cost analytics | none |
| Changes to governance or authorization | none |

`SemanticTimelineQuery.of(attempts, governance)` is the whole entry point. Governance is optional: a
request with no governance scope reports `NotQueried`, one with a scope but no configured query
reports `NotConfigured`, and neither is reported as "governance recorded nothing".

## What the contract refuses to claim

- **No invented causality.** The only association expressed is `CorrelationEvidence`, and it has two
  values: `SAME_WORKFLOW_RUN` (both sections belong to the same run id) and `NONE` (unresolved). There
  is no decision-to-attempt edge, because the stored evidence does not carry one.
- **A governance snapshot is not an event log.** `GovernanceSection.Snapshot` carries the existing
  `GovernanceProjectionView` unchanged: the facts recorded at materialization time, with their
  authority, freshness, currency and coverage. Absent or unrecognized decisions stay
  `NotMaterialized` / `UnsupportedFormat` / `InvalidStoredProjection`, never "nothing happened".
- **A scope for another run is refused.** `TimelineRequest` rejects a `governanceScope` naming a
  different `workflowRunId` rather than presenting that run's governance beside these attempts.
- **Unknown is not zero, and a partial sum is not a total.** A usage component is reported only when
  **every** counted attempt reported it; summing the attempts that happened to report would produce a
  number that reads as the run total while silently omitting the rest. Components are `null`
  otherwise, the summary itself is `null` when nothing was recorded, and `attemptsWithoutUsage` is
  the count to read when a component is `null`. Thinking tokens are their own component and are never
  added into the output total.
- **Uninstrumented history is not empty history.** `AttemptHistoryState` separates `UNINSTRUMENTED`
  (nothing persists, so nothing can be established) from `EMPTY` (recording works and found nothing)
  from `RECORDED`.
- **Attempts stay telemetry.** They carry `AttemptProvenance.OBSERVED_IN_PROCESS`; governance facts
  keep their own authority. Nothing in the timeline promotes an observation to authority.

## Determinism

A correlation id is reported for an invocation only when every attempt carries the same one: an
attempt with an id beside an attempt without one is mixed evidence, and picking the id would assert an
association the records do not support. A snapshot that answers for the same run claims no link at all
when no attempt was observed, because there is no second side to correlate.

Ordering is derived only from recorded fields: invocations by first attempt `startedAt` then
`invocationId`, attempts by `(startedAt, attempt)`, the flat attempt list by
`(startedAt, invocationId, attempt)`. Duplicate `(invocationId, attempt)` rows returned by a store are
collapsed before anything is counted.

## Verification

| Claim | Evidence |
|---|---|
| Retry and repeated invocation stay distinct | `a retry stays two attempts of one invocation and a repeated call stays two invocations` |
| Order comes from recorded fields, not store order | `ordering comes from recorded timestamps and identity, never from store order` |
| Repeated queries agree | `a timeline is stable across repeated queries` |
| Duplicates do not inflate usage | `duplicate observations do not inflate usage` (3 copies → `attemptsCounted=2`, `duplicatesIgnored=2`) |
| Unknown usage stays unknown, thinking tokens not double-counted | `unknown usage stays unknown and thinking tokens are never added to output` |
| No attempts ⇒ no usage summary, not a zero summary | `no attempt at all means no usage summary rather than a zero summary` |
| Uninstrumented ≠ empty | `uninstrumented history is not empty history` |
| Missing correlation stays unresolved | `missing correlation remains unresolved` |
| Only same-run identity is claimed | `a governance snapshot for the same run is the only correlation claimed` |
| Mixed correlation evidence is not agreement | `mixed correlation evidence is unresolved instead of picking the id` |
| A same-run snapshot links to nothing when nothing was observed | `a snapshot for the same run claims no link when no attempt was observed` |
| A partial component total is not a run total | `a component total is null when any counted attempt did not report it` |
| A component every attempt reported still sums | `a component missing from one attempt does not suppress a component all attempts reported` |
| A refused projection keeps its own state | `an unsupported or invalid projection is carried through as its own state` |
| A foreign-run scope is refused | `a governance scope naming another run is refused rather than presented` |
| An unmaterialized projection does not erase attempts | `an unmaterialized projection is reported as such and not as absent governance` |
| Observations never become authority | `observations stay observed telemetry and never become governance evidence` |
| Queries are pure reads | `a query reads and never writes` (append and invocation reads fail loudly if touched) |

Real execution path (`tramai-engine`, no hand-built evidence — a governed, retried call recorded by
the real observer):

| Claim | Evidence |
|---|---|
| A real retried run is one invocation with `FAILED` then `SUCCEEDED` | `a real retried governed run becomes one invocation with a failed and a succeeded attempt` |
| Separate runs are separate invocations | `repeated governed runs are separate invocations, never one merged history` |
| No prompt content in a real timeline | `the timeline of a real run carries no prompt content` |
| A read records nothing | `a run with no recorded attempt is empty history and a query records nothing` |
| Governance sits beside attempts; correlation only on the run id | `governance is reported beside attempts and correlated only by the run identity` |

The engine test opts in to the internal API marker at file level because `GovernedRunScope` is
plumbing, not application vocabulary: it is the sanctioned way to put a real call inside a governed
run without inventing an identity.

Review round 1 corrected three of these claims and added the tests above. Discrimination recorded:
with the partial-sum, `mapNotNull` correlation and unconditional-correlation mutants applied, the
three corresponding tests fail; with the fix restored they pass.

Test totals at the head that opened the PR: `:tramai-security:test` 1275/0, `:tramai-engine:test`
1023/0 (5 new execution tests, 19 timeline tests).

## Not done, deliberately

- No invocation-scoped query. A timeline is addressed by run identity, which is the identity both
  sources can be read by; `findByInvocation` stays available on the store for callers that need it.
- No retention, paging or aggregate-over-runs. Those belong to a query/analytics slice, not this one.
- `finishReasonReported` remains `false` on every attempt, inherited from 0.7.5b: a
  `ModelResponse.finishReason` default is never promoted to a provider claim.
- Automatic Spring wiring of the recorder is still not installed (recorded in 0.7.5b), so a default
  Spring Boot application records no attempts and its timeline reports `UNINSTRUMENTED`. That is a
  truthful answer rather than an empty one, but it is a release gap for Spring users.
