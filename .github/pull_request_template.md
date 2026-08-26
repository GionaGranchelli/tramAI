<!--
TramAI pull request template.
Fill in every section. Replace the hint text in [brackets].
Sections marked (required) must be answered; sections marked (if applicable) can be left out only when the change cannot touch them — do not delete the section, say "not applicable".
-->

## Purpose

<!-- What responsibility changed, and why? (required) -->

## Scope

<!-- Which architectural boundary owns this change? Which change class is this (see CONTRIBUTING.md / AGENTS.md)? (required) -->

- Change class: [runtime-behaviour | public-api | build-logic | canonical-baseline | quality-deviation | ci-workflow | documentation | baseline-migration]
- Files touched: [list]

## Behavioural Contract

<!-- What is the externally observable contract change? What is the safe public error? (required) -->

## Architecture Impact

<!-- Answer the architecture questions explicitly. (required) -->

- [ ] No new dependency edge introduced
- [ ] Core does not depend on an adapter/framework
- [ ] No ownership/lifecycle change (or described below)
- [ ] No global mutable state introduced
- [ ] No duplicate of an existing authoritative model (provider routing, failure taxonomy, runtime events, module catalog, module boundaries, structured contract)
- If any box is unchecked, describe the impact and the ADR/design reference: [describe]

## Compatibility

<!-- Answer the compatibility questions explicitly. (required) -->

- [ ] Stable public API unchanged
- [ ] Preview API unchanged
- [ ] Persisted representation unchanged
- [ ] Event/reason/error code unchanged
- [ ] Configuration semantics unchanged
- If any box is unchecked, describe the impact and the migration path: [describe]

## Correctness & Concurrency

<!-- Answer the correctness questions explicitly. (required) -->

- [ ] Cancellation behaviour unaffected (or described)
- [ ] Retry behaviour unaffected (or described)
- [ ] Concurrency unaffected (or described)
- [ ] Evidence/audit/telemetry emission unaffected (or described)
- [ ] Change is replay-safe
- Which contract test proves the change? [test name(s)]
- If this fixes a defect: what discriminator failed against the baseline before the fix? [RED → GREEN evidence]

## Verification

<!-- State the exact commands run and their results. `./gradlew test` alone is not completion evidence. (required) -->

- [ ] Focused test: [command + result]
- [ ] `./gradlew verifyPr`: [result]
- [ ] Specialized gates (if applicable): [command + result]
- [ ] Skipped checks and why: [list or "none"]

## Quality Impact

<!-- verifyPr result, specialized checks, baseline/deviation changes. (required) -->

- [ ] No baseline regeneration
- [ ] No deviation ceiling increase
- [ ] No quality gate weakened
- If any box is unchecked: [describe]

## Remaining Risks

<!-- Known limitations, follow-up work, anything the reviewer should double-check. (required) -->

## Non-Claims

<!-- What this PR does NOT prove or add. (required) -->

This PR needs review before merge.
