# Sovereign Operator Control Plane Integration Guide

This guide describes how to integrate and operate TramAI's headless privileged approval-control API in a Spring Boot application.

The Sovereign Operator Control Plane allows backend systems, external approval portals, automated orchestration pipelines, and administrative CLI tools to query and resolve human-in-the-loop approvals using signed JSON Web Tokens (OIDC/OAuth2), server-enforced authorization policies, optimistic concurrency version preconditions, and durable transactional audit evidence.

---

## 1. Architectural Principles and Trust Model

The Sovereign Operator Control Plane is designed around four non-negotiable guarantees:

1. **Headless-First (Zero UI Dependency):**
   The control plane is an API-first REST service. It does not require a browser, dashboard, or client-side JavaScript. External systems interact directly over HTTP with standard JSON payloads.
2. **Strict Server-Side Authorization:**
   Client requests never assert authority. HTTP headers and request body fields attempting to claim actor identity, operator capabilities, or approver roles are completely ignored. Identity is derived exclusively from a verified cryptographic signature in the presented JWT, and permissions are mapped exclusively through server-side configuration.
3. **Optimistic Concurrency and Precondition Fencing:**
   Callers must provide their observed version (`expectedVersion`) on every decision. Stale commands (e.g. resolving an approval that was already decided, expired, or advanced by a concurrent actor) are rejected with `409 Conflict` (`STALE`), preventing race conditions and silent overwrites.
4. **Transactionally Coupled Audit Outbox:**
   Approval mutations commit atomically with an outbox audit record in a single database transaction. If the outbox write fails, the approval state transition rolls back completely. Authorization refusals generate isolated refusal audit records without mutating approval state.

```text
HTTP Client (CLI / Workflow Engine / Portal)
   │
   ▼ Bearer <JWT>
[ Spring Security OAuth2 Filter Chain ]
   │ 1. Verify RS256 signature against trusted JWKS
   │ 2. Validate issuer, audience, and expiration
   ▼
[ OperatorPrincipalResolver ]
   │ 3. Extract claims (iss, sub, roles)
   │ 4. Map server-configured OperatorCapability and ApproverRole
   │ 5. Issue immutable OperatorAuthority
   ▼
[ SovereignOpsApprovalDecisionControlPlane ]
   │ 6. Check L2 Capability (OperatorCapability.DECIDE_APPROVAL)
   │ 7. Check L3 Policy (ApproverRole matches binding.policyVersion)
   │ 8. Validate caller expectedVersion against current version
   ▼
[ PostgreSQL / JDBC Mutation Store ]
   │ 9. Compare-and-Set (CAS) version transition
   │ 10. Write durable outbox audit evidence (APPROVAL_APPROVED / DENIED)
   ▼ (Single DB Transaction)
HTTP 200 OK (Committed Decision + Attributed Actor Digest)
```

---

## 2. Supported Boundaries vs. Internal Surfaces

When securing a TramAI deployment, it is critical to distinguish between the **supported library control plane** and **internal platform prototypes**:

- **Supported Library Control Plane (`/tramai/sovereign/**`):**
  Published as part of `tramai-spring-boot-starter-sovereign-ops-rest`. All approval routes (`/tramai/sovereign/approvals/**`) are protected by Spring Security, enforce operator capabilities and role policies, and are designed for production deployment.
- **Internal Server Platform Prototype (`tramai-server`):**
  The `tramai-server` module (`POST /workflows/**`, `POST /workloads/**`, `POST /schedules/**`) is an unpublished internal platform prototype (`id("tramai.kotlin-library")`). It is **not** part of the sovereign-ops library runtime and lacks operator authentication filter chains. **These endpoints must never be exposed to public or untrusted networks without an external gateway or reverse-proxy authentication perimeter.**

---

## 3. Dependencies and Build Setup

Add the following starters to your Spring Boot application's `build.gradle.kts`:

```kotlin
dependencies {
    // 1. TramAI Sovereign REST Control Plane starter
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign-ops-rest:0.7.0")

    // 2. TramAI Sovereign JDBC Persistence (for PostgreSQL outbox and state storage)
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign-persistence-jdbc:0.7.0")

    // 3. Spring Boot Web and OAuth2 Resource Server
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // 4. PostgreSQL driver and connection pool
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
```

---

## 4. Configuration and Fail-Closed Startup

Configure operator security and control plane behavior in `application.yml`:

```yaml
tramai:
  sovereign:
    ops:
      # Enable the REST control plane endpoints
      rest-control-plane-enabled: true
      
      security:
        # Enable the operator authentication and authorization boundary
        enabled: true
        
        # Expected token issuer (iss claim)
        issuer: "https://idp.sovereign.example"
        
        # Required audience (aud claim)
        audience: "tramai-control-plane"
        
        # Identity Provider JWKS endpoint for public key discovery
        jwk-set-uri: "https://idp.sovereign.example/.well-known/jwks.json"
        
        # Claim in the JWT containing operator role identifiers
        role-claim: "roles"
        
        # Server-controlled mapping: IdP Role -> OperatorCapability
        role-mappings:
          approval-operator:
            - "decide-approval"
            - "inspect-governance"
          governance-auditor:
            - "inspect-governance"
            
        # Server-controlled mapping: IdP Role -> ApproverRole
        approver-role-mappings:
          approval-operator:
            - "security-lead"
            - "compliance-reviewer"
          billing-admin:
            - "finance-reviewer"
            
        # Server-controlled per-approval policy: policyVersion -> Permitted ApproverRoles
        approval-policies:
          v1-privileged-tool:
            - "security-lead"
          v1-financial-transfer:
            - "finance-reviewer"
            
        # Protected path patterns (defaults to /tramai/sovereign/**)
        protected-paths:
          - "/tramai/sovereign/**"
```

### Fail-Closed Startup Behavior

To prevent accidental security misconfigurations in production, TramAI fails startup immediately if security invariants are violated:

| Misconfiguration Condition | Failure Mechanism | Exact Error Message Prefix / Key |
| :--- | :--- | :--- |
| `rest-control-plane-enabled: true`, but `security.enabled: false` | Application startup fails with `IllegalStateException` | `tramai-rest-control-plane-requires-operator-security` |
| `security.enabled: true`, but missing `issuer`, `audience`, or `jwk-set-uri`/`public-key` | Application startup fails with `IllegalStateException` | `tramai-operator-authentication-incomplete-configuration` |
| `rest-control-plane-enabled: true`, but no active `ApprovalDecisionControlPlane` / missing approval policy bean | Application startup fails with `IllegalStateException` | `tramai-rest-control-plane-requires-approval-policy` |
| `security.enabled: true`, but missing `approval-policies` entry | Application startup fails with `IllegalArgumentException` | `tramai-approval-policies-missing` |
| `approval-policies` configured with an empty role list for a policy version | Configuration validation fails on startup | `tramai-operator-approval-policy-empty-roles` |
| `approval-policies` references a role not defined in `approver-role-mappings` | Configuration validation fails on startup | `tramai-approval-policy-unsatisfiable` |
| Configured control plane base path not covered by `protected-paths` | Application startup fails with `IllegalStateException` | `tramai-operator-authentication-unprotected-control-plane` |

---

## 5. Operator Authentication & Authorization Model

### 5.1 Verification Pipeline

```text
Incoming HTTP Request
  │ (Authorization: Bearer <JWT>)
  ▼
1. NimbusJwtDecoder: Signature verified via JWKS (RS256 only)
  │ Clock skew allowance: ±60 seconds
  │ Aud validated: contains configured audience
  │ Iss validated: equals configured issuer
  ▼
2. OperatorPrincipalFactory:
  │ Extracts subject (`sub`) and issuer (`iss`)
  │ Extracts claims using `role-claim` (e.g. ["approval-operator"])
  │ Maps claims -> OperatorCapability set (via `role-mappings`)
  │ Maps claims -> ApproverRole set (via `approver-role-mappings`)
  ▼
3. OperatorPrincipalResolver:
  │ Mints request-scoped `OperatorAuthority`
  ▼
4. SovereignOpsApprovalDecisionControlPlane:
  │ L2 Check: Does authority hold OperatorCapability.DECIDE_APPROVAL?
  │           If NO -> Refusal: MISSING_CAPABILITY (HTTP 403)
  │
  │ L3 Check: Look up approval's persisted policyVersion (e.g. "v1-privileged-tool").
  │           Does authority hold an ApproverRole listed in policy for this version?
  │           If policy missing -> Refusal: APPROVAL_POLICY_UNAVAILABLE (HTTP 403)
  │           If role missing   -> Refusal: ROLE_NOT_PERMITTED (HTTP 403)
  ▼
5. Precondition Check:
  │ Does command.expectedVersion == approval.version?
  │ If NO -> Conflict: STALE (HTTP 409)
  ▼
6. Storage Transaction:
  │ Atomic CAS update + Outbox write
```

### 5.2 Existence Disclosure Boundary

- **L2 Capability Check (`DECIDE_APPROVAL`):**
  Evaluated before checking database state. A caller lacking `DECIDE_APPROVAL` receives `403 Forbidden` (`missing-capability`) regardless of whether the target approval exists. This path discloses zero information about approval existence.
- **L3 Policy Check (`ApproverRole` vs `policyVersion`):**
  Requires reading the approval's stored `policyVersion`. Callers that hold `DECIDE_APPROVAL` but lack the specific approver role receive `403 Forbidden` (`role-not-permitted`), disclosing approval existence. This is accepted behavior because operators holding `DECIDE_APPROVAL` are already authorized governance participants.

---

## 6. Headless API Reference

### 6.1 Query Approval Status

Retrieve authoritative approval details and current version.

- **Method:** `GET`
- **Path:** `/tramai/sovereign/approvals/{approvalId}`
- **Headers:** `Authorization: Bearer <token>`

#### Response: `200 OK`

```json
{
  "approvalId": "appr-01j9z5v0k1",
  "status": "PENDING",
  "version": 0,
  "requestedBy": "workflow-agent-worker-1",
  "requestedAt": "2026-11-13T10:15:30.123Z",
  "expiresAt": "2026-11-13T12:15:30.123Z",
  "decidedBy": null,
  "decidedAt": null,
  "continuationStatus": "WAITING_FOR_DECISION"
}
```

---

### 6.2 Approve an Approval

Execute an authoritative privileged approval decision.

- **Method:** `POST`
- **Path:** `/tramai/sovereign/approvals/{approvalId}/approve`
- **Headers:**
  - `Authorization: Bearer <token>`
  - `Content-Type: application/json`

#### Request Body:

```json
{
  "expectedVersion": 0,
  "comment": "Pre-flight verification passed in cluster staging-us-east-1",
  "correlationId": "tx-9948271"
}
```

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `expectedVersion` | Integer (>= 0) | **Yes** | The caller's observed version of the approval. Must match current storage version. |
| `comment` | String | No | Business rationale or ticket reference for the decision. |
| `correlationId` | String | No | Distributed tracing or incident correlation identifier. |

> [!IMPORTANT]
> Omitting `expectedVersion` or supplying a negative number causes an immediate `400 Bad Request` (`INVALID_PRECONDITION`). The request does not reach the domain control plane or database.

#### Response: `200 OK` (Approved)

```json
{
  "status": "APPROVED",
  "approvalId": "appr-01j9z5v0k1",
  "actorId": "alice@sovereign.example",
  "version": 1
}
```

---

### 6.3 Deny an Approval

Execute an authoritative privileged denial decision.

- **Method:** `POST`
- **Path:** `/tramai/sovereign/approvals/{approvalId}/deny`
- **Headers:**
  - `Authorization: Bearer <token>`
  - `Content-Type: application/json`

#### Request Body:

```json
{
  "expectedVersion": 0,
  "comment": "Tool execution rejected due to anomalous payload parameter",
  "correlationId": "tx-9948272"
}
```

#### Response: `200 OK` (Denied)

```json
{
  "status": "DENIED",
  "approvalId": "appr-01j9z5v0k1",
  "actorId": "alice@sovereign.example",
  "version": 1
}
```

---

### 6.4 Error and Precondition Status Codes

The control plane returns standardized typed error responses:

| HTTP Status | Response `status` | Meaning | Response Fields |
| :--- | :--- | :--- | :--- |
| **`400 Bad Request`** | `INVALID_PRECONDITION` | `expectedVersion` was null, missing, or negative. | `message: "tramai-approval-expected-version-required"` |
| **`401 Unauthorized`** | *(Spring Security default)* | Missing, expired, or invalid JWT. | Standard OAuth2 bearer error response. |
| **`403 Forbidden`** | `UNAUTHORIZED` | Authorization refused server-side. | `message`: `missing-capability`, `role-not-permitted`, or `approval-policy-unavailable`. Refusal recorded in outbox. |
| **`404 Not Found`** | *(Empty body)* | Approval ID does not exist in authoritative storage. | — |
| **`409 Conflict`** | `STALE` | `expectedVersion` does not match storage version. | `expectedVersion`, `currentVersion`, `message: "tramai-approval-version-stale"`. |
| **`409 Conflict`** | `EXPIRED` | Approval expiration deadline has passed. | `approvalId` |
| **`409 Conflict`** | `CONFLICT` | Invalid transition (e.g. attempting to deny an already approved item with mismatched version). | `message` |
| **`200 OK`** | `ALREADY_APPROVED` | Idempotent replay of decision on an already approved item. | `actorId` |
| **`200 OK`** | `ALREADY_DENIED` | Idempotent replay of decision on an already denied item. | `actorId` |

#### Example: Stale Precondition Conflict (`409 Conflict`)

```json
{
  "status": "STALE",
  "approvalId": "appr-01j9z5v0k1",
  "expectedVersion": 0,
  "currentVersion": 1,
  "message": "tramai-approval-version-stale"
}
```

#### Example: Role Not Permitted Refusal (`403 Forbidden`)

```json
{
  "status": "UNAUTHORIZED",
  "approvalId": "appr-01j9z5v0k1",
  "message": "role-not-permitted"
}
```

---

## 7. Approval Inbox Query API

Headless systems can search and paginate approval queues without direct database queries.

- **Method:** `GET`
- **Path:** `/tramai/sovereign/approvals`
- **Query Parameters:**
  - `status` (Optional): `PENDING` (default), `APPROVED`, `DENIED`, `EXPIRED`.
  - `requiredRole` (Optional): Filter by role string (e.g. `security-lead`).
  - `requestedBy` (Optional): Filter by requester identifier.
  - `limit` (Optional, 1..100): Page size (default 50).
  - `cursor` (Optional): Pagination cursor from previous page.

#### Response: `200 OK`

```json
{
  "items": [
    {
      "approvalId": "appr-01j9z5v0k1",
      "workflowRunId": "run-8819",
      "toolName": "executeDatabaseQuery",
      "status": "PENDING",
      "requestedBy": "workflow-agent-worker-1",
      "requestedAt": "2026-11-13T10:15:30.123Z",
      "expiresAt": "2026-11-13T12:15:30.123Z",
      "requiredRole": "security-lead",
      "version": 0
    }
  ],
  "nextCursor": "eyJsYXN0SWQiOiJhcHByLTAxajl6NXYwazEifQ=="
}
```

> [!NOTE]
> Inbox query projections never expose sensitive execution arguments, raw replay tokens, or decrypted tool payloads.

---

## 8. Durable Outbox Evidence and Attribution

### 8.1 PostgreSQL Schema and V10 Attribution Columns

When a decision commits, the relational database table `approvals` is updated within a single transaction alongside `audit_outbox`:

- **Actor Subject:** The operator's subject identifier (`sub` from JWT) is stored in `sanitized_metadata` JSONB.
- **Legacy Subject Hash (`decision_actor_hash`):** Retains its historical textual form (`sha256:` followed by 64 lowercase hex characters of the subject) for backwards compatibility.
- **Issuer Attribution (`decision_actor_issuer`):** Added in Schema V10, stores the exact identity provider issuer URI (`iss` from JWT).
- **Canonical Global Identity Digest (`decision_actor_identity_digest`):** Added in Schema V10, binds both `(issuer, subject)` into an immutable, collision-resistant identifier.

#### Canonical Identity Digest Algorithm

A subject alone is only unique *within* an issuer; two operators from different identity providers could share a subject string. To prevent cross-issuer collisions without delimiter ambiguity, `ActorIdentityDigest.of(issuer, subject)` encodes the pair using domain separation and big-endian length prefixing rather than string concatenation:

```text
SHA-256( "tramai/actor-identity" ‖ 0x00 ‖ u32be(1) ‖ u32be(len(issuer)) ‖ issuer (UTF-8)
                                        ‖ u32be(len(subject)) ‖ subject (UTF-8) )
```

- **Domain Separation:** Prefixed with `"tramai/actor-identity"\0`.
- **Versioned Binary Framing:** `u32be(1)` encoding version ensures future v2 encodings cannot collide with v1.
- **Length-Prefixed UTF-8:** `u32be(length)` precedes each string component, preventing separator injection attacks.
- **Output Representation:** 64 lowercase hexadecimal characters, **without prefix** (distinguishable from legacy `sha256:`-prefixed hashes).

### 8.2 Evidence Kinds and Refusal Isolation

The transactional outbox table `audit_outbox` durably records one of three explicit evidence kinds:

1. `APPROVAL_APPROVED`: Written when an approval decision commits. Emits state transition metadata (`approvalStatus=APPROVED`, `approvalVersion=1`, `previousApprovalStatus=PENDING`, `previousApprovalVersion=0`).
2. `APPROVAL_DENIED`: Written when an authorized operator decides to deny. Emits state transition metadata.
3. `AUTHORIZATION_REFUSAL`: Written when an operator request is refused (403 `MISSING_CAPABILITY`, `ROLE_NOT_PERMITTED`, or `APPROVAL_POLICY_UNAVAILABLE`).

> [!IMPORTANT]
> **Refusal Evidence Isolation:**
> `AUTHORIZATION_REFUSAL` records are strictly isolated from state-transition data. They never emit `approvalStatus`, `approvalVersion`, `previousApprovalStatus`, or `previousApprovalVersion`. An authorization refusal documents the refusal of authority, not a state change of the business entity.

---

## 9. Security Hardening Checklist

When deploying to production:

- [ ] **HTTPS / TLS Termination:** Ensure all requests to `/tramai/sovereign/**` terminate over TLS 1.3 to protect Bearer tokens in transit.
- [ ] **JWKS Endpoint Protection:** Pin the JWKS URI or verify IdP TLS certificates. TramAI validates RS256 signatures and checks clock skew with a ±60 second tolerance.
- [ ] **Audience Validation:** Always configure `tramai.sovereign.ops.security.audience` to match your application's client identifier. Tokens without a matching `aud` claim are rejected immediately.
- [ ] **Network Perimeter for Internal Surfaces:** Ensure internal server endpoints (`tramai-server`) are inaccessible from untrusted subnets.
- [ ] **Database Least Privilege:** The application database user should hold permissions only on `approvals`, `approval_continuations`, and `audit_outbox`. Direct modification of existing outbox rows should be restricted.
