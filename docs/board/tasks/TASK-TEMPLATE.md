# TASK-TEMPLATE: Agent execution specification

Copy this template when assigning a task to a coding agent. Fill every section. The agent must stop and report before deviating from the Change Class, Allowed Paths, or Forbidden Paths.

```markdown
# TASK-NNN: Title

- Status: ready / in-progress / done
- Priority: high / medium / low
- Change class: runtime-behaviour | public-api | build-logic | canonical-baseline
  | quality-deviation | ci-workflow | documentation | baseline-migration
- Primary spec: [SPEC-NNN](../specs/spec-NNN-title.md)
- Related ADRs: [ADR-NNN](../../adr/adr-NNN.md)
- Last updated: YYYY-MM-DD

## Rationale

Why this task exists — the user or system problem it solves.

## Allowed paths

Only these file patterns may be modified:
- `module-a/src/main/**`
- `module-a/src/test/**`

## Forbidden paths

These files must NOT be changed:
- `build-logic/**`
- `config/quality/0.6.0-baseline.json`
- `config/quality/maintainability-deviations.yml`
- `.github/workflows/**`

## Invariant

A concise statement of what must remain true after the change.
Example: "Cancellation must propagate without provider fallback or retry."
Example: "Public API surface must remain backward-compatible."
Example: "Analyzer output cardinality and identity must remain unchanged."

## Expected quality delta

What measurements may change — and what must not:
- `cancellationCriticalCount`: may decrease only
- `nondeterminism-findings`: must remain unchanged
- API changes: none / listed
- deviations changed: none / listed

## Required tests

- Test case 1
- Test case 2

## Required verification

./gradlew verifyPr

## Stop conditions

Stop immediately and report if completing this task requires:
- changing scanner semantics, baseline files, deviations, or workflow definitions
- modifying files outside Allowed paths
- changing a file in Forbidden paths
- expanding the scope beyond this task's epics
```

## Example

```markdown
# TASK-042: Fix cancellation propagation in engine retry path

- Status: ready
- Priority: high
- Change class: runtime-behaviour
- Primary spec: [SPEC-001](../../specs/spec-001-core-engine.md)
- Related ADRs: [ADR-002](../../adr/adr-002.md)
- Last updated: 2026-07-26

## Rationale

The engine retry path catches `CancellationException` in some branches,
preventing coroutine cancellation from propagating correctly. This causes
timeout-based cancellation to hang until the retry budget is exhausted.

## Allowed paths

- `tramai-engine/src/main/**`
- `tramai-engine/src/test/**`

## Forbidden paths

- `build-logic/**`
- `config/quality/0.6.0-baseline.json`
- `config/quality/maintainability-deviations.yml`
- `.github/workflows/**`

## Invariant

Cancellation must propagate without provider fallback or retry.

## Expected quality delta

- `cancellationCriticalCount`: may decrease
- analyzer cardinality and identity: must remain unchanged
- all other measurements: must remain unchanged

## Required tests

- cancellation during provider call
- cancellation during retry
- cancellation before fallback

## Required verification

./gradlew verifyPr

## Stop conditions

Stop if completing the task requires changing scanner semantics,
baseline files, deviations, or workflow definitions.
```
