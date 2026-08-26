# Contributing

Tramai is a library project with a high correctness bar. Changes should preserve architectural clarity and be audited by tests.

This document is the **human-facing contribution contract**. Automated coding agents must also follow [AGENTS.md](./AGENTS.md), which defines the machine/agent execution protocol (change classification, pre-edit invariants, stop conditions, CI failure taxonomy, completion-report format). The two documents do not duplicate each other; where this document is about *what evidence a change needs*, AGENTS.md is about *how to execute and verify the change*.

## Before You Change Code

Read the relevant documents first:

- [PLAN.md](./PLAN.md)
- [DESIGN.md](./DESIGN.md)
- [docs/ROADMAP-0.6.0.md](./docs/ROADMAP-0.6.0.md)
- [docs/specs](./docs/specs)
- [docs/adr](./docs/adr)
- [docs/architecture](./docs/architecture)
- [AGENTS.md](./AGENTS.md)

If your change pressures a module boundary or public API contract, update the docs or add a new ADR instead of quietly coding around the design.

## Change Classification

**Classify your PR before coding.** The human-facing categories below map onto the authoritative change classes already defined in [AGENTS.md](./AGENTS.md) (do not invent a second taxonomy):

| Category | Examples | AGENTS.md class |
|----------|----------|-----------------|
| Runtime behavior | retry, routing, lifecycle, policy, orchestration | `runtime-behaviour` |
| Public API | annotations, SPI, exposed types | `public-api` |
| Persistence contract | state/store semantics | `runtime-behaviour` (+ TCK) |
| Concurrency/lifecycle | cancellation, workers, breaker | `runtime-behaviour` |
| Provider integration | adapter/transport/provider behavior | `runtime-behaviour` (+ provider TCK) |
| Module/build architecture | dependencies, Gradle logic | `build-logic` |
| CI/quality | verifier or workflow | `ci-workflow` |
| Documentation/example | docs, samples, AGENTS.md, task descriptions | `documentation` |
| Baseline migration | scanner identity, schema, or cardinality change | `baseline-migration` |
| Quality deviation | adding/modifying `config/quality/maintainability-deviations.yml` | `quality-deviation` |

When running `./gradlew verifyChangePolicy`, pass `-PchangeClass=<class>` if auto-detection is wrong. Supported override values: `runtime-behaviour`, `build-logic`, `baseline-migration`. See [AGENTS.md](./AGENTS.md) for the full protocol, including which class combinations are forbidden.

## Evidence Matrix

The minimum evidence for a PR depends on its change class. If a row applies to your change, the PR must contain that evidence:

| Change type | Minimum evidence |
|-------------|------------------|
| Bug fix | discriminator RED against the relevant baseline + GREEN after the fix |
| Runtime behavior | behavioral tests + `./gradlew verifyPr` |
| Concurrency | deterministic concurrency test — no sleeps as proof |
| Cancellation | cancellation contract proof (rethrow-if-cancelled, no swallowed `CancellationException`) |
| Provider | provider TCK where applicable |
| Persistence | store TCK where applicable |
| Public API | API compatibility verification |
| Module dependency | architecture verification |
| Quality analyzer | analyzer tests + separate baseline handling |
| Refactor | characterization test proving preserved behavior |
| Documentation | links/examples/commands validated against the repository |

**RED → GREEN is mandatory for bug fixes.** A bug-fix PR must prove that its discriminator fails against the relevant baseline *before* the production fix is introduced. A test added together with the fix proves nothing if it never failed against the unfixed code.

## Regression Test Quality

Tests are not ornamental. They are part of the design audit trail. When adding regression coverage, prefer:

- observable behavior over implementation detail;
- exact failure semantics where the contract is behavioral (assert the exception and its payload, not "it threw");
- deterministic concurrency tests (explicit coordination, virtual time, bounded waits) over timing-based tests;
- TCKs where multiple implementations share the same contract;
- characterization before decomposition (freeze behavior, then refactor);
- a regression test for every confirmed defect.

The following are **weak evidence** on their own. They are not absolute bans, but they will not satisfy review on their own:

- `Thread.sleep(...)` or `eventually { ... }` as the sole concurrency proof;
- `assertNotNull(...)` / `assertDoesNotThrow(...)` as the primary assertion (what is the semantic question?);
- tests coupled to private implementation shape (refactor the production code and the test should still be meaningful).

## Pull Request Scope Discipline

A PR should change one thing. Do not mix:

- production behavior + governing analyzer change;
- production behavior + baseline regeneration;
- feature work + unrelated cleanup;
- quality failure + weakened quality gate.

[AGENTS.md](./AGENTS.md) forbids these combinations at the execution level; the same discipline applies to human contributors. If a change legitimately spans categories, say so explicitly in the PR body and justify the combination.

## Verification Hierarchy

`./gradlew test` alone is **not** completion evidence. Work up the hierarchy:

1. **Focused test** — the specific test for the change;
2. **Module/slice verification** — `./gradlew :<module>:test`;
3. **`./gradlew verifyPr`** — the primary local gate (subproject tests + build-logic tests + maintainability baseline + change policy);
4. **Specialized gates** — if the change category requires them (provider TCK, store TCK, cancellation safety scanner, `verifyChangePolicy` with the correct `-PchangeClass`, etc.).

Documentation changes: run `./gradlew verifyPr` and manually verify that referenced files exist, documented commands exist, and Markdown links resolve. Do not document gates that do not exist yet — reference only real executable checks.

## Build and Test

Run:

```bash
./gradlew test
./gradlew publishToMavenLocal
./gradlew -p examples/kotlin-springboot-example test
```

The project targets Java 21 and Kotlin 2.3.0. See the [verification hierarchy](#verification-hierarchy) above — the full local gate is `./gradlew verifyPr`, and [.github/AGENTS.md](./.github/AGENTS.md) lists the CI-equivalent local commands.

Use the example smoke test when a change could affect published-artifact consumption, Spring integration, or documentation-backed setup flows.

## Implementation Principles

- Keep the core runtime framework-agnostic.
- Keep optional modules truly optional.
- Prefer explicit behavior over hidden fallback.
- Do not move parsing logic into `tramai-engine`.
- Do not move retry policy into `tramai-structured`.
- Do not introduce magical API generation that the current runtime cannot honestly support.
- Keep the boundaries documented under [docs/adr](./docs/adr) and [docs/architecture](./docs/architecture).

## Pull Request Standard

Every PR must use the [pull request template](./.github/pull_request_template.md). A good contribution:

- is aligned with an existing spec or ADR
- classifies its change and provides the evidence the [evidence matrix](#evidence-matrix) requires
- adds or updates tests
- keeps boundaries clean
- explains why the change is correct
- states its verification results and any skipped checks

If a reviewer cannot tell what the change guarantees by reading the tests, the change is not ready yet.

## Suppression / Waiver Process

Quality findings are not waived by editing gates. The supported mechanisms are:

- **Maintainability deviations** — temporary, justified, time-boxed entries in `config/quality/maintainability-deviations.yml` (see [AGENTS.md](./AGENTS.md) and the deviation schema in that file). Deviations require a reason, an owner, and a target phase.
- **Baseline migration** — only for scanner identity, schema, or cardinality changes, and only via the `baseline-migration` change class.

Regenerating `config/quality/0.6.0-baseline.json` to make CI pass is forbidden. If a gate reports a defect, the gate is telling the truth until proven otherwise — investigate the defect, do not silence the gate.

## Release-Oriented Changes

If you change:

- publishing metadata
- artifact structure
- example-project dependencies
- release workflows
- public setup or quickstart documentation

also update the release-facing docs under `docs/releases/` and `docs/reference/`, especially the current release readiness document and the [release runbook](./docs/reference/releasing.md). Check `docs/ROADMAP-0.6.0.md` for the current release train — version-specific scope/checklist documents become stale as soon as the train moves, so prefer the stable pointers above.
