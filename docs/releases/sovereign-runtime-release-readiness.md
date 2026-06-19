# Sovereign Runtime Release Readiness

## Purpose

This document tracks the readiness of the current sovereign runtime capabilities on master.

It is NOT a formal release announcement and does NOT promise API stability. It exists so that reviewers, integrators, and the project itself have an honest, auditable snapshot of what is implemented, what is evolving, and what is intentionally not done.

## Target Release Boundary

Candidate release: 0.4.0 or the next unreleased version. No tag, no Maven Central publication, and no API freeze is claimed here.

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
| Evidence generation | Implemented / evolving | Release artifacts, examples | Smoke tests |
| Sovereign document intelligence example | Implemented | examples:sovereign-document-intelligence | Smoke test |

## Not Included — Explicit Non-Goals

These capabilities are NOT claimed as complete, stable, or production-ready:

- Stable 1.0 public API
- Maven Central release of sovereign runtime modules (not verified)
- REST/Actuator operational endpoints
- Micrometer / Prometheus / dashboard integration
- Database-backed persistence or outbox
- Distributed worker leader election
- Key rotation
- Full production deployment guide
- Complete API reference documentation

No timelines are committed for these items.

## Release Validation Commands

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

# Reference example smoke test
./gradlew :examples:sovereign-document-intelligence:run
```

## Release Risks

| Risk | Severity | Mitigation |
|---|---|---|
| APIs are still evolving | Medium | active-development banner on README and docs |
| File persistence is local-node only | Medium | Documented as local-only; DB-backed persistence is future work |
| No Actuator / Micrometer bridge for OT metrics | Low | Documented clearly; metrics available via OT collector |
| No DB-backed outbox | Medium | Explicitly listed as future work |
| No distributed leader election | Medium | Worker assumes single-node operation; documented |
| Sovereign ops worker is opt-in, disabled by default | Low | Production users must explicitly enable |

## Merge-Readiness Checklist

- [x] Full test suite green
- [x] Sovereign example smoke run green
- [x] Status docs aligned with current master
- [x] README not overclaiming
- [x] New modules listed in module matrix
- [x] No production-ready or stable-1.0 claims
- [x] No Maven Central claim for sovereign runtime modules unless verified
- [x] CHANGELOG.md has Unreleased section

Checklist last verified: 2026-06-18. Full test suite: 159 tasks, all green.

## Sovereign Runtime Release-Candidate CI Gate

The repository now includes a dedicated workflow for validating the sovereign runtime release boundary:

`.github/workflows/sovereign-runtime-release-candidate.yml`

**Name:** Sovereign Runtime Release Candidate

**Triggers:**

- `workflow_dispatch` — run manually without tagging or publishing
- `pull_request` targeting release-critical paths

**What it validates:**

- Full test suite (rerun-tasks)
- Release readiness metadata and artifacts
- Local sovereign runtime publication (POMs, sources, javadoc)
- Signed bundle dry-run (bundle manifest + verification repo)
- Consumer-resolution smoke test from mavenLocal()
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
| `sovereign-runtime-local-maven-repo` | Local verification Maven repository |
| `sovereign-release-artifacts` | Release artifact manifest + generated release artifacts |

Run from the Actions tab: **Sovereign Runtime Release Candidate** → **Run workflow**.
