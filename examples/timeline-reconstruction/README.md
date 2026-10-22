# Timeline & Reconstruction example

Reference integration for Epic 0.7.5: read a run's recorded evidence through the shipped timeline query,
reconstruct it, and render a narrative from typed fields only.

Both a Kotlin and a Java version ship here, because TramAI claims Java friendliness and a claim like that
should be compiled, not described.

## Run it

```bash
./gradlew :examples:timeline-reconstruction:run      # Kotlin narrative
./gradlew :examples:timeline-reconstruction:test     # Kotlin + Java + PostgreSQL qualification
```

## What the narrative looks like

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

Read that output as an argument for the design:

- `status PARTIAL` — governance was never asked, and the result says so instead of implying completeness.
- `input=unknown` — one of the two attempts reported no token counts, so there is **no** run total. A `0`
  here would be a fabricated fact.
- `reported=unknown` and `(not provider-reported)` — the provider named no model on the failed attempt, and
  `ModelResponse` defaults `finishReason` to `STOP`, which is a default, not a provider claim.
- `HISTORICAL_CONFIGURATION_UNVERIFIED` — the configuration reference came from a source; nothing in this
  library can authenticate it.

## The three things worth copying

1. **The reconstructor builds its own request.** You pass a run id and an optional governance scope. A
   caller cannot inject a historical configuration reference, which is what makes "never substitute current
   configuration" structural rather than a code review rule.
2. **Your `HistoricalConfigurationSource` implementation is your responsibility.** Return `NotRecorded` only
   when the lookup completed and found nothing; throw when it fails. A swallowed failure reported as
   `NotRecorded` reads as "the run recorded nothing", which is a different and false claim.
3. **Render from typed fields.** Prompts, completions, tool arguments and reasoning are not expressible in
   these types, so no redaction step exists to forget.

## Java notes

- `SemanticTimelineQuery.Companion.of(store, governance)` — the companion member, not a constructor.
- `new HistoricalConfigurationReference.Recorded(configuration, policyVersion, workflowDigest)` — use the
  three-argument form; `@JvmOverloads` makes the one-argument form mean `workflowDigest`.
- `timeline.attempts()` is a function; `timeline.getInvocations()`, `getUsage()`, `getWorkflowRunId()` are
  property accessors.

## What this example does not do

It does not execute anything: no provider call, no tool, no approval, no network. Reconstruction is a read.
It also does not authenticate provenance, import external-runtime events, or measure TTFT — see the
[epic qualification](../../docs/roadmap/0.7.5/evidence/0.7.5h-integration-examples/qualification.md) for the
criteria that remain PARTIAL and why.
