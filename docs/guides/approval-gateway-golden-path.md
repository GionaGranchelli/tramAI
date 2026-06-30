# Approval Gateway Golden Path

> **Status:** Preview. See [Sovereign Runtime API Stability Boundary](../architecture/sovereign-api-stability-boundary.md).

This guide shows how to request human approval from a governed workflow using the Preview `ApprovalGateway` API.

The gateway suspends the workflow by persisting approval, replay, and continuation metadata through the existing Sovereign Runtime stores. It does **not** block a thread while waiting for a human — the suspension is a data model concern, not a thread-blocking wait.

---

## Before: Low-Level Persistence

Without the gateway, requesting human approval required manually creating records in three separate stores:

```kotlin
approvalStore.create(approvalRequest)
suspendedInvocationStore.create(metadata, replayEnvelope)
approvalContinuationStore.create(continuation, sensitiveArguments)
```

Each store has its own contract, lifecycle, and validation rules. A developer needed to understand all three before writing any application logic.

This complexity is now hidden behind `ApprovalGateway`.

---

## After: Ergonomic Workflow Code

With the gateway, the workflow expresses the approval boundary through a single call:

```kotlin
val approvalResult = approvalGateway.requestApproval(
    subject = ApprovalSubject(input.claimId),
    recommendation = ApprovalRecommendation(
        type = "regulated-claim-triage",
        summary = "High-risk claim requires medical review",
        payload = mapOf(
            "riskLevel" to "HIGH",
            "requiredApprover" to "medical-reviewer",
        ),
    ),
    requiredRole = ApproverRole("medical-reviewer"),
    workflowRunId = WorkflowRunId(input.workflowRunId),
)
```

The return type is a sealed interface that makes the workflow state explicit.
With the Preview `ApprovalRequestResult.toWorkflowResult { ... }` mapper,
the workflow becomes a one-liner:

```kotlin
return gateway.requestApproval(
    subject = ApprovalSubject(input.claimId),
    recommendation = ApprovalRecommendation(
        type = "regulated-claim-triage",
        summary = "High-risk claim requires medical review",
        payload = mapOf(
            "riskLevel" to "HIGH",
            "requiredApprover" to "medical-reviewer",
        ),
    ),
    requiredRole = ApproverRole("medical-reviewer"),
    workflowRunId = WorkflowRunId(input.workflowRunId),
).toWorkflowResult { "approved-continue" }
```

The mapper converts each [ApprovalRequestResult] outcome to the
corresponding [SovereignWorkflowResult] variant:

| Gateway outcome | Workflow result | `approvedValue` invoked? |
|:----------------|:----------------|:--------------------------|
| `Suspended` | `SuspendedForApproval` | No — terminal state |
| `AlreadyApproved` | `Completed(approvedValue())` | Yes — exactly once |
| `AlreadyDenied` | `Rejected(decision.reason)` | No — terminal state |
| `Expired` | `Expired(reason)` | No — terminal state |

The `approvedValue` lambda is **lazy** — it is only invoked for
`AlreadyApproved`. Terminal states never execute the lambda, preventing
accidental side effects in suspended/denied/expired paths.

---

## What Gets Persisted

When `requestApproval()` returns `Suspended`, the gateway creates the three core suspension records:

| Store | Written by `DefaultApprovalGateway` | Written by `SovereignOpsTransactionalApprovalGateway` (JDBC) |
|-------|--------------------------------------|--------------------------------------------------------------|
| `ApprovalStore` | Approval request lifecycle | ✓ Same, atomically committed |
| `SuspendedInvocationStore` | Replay-safe invocation metadata | ✓ Same, atomically committed |
| `ApprovalContinuationStore` | Continuation metadata | ✓ Same, atomically committed |
| `AuditOutbox (approval-requested)` | Not emitted — no cross-store transaction boundary | ✓ Emitted atomically when `ApprovalGatewayAuditIntentFactory` is present |

The **surrounding workflow** may also emit additional audit records and operational outbox intent:

| Store | Written by surrounding workflow / application code |
|-------|------------------------------------------------------|
| `AuditStore` | Tamper-evident governance events, such as policy decisions and approval-requested events |
| `SovereignOpsAuditOutboxStore` | Durable operational audit dispatch records |

See the [regulated claim triage E2E test](https://github.com/GionaGranchelli/tramAI/blob/master/examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/RegulatedClaimTriageJdbcE2ETest.kt) for a complete example that exercises both gateway-written and workflow-emitted records.

**Note:** When JDBC-backed sovereign stores are active, `SovereignOpsTransactionalApprovalGateway` automatically replaces `DefaultApprovalGateway` and commits all three core records in a single database transaction. See [current limitations](#current-limitations) below for what this does not yet handle.

---

## Spring Boot Wiring

### Dependency

Add the sovereign operations starter to your project:

```kotlin
implementation(project(":tramai-spring-boot-starter-sovereign-ops"))
```

For the Preview REST approval control plane, also add:

```kotlin
implementation(project(":tramai-spring-boot-starter-sovereign-ops-rest"))
```

And enable it in your configuration:

```yaml
tramai:
  sovereign:
    ops:
      rest-control-plane-enabled: true
```

### Provide an ApprovalGatewayRequestFactory

Spring Boot auto-configuration creates an `ApprovalGateway` bean when the required stores and an `ApprovalGatewayRequestFactory` are available:

- With JDBC-backed stores: auto-config creates `SovereignOpsTransactionalApprovalGateway`, which commits all three records in a single database transaction.
- With generic stores: auto-config creates `DefaultApprovalGateway`, which writes the three stores sequentially.

TramAI does **not** auto-create a generic request factory because the factory depends on workflow-specific metadata: replay envelopes, argument digests, correlation IDs, and resume-token generation.

For **tests and examples**, TramAI provides a reusable test fixture:

```kotlin
// testImplementation(testFixtures(project(":tramai-engine")))
import dev.tramai.engine.approval.testing.TestApprovalGatewayRequestFactory

@Bean
fun approvalGatewayRequestFactory(): ApprovalGatewayRequestFactory =
    TestApprovalGatewayRequestFactory(clock = Clock.systemUTC())
```

The fixture handles all low-level records (approval, continuation, suspended invocation, replay envelope, argument digests) with sensible defaults. Scenario-specific metadata can be customized through `TestApprovalGatewayRequestDefaults` or the builder's fluent API:

```kotlin
TestApprovalGatewayPersistenceRequestBuilder(clock)
    .subject(ApprovalSubject("claim-1"))
    .workflowRunId(WorkflowRunId("run-1"))
    .toolName("claim-triage-model")
    .operationReference(
        serviceInterface = "com.example.MyWorkflow",
        methodName = "handle",
        jvmMethodDescriptor = "(...)",
    )
    .sensitiveArgumentsJson("""{"claimId":"claim-1"}""")
    .build()
```

The fixture is **test/example support only** — it is not production API. Production applications should provide their own factory with production-appropriate metadata.

The reusable fixture is used by:

- [ApprovalGatewaySpringGoldenPathSmokeTest](../../examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/ApprovalGatewaySpringGoldenPathSmokeTest.kt) — minimal Spring/JDBC smoke proof
- [RegulatedClaimTriageApprovalGatewayRequestFactory](../../examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/RegulatedClaimTriageApprovalGatewayRequestFactory.kt) — full regulated-claim E2E scenario

Both factories use the builder's fluent API and avoid manual low-level record construction.

### Java Interop

The approval workflow mapper is accessible from Java through a dedicated facade:

```java
import dev.tramai.core.workflow.ApprovalWorkflowResults;
import dev.tramai.core.workflow.ApprovalRequestResults;
import dev.tramai.core.workflow.HumanApprovalDecisions;

HumanApprovalDecision.Approved decision = HumanApprovalDecisions.approved(
    "approval-1", "reviewer-1", Instant.now(), "approved"
);

SovereignWorkflowResult<String> result =
    ApprovalWorkflowResults.fromApprovalRequestResult(
        ApprovalRequestResults.alreadyApproved(decision),
        approved -> approved.getDecidedBy() + ":" + approved.getComment()
    );
```

All four outcome types (Suspended, AlreadyApproved, AlreadyDenied, Expired) map correctly from Java.
Java-friendly factory methods accept plain `String` parameters where Kotlin inline value classes are used internally.

> **Note:** Kotlin `@JvmInline value class` types (ApprovalId, WorkflowRunId, etc.) erase to `String` at the JVM level. Java consumers pass plain Strings to factory methods — the inline class wrapping and validation happen internally.
>
> See [ApprovalRequestWorkflowResultMappersJavaInteropTest](../../tramai-core/src/test/java/dev/tramai/core/workflow/ApprovalRequestWorkflowResultMappersJavaInteropTest.java) for the full Java interop proof.

You must provide your own factory as a Spring bean:

```kotlin
@Configuration
class MyWorkflowGatewayConfig {

    @Bean
    fun approvalGatewayRequestFactory(): ApprovalGatewayRequestFactory =
        MyWorkflowApprovalGatewayRequestFactory()
}
```

### What the Factory Does

The `ApprovalGatewayRequestFactory` translates the ergonomic gateway input (`ApprovalSubject`, `ApprovalRecommendation`, `ApproverRole`, `WorkflowRunId`) into the low-level persistence records that `DefaultApprovalGateway` writes to the three backing stores.

Internally, it builds an `ApprovalGatewayPersistenceRequest` containing:

| Field | Source |
|-------|--------|
| `ApprovalRequest` | Approval lifecycle state, binding metadata |
| `ApprovalContinuation` | Continuation metadata for resume |
| `SensitiveToolArguments` | Encrypted arguments for safe replay |
| `SuspendedInvocationMetadata` | Invocation identity, operation reference, replay digest |
| `SensitiveReplayEnvelope` | Tool-call messages for replay-safe resume |
| `ResumeToken` | Public credential presented at resume time |

For a working example, see the `RegulatedClaimTriageApprovalGatewayRequestFactory` in the [regulated claim triage E2E test](https://github.com/GionaGranchelli/tramAI/blob/master/examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/RegulatedClaimTriageApprovalGatewayRequestFactory.kt).

---

## Complete Example

The full regulated claim triage E2E test demonstrates the gateway flow end-to-end:

- High-risk claim suspended through `approvalGateway.requestApproval()`
- Durable state survives context restart
- Approval denial with transactional audit outbox
- Audit chain validation across restart
- Low-risk path bypasses approval entirely

For a **smaller** executable proof that focuses only on the ergonomic path:

- [ApprovalGatewaySpringGoldenPathSmokeTest](../../examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/ApprovalGatewaySpringGoldenPathSmokeTest.kt) — minimal Spring/JDBC smoke proof using `ApprovalGateway` and `toWorkflowResult { ... }` without wiring low-level stores

For the **complete** regulated claim triage scenario:

See:

- [Regulated Claim Triage JDBC E2E Test](https://github.com/GionaGranchelli/tramAI/blob/master/examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/RegulatedClaimTriageJdbcE2ETest.kt)
- [Regulated Claim Triage Scenario Documentation](../scenarios/regulated-claim-triage.md)

---

## Current Limitations

The Preview approval gateway has the following limitations:

| Area | Status |
|------|--------|
| Full workflow resume runtime | Service-level `ApprovalResumeControlPlane` available; approved auto-resume worker lifecycle, observability, and E2E proof available (PRs #112–#117) |
| Reviewer UI | Preview reviewer UI available, disabled by default |
| REST control plane for approvals | Preview REST control plane available (`POST /tramai/sovereign/approvals/{id}/approve`, `.../deny`, `.../resume`, `GET .../{id}`) |
| Approval inbox / work queue query API | Preview inbox query API available (`GET .../approvals`, `GET .../approvals/{id}/work-item`) |
| Cross-store transaction boundary at creation | ✅ Implemented for JDBC-backed stores via `SovereignOpsApprovalRequestMutationStore` |
| Approval-requested audit outbox mutation boundary | ✅ Implemented for JDBC-backed stores when an `ApprovalGatewayAuditIntentFactory` bean is provided |
| Generic global `ApprovalGatewayRequestFactory` | Test fixture (`TestApprovalGatewayRequestFactory`) provided via `tramai-engine` test fixtures — production applications must supply their own |
| Production certification / GA stability | Preview — APIs may change |

These limitations are tracked in the [post-roadmap design backlog](../architecture/human-approval-workflow-ergonomics.md).

---

## See Also

- [`ApprovalGateway` SPI source](https://github.com/GionaGranchelli/tramAI/blob/master/tramai-core/src/main/kotlin/dev/tramai/core/approval/gateway/ApprovalGateway.kt)
- [`DefaultApprovalGateway` implementation](https://github.com/GionaGranchelli/tramAI/tree/master/tramai-engine/src/main/kotlin/dev/tramai/engine/approval/DefaultApprovalGateway.kt)
- [Human Approval Workflow Ergonomics Design](../architecture/human-approval-workflow-ergonomics.md)
- [Regulated Claim Triage Reference Scenario](../scenarios/regulated-claim-triage.md)
- [Sovereign Runtime Quickstart](./sovereign-runtime-quickstart.md)
- [`tramai-spring-boot-starter-sovereign-ops-rest` — Preview REST approval control plane module](https://github.com/GionaGranchelli/tramAI/tree/master/tramai-spring-boot-starter-sovereign-ops-rest)
