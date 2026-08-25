# Releasing Tramai

This page is the maintainer runbook for cutting current Tramai releases.

It complements the historical release notes and validation pages under `docs/reference/`.

## Preconditions

Before cutting a release:

- `./gradlew verify050ReleaseReadiness` passes (aggregates version alignment, metadata, artifact, and API stability guards)
- `./gradlew -p examples/kotlin-springboot-example smokeTest` passes
- the board and specs reflect the actual repository state
- the changelog is updated for the version being released

## GitHub Workflows

The repository contains:

- `.github/workflows/ci.yml`
- `.github/workflows/publish.yml`

`publish.yml` is triggered by:

- `workflow_dispatch` (manual)
- tags matching `v*`

### Publish Workflow Safety Hardening

The publish workflow has been hardened against accidental remote publishing:

**Default mode: `local-dry-run`**

Manual workflow runs default to `local-dry-run`. In this mode, the workflow runs:

- `verify050ReleaseReadiness` (aggregates version alignment, metadata, artifact, and API stability guards)
- `verifySovereignRuntimePublication`
- `verifySovereignRuntimeSignedBundle`
- `publishToMavenLocal`
- Kotlin Spring Boot example smoke test
- Sovereign runtime consumer smoke test

No remote publish, no Central upload, no tag/release creation.

**To publish remotely: explicit opt-in**

1. Set `publishMode` to `remote-release`
2. Set `confirmRemoteRelease` to `RELEASE`

Both are required. A single accidental click cannot trigger a remote publish.

**Fail-closed behavior**

If `remote-release` mode is selected but preconditions are not satisfied (non-SNAPSHOT version, missing credentials, missing signing keys, confirmation not entered), the workflow fails with a clear error before any publish step runs.

**Centralized gate: `TRAMAI_CAN_REMOTE_PUBLISH`**

All remote-sensitive steps (verify remote publish inputs, verify signing key on keyservers, publish to configured repository, upload to Central Portal) are guarded by a single workflow-level flag. Remote steps cannot run merely because secrets exist — they also require explicit mode selection, confirmation, and release-version validation.

**Tag-triggered releases**

Tags matching `v*` automatically set `publishMode=remote-release` with implicit confirmation. Remote steps only run if the required credentials and signing keys are present. If credentials are missing, the workflow fails with a clear error rather than silently falling back to local mode.

**Workflow summary**

Every run writes a summary to `$GITHUB_STEP_SUMMARY` showing:
- publish mode
- whether remote publish occurred
- version validated
- credential/signing key status (redacted)
- tag trigger status

No secrets, credentials, or signing keys are printed.

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

Without those secrets, remote publishing is blocked by the TRAMAI_CAN_REMOTE_PUBLISH safety gate. The workflow will fail rather than silently fall back to local mode when remote-release is selected but credentials are missing.

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
./gradlew verify050ReleaseReadiness
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
- sovereign runtime consumer-resolution smoke (full dev.tramai closure from build/sovereign-runtime-release-verification-repo)

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
- `tramai-spring-boot-starter-sovereign-ops-actuator`
- `tramai-spring-boot-starter-sovereign-ops-micrometer`
- `tramai-spring-boot-starter-sovereign-ops-observability`

After publishing, a consumer-resolution smoke test proves an external app can resolve them
from a build-local Maven repository:

```bash
./gradlew verifySovereignRuntimeConsumerSmoke
```

See the **Sovereign Runtime Signed Bundle Dry-Run** section below for the full closure verification.

This validation does **not**:
- Publish to Maven Central
- Require signing keys
- Create a tag or GitHub release
- Bump the version

## Sovereign Runtime Signed Bundle Dry-Run

The sovereign runtime release boundary can be validated as a local signed publication bundle without touching a remote repository, creating a tag, bumping versions, or freezing APIs:

```bash
./gradlew verifySovereignRuntimeSignedBundle
```

This publishes the sovereign runtime modules **and their full transitive dev.tramai dependency closure** to a dedicated local-only Maven repository at `build/sovereign-runtime-release-verification-repo/` and validates:

- POM files
- binary JARs where expected
- sources JARs where expected
- javadoc JARs where expected
- dependency graph / publication metadata
- Generates `build/sovereign-runtime-release/bundle-manifest.json`

The published module set includes:

- 5 transitive framework modules: `tramai-core`, `tramai-standalone`, `tramai-engine`, `tramai-structured`, `tramai-spring-core`
- 10 sovereign modules: `tramai-bom`, `tramai-security`, `tramai-sovereign`, `tramai-persistence-file`, and the 6 Spring Boot starters

After the bundle dry-run, the consumer smoke test resolves all 15 modules exclusively from the verification repo — no `mavenLocal` fallback.

The task always publishes to both `mavenLocal()` and the dedicated build-local repository. The dedicated repo uses a separate `sovereignBundleLocal` Maven repository configuration — never the shared `tramaiRemote` repository — so it cannot accidentally push to a remote publish target.

When signing properties are provided, the task additionally validates `.asc` signatures:

```bash
./gradlew verifySovereignRuntimeSignedBundle \
  -PsigningKey="$SIGNING_KEY" \
  -PsigningPassword="$SIGNING_PASSWORD"
```

To use a custom local path instead of the build directory default:

```bash
./gradlew verifySovereignRuntimeSignedBundle \
  -PtramaiPublishReleaseUrl=file:///path/to/custom-repo \
  -PsigningKey="$SIGNING_KEY" \
  -PsigningPassword="$SIGNING_PASSWORD"
```

This validation does **not**:
- Publish to Maven Central or Sonatype
- Create a tag or GitHub release
- Bump the version
- Require signing keys for the default CI path
- Claim API stability

### Bundle Manifest

The task generates `build/sovereign-runtime-release/bundle-manifest.json`:

| Field | Description |
|-------|-------------|
| `schemaVersion` | `"sovereign-runtime-release-bundle-v1"` — named schema for release evidence clarity |
| `generatedAt` | ISO-8601 timestamp of generation |
| `version` | The TramAI version validated |
| `repository` | Absolute path to the file-based repository |
| `remotePublish` | Always `false` — local validation only |
| `tagCreated` | Always `false` — no tag is created |
| `signaturesPresent` | Whether .asc signatures were validated |
| `modules[]` | List of validated modules with artifact paths, signatures, and checksums |

## Sovereign Runtime Release-Candidate CI Gate

The repository contains a dedicated workflow for validating the sovereign runtime release boundary:

`.github/workflows/sovereign-runtime-release-candidate.yml`

**Name:** Sovereign Runtime Release Candidate

**Triggers:**

- `workflow_dispatch` (manual) — no tag, no remote publish, no version bump
- `pull_request` targeting release-critical paths (build config, sovereign modules, BOM, release docs, consumer smoke example)

The workflow does **not** trigger on tags. It is a pre-publish validation gate, not the real publish workflow.

**What it runs:**

- `./gradlew test --rerun-tasks` — full test suite
- `./gradlew verify050ReleaseReadiness` — release metadata and artifact validation
- `./gradlew verifySovereignRuntimePublication` — local sovereign runtime publishability
- `./gradlew verifySovereignRuntimeSignedBundle` — signed bundle dry-run
- `./gradlew -p examples/sovereign-runtime-consumer-smoke test` — consumer-resolution smoke
- `./gradlew prepareSovereignReleaseArtifacts` — release artifact preparation
- `./gradlew verifySovereignReleaseManifest` — manifest verification
- `./gradlew :examples:sovereign-document-intelligence:run --args=...` — evidence document intelligence
- `./gradlew generateSovereignReleaseEvidenceIndex` — generates release evidence index (JSON + Markdown)

**What it uploads (GitHub Actions artifacts):**

| Artifact | Source Path |
|----------|-------------|
| `sovereign-runtime-bundle-manifest` | `build/sovereign-runtime-release/bundle-manifest.json` |
| `sovereign-runtime-local-maven-repo` | `build/sovereign-runtime-release-verification-repo/` |
| `sovereign-release-artifacts` | `build/sovereign-release/release-artifacts-v1.json` + `build/sovereign-release/artifacts/` |
| `sovereign-release-evidence-index` | `build/sovereign-runtime-release/evidence-index.json` + `build/sovereign-runtime-release/evidence-index.md` |

**GitHub step summary:**

After each run, the workflow writes a summary to `$GITHUB_STEP_SUMMARY` with:
- Remote publish: false
- Tag created: false
- Version validated
- Status of each gate
- Uploaded artifact names

**Run manually:**

Go to the repository Actions tab, select **Sovereign Runtime Release Candidate**, and click **Run workflow**.

## Sovereign Release Evidence Index

The release-candidate workflow generates a single evidence index that ties together all release-candidate evidence:

- `build/sovereign-runtime-release/evidence-index.json` (machine-readable)
- `build/sovereign-runtime-release/evidence-index.md` (human-readable)

The index includes:
- Repository, commit SHA, ref, version, generation timestamp
- `remotePublish: false` and `tagCreated: false` — release-candidate runs never claim Maven Central publication
- Required evidence artifacts with IDs, paths, types, and SHA-256 hashes
- Deterministic tree hashes for directory-based evidence (local Maven verification repo, release artifacts)
- Validation gate results

The index does **not** contain secrets, credentials, signing keys, signing passwords, raw stack traces, absolute user home paths, or local usernames.

The evidence index is uploaded as the `sovereign-release-evidence-index` GitHub Actions artifact.

### Sovereign Runtime Release-Candidate Evidence

Before claiming a sovereign runtime release candidate, run:

```bash
./gradlew generateSovereignReleaseEvidenceIndex --no-configuration-cache
```

The generated evidence index proves:

- release gates passed
- required artifacts exist
- release artifacts are hashed
- verification repository tree hash is recorded
- standalone consumer smoke passed
- `dev.tramai` dependency closure was resolved from the verification repository
- `mavenLocal` and `mavenCentral` were blocked for the verified TramAI closure

The evidence is written to:

- `build/sovereign-runtime-release/evidence-index.json`
- `build/sovereign-runtime-release/evidence-index.md`

## Local Signed-Artifact Validation (All Modules)

When you want to validate signing for ALL publishable modules locally without touching a real remote repository, publish to a file-based Maven repository and verify signatures there:

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

In GitHub Actions, that means running `Publish` with `workflow_dispatch` and setting the `version` input to the intended release version, for example `0.4.0`.

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
4. Create and push a tag such as `v0.x.0` (matching the version being released).
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
