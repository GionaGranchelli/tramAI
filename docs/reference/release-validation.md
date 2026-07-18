# Release Validation

This page records the concrete release-validation evidence currently available in the repository.

It is the public credibility summary for the current release line, with older release milestones treated as historical context rather than the active documentation baseline.

## Validation Snapshot

Validated on: `2026-07-16`

The repository has been exercised through these concrete paths:

- 0.5.0 release-readiness task verification
- signed local publication to a file-based Maven repository
- sovereign consumer dependency closure verification
- release artifact manifest generation and verification
- evidence index generation
- sovereign evidence bundle verification
- tool-governance example test execution
- Spring Boot consumer smoke test

## Concrete Proof Points

### 0.5.0 Release Readiness

```bash
./gradlew verify050ReleaseReadiness --no-configuration-cache --rerun-tasks
```

This aggregates:
- version alignment checks (gradle.properties, build fallback, CHANGELOG, roadmap, consumer docs)
- release readiness checks (publication metadata, published artifacts, observability docs)
- workflow API stability boundary verification
- sovereign runtime API boundary verification
- tool-governance example existence
- release-readiness document presence
- CHANGELOG 0.5.0 section presence
- STATUS and roadmap state correctness
- publish workflow version alignment check
- no absolute /home/... paths in release docs
- no stale "no DB outbox" or "single-node only" claims in sovereign-runtime-release-readiness.md

### Local Publication and Consumer Resolution

The release verification path publishes all TramAI modules to a file-based Maven repository and confirms that the consumer smoke example resolves from the dedicated verification repository.

Relevant commands:

```bash
./gradlew verifyPublicationMetadata
./gradlew verifyPublishedLocalArtifacts
./gradlew verifySovereignRuntimePublication
./gradlew verifySovereignRuntimeSignedBundle
./gradlew -p examples/sovereign-runtime-consumer-smoke test
```

What this proves:

- every publishable module produces a POM with release metadata
- library modules publish JAR, sources JAR, javadoc JAR, POM, and Gradle module metadata
- the consumer smoke application resolves modules from the dedicated verification repository
- `dev.tramai` dependencies are resolved from the verification repository, not `mavenLocal` or `mavenCentral`

### Signed Artifact Proof

The release path was also exercised through a local signed publish into a file-based Maven repository.

Validated command:

```bash
./gradlew verifySovereignRuntimeSignedBundle \
  -PsigningKey="<ascii-armored-test-key>" \
  -PsigningPassword="<test-key-password>" \
  --no-configuration-cache
```

What this proves:

- every publishable sovereign module can be signed by the Gradle release path
- signed POMs and module metadata are published
- signed binary, sources, and javadoc JARs are published for library modules

### Sovereign Evidence Bundle Verification

```bash
./gradlew verifySovereignLabEvidenceBundle --no-configuration-cache --rerun-tasks
```

This validates the full evidence bundle lifecycle: scaffold verification, manifest generation, digest verification, tamper rejection, and re-finalization.

### Tool-Governance Example

```bash
./gradlew :examples:tool-governance:test --no-configuration-cache --rerun-tasks
```

This proves that the governed tool usage example compiles and passes all tests.

### Spring Boot Consumer Smoke

```bash
./gradlew -p examples/kotlin-springboot-example smokeTest --no-configuration-cache
```

This proves that the Spring Boot example consumer resolves and uses the published modules.

## What This Does Not Yet Prove

This validation note does not claim that the following are complete:

- real-provider validation with live external credentials during the release cut
- Maven Central publication (requires post-merge tag and Central Portal acceptance)
- key rotation
- production-grade reviewer UI
- governed MCP connector
- evidence truth validation

## Current Non-claims

TramAI 0.5.0 does not:

- Certify production readiness for every deployment configuration
- Provide legal compliance, regulatory compliance, or EU AI Act conformity
- Guarantee security certification (SOC2, ISO 27001)
- Validate evidence truth — only structural tamper-evidence is verified
- Provide benchmark guarantees — benchmarks are diagnostic only
- Replace an audit or compliance review
- Provide a governed MCP connector
- Provide key rotation
