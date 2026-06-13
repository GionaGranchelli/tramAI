# Task PR #31 — Offline Runtime Profile and Zero-Egress Verification Harness

## Status
In review (PR #31)

## Branch
`feat/offline-runtime-zero-egress`

## Spec Cross-Reference
SPEC-019: Offline Runtime Profile and Zero-Egress Verification Harness

## Summary
Add an explicit `OFFLINE` deployment mode that enforces local-only provider composition at build time, and provide a Docker-based zero-egress verification harness that proves the sovereign runtime can execute a reference workflow in a container with `--network=none`.

## File Changes

### New files
1. `tramai-sovereign/.../SovereignDeploymentMode.kt` — `enum` with `STANDARD` / `OFFLINE`
2. `examples/sovereign-offline-verification/.../OfflineVerificationMain.kt` — Entry point
3. `examples/sovereign-offline-verification/.../LoopbackModelServer.kt` — JDK HTTP loopback server
4. `examples/sovereign-offline-verification/.../LoopbackHttpModelProvider.kt` — Local-only ModelProvider
5. `examples/sovereign-offline-verification/.../ZeroEgressVerificationReportV1.kt` — Report DTO
6. `examples/sovereign-offline-verification/.../ZeroEgressReportWriter.kt` — JSON writer
7. `examples/sovereign-offline-verification/Dockerfile` — Java 25 runtime container
8. `examples/sovereign-offline-verification/build.gradle.kts` — Build config
9. `scripts/verify-zero-egress.sh` — Shell harness script
10. `docs/specs/spec-019-offline-runtime-zero-egress.md`
11. `docs/board/tasks/task-pr31-offline-runtime-zero-egress.md`

### Modified files
12. `tramai-sovereign/.../SovereignProfileConfiguration.kt` — Add `deploymentMode` field
13. `tramai-sovereign/.../SovereignTramai.kt` — Add `validateOfflineDeployment()`
14. `docs/modules/tramai-sovereign.md` — Update limitations, add OFFLINE section
15. `docs/security/SECURITY-MODEL.md` — Update AS-11 and controls matrix
16. `docs/specs/spec-018-local-model-artifact-verification.md` — Fix stale symlink test matrix
17. `settings.gradle.kts` — Add `examples:sovereign-offline-verification`
18. `.github/workflows/ci.yml` — Add zero-egress job

### Test files
19. `tramai-sovereign/.../SovereignOfflineDeploymentTest.kt` — Unit tests
20. `examples/sovereign-offline-verification/.../SovereignOfflineExampleTest.kt` — Module tests

## Implementation Phases

### Phase 1: Production Code (tramai-sovereign)

**Scope:** `SovereignDeploymentMode`, profile extension, builder validation

**Acceptance criteria:**
- [ ] `SovereignDeploymentMode` enum created with `STANDARD` and `OFFLINE`
- [ ] `SovereignProfileConfiguration.deploymentMode` added, default `STANDARD`
- [ ] `SovereignTramai.Builder.validateOfflineDeployment()` checks all providers, routes, fallbacks, default
- [ ] Fixed safe error codes only — no paths/prompts/secrets in exception messages
- [ ] STANDARD mode behavior unchanged
- [ ] Offline validation occurs before artifact registry lookup
- [ ] `SovereignOfflineDeploymentTest` covers 12+ scenarios

### Phase 2: Example Module

**Scope:** Runnable zero-egress verification module

**Acceptance criteria:**
- [ ] Loopback server binds to `127.0.0.1:0`, returns deterministic response
- [ ] Loopback HTTP provider targets loopback URL, increments invocation counter
- [ ] OfflineEchoService annotated with `@AiService` / `@Operation`
- [ ] Real service proxy invocation through TramAI engine
- [ ] PR #30 artifact verification integrated with temp dummy file
- [ ] External TCP probe (1.1.1.1:443) captured
- [ ] External DNS probe (example.com) captured
- [ ] ZeroEgressVerificationReportV1 written as JSON
- [ ] Exit non-zero on any failure

### Phase 3: Docker Harness

**Scope:** Containerized verification

**Acceptance criteria:**
- [ ] Dockerfile uses `eclipse-temurin:25-jre`, runs as non-root
- [ ] `scripts/verify-zero-egress.sh` builds distribution, builds image, runs `--network=none`
- [ ] Python 3 report validation (no jq dependency)
- [ ] All assertions pass, prints `ZERO_EGRESS_HARNESS_GREEN`

### Phase 4: CI and Documentation

**Scope:** CI job, module docs, security model, spec fixes

**Acceptance criteria:**
- [ ] CI `zero-egress` job runs after `build`, uses Docker
- [ ] Report uploaded on failure
- [ ] `docs/modules/tramai-sovereign.md` updated with OFFLINE mode and zero-egress
- [ ] `docs/security/SECURITY-MODEL.md` AS-11 and controls matrix updated
- [ ] `docs/specs/spec-018-local-model-artifact-verification.md` symlink test matrix fixed

## Exit Criteria

- [ ] `./gradlew test --rerun-tasks` green
- [ ] `./gradlew :tramai-sovereign:test --rerun-tasks` green (12+ offline tests)
- [ ] `./gradlew :examples:sovereign-offline-verification:test --rerun-tasks` green
- [ ] `./scripts/verify-zero-egress.sh` green (Docker required)
- [ ] SPEC-019 and task doc updated
- [ ] PR body describes changes, security invariants
