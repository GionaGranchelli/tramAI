# Changelog

## Unreleased

### Added
- Added `ApprovalRequestResult.toWorkflowResult { ... }` ergonomic Preview mapper (PR #122). Converts each gateway outcome (Suspended, AlreadyApproved, AlreadyDenied, Expired) to the corresponding `SovereignWorkflowResult` variant. The `approvedValue` lambda receives the `HumanApprovalDecision.Approved` decision and is lazy — only invoked for `AlreadyApproved`. Terminal states never execute the lambda, preventing accidental side effects.
- Updated the approval gateway golden path proof (PR #121) to use the mapper instead of a manual `when` expression.
- Updated golden path guide to show the mapper.
- Updated human approval workflow ergonomics design doc with PR #121 and PR #122 entries.
- Added `verifySovereignRuntimeClosure`, a canonical verification task for the Sovereign Runtime RC+ / enterprise proof closure boundary.
- Added Sovereign Runtime API stability boundary documenting RC+ stable, preview, internal, and deferred surfaces.
- Added human approval workflow ergonomics design document defining the target API shape for non-blocking human-in-the-loop workflows.
- Added Preview approval gateway SPI for non-blocking human approval workflow ergonomics (`ApprovalGateway`, `ApprovalRequestResult`, `ApprovalGatewayTypes`, `SovereignWorkflowResult`).
- Added Preview store-backed approval gateway adapter over the existing approval, suspended invocation, and continuation stores (`DefaultApprovalGateway`, `ApprovalGatewayRequestFactory`, `ApprovalGatewayPersistenceRequest`).
- Added Preview Spring Boot auto-configuration for the store-backed approval gateway when required stores and an `ApprovalGatewayRequestFactory` are available (`ApprovalGatewayAutoConfiguration`).
- Refactored the regulated claim triage E2E proof to request human approval through the Preview ApprovalGateway (`PR #99`). The workflow now calls `approvalGateway.requestApproval(...)` instead of manually creating low-level store records. A `RegulatedClaimTriageApprovalGatewayRequestFactory` provides the persistence records, and Spring auto-configuration creates the `DefaultApprovalGateway` bean.
- Added Approval Gateway Golden Path developer-facing guide (`PR #100`). The guide explains how to request human approval through the Preview ApprovalGateway API, how Spring Boot auto-configuration wires it, what persistence records are created, and what the current Preview limitations are.
- Added Preview `SovereignOpsApprovalRequestMutationStore` with a JDBC transactional boundary for atomic approval request creation across `ApprovalStore`, `SuspendedInvocationStore`, `ApprovalContinuationStore`, and optional audit outbox intent (`PR #101`). `SovereignOpsTransactionalApprovalGateway` now replaces the sequential `DefaultApprovalGateway` write path when the request mutation store is available.
- Added Preview `ApprovalGatewayAuditIntentFactory` SPI and wiring — `SovereignOpsTransactionalApprovalGateway` now emits approval-requested audit outbox intent atomically when an audit intent factory is present (`PR #102`). The regulated claim triage E2E test proves a `PENDING` approval-requested outbox record exists after gateway request creation.
- Added Preview `ApprovalDecisionControlPlane` service for approving or denying pending approvals through the transactional mutation/outbox boundary (`PR #103`).
- Added Preview `ApprovalResumeControlPlane` service for resuming approved, suspended workflow executions through the engine resume runtime (`PR #104`).
- Added Preview REST approval control plane with approve, deny, resume, and status endpoints, disabled by default (PR #105). The REST layer lives in a new `tramai-spring-boot-starter-sovereign-ops-rest` module to avoid pulling `spring-boot-starter-web` into the base ops starter. The controller carries its own `@ConditionalOnProperty` guard and `@ConditionalOnBean` requirements so it cannot be registered through component scanning. Internal exception messages are never echoed in responses (`Failed.reason` maps to the safe `"approval-resume-failed"` code). Value-type construction errors are caught and mapped to 400 Bad Request. `jackson-module-kotlin` is included for correct Kotlin data class deserialization. ApplicationContextRunner tests verify disabled-by-default and enabled scenarios.
- Added Preview approval inbox query API for safe reviewer work queues over durable approval records (PR #106). Includes `ApprovalInboxQueryService` SPI with safe projection models (`ApprovalInboxWorkItem` with `workflowRunId`, `toolName`, `requiredRole`, `riskLevel`, `subjectType`, `subjectId`, `recommendationType`, `continuationStatus`), JDBC-backed `JdbcApprovalInboxQueryService` querying `approvals` and `approval_continuations` with cursor-based pagination ordered by nearest expiry, and REST `GET .../approvals` list and `GET .../{id}/work-item` endpoints. A separate `ApprovalInboxQueryAutoConfiguration` registers the service when a `DataSource` is available. The inbox never exposes resume tokens, token digests, raw tool arguments, replay envelopes, decision comments, or claim payloads.
- Added persisted safe approval inbox metadata boundary (PR #107). Adds `ApprovalInboxMetadata`, `ApprovalInboxMetadataFactory`, and `ApprovalInboxMetadataPolicy` for safe reviewer-facing labels (`requiredRole`, `riskLevel`, `subjectType`, `subjectId`, `recommendationType`). Inbox metadata is persisted inside the `sanitized_metadata->'inbox'` JSONB field during transactional approval-request creation. `JdbcApprovalInboxQueryService` now reads inbox metadata from the JSONB path and re-enables `requiredRole` filtering. A `RegulatedClaimTriageApprovalInboxMetadataFactory` provides concrete labels for the regulated claim triage scenario. The E2E test proves regulated claim approval appears in the inbox with safe metadata and no sensitive payload leakage. Metadata remains backward-compatible — absent inbox metadata maps to null fields.
- Added Preview approval reviewer UI, disabled by default (PR #110). Serves a self-contained HTML page with inline CSS/JS at `/tramai/sovereign/reviewer` for listing, filtering, inspecting, approving, and denying approval work items through the existing REST control plane. Requires `reviewer-ui-enabled=true`, `rest-control-plane-enabled=true`, `ApprovalInboxQueryService`, and `ApprovalDecisionControlPlane` beans. Resume is intentionally excluded from the UI because safe inbox projections do not expose resume tokens. Includes auto-configuration guards and MVC tests for status and sensitive-field safety.
- Added internal approval resume credential custody (PR #111). Adds `SealedResumeToken` (redacted wrapper with `[REDACTED]` toString), `ApprovalResumeCredentialRecord`, and `ApprovalResumeCredentialStore` SPI for secure internal custody of resume credentials. The resume token is encrypted at rest (AES-256-GCM) and persisted atomically inside the transactional approval-request creation boundary. A new `tramai_approval_resume_credentials` table (`V6__approval_resume_credential_custody.sql`) with expiry-tracking index stores the credential. The JDBC implementation uses `DefaultJdbcPayloadCrypto` for encryption. No resume token is exposed through inbox, REST, audit, logs, or reviewer UI. Enables the future safe auto-resume worker (PR #112) without leaking credentials into human-facing surfaces.
- Added Preview SovereignOpsApprovedContinuationResumeWorker for automatic resumption of approved pending continuations (PR #112). The worker claims eligible records through ApprovedContinuationResumeQueue, reads encrypted resume credentials from tramai_approval_resume_credentials, and invokes ApprovalResumeControlPlane. Retryable failures set resume_last_error_code, resume_next_attempt_at, and resume_attempt_count; terminal failures mark the continuation CANCELLED.
- Added approved-resume worker lifecycle, status store, queue snapshot, health indicator, and Micrometer metrics (PR #113-#116). Queue diagnostics expose aggregate counts such as eligibleNow, delayedRetry, activeLeases, expiredLeases, and terminalFailures, without exposing approval IDs, workflow IDs, resume tokens, or payloads.
- Added `ApprovedResumeLifecycleJdbcE2ETest` — a full lifecycle E2E proof for the approved-resume flow using Testcontainers PostgreSQL (PR #117). Proves: approval request → approve decision → encrypted credential custody → auto-resume worker picks up the approved continuation → continuation is replayed through the engine → terminal success state is observed. Verifies that the resume credential is never exposed in plaintext through any operational surface.
- Added docs sync for Sovereign Runtime post-#117 closure state (PR #118). Updates quickstart with REST control plane, reviewer UI, approved-resume worker config, and human approval auto-resume section. Updates JDBC runbook with V6/V7 migrations, resume credential store, and auto-resume worker configuration. Updates CHANGELOG with PR #112–#118 entries. Updates README module table, capability list, and deferred items.
- Added approved-resume worker Prometheus alert examples, Grafana dashboard JSON, and operator triage runbook (PR #119).
- Added `verifySovereignRuntimeApiBoundary` verification task, API stability manifest, and source-file existence checks (PR #120). The task guards against accidental API promotion, moved/deleted stable source files, and GA/production overclaims. Wired into `verifySovereignRuntimeClosure` and `verifySovereignRuntimeReleaseCandidate`.
- Added approval gateway golden path ergonomics proof (PR #121). Introduces an executable test using `ApprovalGateway` only — no low-level persistence stores — covering Suspended, AlreadyApproved, AlreadyDenied, and Expired outcomes. Updated golden path guide to reflect preview reviewer UI availability. Added docs guard against the stale "Reviewer UI | Not implemented yet" limitation.
- Sovereign runtime profile and routing foundation (`tramai-sovereign`).
- Policy enforcement and DLP/redaction support (`tramai-security`).
- Approval gates and replay-safe resume.
- Encrypted file-backed persistence (`tramai-persistence-file`).
- File-backed audit outbox and recovery workflow (`tramai-spring-boot-starter-sovereign-ops`).
- JDBC-backed sovereign persistence with Spring Boot auto-configuration (`tramai-spring-boot-starter-sovereign-persistence-jdbc`). Activated via `tramai.sovereign.persistence.type=jdbc`.
- JDBC transactional approval mutation outbox boundary via `JdbcSovereignOpsApprovalMutationStore`, committing approval denial and audit intent in one PostgreSQL transaction.
- JDBC E2E restart proof: Spring Boot example with Testcontainers PostgreSQL, five E2E tests proving sovereign state survives context restart, outbox dispatch recovery, and audit stream hash-chain validation.
- JDBC worker lease support for multi-node audit outbox worker coordination via the `worker_leases` table, with atomic lease acquisition (`SELECT ... FOR UPDATE`), heartbeat extension, release, and lease-aware worker wrapper.
- Background worker for audit outbox recovery and dispatch.
- Observer SPI and composite observer pipeline for sovereign ops audit outbox worker cycles and failures.
- OpenTelemetry worker metrics (`tramai-spring-boot-starter-sovereign-ops-observability`).
- Micrometer/Prometheus worker metrics bridge (`tramai-spring-boot-starter-sovereign-ops-micrometer`).
- Optional read-only Actuator worker status endpoint (`/actuator/tramaiSovereignOpsWorker`).
- Optional Actuator worker health component (`tramaiSovereignOpsWorker`, registered in real `HealthContributorRegistry`).
- Sovereign document intelligence reference workflow (`examples:sovereign-document-intelligence`).
- Worker observability runbook covering Actuator status, health component, Micrometer, OpenTelemetry, PromQL, and example alerts.
- Sovereign JDBC production deployment runbook: deployment topology, configuration reference, encryption key requirements, migration order, worker lease setup, health checks, failure modes, rollback strategy, and verification checklist.
- JDBC-backed regulated claim triage E2E proof: high-risk approval denial + audit outbox transactional boundary, fail-closed cloud routing, low-risk no-approval path, audit/outbox dispatch, and sanitized observability assertions.
- Sovereign runtime release-candidate evidence chain and evidence index generation.
- Sovereign runtime release-readiness documentation and module matrix.

### Hardened
- Added canonical `verifySovereignRuntimeReleaseCandidate` Gradle task to aggregate the sovereign runtime release-candidate verification chain.
- Harden approval inbox metadata policy coverage (PR #109). Add focused unit tests for `ApprovalInboxMetadataPolicy` covering validation scenarios. Fix metadata safety test to assert only the `inbox` portion of `sanitized_metadata`. Fix KDoc reference to example class. Use AssertJ assertions throughout.
- Sovereign runtime verification now validates: local publication, signed bundle dry-run, consumer resolution from dedicated verification repository, evidence index generation, observability documentation validation (`verifySovereignOpsObservabilityDocs`), and Actuator health-tree integration tests (`HealthContributorRegistry` component name verification).
- Actuator worker health documentation is backed by health-tree integration tests, not only bean-registration unit tests — the health component name `tramaiSovereignOpsWorker` is now proven through the real Spring Boot `HealthContributorRegistry`.

### Changed
- README and architecture documentation realigned around governed AI workflows.
- Status documentation updated to distinguish implemented, evolving, and not-complete areas.

### Not included
- Stable 1.0 public API.
- Maven Central release of sovereign runtime modules (not verified).
- Broad REST/Actuator operational control endpoints beyond worker status and health.
- Full dashboard integration and production monitoring runbook.
- Key rotation.
- Complete API reference documentation.

For the current release-readiness boundary, see [docs/releases/sovereign-runtime-release-readiness.md](docs/releases/sovereign-runtime-release-readiness.md).

### Documentation
- Added Sovereign Runtime release-candidate boundary declaration and linked it to the canonical verification task.
- Added Sovereign Runtime closure boundary defining the completed RC+ / enterprise proof scope, closure evidence, explicit non-goals, and deferred GA items.
- Added Sovereign Runtime Quickstart for first-time RC evaluation and integration.
- Added a regulated claim triage reference scenario for Sovereign Runtime RC evaluation.
- Added Sovereign JDBC persistence design as the first production-hardening target after the Sovereign Runtime RC boundary.
- Added Sovereign JDBC production deployment runbook covering topology, configuration, encryption, migrations, worker leases, health checks, failure modes, and verification checklist.

### Added
- Added `tramai-persistence-jdbc` module with PostgreSQL schema skeleton and schema contract tests for the five sovereign persistence areas.

## 0.3.1 — 2026-05-24

Patch release focused on streaming stability, memory persistence, and proxy-aware tool scanning.

See [docs/releases/CHANGELOG-0.3.1.md](docs/releases/CHANGELOG-0.3.1.md) for the detailed release notes.

### Features

- `thinkingTokens: Int?` added to `ModelResponse` and `UsageMetrics`, parsed from OpenAI's `completion_tokens_details.reasoning_tokens`, wired through engine and `AzureOpenAiProvider`.

### Refactoring

- `stream()` methods decomposed into `handleHttpError` + `parseXxxResponse` pattern in OpenAI, Anthropic, and Azure providers for improved maintainability.

### Bug Fixes

- Chat memory persistence fix: conversation turns are now correctly persisted on streaming completion and structured output success.

### Tests

- Proxy-aware tool scanning: MCP tool handlers, `TokenAwareChatMemory` improvements.

## 0.3.0

Release entry for the current repository milestone.

See [docs/releases/CHANGELOG-0.3.0.md](docs/releases/CHANGELOG-0.3.0.md) for the detailed release notes.

### Added

- `tramai-memory` module, replacing early state mechanics with production-ready `ChatMemory` implementations (`TokenAwareChatMemory`, `PersistentChatMemory`, `MessageWindowChatMemory`).
- Multimodal / Vision Support across core and providers.
- `ContentPart` sealed interface with `TextPart`, `ImagePart`, and `ImageUrlContent`.
- Built-in `ImageDownloader` (20MB limit, 10s/30s timeouts, MIME detection from URL extension).
- Capability routing via `ProviderCapability.VISION` and execution-time validation in `TramaiEngine`.
- Configurable image fidelity via `ImageDetail` enum (LOW, HIGH, AUTO).
- `UsageMetrics` expansions for tracking `imageCount` and `imageTokensEstimate`.
- Provider serialization updates for OpenAI, Azure OpenAI, Anthropic, Bedrock, Gemini, Ollama, and DeepSeek image inputs.
- `tramai-rag` pipeline module for document loading, chunking, retrieval, and context injection.
- `tramai-vectorstore-spi` with concrete adapters for ChromaDB (`tramai-vectorstore-chroma`) and PostgreSQL (`tramai-vectorstore-pgvector`).
- Comprehensive distributed worker observability and shutdown events (e.g. `onShutdownStarted`, `onDrainProgress`, `onLeaseRenewed`, `onWorkerHeartbeat`).
- Built-in provider modules for `tramai-azure-openai`, `tramai-bedrock`, `tramai-gemini`, and `tramai-deepseek`.

### Changed

- Transitioned content modeling to support additive parallel `ContentPart` sequences within messages, accommodating tool results that generate both text and images seamlessly.
- Project version bumped to `0.3.0` across POM and BOM specifications.

### Notes

- TramAI `0.3.x` targets Java `21+`.
- All memory extensions have been heavily tested for edge cases, token eviction strategies, system deduplication, and concurrent thread safety.
- Engine execution defends its capability invariants (e.g., throwing explicit exceptions when images are sent to non-vision models).

## 0.2.0

Release entry for the orchestration and runtime/platform expansion milestone.

### Added

- `tramai-scheduler` for cron scheduling and durable schedule stores
- `tramai-server` for REST, webhook, OpenAPI, and SSE workflow operations
- `tramai-mcp` for exposing workflows through the MCP protocol
- `tramai-platform` for multi-tenancy, API keys, rate limiting, plugins, and audit logging
- `tramai-dashboard` for the optional Vue 3 admin UI
- worker-pool orchestration features including lease-based work stealing, fencing, heartbeat registry, and graceful shutdown

### Changed

- documentation and repository structure now reflect the broader `0.2.0` operational surface
- orchestration moved from an early optional add-on to a documented core runtime pillar

### Notes

- Tramai `0.2.x` targets Java `21+`.
- See [docs/releases/CHANGELOG-0.2.0.md](docs/releases/CHANGELOG-0.2.0.md) for the detailed module-by-module release summary.

## 0.1.0

Release entry prepared for the first public release of TramAI.

### Added

- `tramai-core`, `tramai-engine`, and `tramai-structured`
- provider modules for Anthropic, OpenAI, OpenAI-compatible APIs, and Ollama
- optional `tramai-observability`
- `tramai-standalone`, `tramai-spring`, `tramai-testing`, and `tramai-bom`
- deterministic testing helpers including mock and simulated-failure providers
- raw text streaming support (`Flow<StreamChunk>`) in core and major providers (OpenAI, Anthropic, Ollama)
- engine-owned, provider-portable tool calling orchestration with `@AiTool` discovery
- repository documentation, ADRs, specs, task board, and contributor guidance
- Kotlin Spring Boot example project backed by locally published artifacts

### Changed

- provider resolution now uses an explicit registry
- timeout and retry hardening is covered in engine and provider tests
- Spring Boot example modernized to support asynchronous Flow streaming

### Notes

- TramAI `0.1.x` targets Java `21+`.
- `tramai-orchestration` ships as an optional experimental module while its API settles.
- Add the final release date when the `v0.1.0` tag and Maven Central publication complete.
