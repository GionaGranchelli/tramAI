# AGENTS.md — .github/workflows/

This directory contains TramAI's CI and release pipeline definitions.

## Rules

1. **Every CI command must have a local equivalent.** If a workflow step runs `./gradlew someTask`, that task must also be callable locally. Workflow-only actions (artifacts, attestations) are exempt, but their underlying verification must exist as a Gradle task.

2. **Failure artifacts must be uploaded with `if: always()`.** When a step fails, its diagnostic output must still be available in the artifact bundle. Never gate artifact upload on success only.

3. **PR-delta gates must fetch the base SHA.** Workflows that compare against a baseline (maintainability, API diff) must use `fetch-depth: 0` and reference `origin/master` or `github.event.pull_request.base.sha`.

4. **No workflow may depend on uncommitted or generated local state.** Every prerequisite must be produced by a step in the same workflow or be committed to the repository.

5. **Workflow changes must be tested independently from runtime changes.** A PR that modifies `.github/workflows/` must not also modify production source code, unless the workflow change is strictly additive (e.g. adding a new trigger or notification).

6. **Maintainability baseline and CI workflows use different JDK versions.**
   - `maintainability-baseline.yml`: JDK 21 (for build-logic compatibility)
   - `ci.yml` and others: JDK 25 (for production code)

## Local equivalents

| CI step | Local command |
|---------|---------------|
| Run tests | `./gradlew test` |
| Verify maintainability baseline | `./gradlew verifyMaintainabilityBaseline` |
| Full maintainability verification | `./gradlew verifyFullMaintainabilityBaseline` |
| Change policy check | `./gradlew verifyChangePolicy` |
| **Complete PR gate** | **`./gradlew verifyPr`** |
| Publish to local + example smoke | `./gradlew publishToMavenLocal && ./gradlew -p examples/kotlin-springboot-example test -PtramaiVersion=$(grep '^tramaiVersion=' gradle.properties \| cut -d= -f2)` |
| Sovereign artifacts | `./gradlew prepareSovereignReleaseArtifacts verifySovereignReleaseManifest` |
| Zero-egress verification | `./scripts/verify-zero-egress.sh` |
