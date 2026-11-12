# 0.7.6g2 — Privileged Control Boundary Adversarial Qualification Contract

**Status:** PROPOSED DISPOSITION — PENDING OWNER REVIEW AND RATIFICATION  
**Target:** `release/0.7.0`  
**Verified baseline:** `6f2854adbec96061a80dab2e9e30f8f893719f20`  
**Epic:** `docs/roadmap/0.7.0/EPIC-0.7.6-AUTH-RUNTIME-CONTROL.md`  
**Prerequisites:** 0.7.6e (`TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md`), 0.7.6f (`TASK-0.7.6f-PRIVILEGED-EVIDENCE-CONTRACT.md`), 0.7.6g1 (`TASK-0.7.6g-ENFORCEMENT-ORIGIN-CONTRACT.md`, PR #573)

---

## 1. Mission and Governing Invariant

The purpose of milestone 0.7.6g2 is to prove through production wiring, adversarial tests, and mutation experiments that privileged approval-control operations cannot bypass established authentication, authorization, precondition, concurrency, and durable evidence guarantees.

The governing invariant of privileged approval mutation:

```text
Privileged approval mutation
  REQUIRES
    verified operator identity (JWT verified against trusted issuer)
    AND authorized operator capability (L2: DECIDE_APPROVAL)
    AND satisfied approval policy (L3: role matching configured policy for binding.policyVersion)
    AND valid caller-observed version (precondition check)
    AND successful authoritative store CAS (concurrency fencing)
    AND durable decision evidence (outbox intent committed atomically with mutation)
```

If any prerequisite fails:
- The mutation MUST NOT be committed.
- The approval status and version in authoritative storage MUST NOT change.
- A business decision event (`APPROVAL_APPROVED` or `APPROVAL_DENIED`) MUST NOT be emitted.
- Where authorization is refused, refusal evidence MUST be durably recorded prior to returning 403 / `UNAUTHORIZED`.

---

## 2. Privileged Security Boundary Inventory Matrix

The Phase 0 inventory evaluated all entry points to approval state mutation and inspection:

| Entry Point | Identity Enforcement | Auth Enforcement | Precondition Enforcement | Evidence Guarantee | Residual Risk | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ApprovalControlPlaneController` (`POST /{id}/approve`, `POST /{id}/deny`) | Verified JWT via Spring Security OAuth2 resource server decoder (`OperatorPrincipalResolver`) | L2 capability (`DECIDE_APPROVAL`) + L3 role policy match | `expectedVersion >= 0` check in controller + CAS in store | Atomically written audit outbox record (`PREPARED` -> `PENDING`), refusal evidence on 403 | None for supported boundary | **ENFORCED** |
| `SovereignOpsApprovalDecisionControlPlane` (`approve`, `deny`) | Requires strongly typed `OperatorAuthority` instance | Evaluates `OperatorCapability.DECIDE_APPROVAL` and `OperatorApprovalPolicy.evaluate()` | `expectedVersion` check against current version + terminal status checks | Atomic audit intent passed to mutation store; refusal recorded via `SovereignOpsRefusalRecorder` | In-process caller could instantiate `OperatorAuthority` directly if running inside JVM | **ENFORCED** (Supported domain boundary) |
| `DefaultSovereignApprovalOperations` (`denyApproval`) | Caller-asserted actor string; no `OperatorAuthority` | None (no capability or role checks) | `expectedVersion` set to read version; CAS in mutation store | `APPROVAL_DENIED` outbox record created; best-effort outbox dispatch | Unauthenticated/unauthorized denial if caller invokes this service directly in-process | **PARTIAL** (Legacy / direct service) |
| `ApprovalStore.transition(...)` | None | None | Optimistic concurrency (version match) | None | Arbitrary in-process code can mutate approval if it has a reference to `ApprovalStore` | **INVENTORIED_ONLY** (Guarded by L4 `verifyDirectApprovalMutations` PSI ratchet) |
| `ApprovalControlPlaneController` (`GET /{id}`) | Authenticated operator principal in security context | None (any authenticated operator can read approval status) | None | None | Information disclosure: any valid operator can inspect status of any approval (F3) | **PARTIAL** (Authenticated read) |
| `ApprovalResumeControlPlaneController` (`POST /{id}/resume`) | Unauthenticated / caller-supplied resume token | Token match + continuation status | `expectedApprovalVersion` and `expectedContinuationVersion` | Resumed continuation event | Separate resumption lifecycle; pending 0.7.7 durable resume fencing | **NOT_APPLICABLE** (Resumption path, not decision path) |

---

## 3. Threat Model

We define seven distinct adversarial personas attempting to breach the privileged approval boundary:

### Adversary A1 — Unauthenticated Network Caller
- **Profile:** Network caller without credentials, with invalid/expired JWT, or with malformed signature.
- **Attack Vector:** Sends `POST /{id}/approve` or `POST /{id}/deny` to REST control plane.
- **Objective:** Trigger an approval state transition or tamper with decision metadata without authentication.
- **Defense:** Spring Security OAuth2 resource server filter chain intercepts request before controller invocation, rejecting with 401 Unauthorized. No database read or write occurs.

### Adversary A2 — Authenticated Operator Without Required Capability or Role
- **Profile:** Legitimate authenticated operator holding valid JWT, but lacking `DECIDE_APPROVAL` capability (L2) or lacking the required `ApproverRole` for the approval's `policyVersion` (L3).
- **Attack Vector:** Attempts to approve or deny an approval via REST endpoint or domain control plane.
- **Objective:** Exceed granted privileges to force an approval decision.
- **Defense:**
  - L2: `capabilityRefusal` checks `authority.holds(OperatorCapability.DECIDE_APPROVAL)` before store read. Refuses with 403 / `UNAUTHORIZED(MISSING_CAPABILITY)`.
  - L3: `approvalRefusal` evaluates `approvalPolicy.evaluate(binding.policyVersion, authority)`. Refuses with 403 / `UNAUTHORIZED(ROLE_NOT_PERMITTED)`.
  - In both cases, refusal evidence is recorded durably; approval state and version remain untouched.

### Adversary A3 — Network Caller Attempting Authority Forgery
- **Profile:** Caller attempting to inject actor identity, capabilities, or roles via HTTP request headers (e.g. `X-Actor`, `X-Operator-Role`), URL query parameters, or request body fields.
- **Attack Vector:** Attaches spoofed claims to `POST /{id}/approve` body or headers.
- **Objective:** Impersonate a privileged operator or elevate roles.
- **Defense:** `ApprovalControlPlaneController` derives identity strictly from Spring Security's `SecurityContextHolder` via `OperatorPrincipalResolver.issueAuthority()`. Body contains only `expectedVersion`, `comment`, and `correlationId`. Request headers are completely ignored for authorization.

### Adversary A4 — Stale Caller / Lost Update Attacker
- **Profile:** Operator acting on outdated workflow state or attempting to re-decide an already decided approval.
- **Attack Vector:** Sends decision with `expectedVersion` lower than current store version.
- **Objective:** Overwrite a newer decision or race against an expiry.
- **Defense:** Domain precondition check validates `command.expectedVersion == approval.version`. If stale, returns 409 Conflict / `STALE(expectedVersion, currentVersion)`. If read raced, store CAS fails and returns `Stale`.

### Adversary A5 — In-Process Direct-Store Caller
- **Profile:** Internal JVM component or plugin attempting to invoke `ApprovalStore.transition(...)` directly, bypassing control plane and outbox.
- **Attack Vector:** Direct Java/Kotlin method invocation on injected bean.
- **Objective:** Mutate approval status without audit outbox recording, identity verification, or role evaluation.
- **Defense:** Source-level static analysis ratchet `verifyDirectApprovalMutations` scans all repository Kotlin/Java sources, enforcing an exact allowlist of reviewed call sites. No runtime sandbox exists; documented as accepted residual L4.

### Adversary A6 — Audit Evidence Manipulator
- **Profile:** Internal component attempting to forge audit events or manipulate evidence metadata to claim authoritative enforcement without passing through the control plane.
- **Attack Vector:** Emitting custom `AuditEvent` with `boundary.enforcementStrength=AUTHORITATIVE` or forging `SovereignOpsAuditOutboxRecord`.
- **Objective:** Create fake compliance evidence or masquerade refusals as state transitions.
- **Defense:**
  - 0.7.6g1: `RuntimeBoundaryProvenance` caps origin strength at `DECLARED` without framework attester.
  - 0.7.6f: Refusal records cannot emit `approvalStatus`, `approvalVersion`, `previousApprovalStatus`, or `previousApprovalVersion`.
  - Outbox store encrypts sensitive payloads and enforces aggregate hash binding.

### Adversary A7 — Concurrent Conflicting Operators
- **Profile:** Two authorized operators submitting conflicting decisions (`APPROVE` vs `DENY`) simultaneously against the same approval at identical observed version.
- **Attack Vector:** Parallel HTTP requests released at the exact same millisecond against PostgreSQL.
- **Objective:** Induce a race condition resulting in split-brain, lost updates, or duplicate outbox records.
- **Defense:** PostgreSQL row-level lock and CAS (`UPDATE approvals SET status = ?, version = version + 1 WHERE approval_id = ? AND version = ?`). Exactly one transaction succeeds and commits; the other fails CAS and reports `STALE`. Exactly one decision outbox record is persisted.

---

## 4. Adversarial Qualification Criteria (G2-01 through G2-14)

| Test ID | Adversary / Capability | Target Enforcement Point | Expected Qualification Verdict |
| :--- | :--- | :--- | :--- |
| **G2-01** | A1 (Unauthenticated) | REST `POST /{id}/approve\|deny` | **PASS**: 401 Unauthorized; zero store lookups; zero state mutations; zero audit events emitted. |
| **G2-02** | A2 (Missing L2 capability) | Control Plane & REST | **PASS**: 403 Forbidden / `UNAUTHORIZED(MISSING_CAPABILITY)`; refusal recorded before return; zero approval mutation. |
| **G2-03** | A2 (Wrong L3 role) | Control Plane & REST | **PASS**: 403 Forbidden / `UNAUTHORIZED(ROLE_NOT_PERMITTED)`; refusal recorded; approval status and version unchanged. |
| **G2-04** | A3 (Authority forgery) | REST Controller | **PASS**: Spoofed headers/body ignored; authority extracted strictly from verified JWT claims. |
| **G2-05** | A1 (Untrusted issuer) | OIDC Authentication Boundary | **PASS**: Token from unknown or untrusted issuer rejected with 401 / `JwtException`; zero downstream execution. |
| **G2-06** | A2 / Host Misconfiguration | Control Plane Auto-Configuration | **PASS**: Application fails startup if REST control plane is enabled without operator security or without approval policy bean. |
| **G2-07** | A4 (Stale version) | Control Plane & REST | **PASS**: 409 Conflict / `STALE(expected, current)`; no mutation committed; no transition event emitted. |
| **G2-08** | A7 (Concurrent conflicting operators) | Real PostgreSQL JDBC Store | **PASS**: Approve vs Deny released at barrier; exactly one succeeds; exactly one returns Stale; exactly one durable pending outbox record (zero PREPARED records); zero duplicate outbox records. |
| **G2-09** | A7 (Repeated identical commands) | Real PostgreSQL JDBC Store | **PASS**: 5 parallel identical commands released at barrier; exactly one commits; 4 fail with Stale; zero duplicate outbox records. |
| **G2-10** | A5 (Direct store mutation) | Build-Logic PSI Guard | **PASS**: `verifyDirectApprovalMutations` detects and fails on unreviewed direct calls to `ApprovalStore.transition`. |
| **G2-11** | Fail-Closed Policy | Control Plane Policy Evaluation | **PASS**: Unconfigured or missing policy version in `OperatorApprovalPolicy` refuses decision with `UNAUTHORIZED(APPROVAL_POLICY_UNAVAILABLE)` or `UNAUTHORIZED(ROLE_NOT_PERMITTED)`. |
| **G2-12** | Outbox Failure Atomicity | Transactional Mutation Store | **PASS**: Failure during outbox persistence in real PostgreSQL transaction rolls back the entire transaction; approval status remains PENDING and zero outbox records commit. |
| **G2-13** | Refusal Replay & Deduplication | Authoritative JDBC & Control Plane | **PASS**: Replaying refused commands leaves approval state unmutated with zero outbox records. Durable refusal deduplication onto exactly one persistent record across retries/restarts is proven by `JdbcRefusalRecordingRestartTest`. |
| **G2-14** | A6 (Origin strength cap) | Enforcement Origin Boundary | **PASS**: Event naming registered boundary without framework attester is capped at `DECLARED` strength; `attested` refused. |

---

## 5. Mutation Qualification Schedule (M1 through M8)

Individual mutation experiments executed and distinguished from unverified assertions:

| Mutation ID | Target Invariant | Mutated Source & Fault Injection | Target Discriminator Test | Execution Result & Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **M1** | L2 Operator Capability Check | Remove `capabilityRefusal` check for `OperatorCapability.DECIDE_APPROVAL` | `PrivilegedControlBoundaryAdversarialTest.G2-02` | **KILLED** (Operator lacking capability received decision rather than `UNAUTHORIZED(MISSING_CAPABILITY)`). |
| **M2** | L3 Approver Role Policy | Invert role policy condition in `approvalRefusal` (`roles.intersect().isEmpty()`) | `PrivilegedControlBoundaryAdversarialTest.G2-03` | **KILLED** (Permitted roles refused; unpermitted roles authorized). |
| **M3** | Actor Identity Forgery | Substitute caller header/body actor string in `ApprovalDecisionRestController` | `PrivilegedControlBoundaryRestAdversarialTest.G2-04` | **KILLED** (Controller extracts actor identity strictly from verified `OperatorAuthority`; spoofed headers ignored). |
| **M4** | Version Precondition Check | Remove `command.expectedVersion == approval.version` precondition guard | `PrivilegedControlBoundaryAdversarialTest.G2-06` | **KILLED** (Stale commands bypass precondition check and proceed to mutation). |
| **M5** | Authoritative Store CAS Fencing | Replace CAS `WHERE version = ?` with unconditional `UPDATE approvals SET status = ?` | `PrivilegedControlBoundaryConcurrencyQualificationTest.G2-08, G2-09` | **KILLED** (Parallel barrier races result in lost updates and multiple conflicting winners). |
| **M6** | Fail-Closed Policy Evaluation | Return permissive `Allow` when `policyVersion` is unconfigured | `PrivilegedControlBoundaryAdversarialTest.G2-07` | **KILLED** (Unconfigured versions succeed instead of failing closed with `APPROVAL_POLICY_UNAVAILABLE`). |
| **M7** | Refusal Audit Evidence Isolation | Populate `approvalStatus`/`approvalVersion` metadata onto `AUTHORIZATION_REFUSAL` | `PrivilegedControlBoundaryAdversarialTest.G2-11`, `SovereignOpsAuditEvidenceDispatchTest` | **KILLED** (Malicious refusal evidence with decision state emitted; caught by refusal metadata discriminator). |
| **M8** | Enforcement Origin Strength Capping | Allow un-attested declared origin to claim `AUTHORITATIVE` strength | `EnforcementOriginBoundaryAdversarialTest.G2-14` | **KILLED** (Declared origins elevated without attester; caught by strength boundary discriminator). |

---

## 6. Residual Security Findings Disposition (Pending Owner Review)

All residual security findings are documented with explicit rationale and submitted for repository-owner review and ratification:

1. **F3 — `GET /{approvalId}` Read Endpoint Role Filtering:**
   - *Status:* **PROPOSED RESIDUAL FOR OWNER REVIEW**.
   - *Rationale:* The read endpoint requires an authenticated operator, but does not enforce fine-grained role-based read filtering. The response exposes metadata and continuation status, but no sensitive execution payloads (payloads are protected by hash digest). Full read authorization requires tenant/domain policy alignment in 0.8.x.

2. **F5 — `requiredRole` Persisted as Policy Version Rather Than Explicit Column:**
   - *Status:* **PROPOSED RESIDUAL FOR OWNER REVIEW**.
   - *Rationale:* Approver requirements are evaluated via `binding.policyVersion` mapped against `OperatorApprovalPolicy`. The client-requested `requiredRole` in `ApprovalGateway.requestApproval` is stored as queryable metadata, not authoritative state. Persisting dynamic per-approval required roles into the schema requires a schema migration and ADR deferred to a future milestone.

3. **L4 — In-Process `ApprovalStore.transition` Public Method:**
   - *Status:* **PROPOSED RESIDUAL FOR OWNER REVIEW (Gated by Static Analysis Ratchet)**.
   - *Rationale:* `ApprovalStore` is a public SPI. In JVM architectures without OSGi/JPMS module isolation, any code within the same process can call public methods. The risk is bounded and gated by the `verifyDirectApprovalMutations` PSI ratchet, which prevents unreviewed call sites in production code.

4. **Issue #524 — Enforcement-Origin Attestation Slice 2:**
   - *Status:* **PROPOSED DEFERRAL FOR OWNER REVIEW (Issue #524 remains OPEN)**.
   - *Rationale:* Slice 1 (PR #573) delivered declaration-safe enforcement provenance. Slice 2 (wiring-held capability and store readback) remains deferred until a real production runtime boundary exists. Issue #524 remains open.
