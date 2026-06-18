# Releasing Tramai

This page is the maintainer runbook for cutting current Tramai releases.

It complements the historical release notes and validation pages under `docs/reference/`.

## Preconditions

Before cutting a release:

- `./gradlew verifyReleaseReadiness` passes
- `./gradlew -p examples/kotlin-springboot-example test` passes
- the board and specs reflect the actual repository state
- the changelog is updated for the version being released

## GitHub Workflows

The repository contains:

- `.github/workflows/ci.yml`
- `.github/workflows/publish.yml`

`publish.yml` is triggered by:

- `workflow_dispatch`
- tags matching `v*`

For `workflow_dispatch`, you can optionally provide a `version` input:

- leave it empty to run the snapshot path as `0.3.1-SNAPSHOT`
- set it to a release like `0.3.1` when you want to preflight the real release publish path before pushing the tag

## Required Secrets

Remote publishing requires these GitHub Actions secrets:

- `TRAMAI_PUBLISH_RELEASE_URL`
- `TRAMAI_PUBLISH_SNAPSHOT_URL`
- `TRAMAI_PUBLISH_USERNAME`
- `TRAMAI_PUBLISH_PASSWORD`
- `TRAMAI_SIGNING_KEY`
- `TRAMAI_SIGNING_PASSWORD`
- `SONAR_HOST_URL`
- `SONAR_TOKEN`

Without those secrets, the publish workflow falls back to `publishToMavenLocal`.

If you want a stable non-default SonarQube key, also set:

- `SONAR_PROJECT_KEY`

For Sonatype Central Portal with the current Gradle `maven-publish` flow, set:

- `TRAMAI_PUBLISH_RELEASE_URL=https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/`
- `TRAMAI_PUBLISH_SNAPSHOT_URL=https://central.sonatype.com/repository/maven-snapshots/`
- `TRAMAI_PUBLISH_USERNAME=<central-portal-token-username>`
- `TRAMAI_PUBLISH_PASSWORD=<central-portal-token-password>`

The publish workflow now runs SonarQube analysis before the remote publish path, then performs the required post-upload handoff to the Central Portal for tagged releases by calling the OSSRH Staging API manual upload endpoint for the `dev.tramai` namespace with `publishing_type=user_managed`.

The publish path has already been exercised successfully. This runbook remains the operational reference for subsequent releases.

Before a real release publish, the public half of the signing key must already be available from a Sonatype-supported public keyserver. Sonatype currently documents these supported servers:

- `keyserver.ubuntu.com`
- `keys.openpgp.org`
- `pgp.mit.edu`

If the public key is not visible there, Central will reject the uploaded `.asc` signatures during deployment validation even if Gradle signed the artifacts successfully.

That means the first live release flow is:

1. push the release tag
2. let GitHub Actions upload the artifacts and hand them off to the Portal
3. open the Central Portal deployment view and inspect validation
4. press `Publish` in the Portal once validation passes

## Local Release Validation

Useful commands:

```bash
./gradlew verifyReleaseReadiness
./gradlew verifySovereignRuntimePublication
./gradlew -p examples/sovereign-runtime-consumer-smoke test
./gradlew -p examples/kotlin-springboot-example test
```

These validate:

- the internal module graph through the root test suite
- generated POM metadata for every publishable module
- published local artifacts including sources and javadoc jars
- consumer resolution from `mavenLocal()`
- the narrow Spring example consumer smoke path
- sovereign runtime module publishability (POMs, sources, javadoc, dependency graph)
- sovereign runtime consumer-resolution smoke (context loads from mavenLocal artifacts)

For a public credibility summary of the currently validated paths, see [Release Validation](./release-validation.md).

## Sovereign Runtime Publishability Validation

The sovereign runtime modules can be validated for local publishability without touching a remote repository:

```bash
./gradlew verifySovereignRuntimePublication
```

This publishes the following to mavenLocal() and runs their test suites:

- `tramai-security`
- `tramai-sovereign`
- `tramai-persistence-file`
- `tramai-spring-boot-starter-sovereign`
- `tramai-spring-boot-starter-sovereign-persistence-file`
- `tramai-spring-boot-starter-sovereign-ops`
- `tramai-spring-boot-starter-sovereign-ops-observability`

After publishing, a consumer-resolution smoke test proves an external app can resolve them:

```bash
./gradlew -p examples/sovereign-runtime-consumer-smoke test
```

The smoke project uses `mavenLocal()` dependencies — not `project()` dependencies — to prove real consumer resolution.

This validation does **not**:
- Publish to Maven Central
- Require signing keys
- Create a tag or GitHub release
- Bump the version

## Local Signed-Artifact Validation

When you want to validate signing locally without touching a real remote repository, publish to a file-based Maven repository and verify signatures there:

```bash
./gradlew verifySignedPublicationBundle \
  -PtramaiPublishReleaseUrl=file://$PWD/build/release-verification-repo \
  -PsigningKey="$SIGNING_KEY" \
  -PsigningPassword="$SIGNING_PASSWORD"
```

This verifies:

- signed POMs
- signed binary JARs for library modules
- signed sources and javadoc JARs for library modules

Use `verifyReleasePublishInputs` when you want to preflight the real remote-publish property set before tagging.

## Publish The Signing Key

Before the first live release with a given signing key, publish its public key to at least one supported keyserver and verify that it can be fetched by fingerprint.

Example:

```bash
gpg --list-keys
gpg --keyserver keyserver.ubuntu.com --send-keys <your-signing-key-fingerprint>
gpg --keyserver keys.openpgp.org --send-keys <your-signing-key-fingerprint>
```

Then verify resolution:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys <your-signing-key-fingerprint>
```

If this step fails, do not tag the release yet. Central will reject the deployment with an invalid-signature error because it cannot resolve the public key for the uploaded signatures.

In GitHub Actions, that means running `Publish` with `workflow_dispatch` and setting the `version` input to the intended release version, for example `0.3.1`.

## Guarded Real-Provider Checks

Tramai now includes opt-in provider integration tests that are skipped by default unless you explicitly enable them.

### Ollama

Required environment:

```bash
export TRAMAI_RUN_OLLAMA_INTEGRATION=true
export TRAMAI_OLLAMA_MODEL=<your-local-model>
# optional
export TRAMAI_OLLAMA_BASE_URL=http://localhost:11434
```

Run:

```bash
./gradlew :tramai-ollama:test --tests '*OllamaProviderIntegrationTest'
```

### OpenAI Or OpenAI-Compatible

Required environment:

```bash
export TRAMAI_RUN_OPENAI_INTEGRATION=true
export TRAMAI_OPENAI_MODEL=<model-name>
export TRAMAI_OPENAI_API_KEY=<api-key>
# or
export TRAMAI_OPENAI_BEARER_TOKEN=<bearer-token>
# optional
export TRAMAI_OPENAI_BASE_URL=https://api.openai.com/v1
```

Run:

```bash
./gradlew :tramai-openai:test --tests '*OpenAiProviderIntegrationTest'
```

These checks are intentionally light-touch. They verify that Tramai can make a real provider call through the shipped provider modules without making the default test suite depend on external credentials or services.

## Cutting A Release

1. Freeze scope and confirm the checklist in `docs/reference/release-<version>.md`.
2. Update `CHANGELOG.md` from snapshot wording to the release entry.
3. Commit the release-ready state.
4. Create and push a tag such as `v0.3.1`.
5. Verify the `Publish` workflow result, including the Central Portal handoff step.
6. Open the deployment in Central Portal and confirm validation succeeds.
7. Publish from the Portal UI.
8. Confirm the published coordinates and artifacts externally.

## What This Runbook Does Not Replace

This runbook does not replace:

- repository secret management
- external repository onboarding steps
- Sonatype or Maven Central account setup
- post-release announcement or adoption work

Those remain operational tasks owned by the maintainer.
