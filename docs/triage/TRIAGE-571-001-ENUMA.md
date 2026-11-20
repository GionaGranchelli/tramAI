# TRIAGE-571-001 — Enuma failure qualification (Issue #571)

Recorded in-repo as the stop evidence for the bounded triage. Posted verbatim to Issue #571.
Base: `release/0.7.0` at `8de4f9d6`. Status: **UNRESOLVED on the observation, INFERRED on the cause.**

**Verdict: most likely integration drift, not a `release/0.7.0` defect.** The only enum-shaped artifact in the demo material is a *parked* workaround patch, and the failure mode it addresses was fixed on 2026-08-22 — before the demo, but **after** the TramAI revision the demo actually pinned.

### 1. What "Enuma" is — UNRESOLVED, and probably a misremembering of "enum"

Exhaustive bounded search found no `Enuma` anywhere:

- `grep -ril enuma` over `~/Development/ktconf-2026-typed-ai-boundaries{,-2}` (excluding `.git`/`build`): 0 hits
- `git log --all --grep=enuma -i` in both demo repos: 0 hits
- GitHub `search/issues?q=enuma+repo:GionaGranchelli/tramAI`: 1 hit — this issue
- GitHub `search/code?q=enuma+org:GionaGranchelli`: 0 hits
- shell history: 0 hits

What *does* exist is an enum-shaped artifact, uncommitted, in the demo repo:

```
~/Development/ktconf-2026-typed-ai-boundaries
  M vendor/tramai (0.3.0-468-g852c89ba)
  M  tramai-structured/.../JacksonStructuredOutputHandler.kt     (+10 -3)
  M  tramai-structured/.../JacksonStructuredOutputHandlerTest.kt  (+19)
  ?? dont_for_now.patch          # the same change, parked
```

So the working hypothesis is `Enuma ≈ enum`. It is a hypothesis, not a finding: nothing in the demo records the word.

### 2. The failure mode, reproduced deterministically

The KTConf demo's structured-output type carries two Kotlin enums (`InvoiceAssessment.risk`, `.recommendedAction`).

At the **demo's pinned revision** `852c89ba` (2026-07-17), the schema generator routes an enum `KClass` into object-schema generation:

```json
"risk" : { "type" : "object", "properties" : { "name" : {"type":"string"}, "ordinal" : {"type":"integer"} } }
"recommendedAction" : { "type" : "object", "properties" : { "name" : ... , "ordinal" : ... } }
```

At **`release/0.7.0` (`8de4f9d6`)** the same contract is correct:

```json
"risk" : { "type" : "string", "enum" : [ "LOW", "HIGH" ] }
"recommendedAction" : { "type" : "string", "enum" : [ "REVIEW_ONLY", "REQUEST_HUMAN_APPROVAL", "SCHEDULE_PAYMENT" ] }
```

and a well-formed model response parses: `StructuredOutputResult.Success(value=InvoiceAssessment(... risk=HIGH, recommendedAction=REQUEST_HUMAN_APPROVAL ...))`.

Both runs are the same 40-line reproducer (`JacksonStructuredOutputHandler.createContract(typeOf<InvoiceAssessment>())` + `analyze(...)`), executed on a worktree at each revision. It was not committed: it is a triage artifact, not a slice.

### 3. Why the demo failed and the release does not

`f4a2cb1a` (2026-08-22) — *"fix(structured): generate string enum schemas instead of name/ordinal objects"* — states the mechanism in its own body:

> Kotlin enums were routed into object schema generation, producing `{"type":"object","properties":{"name","ordinal"}}` schemas. Jackson deserializes enums from flat strings, so no model output could satisfy both the schema and the parser: any model following the schema was rejected after deserialization. Verified with gemma-4-12b, gemma4:e4b and deepseek-chat — all three emitted name/ordinal objects exactly as the schema instructed.

- `f4a2cb1a` **is** an ancestor of `release/0.7.0` (`8de4f9d6`).
- `f4a2cb1a` **is not** an ancestor of the demo pin `852c89ba`.

The demo (2026-09-18) ran a TramAI two months older than the fix. **Ordinary SDK consumers on `release/0.7.0` are not affected.**

### 4. Two demo-repo defects worth fixing there, not here

1. **Stale pin.** `vendor/tramai` is at `0.3.0-468-g852c89ba`, predating the enum fix. The demo needs a version bump, not a patch.
2. **The vendored tree is currently broken.** Uncommitted edits replace the file's package declaration with the literal text `pro` — `JacksonStructuredOutputHandler.kt` line 1 is `pro`, not `package dev.tramai.structured`. That tree cannot compile, so any "reproduce the demo" attempt today fails for a second, unrelated reason. `dont_for_now.patch` is unnecessary once the pin moves (the library handles enums), and should be dropped rather than re-applied.

### 5. What this triage could not establish

- **No original failure evidence exists** (no logs, stack trace, command, or environment captured at the demo). The diagnosis above is a deterministic reproduction of a *plausible* cause, not a recording of the observed one.
- **"Enuma" was never identified.** If it is a product, service or participant name unrelated to enums, this triage does not cover it and the issue needs that name resolved first.
- **Acceptance criterion 4 is not met**: the actual demo scenario was not re-run end-to-end, because the demo's vendor tree does not compile (item 4.2) and the demo needs its own repository changes.

### 6. Recommendation

- **Keep this issue open**; it is blocked on owner input, not on TramAI work: (a) confirm or correct the `Enuma ≈ enum` reading, (b) bump the demo's TramAI pin to `release/0.7.0`, (c) drop `dont_for_now.patch` and repair the vendored package declaration, then (d) re-run the demo scenario and capture the result.
- **No TramAI fix is authorized or needed on this evidence**: `release/0.7.0` already emits string enums and parses them.
- **0.7.7 (cancellation & resume fencing) is not blocked by this** and proceeds next.
