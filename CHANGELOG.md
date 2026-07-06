# Changelog

## Unreleased

### Added
- Structured output repair feedback loop tests (PR #171). The tests verify that parse and validation failures replay the failed assistant output, add repair feedback as a user message, retry structured output generation, respect retry limits, and throw diagnostic StructuredOutputException errors on exhaustion. This does not add runtime behavior, API changes, custom validator implementation, Java boundary changes, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Structured output validator extension model documentation (PR #170). The document defines the future design boundary for custom structured-output validators, including annotation-based validators, SPI-based validators, schema contribution, runtime validation, repair feedback, stability boundaries, Java compatibility, security considerations, and open questions. This does not add runtime behavior, API changes, custom validator implementation, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Structured output contract evolution tests (PR #169). The tests verify that generated contracts reflect return-type field changes, that `@AIRange` and `@AIMinItems` contribute to schema generation and validation, and that parse/validation failures produce repair-friendly feedback. This does not add runtime behavior, API changes, custom validator support, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Structured output contract lifecycle documentation (PR #168). The document explains how TramAI moves from Kotlin/Java return types through contract/schema generation, validator annotations, provider output parsing, validation, repair feedback, retry exhaustion, and typed result or failure. It identifies stable, preview, internal, and deferred structured-output surfaces and records open questions for follow-up tests. Based on current code — where behavior was not proven, it is explicitly noted. This does not add runtime behavior, API changes, model calls, benchmark execution, runtime evidence export, compliance claims, production-readiness claims, or certification claims.
- Workflow lifecycle model documentation (PR #167). The lifecycle model explains how governed TramAI workflows move from request through contract binding, policy evaluation, provider/tool execution, structured output repair, approval gates, audit/persistence, and typed result or failure. It also reserves a future place for runtime evidence export. Cross-links to the workflow API stability boundary and the post-sovereignty roadmap. This does not add runtime behavior, API changes, model calls, benchmark execution, runtime evidence export, compliance claims, production-readiness claims, or certification claims.
- Workflow API stability boundary verification (PR #166). The new `verifyWorkflowApiStabilityBoundary` Gradle task uses section-scoped checks to verify that the workflow API boundary document exists, contains the required stable/preview/internal/deferred classifications with correct API references, and that forbidden claims are properly rejected. The task is wired into `check`. This does not add runtime behavior, API changes, model calls, benchmark execution, runtime evidence export, compliance claims, production-readiness claims, or certification claims.
- Workflow API stability boundary documentation (PR #165). The boundary classifies TramAI workflow-facing APIs as stable, preview, internal, or deferred, and defines allowed and forbidden claims for workflow API stability. Covers AI service declaration, structured output, policy, approval gateway, sovereign configuration, testing, and exception types. This does not add runtime behavior, API changes, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Post-Sovereignty TramAI roadmap document (PR #164). Declares Sovereign Lab Evidence Handoff v1 complete and defines the next phase: workflow ergonomics, API stability, structured output contracts, approval ergonomics, runtime evidence export, tool/MCP governance, and product narrative. Adds a Gradle guard requiring the roadmap to exist and contain key terms. This does not add runtime behavior, API changes, model calls, benchmark execution, compliance claims, production-readiness claims, signature/attestation automation, or Release Console work.
- Synced sovereign lab archive signature verification handoff documentation (PR #162).

## 0.4.0 — Sovereign Evidence Handoff

### Added

- Sovereign lab evidence capture workflow.
- Machine-readable evidence bundle manifest.
- Finalization and standalone bundle verification.
- Archive export with checksum sidecar.
- Safe archive verifier with strict sidecar parsing and tar-entry validation.
- Archive verifier negative fixture coverage.
- Optional detached signature verification for archive checksum sidecars.
- Reviewer guide, release-readiness checklist, evidence-chain overview, archive signing boundary documentation, and handoff documentation.
- Deterministic archive export regression coverage.

### Verified

- Release readiness checks.
- Sovereign runtime publication dry-run.
- Signed bundle dry-run.
- Sovereign runtime consumer smoke.
- Sovereign lab evidence bundle lifecycle.
- Archive verification and negative fixture matrix.
- Optional signature-verification coverage.

### Non-goals

This release does not certify production readiness, legal compliance, EU AI Act conformity, security certification, evidence truth, benchmark guarantees, model quality, operator identity, or audit acceptance.

### Detailed changes
- Added optional sovereign lab evidence archive signature verification (PR #161). Reviewers can verify a detached signature over the archive checksum sidecar using a caller-supplied public key, then run the existing archive verifier. Gradle coverage includes valid signature verification and negative fixtures for missing signatures, tampered sidecars, wrong public keys, and missing public keys. This does not add key management, private keys, signing automation, attestation, upload, evidence truth validation, regulatory certification, or production-readiness claims.
- Added sovereign lab evidence archive signing boundary documentation (PR #160). The new [ARCHIVE-SIGNING.md](examples/sovereign-lab/ARCHIVE-SIGNING.md) guide explains the difference between checksum sidecars, deterministic archive hashes, signatures, attestations, and certification. It documents that current archive export provides transfer-integrity evidence only and does not prove signer identity, operator identity, regulatory compliance, production readiness, or evidence truth. This does not add runtime changes, model calls, benchmark execution, key generation, signing implementation, attestation, upload, evidence truth validation, or production-readiness claims.
- Hardened sovereign lab evidence archive structure checks (PR #159). The archive verifier now rejects top-level regular files during tar inspection before extraction, requiring archived evidence bundles to be directory-shaped under one top-level bundle directory. Gradle coverage includes a top-level-file archive negative fixture. This does not add signing, attestation, upload, evidence truth validation, benchmark execution, or production-readiness claims.
- Hardened sovereign lab evidence archive sidecar parsing (PR #158). The archive verifier now validates the checksum sidecar as exactly one SHA-256 digest plus one archive filename, rejects missing or extra sidecar fields, and accepts the `sha256sum -b` filename marker. Gradle coverage includes binary-mode sidecar acceptance and malformed sidecar rejection. This does not add signing, attestation, upload, evidence truth validation, benchmark execution, or production-readiness claims.
- Expanded sovereign lab evidence archive verifier negative fixtures (PR #157). Gradle coverage now exercises additional unsafe archive cases including absolute entries, hardlinks, special file entries, empty archives, multiple top-level directories, invalid checksum digests, and multi-line checksum sidecars. This does not add signing, attestation, upload, evidence truth validation, benchmark execution, or production-readiness claims.
- Added safe sovereign lab evidence archive verifier (PR #156). Reviewers can now verify archived evidence bundles through `verify-evidence-archive.sh`, which checks the SHA-256 sidecar, rejects unsafe archive entries (absolute paths, traversal, symlinks, hardlinks, special files), extracts into a temporary directory, and runs the standalone bundle verifier. Gradle coverage includes valid archive verification and negative archive fixtures for missing checksum, tampered archive, unsafe paths, and symlink entries. This does not add signing, attestation, upload, evidence truth validation, benchmark execution, or production-readiness claims.
- Hardened sovereign lab evidence bundles against symlinks (PR #155). The finalizer and verifier now reject symlinks anywhere in a bundle tree, and Gradle evidence-bundle verification covers required-file, report-file, and unlisted symlink fixtures. This does not add signing, attestation, upload, evidence truth validation, benchmark execution, or production-readiness claims.
- Added deterministic archive export regression coverage for sovereign lab evidence bundles (PR #154). The Gradle evidence-bundle verification now packages the same finalized bundle twice and requires byte-identical archive SHA-256 values and matching checksum sidecars. This does not add signing, attestation, upload, evidence truth validation, benchmark execution, or production-readiness claims.
- Added sovereign lab evidence chain overview (PR #153). The overview documents the full `create → fill → finalize → verify → readiness → review → package → extract → re-verify` lifecycle, maps each step to its script or guide, and distinguishes machine-verified properties from unsupported claims such as production readiness, certification, compliance, evidence truth validation, signing, or benchmark guarantees.
- Added sovereign lab evidence bundle archive export (PR #152). Operators can now verify a finalized evidence bundle and package it into a `.tar.gz` archive with a SHA-256 sidecar for handoff. The Gradle evidence-bundle verification extracts the archive and re-runs the standalone verifier against the extracted bundle. This does not sign, certify, upload, validate evidence truth, run models, run benchmarks, or create production-readiness claims.
- Added negative fixture coverage for sovereign lab evidence bundle verification (PR #151). The Gradle evidence-bundle verification now proves malformed or unsafe bundles are rejected, including unsafe paths, duplicate file metadata, manifest self-digests, weakened claim-boundary flags, invalid digest metadata, negative file sizes, and missing finalized files. This does not add evidence truth validation, signing, archive packaging, model calls, benchmark execution, or production-readiness claims.
- Added sovereign lab evidence bundle reviewer guide (PR #150). The guide explains how to verify and inspect finalized bundles, interpret manifest metadata, review claim-boundary flags, and distinguish structural tamper-evidence from evidence truth, compliance, certification, or production-readiness claims. The guide does not certify production readiness, prove legal compliance, prove EU AI Act conformity, or replace an audit. `verifySovereignLabProfile` now guards that the guide exists and covers the required reviewer topics.
- Added sovereign lab release readiness checklist (PR #149). The checklist defines required verification commands, evidence bundle lifecycle expectations, required evidence files, allowed claims, forbidden claims, and release-candidate blockers. `verifySovereignLabProfile` now guards that the checklist exists and covers the required sovereign-lab readiness topics.
- Added sovereign lab evidence bundle finalization workflow (PR #148). Operators can now create a bundle, fill evidence files, finalize the bundle to refresh manifest digests, and verify the finalized bundle. The Gradle verification proves that edited bundles fail before finalization, pass after finalization, and fail again after post-finalization tampering. This does not validate evidence truth, run models, run benchmarks, sign manifests, or create archives.
- Added standalone sovereign lab evidence bundle verifier (PR #147). The verifier checks an existing generated bundle against manifest.json, including required files, claim-boundary flags, SHA-256 digests, file sizes, and safe relative paths. The Gradle scaffold verification now proves the verifier accepts a clean bundle and rejects a tampered generated bundle. This does not validate evidence truth, run models, run benchmarks, sign manifests, or create archives.
- Added SHA-256 file integrity metadata to sovereign lab evidence bundle manifests (PR #146). Generated manifests now record required evidence file paths, file sizes, and SHA-256 digests in a `files` array. The `verifySovereignLabEvidenceBundle` task recomputes and verifies these digests against the generated bundle. This makes bundles tamper-evident without validating evidence truth, running models, running benchmarks, or adding certification claims.
- Added machine-readable sovereign lab evidence bundle manifest generation and verification (PR #145). Generated evidence bundles now include `manifest.json` with schema version, bundle type, source commit, required files, and claim-boundary flags. The `verifySovereignLabEvidenceBundle` task verifies the manifest without running local models, benchmarks, or evidence validation.
- Added sovereign lab evidence bundle scaffold verification (PR #144). Adds a root `verifySovereignLabEvidenceBundle` task that runs the bundle helper with a deterministic test timestamp and verifies the generated bundle layout, required templates, reports directory, generated README, and non-certification language. No runtime behavior changes, local model calls, benchmark execution, or evidence auto-collection.
- Added sovereign lab evidence bundle scaffold for reproducible timestamped evidence packs (PR #143). Adds `examples/sovereign-lab/create-evidence-bundle.sh` helper script, `evidence-template/MANIFEST.md` and `command-log.md` templates, updated `environment.md` template, updated `EVIDENCE.md` with bundle workflow, and extended `verifySovereignLabProfile` guards. The bundle is generated under `build/` — not committed — and copies all existing evidence templates. No runtime behavior changes, no benchmark thresholds, no GPU profiling, no cloud comparison.
- Promoted the approval workflow golden-path APIs to **RC+ Stable** for the Sovereign Runtime RC+ milestone (PR #133). This includes `ApprovalGateway`, `ApprovalRequestResult`, `SovereignWorkflowResult`, `ApprovalRequestResult.toWorkflowResult`, `ApprovalWorkflowResults`, `ApprovalWorkflowResults.fromApprovalRequestResult`, `ApprovalRequestResults`, and `HumanApprovalDecisions` — the core developer-facing golden path covered by Kotlin tests, Java interop tests, source-shape guards, manifest checks, and executable Spring/JDBC smoke proofs. Control-plane (`ApprovalDecisionControlPlane`, `ApprovalResumeControlPlane`, `ApprovalInboxQueryService`), REST/UI, auto-configuration, fallback gateway, and JDBC implementation surfaces remain Preview/Internal.
- Updated the Approval Gateway golden path guide after PR #133 to describe the core approval workflow APIs as RC+ Stable while keeping REST/control-plane, reviewer UI, Spring auto-configuration, fallback gateway, and implementation details marked Preview/Internal. Added build guards preventing stale Preview language from reappearing in the guide.
- Added sovereign lab profile for physical local-model testing (PR #135). Adds `application-sovereign-lab.yml` Spring profile with PostgreSQL persistence, local OpenAI-compatible provider, JDBC encryption, REST control plane, reviewer UI, and zero-egress config. Adds `examples/sovereign-lab/docker-compose.yml` for local PostgreSQL and `examples/sovereign-lab/README.md` with full setup and verification commands. Adds `verifySovereignLabProfile` Gradle task guarding the profile and docs.
- Added sovereign lab runtime smoke verification (PR #137). Adds `SovereignLabProfileSmokeTest` that proves the `sovereign-lab` Spring profile boots with JDBC persistence (embedded PostgreSQL) and local OpenAI-compatible provider configuration, without requiring a real cloud provider or LLM endpoint. Adds `verifySovereignLabRuntimeSmoke` Gradle task. Updates lab README with CI smoke verification section.
- Added Spring auto-configuration for the sovereign lab local OpenAI-compatible provider (PR #138). Creates a new `tramai-spring-boot-starter-local-provider-openai` module with `OpenAiCompatibleProviderAutoConfiguration` that maps the `tramai.providers.local-lab-provider` YAML entry to an `OpenAiCompatibleProvider` bean. The `SovereignLabProfileSmokeTest` now proves the `local-lab-provider` bean is created from YAML configuration rather than test-only manual registration.
- Added an opt-in sovereign lab local-model invocation proof (PR #140). The new `verifySovereignLabLocalModel` task runs only when `TRAMAI_ENABLE_LOCAL_MODEL_TEST=true` and validates the sovereign-lab profile against a real local OpenAI-compatible endpoint. Uses embedded PostgreSQL and a temporary encryption key; requires only a reachable local model endpoint.
- Added a sovereign lab evidence capture guide with reproducible commands and templates for local model invocation, approval workflow proof, restart durability, JDBC persistence, and no-cloud provider evidence (PR #141). Adds `examples/sovereign-lab/EVIDENCE.md`, `evidence-template/` folder, README link, and `verifySovereignLabProfile` guard extensions covering evidence doc completeness.
- Added an opt-in sovereign lab local-model benchmark harness with a manual Gradle task, helper script, JSON report, and evidence template. The benchmark is diagnostic only and is not part of CI or any production performance guarantee.
- Added approval workflow API stabilization candidate boundary (PR #132). Documents the golden-path approval workflow APIs (`ApprovalGateway`, `ApprovalRequestResult`, `SovereignWorkflowResult`, `ApprovalRequestResult.toWorkflowResult`, `ApprovalWorkflowResults`, `ApprovalWorkflowResults.fromApprovalRequestResult`, `ApprovalRequestResults`, `HumanApprovalDecisions`) as candidates for future RC+ Stable promotion. Adds a new `stabilizationCandidates` section in the API stability manifest separated from both `preview` and `rcPlusStable`. Control-plane, REST/UI, auto-configuration, and implementation details remain Preview/Internal. Adds build guards preventing the candidate list from drifting or leaking into rcPlusStable. At the time of PR #132, no APIs were promoted yet.
- Synced human approval workflow ergonomics architecture doc with post-#122 gateway hardening (PR #131). Removed stale claim that Spring auto-configuration created `DefaultApprovalGateway` alongside JDBC stores. Added continuation section covering PRs #123–#130 (API boundary guard, Spring smoke proof, test fixture, factory fixture adoption, Java interop proof, Java facade boundary guard, non-transactional fallback opt-in). Updated implementation sequence table with PRs #129–#130. Added build guard preventing stale DefaultApprovalGateway auto-wiring language from returning. Marked the "does not implement full workflow resume" limitation as resolved by PR #104.
- Made non-transactional `DefaultApprovalGateway` fallback require explicit opt-in (PR #130). The Spring Boot auto-configuration now only creates `DefaultApprovalGateway` when `tramai.sovereign.ops.approval-gateway.non-transactional-fallback-enabled=true`. The transactional `SovereignOpsTransactionalApprovalGateway` remains automatic when the mutation store and request factory are present. Adds auto-configuration test proving the fallback is not created by default and requires explicit property. Adds build guard preventing the fallback from becoming implicit again. Updates golden path guide and ergonomics doc with the opt-in requirement.
- Added Java approval workflow facade to Preview API stability boundary (PR #129). Documents `ApprovalWorkflowResults`, `ApprovalRequestResults`, and `HumanApprovalDecisions` as Preview types in the API stability manifest and boundary doc. Adds `ApprovalWorkflowResults.fromApprovalRequestResult` to the Preview functions manifest. Adds source-file guards in `verifySovereignRuntimeApiBoundary` proving the facade maintains its JVM entrypoint name, String-based factories, `@JvmOverloads` shape, and absence of inline-value-class-returning factories. Adds API boundary test proving the facade delegates to the Kotlin mapper and constructs inline value wrappers from plain Strings.
- Added reusable `TestApprovalGatewayRequestFactory` test fixture in `tramai-engine` test fixtures (PR #125). Provides a builder-based `ApprovalGatewayRequestFactory` for tests and examples that handles all low-level persistence records (approval, continuation, suspended invocation, replay envelope, argument digests) with sensible defaults. Replaces the 150-line `SmokeTestApprovalGatewayRequestFactory` with a 1-liner bean definition. Includes 7 unit tests proving consistency and customization.
- Refactored `RegulatedClaimTriageApprovalGatewayRequestFactory` from ~170 lines of manual low-level record construction to a 52-line thin wrapper over `TestApprovalGatewayPersistenceRequestBuilder` (PR #127). The builder now supports deterministic `approvalId`, `correlationId`, `toolCallId` overrides and a `sensitiveArguments(vararg pairs)` convenience helper. A build guard prevents manual low-level record construction from returning. The fixture is now used by both the smoke proof and regulated scenario.
- Added Java interop proof for approval workflow mapper (PR #128). Adds `ApprovalWorkflowResults.fromApprovalRequestResult()` facade, `ApprovalRequestResults` and `HumanApprovalDecisions` Java-friendly factory objects with String-based parameters (bypassing JVM inline value class name mangling). Adds `@get:JvmName` annotations on `SovereignWorkflowResult.SuspendedForApproval` properties to restore clean getter names. Includes a Java compile/runtime test covering all four outcome types and the decision-aware lambda contract. Documents inline value class JVM erasure behavior in the golden path guide. A build guard prevents the Java interop proof from disappearing.
- Added minimal Spring/JDBC golden path smoke proof (PR #124). `ApprovalGatewaySpringGoldenPathSmokeTest` proves a sovereign workflow can use `ApprovalGateway` + `toWorkflowResult { ... }` with real JDBC persistence, without wiring low-level stores. Includes a source guard that prevents store references from leaking into the workflow class.
- Added API boundary guard for `ApprovalRequestResult.toWorkflowResult` Preview function (PR #123). Lists the mapper in the API stability manifest as a Preview function, documents it in the boundary doc, adds source-file existence and signature-presence checks to `verifySovereignRuntimeApiBoundary`, and adds a focused API-boundary test proving the decision-aware lambda contract.
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
- Refactored the regulated claim triage E2E proof to request human approval through the Preview ApprovalGateway (`PR #99`). The workflow now calls `approvalGateway.requestApproval(...)` instead of manually creating low-level store records. A `RegulatedClaimTriageApprovalGatewayRequestFactory` provides the persistence records. Later hardening changed Spring Boot wiring so JDBC deployments prefer the transactional gateway, while `DefaultApprovalGateway` requires explicit non-transactional fallback opt-in.
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
