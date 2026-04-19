# Contributing

Tramai is a library project with a high correctness bar. Changes should preserve architectural clarity and be audited by tests.

## Before You Change Code

Read the relevant documents first:

- [PLAN.md](./PLAN.md)
- [DESIGN.md](./DESIGN.md)
- [docs/specs](./docs/specs)
- [docs/adr](./docs/adr)
- [AGENTS.md](./AGENTS.md)

If your change pressures a module boundary or public API contract, update the docs or add a new ADR instead of quietly coding around the design.

## Quality Expectations

Tramai does not treat tests as optional. Every meaningful behavior change should include tests that verify:

- the happy path
- the failure path
- retry behavior where relevant
- exception contents where debugging context matters
- module-boundary behavior when the seam matters

Prefer strong assertions over shallow assertions.

## Build and Test

Run:

```bash
./gradlew test
./gradlew publishToMavenLocal
./gradlew -p examples/kotlin-springboot-example test
```

The project targets Java 25 and Kotlin 2.3.0.

Use the example smoke test when a change could affect published-artifact consumption, Spring integration, or documentation-backed setup flows.

## Implementation Principles

- Keep the core runtime framework-agnostic.
- Keep optional modules truly optional.
- Prefer explicit behavior over hidden fallback.
- Do not move parsing logic into `tramai-engine`.
- Do not move retry policy into `tramai-structured`.
- Do not introduce magical API generation that the current runtime cannot honestly support.

## Pull Request Standard

A good contribution:

- is aligned with an existing spec or ADR
- adds or updates tests
- keeps boundaries clean
- explains why the change is correct

If a reviewer cannot tell what the change guarantees by reading the tests, the change is not ready yet.

## Release-Oriented Changes

If you change:

- publishing metadata
- artifact structure
- example-project dependencies
- release workflows
- public setup or quickstart documentation

also update the release-facing docs under `docs/reference/`, especially the `0.1.0` scope/checklist and release runbook.
