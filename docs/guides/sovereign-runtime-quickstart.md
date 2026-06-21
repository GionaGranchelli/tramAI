# Sovereign Runtime Quickstart

## What this guide is

A practical path for trying the Sovereign Runtime Release Candidate in a JVM / Spring Boot application.

This guide is for **evaluation and integration exploration**. It is not a production deployment guide.

The Sovereign Runtime RC is verified from source / local release-candidate evidence. Public Maven Central availability is **not** claimed.

## What you will build

A small governed AI workflow that demonstrates:

- classification of sensitive input
- policy-based routing to a trusted model zone
- DLP/redaction of sensitive data before model submission
- approval / suspension point before a high-risk action
- audit chain and outbox recovery
- worker observability through Actuator and metrics
- local release-candidate verification

## Prerequisites

- **JDK 21+** — the project targets JVM 21+
- **Gradle wrapper** — the project uses the standard Gradle wrapper
- **Spring Boot 3.4+** — for the sovereign runtime auto-configuration modules
- **A local model provider** (e.g. Ollama with a local model) or a configured remote provider
- **TramAI source checkout** — the RC boundary is verified from source; Maven Central coordinates are not claimed

## Add dependencies

The following modules are part of the Sovereign Runtime RC. Add them to your `build.gradle.kts` (or equivalent):

```kotlin
dependencies {
    implementation("dev.tramai:tramai-core:<version>")
    implementation("dev.tramai:tramai-sovereign:<version>")
    implementation("dev.tramai:tramai-security:<version>")
    implementation("dev.tramai:tramai-persistence-file:<version>")
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign:<version>")
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign-persistence-file:<version>")
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign-ops:<version>")
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign-ops-actuator:<version>")
}
```

> **Important:** These coordinates are shown as a conceptual example. The exact published coordinates, versioning scheme, and Maven Central availability are still evolving. For local evaluation, depend on source modules via the project's local publication workflow.

## Minimal configuration

Enable the Sovereign Runtime and its subsystems in `application.yml`:

```yaml
tramai:
  sovereign:
    enabled: true
    persistence:
      file:
        root-dir: ./build/tramai-sovereign
    ops:
      audit-outbox:
        worker:
          enabled: true
      actuator:
        worker-status:
          enabled: true
        worker-health:
          enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,tramaiSovereignOpsWorker
```

This activates:
- sovereign runtime policy engine and routing
- encrypted file-backed persistence (approvals, continuations, audit stream, outbox)
- audit outbox background worker (recovery + dispatch loop)
- optional read-only Actuator worker status endpoint
- optional Actuator worker health component

## Define a governed operation

Define an AI-powered service through a typed Kotlin interface. The following is a conceptual example — exact API names may evolve before 1.0:

```kotlin
@AiService
interface ClaimTriageService {
    @Operation(model = "gemma4:e2b")
    fun triage(input: ClaimTriageInput): ClaimTriageRecommendation
}
```

The `@AiService` annotation declares this as a governed AI operation. The runtime handles typed schema generation, structured output validation, policy enforcement, and audit.

## Policy and DLP flow

When the runtime executes a governed operation, data flows through these stages:

```
input document
  -> DLP classification (assign sensitivity level)
  -> policy decision (allow / deny based on data class and model zone)
  -> sovereign routing (local-only, eu-only, or approved-cloud)
  -> model execution (denied routes throw before reaching the model)
  -> structured output validation
  -> optional approval gate (suspend execution for human review)
  -> audit emission (tamper-evident chain)
  -> outbox persistence (durable claim-based dispatch)
```

Each stage is an explicit runtime concern. No stage requires you to scatter guard logic across application code.

## Human approval boundary

High-risk operations can suspend execution at an approval gate:

- **Approvals may suspend execution** — the continuation is persisted to the encrypted file store for replay-safe resume
- **Execution must not block a thread** waiting for a human — suspension is async and designed for non-blocking workflows
- **Resume must be replay-safe** — the continuation envelope is cryptographically protected against tampering
- **Approval decisions must be audited** — every approve / deny / escalate decision emits a sequenced audit event

## Worker observability

The optional Actuator modules expose operational surfaces for the audit outbox background worker:

```bash
# Worker status (cycle count, last run, dispatch state)
curl http://localhost:8080/actuator/tramaiSovereignOpsWorker

# Overall application health (includes worker health component)
curl http://localhost:8080/actuator/health
```

**What these endpoints expose:**
- worker cycle statistics (success count, failure count, last run timestamp)
- worker health status (UP / DOWN / DEGRADED)

**What they intentionally do NOT expose:**
- no prompts sent to models
- no model responses
- no raw exception messages or stack traces
- no sensitive identifiers or personally identifiable information
- no tool arguments or intermediate workflow data

For deeper observability, the Micrometer and OpenTelemetry metric modules (`tramai-spring-boot-starter-sovereign-ops-micrometer`, `tramai-spring-boot-starter-sovereign-ops-observability`) expose worker metrics compatible with PromQL and distributed tracing.

## Verify the RC locally

Run the full local release-candidate verification chain:

```bash
./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks
```

This validates: full test suite, release readiness, local publication, signed bundle dry-run, consumer-resolution smoke, sovereign document intelligence evidence run, and evidence index generation.

It does **not** publish remotely, create a tag, or claim Maven Central availability.

## What this quickstart does not cover

For a regulated-domain walkthrough, see [Regulated Claim Triage Reference Scenario](../scenarios/regulated-claim-triage.md).

- Production deployment and operational runbooks
- Distributed worker coordination and leader election
- Database-backed persistence or outbox (JDBC / Postgres)
- Key rotation and secrets lifecycle
- Maven Central release or public artifact publication
- Stable 1.0 API — interfaces are still evolving
- Broad REST / Actuator control-plane endpoints beyond worker status and health
