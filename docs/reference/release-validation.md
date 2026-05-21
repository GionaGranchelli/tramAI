# Release Validation

This page records the concrete release-validation evidence currently available in the repository.

It is the public credibility summary for the current release line, with older release milestones treated as historical context rather than the active documentation baseline.

## Validation Snapshot

Validated on: `2026-04-21`

The repository has been exercised through these concrete paths:

- root publication metadata generation and local Maven publication
- local signed publication to a file-based Maven repository
- Spring Boot example consumption from `mavenLocal()`
- GraalVM native-image compilation and binary execution for a standalone TramAI service
- OpenTelemetry metrics export over OTLP HTTP

## Concrete Proof Points

### Local Publication And Consumer Resolution

The release verification path publishes all TramAI modules to `mavenLocal()` and confirms that the Spring example resolves and uses those published coordinates.

Relevant commands:

```bash
./gradlew verifyPublicationMetadata
./gradlew verifyPublishedLocalArtifacts
./gradlew -p examples/kotlin-springboot-example test
```

What this proves:

- every publishable module produces a POM with release metadata
- library modules publish JAR, sources JAR, javadoc JAR, POM, and Gradle module metadata
- the example application can consume the published modules as a real downstream project

### Signed Artifact Proof

The release path was also exercised through a local signed publish into a file-based Maven repository.

Validated command:

```bash
./gradlew verifySignedPublicationBundle \
  -PtramaiPublishReleaseUrl=file://$PWD/build/release-verification-repo \
  -PsigningKey="<ascii-armored-test-key>" \
  -PsigningPassword="<test-key-password>" \
  --no-configuration-cache
```

What this proves:

- every publishable module can be signed by the Gradle release path
- signed POMs and module metadata are published
- signed binary, sources, and javadoc JARs are published for library modules

### Native Image Proof

The repository now includes a minimal native smoke example under `examples/kotlin-native-smoke-example`.

Validated commands:

```bash
JAVA_HOME=/home/gionag/.sdkman/candidates/java/25.0.2-graalce ./gradlew -p examples/kotlin-native-smoke-example nativeSmokeCompile
./examples/kotlin-native-smoke-example/build/native/nativeSmoke/tramai-native-smoke
```

Observed runtime output:

```text
native-smoke-ok:native-smoke-model
```

What this proves:

- TramAI proxy metadata generation is sufficient for a real GraalVM native binary
- a compiled native executable can instantiate and invoke a real TramAI `@AiService`

### Observability Export Proof

The observability module now includes OTLP HTTP smoke coverage in:

- [OpenTelemetryOperationObserverTest.kt](/home/gionag/Development/aurora/tramai-observability/src/test/kotlin/dev/tramai/observability/OpenTelemetryOperationObserverTest.kt)

Validated command:

```bash
./gradlew :tramai-observability:test --tests 'dev.tramai.observability.OpenTelemetryOperationObserverTest'
```

What this proves:

- TramAI metrics are not only asserted in memory
- the metrics path is exercised through a collector-facing OTLP HTTP exporter

## What This Does Not Yet Prove

This validation note does not claim that the following are complete:

- real-provider validation with live external credentials during the release cut

The Maven Central publication path and release-key signing have been exercised successfully for the current published release line.

Remaining operator-driven confidence work is still tracked in:

- [TASK-012](../board/tasks/task-012.md)
- [TASK-013](../board/tasks/task-013.md)
