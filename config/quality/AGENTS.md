# AGENTS.md — config/quality/

This directory contains TramAI's quality baseline and configuration files.

## File roles

| File | Purpose |
|------|---------|
| `0.6.0-baseline.json` | Immutable canonical release measurement for v0.5.0. Do not edit directly. |
| `maintainability-deviations.yml` | Accepted deviations from the canonical baseline. |
| `module-catalog.yml` | Module registry used by dependency analysis. |
| `module-boundaries.yml` | Allowed dependency directions between modules. |
| `test-quality.yml` | Coverage, mutation, and performance targets for critical modules. |
| `mutation-classifications.yml` | Pitest mutation family classification overrides. |
| `runtime-protocol-catalog.json` | Registered runtime protocol identifiers (auto-generated). |

## Rules

1. **`0.6.0-baseline.json` is the immutable canonical release measurement.** It represents the state at the v0.5.0 tag, measured by the analyzer version that existed at that tag.

2. **PR regression checks compare against the PR base**, not the canonical release. When a PR branch compares against `origin/master`, the reference is the master baseline — deviations from canonical are expected and tracked via `maintainability-deviations.yml`.

3. **Deviations require** all of: measured baseline value, current allowed value, rationale, acceptance date, target phase, owner. A deviation without evidence is not a deviation — it is a gap.

4. **The `allowed` value** (the ceiling) may not exceed the actual accepted current population without explicit approval in the deviation record. If 65 cancellations were measured and 72 are allowed, the extra 7 must be individually justified.

5. **Analyzer schema migrations are not normal deviations.** When a scanner changes its output schema, identity, or cardinality, a new baseline must be generated from the same v0.5.0 tag with the new analyzer. This is a `baseline-migration` PR, not a deviation update.

6. **Do not edit `0.6.0-baseline.json` directly.** To regenerate it, run:
   ```
   ./gradlew generateCanonicalMaintainabilityBaseline \
     -Pmaintainability.sourceRoot=<v0.5.0-worktree-path>
   ```
   This requires a clean checkout and the v0.5.0 tag available as a git worktree.
