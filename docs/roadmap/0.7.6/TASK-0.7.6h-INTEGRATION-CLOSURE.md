# 0.7.6h — Headless Control API, Security Surface Inventory & Epic Acceptance Gate

**Status:** PROPOSED CLOSURE — PENDING OWNER REVIEW AND RATIFICATION  
**Target:** `release/0.7.0`  
**Verified baseline:** `226f78947439bc379e53a38d4d13db8c914b308c`  
**Branch:** `task/0.7.6h-integration-closure`  
**Epic:** [EPIC-0.7.6-AUTH-RUNTIME-CONTROL.md](../0.7.0/EPIC-0.7.6-AUTH-RUNTIME-CONTROL.md)  
**Governing Audit:** [AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md)  
**Preceding Deliverables:**
- 0.7.6b: [TASK-0.7.6b-PRINCIPAL-OPERATOR-CAPABILITY.md](TASK-0.7.6b-PRINCIPAL-OPERATOR-CAPABILITY.md)
- 0.7.6c: [TASK-0.7.6c-OIDC-SPRING-BOUNDARY.md](TASK-0.7.6c-OIDC-SPRING-BOUNDARY.md)
- 0.7.6d: [TASK-0.7.6d-TYPED-CONTROL-COMMANDS.md](TASK-0.7.6d-TYPED-CONTROL-COMMANDS.md)
- 0.7.6e: [TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md](TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md)
- 0.7.6f: [TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md](TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md) (PR #592)
- 0.7.6g: [TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md](TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md) (PR #573) & [TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md](TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md) (PR #593)

---

## 1. Mission and Governing Objective

The mission of milestone 0.7.6h is to complete **Epic 0.7.6 (Authentication, Authorization & Runtime Control)** by:
1. Reconciling delivered authentication, authorization, control, and evidence contracts with their actual runtime behavior.
2. Publishing an authoritative, whole-repository security surface inventory that formally accounts for all endpoints, mutation interfaces, and historical findings (specifically reconciling finding **F3** from [AUDIT-0.7.6a](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md)).
3. Publishing comprehensive, practical headless control plane integration documentation in [sovereign-operator-control-plane.md](../../guides/sovereign-operator-control-plane.md) for Spring Boot consumers.
4. Executing the formal Epic 0.7.6 Acceptance Gate against the five core invariants and acceptance criteria defined in the Epic charter.

**Primary Success Criterion:**
> A Spring Boot consumer can integrate TramAI's supported privileged approval-control API with verified OIDC/JWT identity, server-side authorization, typed version preconditions, and durable evidence, without relying on a dashboard or client-side authorization. No unsupported surface is presented as secured.

---

## 2. Phase 0 — Baseline and Dependency Verification

Before executing 0.7.6h closure, the prerequisite delivery chain was verified against `release/0.7.0`:

- **Preceding PR #592 (0.7.6f Privileged Evidence):** Squashed and merged into `release/0.7.0` at commit `6f2854adbec96061a80dab2e9e30f8f893719f20`. Delivered durable `(issuer, subject)` attribution, V10 schema migration, V3 outbox payload, explicit evidence kinds (`APPROVAL_APPROVED`, `APPROVAL_DENIED`, `AUTHORIZATION_REFUSAL`), refusal isolation, and two-issuer PostgreSQL integration proof.
- **Preceding PR #593 (0.7.6g2 Privileged Control Boundary Adversarial Qualification):** Squashed and merged into `release/0.7.0` at commit `226f78947439bc379e53a38d4d13db8c914b308c`. Delivered 14 adversarial test scenarios (`G2-01` through `G2-14`), real PostgreSQL transaction rollback proof on outbox trigger failures, real PostgreSQL concurrency fencing, mutation experiments `M1` through `M8`, and formal characterization of residual risks.
- **Starting Head:** Verified clean workspace at baseline commit `226f78947439bc379e53a38d4d13db8c914b308c` on branch `task/0.7.6h-integration-closure`.

---

## 3. Phase 1 — Authoritative Security Surface Inventory

### 3.1 Whole-Repository Surface Matrix

The following matrix documents the exact identity, authorization, precondition, and evidence posture of every control endpoint, REST route, and mutation interface across the repository:

| Boundary / Path | Transport & HTTP Method | Identity Enforcement | Authorization Enforcement | Precondition Enforcement | Evidence & Audit Guarantee | Surface Classification | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Sovereign Ops Approval Decisions**<br>`/tramai/sovereign/approvals/{id}/approve`<br>`/tramai/sovereign/approvals/{id}/deny` | REST `POST` | **Verified RS256 JWT** via Spring Security OAuth2 Resource Server (`NimbusJwtDecoder`). Issuer and audience validated; clock skew checked. | **Server-side 2-tier enforcement:**<br>1. L2: `OperatorCapability.DECIDE_APPROVAL`<br>2. L3: `OperatorApprovalPolicy` matches verified `ApproverRole` against `binding.policyVersion`. | **Required non-null `expectedVersion` >= 0** in request body; store CAS fencing. | Atomic transactional outbox write (`APPROVAL_APPROVED` / `APPROVAL_DENIED`), V10 attribution columns (`decision_actor_issuer`, `decision_actor_identity_digest`). Refusal outbox on 403. | **Supported Privileged Control Plane** (`tramai-spring-boot-starter-sovereign-ops-rest`) | **FULLY ENFORCED** |
| **Sovereign Ops Approval Status**<br>`/tramai/sovereign/approvals/{id}` | REST `GET` | **Verified RS256 JWT** via Spring Security OAuth2 Resource Server. Returns HTTP 401 if unauthenticated. | None (any authenticated operator may inspect status and metadata). | None (read-only). | None emitted for read. Exposes metadata only (no raw execution payload). | **Supported Read Surface** (`tramai-spring-boot-starter-sovereign-ops-rest`) | **AUTHENTICATED READ** (Role-based read filtering deferred under F3) |
| **Sovereign Ops Approval Inbox**<br>`/tramai/sovereign/approvals`<br>`/tramai/sovereign/approvals/{id}/work-item` | REST `GET` | **Verified RS256 JWT** via Spring Security OAuth2 Resource Server. Returns HTTP 401 if unauthenticated. | None (returns queryable summaries to any authenticated operator). | Bounded pagination limits (`limit` in 1..100). | None. Exposes safe summaries (no resume tokens, no raw payloads). | **Supported Query Surface** (`tramai-spring-boot-starter-sovereign-ops-rest`) | **AUTHENTICATED QUERY** |
| **Sovereign Ops Approval Resume**<br>`/tramai/sovereign/approvals/{id}/resume` | REST `POST` | **Verified RS256 JWT** at HTTP transport layer under `/tramai/sovereign/**`. | Caller-supplied `resumedBy` string in body; token verification via `ApprovalResumeControlPlane`. | Token match and continuation status check (`expectedApprovalVersion`, `expectedContinuationVersion`). | Continuation resume event emitted. | **Partially Secured Resumption Path** | **AUTHENTICATED TRANSPORT ONLY** (Durable resume fencing deferred to 0.7.7) |
| **Sovereign Ops Reviewer UI**<br>`/tramai/sovereign/reviewer` | Web `GET` (HTML) | **Verified RS256 JWT** at HTTP transport layer under `/tramai/sovereign/**`. | None (serves static SSR HTML shell). | None. | None. All mutations initiated by the UI route back to the headless approve/deny endpoints. | **Supported UI Shell** (`tramai-spring-boot-starter-sovereign-ops-rest`) | **AUTHENTICATED SHELL** (Zero client-side trust) |
| **Internal Server Workflow Engine**<br>`POST /workflows/{name}/run`<br>`DELETE /workflows/{name}/runs/{id}`<br>`GET /workflows/runs/{id}` | REST | **MISSING** | **MISSING** | Run existence / resumable check. | None about caller identity. | **Unpublished Internal Prototype** (`tramai-server`) | **UNSUPPORTED / UNPROTECTED** (Must not be exposed without network perimeter) |
| **Internal Server Workload Registry**<br>`POST /workloads`<br>`PUT /workloads/{id}/metadata`<br>`POST /workloads/{id}/lifecycle` | REST | **MISSING** | **MISSING** | Declaration idempotency / version CAS in `WorkloadRegistrationAuthority`. | None about caller identity. | **Unpublished Internal Prototype** (`tramai-server`) | **UNSUPPORTED / UNPROTECTED** (Must not be exposed without network perimeter) |
| **Internal Server Schedules & Workers**<br>`POST /schedules`<br>`POST /workers/register`<br>`POST /workers/heartbeat` | REST | **MISSING** | **MISSING** | Store-level existence checks. | None. | **Unpublished Internal Prototype** (`tramai-server`) | **UNSUPPORTED / UNPROTECTED** (Must not be exposed without network perimeter) |
| **Internal Server Webhook Ingress**<br>`POST /webhooks/{name}` | REST `POST` | Shared secret HMAC (`X-Hub-Signature-256`). | Signature validity verification against configured secret. | Replay cache check (`GitHubWebhookSignatureVerifier`). | None. | **Machine Ingress Precedent** (`tramai-server`) | **CRYPTO AUTHENTICATED** (Machine webhook, not operator) |
| **Internal Dashboard Settings**<br>`GET /api/settings` | REST `GET` | **MISSING** | None (inspects bean presence in Spring context). | None. | None. | **Unpublished UI Prototype** (`tramai-dashboard`) | **UNSUPPORTED / READ ONLY** |
| **Platform API Key Management**<br>`/api/keys/**` | REST | `ApiKeyAuthenticator` (hashed API keys, `tmr_` prefix). | `ApiKeyScope` (`RUN`, `READ`, `ADMIN`). | Token-bucket rate limiting. | None. | **Unwired Standalone Module** (`tramai-platform`) | **UNWIRED** (No module depends on `tramai-platform`) |
| **Direct Store SPI Mutation**<br>`ApprovalStore.transition(...)` | JVM Method Call | **MISSING** (In-process JVM method) | **MISSING** (In-process JVM method) | Optimistic concurrency version CAS. | None (bypasses outbox). | **Public Core SPI** (`tramai-core`) | **INVENTORIED ONLY** (Guarded by build-time PSI ratchet `verifyDirectApprovalMutations`) |

---

### 3.2 Reconciliation of 0.7.6a Finding F3

In the baseline audit [AUDIT-0.7.6a](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md) (§1.1, §3.1, §6), finding **F3** was designated as a **BLOCKER**:
> *"No authentication or authorization exists on any `tramai-server` control endpoint (workload register/metadata/lifecycle, run, resume, cancel). `grep` for `@PreAuthorize`, `SecurityFilterChain`, `HttpSecurity`, `@RolesAllowed` over `tramai-server`, `tramai-spring*`, `tramai-dashboard` returns nothing but a bean-existence probe."*

During Epic 0.7.6 execution, the architectural scope and boundaries were clarified:
1. **Extraction of the Supported Sovereign Control Plane:**
   - TramAI is a library-first JVM framework. Application consumers embedding TramAI do not run `tramai-server`; they embed `tramai-spring-boot-starter-sovereign-ops-rest` and `tramai-spring-boot-starter-sovereign-persistence-jdbc`.
   - The supported privileged operational control plane was therefore extracted and implemented in `tramai-spring-boot-starter-sovereign-ops-rest` under the base path `/tramai/sovereign/**`.
   - In milestones 0.7.6c, 0.7.6d, 0.7.6e, 0.7.6f, 0.7.6g1, and 0.7.6g2, this supported control plane was completely secured:
     - An `@Order(1)` stateless `SecurityFilterChain` intercepts `/tramai/sovereign/**`, enforcing RS256 JWT validation via `NimbusJwtDecoder`.
     - `ApprovalControlPlaneController` (`POST /{id}/approve` and `POST /{id}/deny`) enforces server-side L2 operator capability (`DECIDE_APPROVAL`) and L3 approver role policy (`OperatorApprovalPolicy` mapped against `binding.policyVersion`).
     - `expectedVersion` was made a non-null, caller-observed precondition.
     - Outbox persistence was coupled transactionally with approval mutations, recording immutable `(issuer, subject)` attribution.
     - 14 adversarial bypass scenarios (`G2-01`–`G2-14`), 7 mutation kills (`M1`–`M4`, `M6`–`M8`), and 1 verified compound-defense mutant (`M5`, preserved as `NOT_EVALUATED`) proved the robustness of these guards.
2. **Status of `tramai-server` Endpoints:**
   - `tramai-server` is an unpublished internal platform prototype (`id("tramai.kotlin-library")`, not published to Maven Central). It contains exploratory controllers (`WorkflowController`, `WorkloadControlPlaneController`, `ScheduleController`, `WorkerController`).
   - These internal server routes were **not** brought into the supported library control plane in Epic 0.7.6.
   - **F3 Reconciliation Decision:** Rather than applying superficial auth filters to internal prototype code, `tramai-server` is officially classified as an **unsupported, internal platform surface**. It must never be published as a library starter, and if deployed in internal environments, it **must be isolated behind a secure reverse-proxy or private network perimeter**.
   - No supported privileged mutation endpoint in the published TramAI library remains unprotected.
3. **Status of Read Endpoints Under `/tramai/sovereign/**`:**
   - `GET /tramai/sovereign/approvals/{id}` and `GET /tramai/sovereign/approvals` (inbox) are protected by OAuth2 authentication (HTTP 401 if unauthenticated).
   - However, they do not enforce fine-grained role-based read filtering (any authenticated operator with a valid JWT may inspect status and query inbox items).
   - Because these endpoints expose only safe metadata and hash digests (never raw tool execution arguments or resume tokens), this read-visibility posture is submitted for owner decision as a conditional deferral, pending multi-tenant domain policy in 0.8.x.

---

### 3.3 Formal Disposition of Residual Findings

The following table records the formal proposed disposition of all residual risks and open items for repository owner ratification:

| Finding / Item | Description | Mitigation in 0.7.6 | Boundary Limitation | Formal Proposed Disposition | Target Milestone |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **F3 (Read Auth)** | Read endpoint `GET /{approvalId}` permits any authenticated operator to view approval status and metadata without role filtering. | OAuth2 JWT authentication required (401 on missing/invalid token); responses strip sensitive execution payloads and resume tokens. | Limited existence and status disclosure to authenticated operators within the same IdP trust realm. | **PROPOSED: CONDITIONAL DEFERRAL (PENDING OWNER DECISION)** — Requires documented deployment perimeter / access restrictions. | 0.8.x (Multi-tenant policy) |
| **F5 (Dynamic Role Persistence)** | `ApproverRole` requested by `ApprovalGateway.requestApproval` is stored as queryable inbox metadata, not an authoritative schema column. | Decisions evaluated against `binding.policyVersion` mapped via server-controlled `OperatorApprovalPolicy`. | Dynamic per-approval required role is not persisted into relational schema column. | **PROPOSED: DEFERRAL (PENDING OWNER DECISION)** — Defer to a separately specified schema/ADR task; retain policy-version enforcement. | 0.8.x |
| **L4 (Direct Store SPI)** | `ApprovalStore.transition(...)` is a public JVM SPI method callable by any in-process code. | Guarded by build-time AST/PSI ratchet `verifyDirectApprovalMutations` enforcing strict allowlist of reviewed call sites. | Build-time syntactic check; does not protect against dynamic reflection, bytecode manipulation, or hostile in-process plugins. | **PROPOSED: BOUNDED ACCEPTANCE (PENDING OWNER DECISION)** — Accept only within trusted in-process code boundary; hostile plugins excluded. | Long-term (JPMS / module boundaries) |
| **Issue #524 (Attestation Slice 2)** | Enforcement-origin attestation Slice 2 (wiring-held capability and store readback). | Slice 1 delivered declaration-safe provenance (`RuntimeBoundaryProvenance` capping unattested strength at `DECLARED`). | Store readback verification of provenance is not implemented. | **PRESERVED AS OWNER DEFERRED** (Issue #524 remains OPEN per 0.7.6g1 ratification) | Post-0.7.6 |
| **M5 (CAS SQL Predicate Mutation)** | Mutation removing `WHERE version = ?` from PostgreSQL JDBC store could not be killed because control plane precondition and row serialization mask the mutant. | Domain precondition check rejects stale commands before SQL; PostgreSQL row serialization prevents race corruption in 10-thread concurrency suite. | Compound defense masks direct SQL predicate deletion in test suite. | **PRESERVED AS NOT_EVALUATED** (Compound defense verified; 7/8 kills reported) | 0.8.x |

---

### 3.4 Invariant Boundary Inherited by Epic 0.7.7

There is a consequential distinction between what Epic 0.7.6 establishes and what subsequent epics inherit:

- **What Epic 0.7.6 Delivers:** A verified, IdP-neutral operator decision boundary for human-in-the-loop approval mutations (`POST /tramai/sovereign/approvals/{id}/approve` and `POST /tramai/sovereign/approvals/{id}/deny`), backed by Spring Security OAuth2 resource server, server-side capability and approver-role mapping (`OperatorAuthority`), caller-observed optimistic version fencing (`expectedVersion >= 0`), and durable transactional outbox evidence (`ActorIdentityDigest`).
- **What Epic 0.7.6 Does NOT Establish:** Universal authorization across every existing lifecycle control endpoint in internal platform prototypes (`tramai-server` workflow/workload endpoints); fine-grained per-role read authorization on approval status or inbox queries (F3); dynamic per-approval role persistence in relational schema columns (F5); or JVM runtime sandboxing against arbitrary in-process SPI invocations (L4).

**Epic 0.7.7 (Execution & Governance Coordination) inherits specifically the verified operator decision boundary and durable attribution contract**, not universal endpoint authorization across non-library prototype modules.

---

## 4. Phase 2 — Headless Integration Architecture

Full integration guidance is published in [docs/guides/sovereign-operator-control-plane.md](../../guides/sovereign-operator-control-plane.md).

### 4.1 Summary of Integration Guarantees

A Spring Boot application integrating `tramai-spring-boot-starter-sovereign-ops-rest` obtains:
1. **Zero UI / Headless Operation:** The control plane is completely headless. No dashboard, browser, or client-side JavaScript is required. Automated CI/CD pipelines, external approval engines, or CLI tools can execute privileged decisions via standard HTTP REST requests.
2. **Fail-Closed Security Posture:**
   - Setting `tramai.sovereign.ops.rest-control-plane-enabled=true` without `tramai.sovereign.ops.security.enabled=true` triggers an immediate `IllegalStateException` on application startup: `tramai-rest-control-plane-requires-operator-security`.
   - Enabling security without configuring an active `OperatorApprovalPolicy` triggers startup failure: `tramai-rest-control-plane-requires-approval-policy`.
3. **IdP-Neutral Authentication:** Standard RS256 OAuth2 JWT verification using Nimbus. Compatible with Entra ID, Okta, Keycloak, Auth0, or custom identity providers publishing a standard JWKS endpoint.
4. **Server-Side Authorization:** Identity claims (`iss`, `sub`, and custom role claims) are extracted and mapped server-side via `role-mappings` to `OperatorCapability.DECIDE_APPROVAL` and `approver-role-mappings` to `ApproverRole`. Untrusted HTTP request headers or body fields attempting to assert actor identity or roles are strictly ignored.
5. **Caller-Observed Optimistic Concurrency:** Callers must provide `expectedVersion` in the request body. If the approval has advanced or been decided concurrently, the request is rejected with `409 Conflict` (`STALE`), preventing lost updates and silent overwrites.
6. **Transactional Audit Evidence:** State transitions and outbox audit records are committed in the same database transaction. Refusals emit isolated refusal audit events without leaking state-transition metadata.

---

## 5. Phase 3 — Epic 0.7.6 Acceptance Gate

### 5.1 Evaluation Against Core Epic Invariants

| # | Invariant | Enforcement Mechanism | Verification Evidence & Test Mapping | Gate Verdict |
|---|---|---|---|:---:|
| **I1** | **UI visibility != authorization** | Reviewer UI is a static HTML shell; all mutations invoke `POST /tramai/sovereign/approvals/{id}/approve` or `/deny`. Server-side `OperatorAuthority` evaluation occurs independently of UI presence. | • Unit: `ApprovalControlPlaneControllerTest`<br>• HTTP Integration: `PrivilegedControlBoundaryAdversarialTest` (scenario `G2-01`: unauthenticated UI caller rejected with 401). | **PASS** |
| **I2** | **Privileged mutation requires authenticated actor + server-side authorization** | Spring Security OAuth2 resource server filter chain validates JWT; `ApprovalControlPlaneController` delegates to `SovereignOpsApprovalDecisionControlPlane`, which checks L2 capability (`DECIDE_APPROVAL`) and L3 approver role policy against `binding.policyVersion`. | • Unit/Resolver: `OperatorPrincipalResolverTest`, `OperatorCapabilityMappingTest`<br>• HTTP Integration: `OperatorAuthenticationHttpTest`, `PrivilegedControlBoundaryAdversarialTest` (scenarios `G2-01` [401], `G2-02` [403 `MISSING_CAPABILITY`], `G2-03` [403 `ROLE_NOT_PERMITTED`], `G2-11` [403 `APPROVAL_POLICY_UNAVAILABLE`])<br>• Mutations: M1, M2, M6 KILLED. | **PASS** |
| **I3** | **Stale command cannot silently overwrite newer authoritative state** | Controller rejects missing or negative `expectedVersion` with 400 `INVALID_PRECONDITION`. Control plane checks `command.expectedVersion == current.version` returning 409 `STALE`. Storage CAS provides final fence. | • Controller: `ApprovalControlPlaneControllerTest` (precondition rejection)<br>• HTTP Integration: `PrivilegedControlBoundaryAdversarialTest` (scenarios `G2-06` [400], `G2-07` [409])<br>• Real PostgreSQL Concurrency: `PrivilegedControlBoundaryAdversarialTest` (`G2-08`, `G2-09`: 10 concurrent threads, 1 winner)<br>• Mutations: M4 KILLED, M5 NOT_EVALUATED (compound defense). | **PASS** |
| **I4** | **Dashboard/client cannot directly mutate authoritative persistence** | Public `ApprovalStore.transition(...)` method bounded by `verifyDirectApprovalMutations` PSI ratchet. Direct calls outside allowlisted packages fail the Gradle build. Client-supplied actor strings in body are ignored. | • HTTP Integration: `OperatorTrustBoundarySubstitutionTest`, `PrivilegedControlBoundaryAdversarialTest` (scenario `G2-04`: spoofed body actor ignored)<br>• Build Ratchet: `verifyDirectApprovalMutations`<br>• Mutations: M3 KILLED. | **PASS** |
| **I5** | **Operator capability != runtime capability** | Operator capabilities (`dev.tramai.core.operator.OperatorCapability`) are closed, immutable enums strictly separated from tool, MCP, model, and network scopes. | • Core Contracts: `OperatorCapabilityTest`, `OperatorCapabilityMappingTest`<br>• Zero runtime tool scopes exposed on operator principal. | **PASS** |

---

### 5.2 Evaluation Against Epic Acceptance Criteria

| # | Acceptance Criterion | Executable Test Mapping (Unit, HTTP, Persistence) | Gate Verdict |
|---|---|---|:---:|
| **AC1** | **Supported privileged operations are usable headlessly.** | • **Privileged Mutations (`POST /{id}/approve`, `POST /{id}/deny`):** Fully operable headlessly with typed version preconditions and server-side authorization. Tested in `ApprovalControlPlaneControllerTest` (direct controller) and `OperatorAuthenticationHttpTest`, `PrivilegedControlBoundaryAdversarialTest` (full HTTP filter chain) — **PASS**.<br>• **Read Operations (`GET /{id}`, `GET /approvals`):** Operable headlessly with JWT auth, but lack fine-grained per-role read authorization (F3 residual submitted for conditional deferral) — **PARTIAL**. | **PARTIAL** (Mutations: PASS / Read: PARTIAL) |
| **AC2** | **Authorization is enforced server-side and is IdP-neutral at the governance boundary.** | • **IdP-Neutral Authentication:** Nimbus RS256 JWT decoding over JWKS, tested in `OperatorAuthenticationHttpTest` and `PrivilegedControlBoundaryAdversarialTest` (G2-01).<br>• **Server-Side Policy Mapping:** Verified claims mapped to `OperatorCapability` and `ApproverRole` by `OperatorPrincipalResolver`; tested in `OperatorCapabilityMappingTest`, `OperatorTrustBoundarySubstitutionTest`, and adversarial scenarios G2-02, G2-03, G2-11. | **PASS** |
| **AC3** | **Successful controls are attributable and reconstructable.** | • **V10 Relational Schema & Outbox:** `approvals` columns (`decision_actor_issuer`, `decision_actor_identity_digest`) and `audit_outbox` transactional coupling tested in `JdbcSovereignOpsApprovalProvenanceTest` and `ApprovedResumeLifecycleJdbcE2ETest`.<br>• **Canonical Binary Digest:** Domain-separated, length-prefixed UTF-8 digest verified in `ActorIdentityDigestTest`.<br>• **Transactional Rollback:** Real PostgreSQL trigger failure rollback verified in `PrivilegedControlBoundaryAdversarialTest` (scenario `G2-12`). | **PASS** |
| **AC4** | **Direct-store/client bypasses are absent from supported control paths.** | • **Supported REST Control Paths:** Client-asserted identity in request body/headers is strictly ignored; tested in `OperatorTrustBoundarySubstitutionTest` and scenario `G2-04` — **PASS**.<br>• **In-Process JVM Direct Store SPI:** Raw `ApprovalStore.transition(...)` is bounded by AST/PSI build-time ratchet (`verifyDirectApprovalMutations`) within trusted codebase, but runtime JVM sandboxing/JPMS boundary is deferred (L4 residual submitted for bounded acceptance) — **PARTIAL**. | **PARTIAL** (Supported Paths: PASS / JVM SPI: PARTIAL) |
| **AC5** | **Operator authorization vocabulary is unambiguous and does not become canonical for runtime capabilities.** | • `OperatorCapability` enum (`DECIDE_APPROVAL`, `RESUME_APPROVAL`, `INSPECT_GOVERNANCE`, `ADMINISTER_OPERATORS`) is closed, immutable, audit-specific, and completely distinct from `tramai-core` tool or MCP policies.<br>• Verified in `OperatorCapabilityTest`, `OperatorCapabilityMappingTest`. | **PASS** |

---

### 5.3 Epic 0.7.6 Milestone Delivery Ledger

| Milestone | Scope / Deliverable | Status | Git Commit / PR | Key Artifacts |
| :--- | :--- | :--- | :--- | :--- |
| **0.7.6a** | Security/Control Baseline Audit | **DELIVERED** | `0c3a8120` | [AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md) |
| **0.7.6b** | Principal + Operator Capability Contract | **DELIVERED** | `b78292f1` | [TASK-0.7.6b-PRINCIPAL-OPERATOR-CAPABILITY.md](TASK-0.7.6b-PRINCIPAL-OPERATOR-CAPABILITY.md) |
| **0.7.6c** | OIDC / Spring Security Boundary | **DELIVERED** | `445a5573` | [TASK-0.7.6c-OIDC-SPRING-BOUNDARY.md](TASK-0.7.6c-OIDC-SPRING-BOUNDARY.md) |
| **0.7.6d** | Typed Control Commands & Version Preconditions | **DELIVERED** | `e1d52ce0` | [TASK-0.7.6d-TYPED-CONTROL-COMMANDS.md](TASK-0.7.6d-TYPED-CONTROL-COMMANDS.md) |
| **0.7.6e** | Runtime Authorization Enforcement | **DELIVERED** | `440d63fc` | [TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md](TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md) |
| **0.7.6f** | Privileged Evidence & Durable Attribution | **DELIVERED** | PR #592 (`6f2854ad`) | [TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md](TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md) |
| **0.7.6g1** | Enforcement-Origin Contract (Slice 1) | **DELIVERED** | PR #573 (`0e9e42e4`) | [TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md](TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md) |
| **0.7.6g2** | Privileged Control Boundary Adversarial Qualification | **DELIVERED** | PR #593 (`226f7894`) | [TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md](TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md) |
| **0.7.6h** | Headless Control API, Security Docs & Epic Acceptance Gate | **PROPOSED CLOSURE** | Active Branch | [TASK-0.7.6h-INTEGRATION-CLOSURE.md](TASK-0.7.6h-INTEGRATION-CLOSURE.md), [sovereign-operator-control-plane.md](../../guides/sovereign-operator-control-plane.md) |

---

## 6. Conclusion and Merge Authorization Notice

All milestones of **Epic 0.7.6 (Authentication, Authorization & Runtime Control)** are implemented, adversarially qualified, and fully documented.

Per `AGENTS.md` and repository governance rules:
- **Strict Merge Hold:** This pull request targets `release/0.7.0`. Automated CI passing is necessary but never sufficient.
- **Merge Authorization:** Merging this PR into `release/0.7.0` requires explicit authorization from the repository owner after independent review of this document and the accompanying integration guide.
