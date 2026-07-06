# Workflow API Stability Boundary

> **Status:** Active — defines the workflow-facing API boundary for the post-sovereignty roadmap.
> **Phase:** Phase 1 / Epic 1 of the [Post-Sovereignty Roadmap](POST-SOVEREIGNTY-ROADMAP.md).
> **Guard:** Protected by `verifyWorkflowApiStabilityBoundary` (wired into `check`).

---

## Purpose

TramAI already has many serious runtime pieces — typed AI service declarations, structured output, policy enforcement, approval gates, replay-safe resume, sovereign routing, audit chains, evidence bundles. The next question is:

**Which TramAI workflow APIs can users safely build against today?**

This document defines the answer. It classifies every workflow-facing API and concept into one of four stability levels.

It does **not** guarantee production-readiness, legal compliance, or certification of any kind.

---

## Stability Levels

| Level | Meaning | Compatibility Promise |
|-------|---------|----------------------|
| **Stable** | Safe for examples, docs, and application code. Intended to remain source-compatible where possible. | Breaking changes will be reflected in this document and versioned. |
| **Preview** | Usable, but the API shape may still change before the next stable milestone. | May change without notice. |
| **Internal** | Implementation detail. Not for user workflows. | May change without notice. |
| **Deferred** | Explicitly out of scope for the current workflow stability boundary. | Not supported until a future phase declares otherwise. |

---

## Stable Workflow Surface

These APIs are considered the stable workflow-facing surface, subject to the existing [Sovereign Runtime API stability boundary](architecture/sovereign-api-stability-boundary.md) and API boundary tests.

### AI Service Declaration

| API | Module | Notes |
|-----|--------|-------|
| `@AiService` | `tramai-core` | Annotated interface marker for AI-powered services |
| `@Operation(model = "...")` | `tramai-core` | Operation configuration — model, temperature, max tokens |
| `@SystemMessage("...")` | `tramai-core` | System prompt template |
| `@UserMessage("...")` | `tramai-core` | User message template |
| `@AiDescription("...")` | `tramai-core` | Annotation for parameter and return-type metadata |
| `@AiTool` | `tramai-core` | Tool function marker |
| `@ConversationId` | `tramai-core` | Conversation ID injection |
| Typed request/response contracts | — | Kotlin data classes as AI service method parameters and return types |

### Provider Declaration

| API | Module | Notes |
|-----|--------|-------|
| `Tramai.builder()` / `Tramai.create()` | `tramai-standalone` | Framework-free entry point for building AI services |
| Provider interface (`ModelProvider`) | `tramai-core` | SPI for model providers |
| `ProviderRegistry` | `tramai-core` | Provider registration and resolution |
| Each provider adapter (Ollama, OpenAI, Anthropic, Azure, Bedrock, Gemini, DeepSeek) | respective modules | Provider implementations with connection configuration |

### Structured Output

| API | Module | Notes |
|-----|--------|-------|
| `@AIRange` | `tramai-core` | Numeric range validator annotation |
| `@AIMinItems` | `tramai-core` | Minimum items validator annotation |

### Deterministic Testing

| API | Module | Notes |
|-----|--------|-------|
| Deterministic mock providers | `tramai-testing` | Zero-network test providers for unit testing |
| Mock assertions | `tramai-testing` | Assertions for verifying AI service behavior in tests |

### Policy and DLP

| API | Module | Notes |
|-----|--------|-------|
| `PolicyEngine` | `tramai-core` | Policy evaluation entry point |
| `PolicyDecision` | `tramai-core` | Policy decision result type |
| `PolicyContext` | `tramai-core` | Context for policy evaluation |
| `EnforcementPoint` | `tramai-core` | Policy enforcement hook |
| Data classification enums (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED`) | `tramai-core` | Standard data sensitivity levels |

### Approval Gateway (RC+ Stable)

The following approval gateway APIs are classified as RC+ Stable per the [Sovereign Runtime API stability boundary](architecture/sovereign-api-stability-boundary.md):

| API | Module | Notes |
|-----|--------|-------|
| `ApprovalGateway` | `tramai-core` | Front-door contract for non-blocking human approval |
| `ApprovalRequestResult` | `tramai-core` | Sealed type for approval request outcomes |
| `SovereignWorkflowResult` | `tramai-core` | Sealed type for workflow-level outcomes |
| `ApprovalRequestResult.toWorkflowResult { ... }` | `tramai-core` | Decision-aware mapper |
| `ApprovalWorkflowResults` | `tramai-core` | Java-friendly facade |
| `ApprovalWorkflowResults.fromApprovalRequestResult { ... }` | `tramai-core` | Java-friendly mapper |
| `ApprovalRequestResults` | `tramai-core` | Java-friendly factories |
| `HumanApprovalDecisions` | `tramai-core` | Java-friendly decision factories |

### Sovereign Runtime Configuration

| API | Module | Notes |
|-----|--------|-------|
| `tramai.sovereign.enabled` | Spring Boot | Enable sovereign runtime |
| `tramai.sovereign.persistence.*` | Spring Boot | Persistence configuration |
| `tramai.sovereign.allowed-models` | Spring Boot | Model allowlist |
| `tramai.sovereign.allowed-providers` | Spring Boot | Provider allowlist |
| `tramai.sovereign.provider-zones.*` | Spring Boot | Trust zone configuration |
| `tramai.sovereign.models.*` | Spring Boot | Model registry configuration |

### Exception Types

| API | Module | Notes |
|-----|--------|-------|
| `TramaiException` | `tramai-core` | Base exception for all TramAI errors |
| `PolicyViolationException` | `tramai-core` | Thrown when policy denies an operation |
| `ToolException` | `tramai-core` | Thrown when a tool invocation fails |
| `ModelRegistryException` | `tramai-core` | Thrown when model verification fails |
| `ApprovalRequiredException` | `tramai-core` | Thrown when human approval is required |
| `ApprovalSuspendedException` | `tramai-core` | Thrown when a workflow is suspended for approval |

---

## Preview Workflow Surface

These capabilities exist and are usable, but the developer-facing API is **not** final and may change before the next stable milestone.

| Area | Why Preview |
|------|-------------|
| Workflow orchestration patterns (`tramai-orchestration`) | Still being shaped — typed workflow coordination, checkpoints, worker pools are evolving |
| Runtime evidence export | Planned as Phase 5 of the post-sovereignty roadmap; not yet stable |
| Advanced approval ergonomics | `DefaultApprovalGateway`, `ApprovalGatewayRequestFactory`, `ApprovalGatewayPersistenceRequest` — the existing runtime exists but ergonomics are still evolving |
| Approval REST/control-plane surface | `ApprovalDecisionControlPlane`, `ApprovalResumeControlPlane`, `ApprovalInboxQueryService` — preview per sovereign boundary |
| Provider routing ergonomics | Trust zones and local/cloud routing exist but are still connected to sovereign/local profile evolution |
| Structured-output custom validators | Discussed and partially designed (Phase 2 of roadamp); extension point is not yet stabilized |
| Tool governance APIs | Planned as Phase 6; not yet stable |
| MCP adapter surface (`tramai-mcp`) | Exists and usable, but MCP governance model is still being defined |
| Sovereign runtime Quickstart / guides | Usage-level documentation may evolve as the workflow ergonomics phase progresses |
| Spring Boot specific auto-configuration integration details | Auto-configuration classes are implementation details; configuration properties are stable |
| Preview reviewer UI | Disabled by default, served via Spring Boot auto-configuration |
| Sovereign lab evidence chain tooling | Reviewer scripts, archive verifier, signature verifier — sovereign lab tooling, not workflow API |

---

## Internal Workflow Surface

The following should **not** be treated as public workflow-facing API. Application code must not depend on these directly.

| Area | Why Internal |
|------|--------------|
| Engine proxy dispatch and retry internals | `tramai-engine` owns execution, retry policy, and proxy dispatch — these are implementation strategies |
| Structured output schema generation internals | `tramai-structured` — schema generation, extraction, deserialization, and failure analysis internals |
| Concrete JDBC store implementations | `JdbcApprovalStore`, `JdbcSuspendedInvocationStore`, `JdbcApprovalContinuationStore`, `JdbcAuditStore`, `JdbcSovereignOpsAuditOutboxStore`, `JdbcSovereignOpsApprovalMutationStore`, `JdbcSovereignOpsWorkerLeaseStore`, `JdbcApprovalResumeCredentialStore` — runtime storage, not workflow API |
| Worker lease internals | `SovereignOpsWorkerLeaseStore`, worker lease coordination — operational mechanism |
| Audit outbox internals | `SovereignOpsAuditOutboxStore`, audit outbox record/status — persistence/dispatch mechanism |
| Approved-continuation resume internals | `ApprovedContinuationResumeQueue`, `ApprovedContinuationResumeQueueStatusStore`, `SovereignOpsApprovedContinuationResumeWorker`, `ApprovedContinuationResumeWorkerLifecycle` — background worker implementation |
| Resume credential custody | `SealedResumeToken`, `ApprovalResumeCredentialStore` — encrypted credential handling internals |
| Evidence bundle verifier implementation | Sovereign lab tooling scripts, archive verifier, signature verifier — reviewer tooling, not workflow API |
| Gradle verification task internals | `verifySovereignRuntimeClosure`, `verifySovereignRuntimeApiBoundary`, `verifyPostSovereigntyRoadmap`, and other project guard tasks |
| Archive/package scripts | Shell scripts under `examples/sovereign-lab/` — reviewer tooling |
| Spring Boot auto-configuration classes | Implementation details of how beans are wired |
| E2E test harness internals | Fake components, embedded PostgreSQL test support, test-specific configuration |
| Example-specific code | `examples/` modules — demonstrative, not reusable API |
| Dashboard UI | `tramai-dashboard` — admin UI, not workflow API |

---

## Deferred Workflow Surface

The following are explicitly out of scope for the current workflow stability boundary. They may be reconsidered in future phases.

| Area | Reason |
|------|--------|
| Release Console APIs | Future product track (Phase 8 deferred) |
| Compliance certification APIs | TramAI provides evidence support, not compliance proof |
| Production-readiness certification | Requires independent validation outside project scope |
| Attestation / key-management APIs | Future optional track (Phase 8 deferred) |
| Full MCP connector API stability | Future governance track (Phase 6 planned, not stable) |
| Runtime evidence export format stability | Not stable until Phase 5 is implemented and tested |
| Dashboard / admin UI stability | UI surfaces are not workflow-facing APIs |
| Platform / multi-tenancy APIs | `tramai-platform` — still evolving, not workflow-facing |
| RAG pipeline API stability | `tramai-rag`, `tramai-embedding`, `tramai-vectorstore-*` — higher-level capabilities, not part of the core workflow boundary |
| Scheduler / server module API stability | `tramai-scheduler`, `tramai-server` — optional infrastructure modules |
| Chat memory API stability | `tramai-memory`, `tramai-memory-store` — evolving capability |
| Key rotation | Deferred in sovereign closure boundary |
| Maven Central release | Publishing infrastructure, not workflow API |
| Stable 1.0 API across all TramAI modules | Explicitly deferred per sovereign closure boundary |

---

## Cross-References

| Document | Relationship |
|----------|--------------|
| [Post-Sovereignty Roadmap](POST-SOVEREIGNTY-ROADMAP.md) | Phase 1 of this roadmap defines the API stability epic |
| [Sovereign Runtime API Stability Boundary](architecture/sovereign-api-stability-boundary.md) | Defines the sovereign-runtime-specific stability levels for stores, SPIs, and operational surfaces |
| [Sovereign Runtime API Stability Manifest](architecture/sovereign-api-stability-manifest.yml) | Machine-readable manifest consumed by `verifySovereignRuntimeApiBoundary` |
| [ROADMAP.md](../ROADMAP.md) | Original enterprise roadmap (superseded by post-sovereignty roadmap) |

---

## Allowed Claims

It is allowed to say:

- TramAI defines a workflow-facing API stability boundary.
- Some APIs are stable, some preview, some internal, and some deferred.
- The boundary helps prevent accidental promotion of internal implementation details.
- The boundary guides examples, docs, and future verification tasks.
- The boundary is the foundation for machine-checked API stability verification (planned in PR #166).
- Stable APIs are safe to build examples and applications against, subject to the existing sovereign boundary tests.
- Preview APIs are usable but may change.

## Forbidden Claims

It is not allowed to say:

- All workflow APIs are stable.
- TramAI is production-certified.
- TramAI guarantees backward compatibility for preview APIs.
- TramAI proves legal or regulatory compliance.
- TramAI provides EU AI Act conformity certification.
- Internal persistence, worker, audit, verifier, or archive APIs are public workflow APIs.
- The stability boundary is complete or frozen — it will evolve as the roadmap progresses.
- Deferred capabilities are secretly stable.

---

## Acceptance Criteria

This document is considered complete when:

1. It defines Stable, Preview, Internal, and Deferred stability levels.
2. It lists the current stable workflow-facing surface.
3. It lists preview workflow areas.
4. It lists internal implementation areas.
5. It lists deferred areas.
6. It includes allowed and forbidden claims sections.
7. It links back to the post-sovereignty roadmap.
8. The post-sovereignty roadmap links forward to this document.
9. The changelog records PR #165.
10. `./gradlew check` passes.

---

## Maintenance

This document should be updated when:

- An API moves between stability levels (e.g., Preview → Stable).
- A new workflow-facing API is added to the stable surface.
- An internal implementation detail is accidentally promoted to preview or stable.
- A deferred capability is reconsidered for an upcoming phase.

A new PR should add a machine-checked verification task for this boundary (planned as PR #166).

---

*Part of Phase 1 / Epic 1 of the [Post-Sovereignty TramAI Roadmap](POST-SOVEREIGNTY-ROADMAP.md).*
