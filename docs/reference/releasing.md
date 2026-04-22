# Releasing Tramai

This page is the maintainer runbook for cutting Tramai releases.

It complements the frozen scope in [Release 0.1.0 Scope and Checklist](./release-0.1.0.md).

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

## Required Secrets

Remote publishing requires these GitHub Actions secrets:

- `TRAMAI_PUBLISH_RELEASE_URL`
- `TRAMAI_PUBLISH_SNAPSHOT_URL`
- `TRAMAI_PUBLISH_USERNAME`
- `TRAMAI_PUBLISH_PASSWORD`
- `TRAMAI_SIGNING_KEY`
- `TRAMAI_SIGNING_PASSWORD`

Without those secrets, the publish workflow falls back to `publishToMavenLocal`.

## Local Release Validation

Useful commands:

```bash
./gradlew verifyReleaseReadiness
./gradlew -p examples/kotlin-springboot-example test
```

These validate:

- the internal module graph through the root test suite
- generated POM metadata for every publishable module
- published local artifacts including sources and javadoc jars
- consumer resolution from `mavenLocal()`
- the Spring example smoke path

For a public credibility summary of the currently validated paths, see [Release Validation](./release-validation.md).

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
4. Create and push a tag such as `v0.1.0`.
5. Verify the `Publish` workflow result.
6. Confirm the published coordinates and artifacts externally.

## What This Runbook Does Not Replace

This runbook does not replace:

- repository secret management
- external repository onboarding steps
- Sonatype or Maven Central account setup
- post-release announcement or adoption work

Those remain operational tasks owned by the maintainer.
