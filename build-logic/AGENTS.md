# AGENTS.md — build-logic/

This directory contains TramAI's build-logic: Gradle convention plugins, maintainability scanners, baseline generation, and verification tasks.

## Ownership

- Analyzer output is a **versioned contract**. Schema changes require a `baseline-migration` PR type.
- Line numbers in findings are **diagnostic aids**, not stable identity. Use source location heuristics (function name, file path) for durable matching.
- Cardinality changes in analyzer output require explicit migration tests comparing old vs new output on the same fixture.

## Rules

1. Every gate (scanner, verifier) must have **positive and negative functional tests** — a test that proves it catches a violation and a test that proves it passes clean code.

2. Never use production deviations to conceal analyzer defects. An analyzer that reports a false positive is a bug in `build-logic/`, not a deviation in production.

3. Never change an analyzer and remediate its findings in the same PR. These are two separate PR types:
   - `build-logic` PR: changes scanner behaviour, adds migration/compatibility tests, explains old vs new output
   - `runtime-remediation` PR: fixes production findings, uses the already-merged analyzer unchanged

4. When modifying a scanner, regenerate the `canonical probe` fixture and verify that old and new output are comparable:
   ```
   ./gradlew :build-logic:canonicalProbeIntegrationTest
   ```

5. The `build-logic/` module uses JDK 21 (not JDK 25 used by production modules).

## Test commands

```
./gradlew :build-logic:test                                    # unit tests
./gradlew :build-logic:canonicalProbeIntegrationTest           # integration tests
```
