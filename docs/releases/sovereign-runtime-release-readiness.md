# Sovereign Runtime Release Readiness

## Purpose

This document tracks readiness evidence for the declared Sovereign Runtime Release Candidate boundary on `master`.

It is NOT a formal release announcement and does NOT promise API stability. It exists so that reviewers, integrators, and the project itself have an honest, auditable snapshot of what is implemented, what is evolving, and what is intentionally not done.

For the declared RC boundary, see [Sovereign Runtime RC Boundary](sovereign-runtime-rc-boundary.md).

For a practical first-use guide, see [Sovereign Runtime Quickstart](../guides/sovereign-runtime-quickstart.md).

## Target Release Boundary

Candidate release: 0.4.0 was published as the Sovereign Runtime milestone. The post-sovereignty work on master targets 0.5.0.

## Included Capability Areas

| Area | Status | Representative Modules | Evidence |
|---|---|---|---|
| Policy enforcement | Implemented / evolving | tramai-security | Unit + integration tests |
| DLP / redaction | Implemented / evolving | tramai-security | Unit + integration tests |
| Sovereign routing | Implemented / evolving | tramai-sovereign | Unit tests |
| Approval gates | Implemented / evolving | tramai-security / tramai-sovereign | Unit + integration tests |
| Replay-safe resume | Implemented / evolving | `tramai-security`, `tramai-engine` | Unit + integration tests |
| Encrypted file persistence | Implemented / evolving | tramai-persistence-file | Unit + integration tests |
| Audit chain | Implemented / evolving | tramai-security | Unit tests |
| Audit outbox (persistence + dispatch) | Implemented / evolving | tramai-spring-boot-starter-sovereign-ops | Unit + integration tests |
| Background worker (recovery + dispatch) | Implemented / evolving | tramai-spring-boot-starter-sovereign-ops | Unit + integration tests |
| Worker observer SPI | Implemented | tramai-spring-boot-starter-sovereign-ops | Unit tests |
| OpenTelemetry worker metrics | Implemented | tramai-spring-boot-starter-sovereign-ops-observability | Unit tests |
| Optional read-only Actuator worker status endpoint and health component | Implemented / opt-in | tramai-spring-boot-starter-sovereign-ops-actuator | Unit + auto-config + health-tree integration tests |
| Micrometer worker metrics bridge | Implemented / opt-in | tramai-spring-boot-starter-sovereign-ops-micrometer | Unit tests |
| Worker observability runbook | Implemented | docs/operations/sovereign-ops-worker-observability-runbook.md | Documentation review |
| Evidence generation | Implemented / evolving | Release artifacts, examples | Smoke tests |
| Sovereign document intelligence example | Implemented | examples:sovereign-document-intelligence | Smoke test |

## Not Included in the Original 0.4.0 RC — Now Implemented on Master

These capabilities were not included in the original 0.4.0 Sovereign Runtime RC, but have been completed on the `master` branch targeting 0.5.0:

| Capability | Current Status |
|------------|---------------|
| JDBC/database-backed persistence (PostgreSQL-backed approval, audit, outbox stores) | ✅ Implemented on master |
| Transactional approval mutation + audit outbox boundary | ✅ Implemented on master |
| JDBC worker lease coordination (multi-node audit outbox worker coordination) | ✅ Implemented on master |
| Production deployment runbook (JDBC persistence stack) | ✅ Implemented on master |
| Regulated claim triage JDBC E2E proof | ✅ Implemented on master |
| Approved-resume lifecycle JDBC E2E proof | ✅ Implemented on master |

## Not Included — Explicitly Deferred

These capabilities remain intentionally deferred and are not claimed as complete, stable, or production-ready in 0.5.0:

- Stable 1.0 public API
- Maven Central release of sovereign runtime modules (not verified)
- Broad REST/Actuator operational control endpoints
- Full dashboard integration and production monitoring runbook
- Key rotation
- Complete API reference documentation

No timelines are committed for these items.

## Release Validation Commands

### Canonical release-candidate verification

Run the full local sovereign runtime release-candidate validation chain:

```bash
./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks
```

This validates the full evidence chain — full subproject test suite, release readiness, local publication, signed bundle dry-run, consumer-resolution smoke, release artifact generation, release manifest verification, sovereign document intelligence evidence run, and evidence index generation. It does not publish remotely, create a tag, or claim Maven Central availability.

### Targeted validation commands

For debugging or rapid iteration, use individual commands:

```bash
# Full test suite (all modules, no cache)
./gradlew test --rerun-tasks

# Release metadata and artifact validation
./gradlew verifyReleaseReadiness

# Sovereign runtime local publishability
./gradlew verifySovereignRuntimePublication

# Sovereign runtime signed bundle dry-run (default — no signing)
./gradlew verifySovereignRuntimeSignedBundle

# Sovereign runtime signed bundle dry-run (with optional signing)
./gradlew verifySovereignRuntimeSignedBundle \
  -PsigningKey="$SIGNING_KEY" \
  -PsigningPassword="$SIGNING_PASSWORD"

# Sovereign runtime consumer-resolution smoke
./gradlew -p examples/sovereign-runtime-consumer-smoke test

# Sovereign ops module tests
./gradlew :tramai-spring-boot-starter-sovereign-ops:test --rerun-tasks

# Observability module tests
./gradlew :tramai-spring-boot-starter-sovereign-ops-observability:test --rerun-tasks

# Micrometer module tests
./gradlew :tramai-spring-boot-starter-sovereign-ops-micrometer:test --rerun-tasks

# Actuator module tests (worker status endpoint + health component)
./gradlew :tramai-spring-boot-starter-sovereign-ops-actuator:test --rerun-tasks

# Reference example smoke test
./gradlew :examples:sovereign-document-intelligence:run
```

Also runnable via the canonical verification chain: `./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks`.

## Release Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| APIs are still evolving | Medium | active-development banner on README and docs |
| Preview APIs may evolve | Medium | Documented as preview in API stability boundary; build guards prevent accidental promotion |
| JDBC implementations are internal | Medium | Documented as internal in API stability boundary; consumers should depend on SPIs only |
| No key rotation | Low | Documented as deferred; encrypted resume credential custody uses fixed key |
| Reviewer UI is not production-grade | Low | Disabled by default; documented as preview |
| Alert thresholds need tuning | Low | Alert examples carry WARNING headers stating thresholds must be tuned |
| External credentials not in deterministic gate | Low | External credential validation is out of scope for the sovereign offline verification harness |
| Evidence verifies structure not truth | Low | Documented as structural tamper-evidence only; no evidence-truth claims

## Merge-Readiness Checklist

- [x] Full test suite green
- [x] Sovereign example smoke run green
- [x] Status docs aligned with current master
- [x] README not overclaiming
- [x] New modules listed in module matrix
- [x] No production-ready or stable-1.0 claims
- [x] No Maven Central claim for sovereign runtime modules unless verified
- [x] CHANGELOG.md has Unreleased section

Checklist last verified: 2026-07-16 (PR #202 0.5.0 release preparation).

## Sovereign Runtime Release-Candidate CI Gate

The repository now includes a dedicated workflow for validating the sovereign runtime release boundary:

`.github/workflows/sovereign-runtime-release-candidate.yml`

**Name:** Sovereign Runtime Release Candidate

**Triggers:**

- `workflow_dispatch` — run manually without tagging or publishing
- `pull_request` targeting release-critical paths

**What it validates:**

- Canonical `verifySovereignRuntimeReleaseCandidate` task: full subproject test suite, release readiness metadata and artifacts, local sovereign runtime publication (POMs, sources, javadoc), signed bundle dry-run (bundle manifest + verification repo), consumer-resolution smoke test using the generated sovereign runtime verification repository, release artifact preparation and manifest verification, sovereign document intelligence evidence run, and evidence index generation
- Verified `dev.tramai` dependency closure policy:
  - only `build/sovereign-runtime-release-verification-repo` is allowed for TramAI dependencies
  - `mavenLocal` and `mavenCentral` are blocked for the verified closure
- Release artifact preparation and manifest verification
- Sovereign document intelligence evidence run

**What it does NOT do:**

- Publish to Maven Central or Sonatype
- Create a tag or GitHub release
- Bump the version
- Require signing keys
- Freeze APIs

**Artifacts uploaded:**

| Artifact | Contents |
|----------|----------|
| `sovereign-runtime-bundle-manifest` | Bundle manifest JSON |
| `sovereign-runtime-local-maven-repo` | Dedicated verification Maven repository used by the standalone consumer smoke test |
| `sovereign-release-artifacts` | Release artifact manifest + generated release artifacts |
| `sovereign-release-evidence-index` | Evidence index JSON + Markdown |

Run from the Actions tab: **Sovereign Runtime Release Candidate** → **Run workflow**.

## Sovereign Release Evidence Index

The release-candidate workflow also generates a release evidence index at `build/sovereign-runtime-release/`:

- `evidence-index.json` — machine-readable, ties together commit metadata, validation gates, artifact hashes
- `evidence-index.md` — human-readable summary with artifact and gate tables

The index includes SHA-256 hashes for files and deterministic tree hashes for directories.

The index does **not** contain secrets, credentials, signing keys, or absolute machine paths.

## Current Evidence Chain

The current sovereign runtime release-candidate chain is:

1. Run the canonical `verifySovereignRuntimeReleaseCandidate` task, which aggregates the full local verification chain.
2. Generate required release artifacts.
3. Publish the sovereign runtime modules into the dedicated local verification repository.
4. Run the standalone consumer smoke test.
5. Ensure the consumer resolves `dev.tramai` dependencies from the verification repository only.
6. Generate the evidence index from a typed model.
7. Serialize the evidence index with structured JSON generation.
8. Parse the written JSON back with `JsonSlurper`.
9. Validate the evidence schema, artifact list, check map, and `devTramaiResolutionPolicy`.
10. Generate the human-readable Markdown evidence index.

The evidence index records file SHA-256 hashes and deterministic directory tree hashes for the generated release evidence.

The observability documentation validation (`verifySovereignOpsObservabilityDocs`) ensures that the runbook, metric names, health indicator docs, and PromQL references do not silently drift from the implementation. The Actuator health-tree integration tests (`SovereignOpsWorkerHealthEndpointIntegrationTest`) prove that the health component is registered in the real Spring Boot `HealthContributorRegistry` with the expected component name `tramaiSovereignOpsWorker`.

## Verified Dependency Closure

The sovereign runtime release-candidate workflow now verifies that the standalone consumer smoke test resolves the TramAI sovereign runtime from the dedicated verification repository:

`build/sovereign-runtime-release-verification-repo`

The generated evidence index records this as `devTramaiResolutionPolicy`.

For `dev.tramai` dependencies, the policy explicitly validates:

- `allowedRepositories`: `["build/sovereign-runtime-release-verification-repo"]`
- `blockedRepositories`: `["mavenLocal", "mavenCentral"]`
- `coverage`: `"full-dev-tramai-dependency-closure"`

This means the release-candidate evidence does not merely prove that the consumer smoke test passed. It proves that the standalone consumer resolved the full `dev.tramai` dependency closure from the generated verification repository, not from the developer machine's Maven local cache or Maven Central.

This does not claim Maven Central publication. It only proves local release-candidate completeness.
