# Epic 0.7.6 — Formal Security-Risk Acceptance Record

**Status:** PROPOSED — PENDING EXPLICIT REPOSITORY-OWNER RATIFICATION
**Target:** `release/0.7.0`
**Verified Baseline:** `19bb92c944a91fec98f83896c5c58f07f2fde113`
**Governing Epic:** [EPIC-0.7.6-AUTH-RUNTIME-CONTROL.md](../0.7.0/EPIC-0.7.6-AUTH-RUNTIME-CONTROL.md)
**Governing Baseline Audit:** [AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md)
**Governing Delivery Milestones:**
- 0.7.6a: [AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md) (`0c3a8120`)
- 0.7.6b: [TASK-0.7.6b-PRINCIPAL-OPERATOR-CAPABILITY.md](TASK-0.7.6b-PRINCIPAL-OPERATOR-CAPABILITY.md) (`b78292f1`)
- 0.7.6c: [TASK-0.7.6c-OIDC-SPRING-BOUNDARY.md](TASK-0.7.6c-OIDC-SPRING-BOUNDARY.md) (`445a5573`)
- 0.7.6d: [TASK-0.7.6d-TYPED-CONTROL-COMMANDS.md](TASK-0.7.6d-TYPED-CONTROL-COMMANDS.md) (`e1d52ce0`)
- 0.7.6e: [TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md](TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md) (`440d63fc`)
- 0.7.6f: [TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md](TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md) (PR #592, `6f2854ad`)
- 0.7.6g1: [TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md](TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md) (PR #573, `0e9e42e4`)
- 0.7.6g2: [TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md](TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md) (PR #593, `226f7894`)
- 0.7.6h: [TASK-0.7.6h-INTEGRATION-CLOSURE.md](TASK-0.7.6h-INTEGRATION-CLOSURE.md) (PR #594, `19bb92c9`)

---

## 1. Executive Summary & Governance Context

Milestones 0.7.6a through 0.7.6h of **Epic 0.7.6 (Authentication, Authorization & Runtime Control)** implemented, adversarially qualified, and documented a complete, IdP-neutral, server-authorized privileged control plane for human-in-the-loop approval decisions.

Under TramAI's strict engineering governance (`AGENTS.md`), green CI builds, test suites, and merged code prove technical implementation and test passage; they **never constitute authorization to accept residual security risks or close an Epic security gate on behalf of the repository owner**.

This document is the formal security-risk acceptance record for Epic 0.7.6. It establishes:
1. Complete, transparent profiles for residual security risks **F3**, **F5**, and **L4**, detailing their historical audit origin, delivered mitigations, exact residual exposure, operational compensating controls, and proposed dispositions.
2. Formal accounting of deferred attestation scope (**Issue #524**) and mutation qualification limits (**Mutation M5**).
3. The precise technical justification for the **PARTIAL** status of Acceptance Criteria **AC1** and **AC4**.
4. An explicit ratification boundary awaiting repository-owner decision.

**Overall Epic 0.7.6 Status:**
> `🟡 Implementation Complete — Security Acceptance Pending Owner Ratification`

---

## 2. Formal Residual Security Risk Profiles

### 2.1 Risk F3: Unauthenticated / Unfiltered Read Access & Unprotected Prototype Endpoints

| Attribute | Specification |
| :--- | :--- |
| **Risk Identifier** | **F3** |
| **Classification** | Residual Read-Visibility Gap & Internal Prototype Scope Boundary |
| **Original Audit Reference** | [AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md) (§1.1, §3.1, §6) — flagged as BLOCKER: *"No authentication or authorization exists on any `tramai-server` control endpoint... grep for `@PreAuthorize`, `SecurityFilterChain` returns nothing but a bean-existence probe."* |
| **Delivered Enforcement in 0.7.6** | 1. **Supported Control Plane Extraction:** Supported privileged operations were extracted into `tramai-spring-boot-starter-sovereign-ops-rest` under `/tramai/sovereign/**`.<br>2. **Mandatory OAuth2 Authentication:** An `@Order(1)` stateless `SecurityFilterChain` intercepts `/tramai/sovereign/**`, requiring valid RS256 JWT tokens via `NimbusJwtDecoder`. Requests lacking valid tokens return HTTP 401.<br>3. **Full Mutation Authorization:** Privileged decision endpoints (`POST /{id}/approve`, `POST /{id}/deny`) enforce server-side L2 operator capability (`DECIDE_APPROVAL`) and L3 approver-role policy against `binding.policyVersion`.<br>4. **Payload Sanitization on Read:** Read endpoints (`GET /{id}`, `GET /approvals`, `GET /{id}/work-item`) redact raw execution payloads and resume tokens, exposing only safe metadata and hash digests. |
| **Delivered Verification Evidence** | • `OperatorAuthenticationHttpTest`: proves 401 on unauthenticated access across filter chain.<br>• `PrivilegedControlBoundaryRestAdversarialTest` (scenario `G2-01`): proves unauthenticated approval control is rejected with 401.<br>• `PrivilegedControlBoundaryAdversarialTest` (scenarios `G2-02`, `G2-03`, `G2-11`): proves 403 on missing capability, missing role, or unconfigured policy.<br>• Mutations `M1`, `M2`, `M6` KILLED. |
| **Exact Residual Risk** | 1. **Supported Read Surface:** Read endpoints (`GET /tramai/sovereign/approvals/{id}` and `/approvals` inbox) enforce OAuth2 JWT authentication but do not enforce fine-grained role-based read visibility. Any authenticated operator within the same OIDC trust realm may query approval status and inbox metadata.<br>2. **Internal Prototype Surface:** `tramai-server` internal endpoints (`/workflows/**`, `/workloads/**`, `/schedules/**`, `/workers/**`) remain unauthenticated prototypes. |
| **Proposed Disposition** | **CONDITIONAL DEFERRAL (PENDING OWNER DECISION)** |
| **Proposed Target Milestone** | **Epic 0.8.0** (Multi-Tenant Domain Policy & Read Authorization) |
| **Compensating Controls & Operational Restrictions** | 1. **Trust Realm Scope:** Deploy `tramai-spring-boot-starter-sovereign-ops-rest` only within an organizational trust boundary where all authenticated operators possessing valid JWTs are cleared to view approval metadata.<br>2. **Sanitized Exposure:** Verify that application configurations do not place raw sensitive secrets in queryable approval metadata.<br>3. **Network Isolation for Internal Server:** `tramai-server` is an unpublished internal module (`id("tramai.kotlin-library")`, not published to Maven Central). It **must never be exposed to public networks** and must reside behind an isolated private network perimeter or mTLS reverse-proxy. |
| **Unsupported Deployment Conditions** | • Deployments requiring multi-tenant read partitioning among different operator groups on the same TramAI instance are **UNSUPPORTED** in 0.7.x.<br>• Direct public exposure of `tramai-server` is **UNSUPPORTED**. |
| **Tracked Issue / Follow-up** | Issue #530 / Epic 0.8.0 Read Authorization. |
| **Invalidation Conditions** | This risk profile is invalidated if read endpoints begin exposing raw tool payloads, decrypted credentials, or resume tokens, or if `tramai-server` is published as a supported library artifact. |
| **Owner Ratification Status** | **PROPOSED — PENDING EXPLICIT REPOSITORY-OWNER RATIFICATION**<br>*Formal Owner Decision: [Pending]* |

---

### 2.2 Risk F5: Dynamic Approver-Role Relational Schema Persistence

| Attribute | Specification |
| :--- | :--- |
| **Risk Identifier** | **F5** |
| **Classification** | Relational Persistence & Dynamic Role Schema Gap |
| **Original Audit Reference** | [AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md) (§1.1, §3.3) — flagged as BLOCKER: *"Enforcement of the declared approver role (`requiredRole` is requested and stored, never compared)."* |
| **Delivered Enforcement in 0.7.6** | 1. **Server-Side Policy Enforcement:** `OperatorApprovalPolicy` enforces server-controlled approver roles mapped against `binding.policyVersion` (`policyVersion` in `ApprovalDecisionCommand`).<br>2. **Startup Fail-Closed Validation:** Application startup fails closed with `tramai-rest-control-plane-requires-approval-policy` if security is enabled without a policy, and with `tramai-approval-policy-unsatisfiable` if configured approver roles cannot be satisfied.<br>3. **Untrusted Input Ignored:** Request body/header assertions attempting to supply or override approver roles are strictly ignored. |
| **Delivered Verification Evidence** | • `OperatorApprovalPolicyTest`: proves role-to-policy-version matching logic.<br>• `PrivilegedControlBoundaryAdversarialTest` (scenario `G2-03`): proves 403 `ROLE_NOT_PERMITTED` when operator lacks required role for policy version.<br>• Mutation `M2` KILLED. |
| **Exact Residual Risk** | When a workflow initiates an approval request via `ApprovalGateway.requestApproval(..., requiredRole = "...")`, the requested `requiredRole` is stored only inside `approvals.metadata_json` as queryable inbox metadata. It is **not** persisted as an authoritative relational schema column (`approvals.required_role`). Consequently, runtime authorization decisions evaluate the server-configured `OperatorApprovalPolicy` bound to the approval's `policyVersion`, rather than dynamically comparing against an immutable relational column per approval instance. |
| **Proposed Disposition** | **DEFERRAL TO SCHEMA/ADR TASK (PENDING OWNER DECISION)** |
| **Proposed Target Milestone** | **Epic 0.8.0** (Relational Schema Migration V11 & Dynamic Policy ADR) |
| **Compensating Controls & Operational Restrictions** | 1. **Policy-Version Mapping:** Workloads requiring distinct approver roles must map separate, immutable policy versions via server configuration (`tramai.sovereign.ops.security.approver-role-mappings`).<br>2. **Client Understanding:** Integrating applications must understand that client-requested `requiredRole` strings in `ApprovalRequest` serve inbox presentation and query indexing, while authoritative authorization is governed by server-side policy version binding. |
| **Unsupported Deployment Conditions** | • Workloads requiring ad-hoc, dynamic per-approval approver roles that cannot be mapped to server-controlled policy versions are **UNSUPPORTED** in 0.7.x. |
| **Tracked Issue / Follow-up** | Issue #531 / Epic 0.8.0 Dynamic Approver-Role Schema. |
| **Invalidation Conditions** | This risk profile is invalidated if application code or documentation claims that dynamic `requiredRole` strings in `ApprovalRequest` provide authoritative access control. |
| **Owner Ratification Status** | **PROPOSED — PENDING EXPLICIT REPOSITORY-OWNER RATIFICATION**<br>*Formal Owner Decision: [Pending]* |

---

### 2.3 Risk L4: In-Process Direct-Store SPI Mutation Boundary

| Attribute | Specification |
| :--- | :--- |
| **Risk Identifier** | **L4** |
| **Classification** | In-Process JVM Surface / Architectural Boundary Limitation |
| **Original Audit Reference** | [AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md](AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md) (§1.1, §3.5) — flagged as BLOCKER: *"Direct store mutation bypasses authorization and outbox evidence (`ApprovalStore.transition` callable directly)."* |
| **Delivered Enforcement in 0.7.6** | 1. **Syntactic AST/PSI Ratchet:** Build-time ratchet `verifyDirectApprovalMutations` parses all Kotlin/Java source files in the repository, maintaining a strict zero-allowlist policy outside reviewed internal call sites (`SovereignOpsApprovalDecisionControlPlane`, migration fixtures, and test harnesses). Direct calls from unauthorized packages fail `./gradlew verifyDirectApprovalMutations` and `verifyPr`.<br>2. **Headless Control Plane Gate:** Supported REST control paths strictly route mutations through `SovereignOpsApprovalDecisionControlPlane`, which mints `OperatorAuthority` and couples state transitions with transactional outbox emission.<br>3. **Ignored Client Assertions:** Controller rejects caller-supplied actor identity and spoofed headers (adversarial scenario `G2-04`, mutation `M3` KILLED). |
| **Delivered Verification Evidence** | • `verifyDirectApprovalMutations` Gradle task: passes cleanly in CI and blocks direct store mutation call sites.<br>• `OperatorTrustBoundarySubstitutionTest`: proves client-supplied body identity is ignored.<br>• `PrivilegedControlBoundaryRestAdversarialTest` (scenario `G2-04`): proves spoofed headers cannot mint authority.<br>• Mutation `M3` KILLED. |
| **Exact Residual Risk** | `ApprovalStore` is a public Kotlin/JVM interface in `tramai-core` (`dev.tramai.core.approval.ApprovalStore`). Because TramAI is an embedded JVM library without Java Platform Module System (JPMS) strong encapsulation or runtime bytecode sandboxing, in-process JVM code running in the same classloader could theoretically invoke `ApprovalStore.transition(...)` directly via reflection or direct references, bypassing operator authorization and outbox audit emission. |
| **Proposed Disposition** | **BOUNDED ACCEPTANCE (PENDING OWNER DECISION)** |
| **Proposed Target Milestone** | **TramAI 1.0** (JPMS Strong Encapsulation & Core SPI Segregation) |
| **Compensating Controls & Operational Restrictions** | 1. **Trusted In-Process Boundary:** TramAI's security architecture explicitly assumes a **trusted in-process host application**. Untrusted code, untrusted plugins, or malicious third-party dependencies sharing the same JVM memory space are excluded from the threat model.<br>2. **CI AST Ratchet:** The `verifyDirectApprovalMutations` Gradle task remains a required CI quality gate to prevent accidental or unauthorized direct-store call sites from being introduced into the repository. |
| **Unsupported Deployment Conditions** | • Multi-tenant or pluggable execution models running untrusted, hostile code in the same JVM without sandbox boundaries are **UNSUPPORTED**. |
| **Tracked Issue / Follow-up** | Architectural Debt Ledger L4 / TramAI 1.0 JPMS boundary. |
| **Invalidation Conditions** | This risk profile is invalidated if `verifyDirectApprovalMutations` is removed or weakened in CI, or if supported REST controllers bypass `SovereignOpsApprovalDecisionControlPlane`. |
| **Owner Ratification Status** | **PROPOSED — PENDING EXPLICIT REPOSITORY-OWNER RATIFICATION**<br>*Formal Owner Decision: [Pending]* |

---

## 3. Formal Accounting of Deferred Scope & Qualification Limitations

### 3.1 Issue #524: Enforcement-Origin Attestation Slice 2 (Deferred)

In milestone 0.7.6g1 ([TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md](TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md), PR #573), the enforcement-origin architecture was established:
- **Slice 1 (Delivered):** Implemented declaration-safe provenance (`RuntimeBoundaryProvenance.of`), ensuring that unverified, caller-asserted, or wiring-held credentials cannot elevate origin strength beyond `DECLARED`.
- **Slice 2 (Owner-Deferred):** Store readback verification of provenance tokens and cryptographic attestation against hardware or platform roots.
- **Governance Status:** Preserved as **OWNER DEFERRED**. Issue #524 remains open. It is not an unaddressed defect, but an explicitly ratified milestone deferral.

### 3.2 Mutation M5: PostgreSQL JDBC CAS SQL Predicate Mutation (Not Evaluated)

In milestone 0.7.6g2 ([TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md](TASK-0.7.6g2-PRIVILEGED-CONTROL-ADVERSARIAL-QUALIFICATION.md), PR #593), mutation qualification evaluated 8 targeted architectural mutations (`M1`–`M8`):
- **7 of 8 Mutations KILLED:** `M1` (Capability enforcement), `M2` (Approver-role policy), `M3` (Authority substitution), `M4` (Stale-version precondition), `M6` (Missing-policy fail-closed), `M7` (Refusal evidence isolation), and `M8` (Enforcement-origin strength) were all confirmed KILLED with executable failure evidence.
- **Mutation M5 (`NOT_EVALUATED`):** Mutation M5 removed the `AND version = ?` predicate from the SQL update statement in `JdbcSovereignOpsApprovalMutationStore.kt`. This mutation could not be killed in isolation because:
  1. The domain precondition guard in `SovereignOpsApprovalDecisionControlPlane` rejects stale commands before they reach the database (verified by `M4`).
  2. PostgreSQL's row-level transaction serialization prevents concurrent update races from corrupting state.
- **Governance Status:** Preserved as **NOT_EVALUATED**. The combined concurrency defense was independently proven in `PrivilegedControlBoundaryConcurrencyQualificationTest` (scenarios `G2-08` and `G2-09`, 10 concurrent threads against real PostgreSQL with zero state corruption or lost updates).

---

## 4. Acceptance Criteria Qualifications (AC1 and AC4)

The Epic 0.7.6 charter defines five core acceptance criteria. In accordance with the engineering evidence protocol (`AGENTS.md`), criteria with bounded or layered evidence are rigorously classified as **PARTIAL** rather than claimed as fully satisfied:

### 4.1 Acceptance Criterion AC1: Supported Privileged Operations Usable Headlessly (PARTIAL)

- **Requirement:** Supported privileged operations are operable headlessly without browser, dashboard, or client-side trust.
- **Delivered & Verified (PASS):**
  - Privileged mutations (`POST /tramai/sovereign/approvals/{id}/approve` and `POST /tramai/sovereign/approvals/{id}/deny`) are completely headless. They enforce RS256 JWT authentication, typed `expectedVersion` preconditions, server-side capability authorization, and emit transactional outbox audit records.
  - Integration guide [sovereign-operator-control-plane.md](../../guides/sovereign-operator-control-plane.md) provides complete curl/HTTP examples.
- **Boundary Qualification (PARTIAL Justification):**
  1. **Layered Integration vs. Single Composed Suite:** The HTTP security filter chain is verified in `OperatorAuthenticationHttpTest` (via MockMvc on an endpoint). The full OIDC verification, controller execution, PostgreSQL JDBC persistence, outbox dispatch, and audit reconstruction chain is verified in `TwoIssuerOidcJdbcEvidenceAttributionEndToEndTest` (invoking the controller directly). While layered evidence comprehensively proves each boundary, a single test suite executing an HTTP client against a live HTTP port connected to real PostgreSQL and outbox dispatch was not run.
  2. **Read Operations (F3):** Read endpoints (`GET /{id}` and `/approvals` inbox) enforce OAuth2 authentication but lack fine-grained per-role read authorization (residual F3).
- **Formal Status:** **PARTIAL** (Privileged Mutations: PASS; End-to-End Composed Suite & Read Auth: PARTIAL).

### 4.2 Acceptance Criterion AC4: Direct-Store / Client Bypasses Absent (PARTIAL)

- **Requirement:** Direct-store and client-side bypasses are absent from supported control paths.
- **Delivered & Verified (PASS):**
  - Supported REST control paths strictly reject or ignore caller-supplied actor identity, spoofed headers, and bypass attempts (adversarial scenario `G2-04`, mutation `M3` KILLED).
  - Preconditions fail closed on missing or stale versions (`G2-06`, `G2-07`, mutation `M4` KILLED).
- **Boundary Qualification (PARTIAL Justification):**
  - The public JVM SPI method `ApprovalStore.transition(...)` in `tramai-core` is guarded within the repository by the AST/PSI build ratchet `verifyDirectApprovalMutations`. However, runtime JVM bytecode sandboxing against reflection or arbitrary in-process invocation is not provided (residual L4).
- **Formal Status:** **PARTIAL** (Supported Control Paths: PASS; In-Process JVM SPI Sandboxing: PARTIAL).

---

## 5. Summary Table: Epic 0.7.6 Invariants and Acceptance Criteria

| Item | Description | Delivered Mechanism | Verification Evidence | Status |
| :--- | :--- | :--- | :--- | :---: |
| **I1** | UI visibility != authorization | Headless REST endpoints; Reviewer UI is an unprivileged SSR shell | `ApprovalControlPlaneControllerTest`, `G2-01` | **PASS** |
| **I2** | Privileged mutation requires auth + authz | OAuth2 filter chain + L2 capability + L3 approver-role policy | `G2-01`, `G2-02`, `G2-03`, `G2-11`, M1, M2, M6 | **PASS** |
| **I3** | Stale command cannot overwrite newer state | Mandatory `expectedVersion >= 0` + control-plane check + store CAS | `G2-06`, `G2-07`, `G2-08`, `G2-09`, M4, M5 | **PASS** |
| **I4** | Client cannot directly mutate persistence | Controller ignores client body actor; AST ratchet guards SPI | `G2-04`, `verifyDirectApprovalMutations`, M3 | **PASS** |
| **I5** | Operator capability != runtime capability | Closed `OperatorCapability` enum distinct from tool/MCP policies | `OperatorCapabilityContractTest`, `OperatorCapabilityMappingTest` | **PASS** |
| **AC1** | Headless usability | Supported REST decision endpoints operable via standard HTTP | `TwoIssuerOidcJdbcEvidenceAttributionEndToEndTest` | **PARTIAL** |
| **AC2** | Server-side IdP-neutral authorization | RS256 JWKS validation; server-side claim mapping to capability/role | `OperatorAuthenticationHttpTest`, `G2-02`, `G2-03`, `G2-05` | **PASS** |
| **AC3** | Attributable & reconstructable | V10 relational schema + V3 outbox + canonical `ActorIdentityDigest` | `TwoIssuerOidcJdbcEvidenceAttributionEndToEndTest`, `G2-12` | **PASS** |
| **AC4** | Bypasses absent | REST controllers secure; AST ratchet guards direct store calls | `G2-04`, `verifyDirectApprovalMutations`, M3 | **PARTIAL** |
| **AC5** | Unambiguous operator vocabulary | Immutable enum strictly segregated from tool/model capabilities | `OperatorCapabilityContractTest` | **PASS** |

---

## 6. Formal Owner Ratification Ledger

The proposed dispositions for residual security risks F3, F5, and L4, the deferral of Issue #524, and the qualification bounds of M5, AC1, and AC4 are formally submitted for repository-owner review:

```text
========================================================================================
EPIC 0.7.6 SECURITY-RISK ACCEPTANCE DECISION LEDGER
========================================================================================
Risk F3 (Read Auth & Prototype Boundary):    [ PENDING OWNER RATIFICATION ]
Risk F5 (Dynamic Role Schema Persistence):  [ PENDING OWNER RATIFICATION ]
Risk L4 (In-Process Direct Store SPI):      [ PENDING OWNER RATIFICATION ]
Issue #524 (Attestation Slice 2 Deferral):  [ PENDING OWNER RATIFICATION ]
Mutation M5 (CAS SQL Predicate Boundary):   [ PENDING OWNER RATIFICATION ]
AC1 & AC4 Partial Status Acceptance:        [ PENDING OWNER RATIFICATION ]
----------------------------------------------------------------------------------------
Overall Epic 0.7.6 Security Acceptance:     [ PENDING OWNER RATIFICATION ]
Repository Owner Signature:                 _______________________________
Date:                                       ____-____-____
========================================================================================
```

Until the repository owner explicitly ratifies this ledger, Epic 0.7.6 remains in status:
**🟡 Implementation Complete — Security Acceptance Pending Owner Ratification**.
