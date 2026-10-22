# AUDIT 0.7.6a — Security & Control Baseline

**Task:** 0.7.6a — Security/control baseline audit (Epic 0.7.6)
**Starting head:** `ff1d3da47d72d7c51bbcc416bcd555fde68a90c0` (`release/0.7.0`)
**Class:** architecture audit + security assessment + implementation planning. **No production code changed.**
**Verdict:** **INCOMPLETE** — the control-plane *mutation* layer is strong and reusable; the *identity and
authorization* layer does not exist on any supported control path, and one existing concurrency guarantee
is fail-open by default. Details and evidence below.

Every claim carries a source location. Claims are labelled **VERIFIED** (command/source inspected, output
recorded), **INFERRED** (follows from verified facts) or **UNRESOLVED** (not established). A security
property is never marked safe because a type exists.

---

## 1. Headline findings

| # | Finding | Class | Evidence |
|---|---|---|---|
| F1 | Approval decisions take `actorId` and `actorRole` **from the HTTP request body**. No authentication, no principal, no verification that the actor may approve. | **BLOCKER** | `ApprovalControlPlaneController.kt:75-108`, `ApprovalControlPlaneRestDtos.kt:8-14` |
| F2 | `ApprovalDecisionCommand.expectedVersion` defaults to `null`, and `null` means *auto-detect*: the control plane substitutes the current version, so the store CAS always succeeds. Two concurrent decisions both commit; the second silently overwrites the first. | **BLOCKER** | `ApprovalDecisionControlPlane.kt:50`, `SovereignOpsApprovalDecisionControlPlane.kt:109,118` |
| F3 | No authentication or authorization exists on any `tramai-server` control endpoint (workload register/metadata/lifecycle, run, resume, cancel). `grep` for `@PreAuthorize`, `SecurityFilterChain`, `HttpSecurity`, `@RolesAllowed` over `tramai-server`, `tramai-spring*`, `tramai-dashboard` returns nothing but a bean-existence probe. | **BLOCKER** | §3 matrix; `DashboardSettingsController.kt:57-63` is the only match and only *detects* a bean |
| F4 | The only working authentication mechanism in the repository (`ApiKeyAuthenticator`, scoped API keys, rate limiting) lives in `tramai-platform`, which **no module depends on**. It is unwired. | REQUIRED (reuse candidate) | `Security.kt:144-171`, `PlatformController.kt:44-45`; `grep tramai-platform --include=build.gradle.kts` → no dependents |
| F5 | `ApproverRole` / `requiredRole` is requested by `ApprovalGateway.requestApproval` and **never enforced on the decision path**: the decision implementation contains no role comparison. Role is stored as inbox metadata and queryable, which reads like enforcement and is not. | **BLOCKER** | `ApprovalGateway.kt:22-25`; `DefaultSovereignApprovalOperations.kt` — zero `actorRole`/`requiredRole` references |
| F6 | The dashboard reports its auth posture from **bean presence** (`ApiKeyAuthenticator`, `JwtDecoder`, `SecurityFilterChain`, …), not from observed enforcement. | REQUIRED (evidence-vs-enforcement) | `DashboardSettingsController.kt:49-63` |
| F7 | Actor identity is documented and validated as a *trusted stable reference*: shape-only validation, authenticity explicitly out of scope. | (informs F1) | `SafeActorIdPolicy.kt:1-20,29` |

F1/F2/F5 are the gap Epic 0.7.6 exists to close; they are recorded here, **not remediated** (audit-only
task). No critical bypass of an *existing enforced* boundary was found: there is no enforced operator
boundary to bypass.

---

## 2. Gate 1 — Existing security and identity inventory

### 2.1 Identity contracts (0.7.1)

| Component | File | Responsibility | Authority | Identity trusted | Persistence | Failure behaviour | Reuse |
|---|---|---|---|---|---|---|---|
| `WorkloadConfigurationIdentity` | `tramai-core/.../core/identity/WorkloadConfigurationIdentity.kt` | Names a configuration + version | Validated at construction | Self-declared by the caller, format-validated | No | Rejects malformed ids | **Reusable** as command target |
| `WorkloadDeploymentIdentity` | `tramai-core/.../core/identity/WorkloadDeploymentIdentity.kt` | Deployment scope: workload/env/deployment | Registration authority | Caller-supplied | Read by registration authority | Rejects unknown scope on admission | **Reusable** as command target |
| `GovernedRunIdentity` | `tramai-core/.../core/identity/GovernedRunIdentity.kt` | Binds a run to its deployment + configuration | Checkpoint attribution | Recovered from persisted checkpoint | Read/written | Resume rejects unattributable run | **Reusable** as command subject |
| `WorkloadStateVersion` | `tramai-control-plane/.../WorkloadStateVersion.kt` | CAS token for lifecycle/metadata | Store `compareAndSet` | N/A (a token) | Yes | Stale → typed `Stale`, never silent | **Reusable** (the model 0.7.6d should copy) |

These are **resource identities, not actor identities**. Nothing in 0.7.1 establishes *who* is calling.

### 2.2 Actor and approval identity

| Component | File | Responsibility | Authority | Identity trusted | Persistence | Failure behaviour | Reuse |
|---|---|---|---|---|---|---|---|
| `SafeActorIdPolicy` | `tramai-core/.../core/approval/SafeActorIdPolicy.kt` | Validates actor-id *shape*; normalizes for audit | Store/coordinator entry points | **Explicitly assumes the caller is authenticated upstream**: "Actor identifiers are trusted stable references" (`:5`) | No | `IllegalArgumentException` on malformed id | **Adaptable** — keep shape rules, never treat as authentication |
| `ApprovalRequest` | `tramai-core/.../core/approval/ApprovalRequest.kt:21-33` | Records `requestedBy`, `decidedBy`, `consumedBy`, `version` | Store | Whatever string the caller supplies | Yes | — | **Reusable** as the attribution carrier |
| `ApproverRole` | `tramai-core/.../core/approval/gateway/ApprovalGatewayTypes.kt:15` | Role label for approval | Nothing enforces it (F5) | Caller-asserted | Stored as inbox metadata | Not evaluated on decision | **Adaptable** — becomes operator capability input only after 0.7.6b |
| `ApprovalGateway.requestApproval(requiredRole)` | `.../gateway/ApprovalGateway.kt:22-25` | Declares the role permitted to decide | — | — | — | Declared, unenforced | **Adaptable** |

### 2.3 Authentication components present

| Component | File | Responsibility | Enforcement point | Reuse verdict |
|---|---|---|---|---|
| `ApiKeyService` | `tramai-platform/.../platform/Security.kt:55-127` | Mint/list/revoke API keys, `tmr_`-prefixed, hashed at rest | Service | **Reusable** (hashing, lifecycle) |
| `ApiKeyAuthenticator` | `tramai-platform/.../platform/Security.kt:144-171` | Authenticates a raw key: prefix lookup → `PasswordEncoder.matches` → `AuthenticatedApiKey` | Called by `PlatformController` per request | **Reusable** as the *shape* of a principal resolver |
| `ApiKeyAuthenticator.requireScope` | `Security.kt:163-171` | Scope authorization (`AuthorizationException`) | Called by `PlatformController` per endpoint | **Reusable** as the *shape* of operator authorization |
| `ApiKeyRateLimiter` | `Security.kt:173+` | Token-bucket per key | Service | Out of scope for 0.7.6 |
| `GitHubWebhookSignatureVerifier` | `tramai-server/.../server/WebhookSignatureVerifier.kt:17-44` | HMAC-SHA256 verification + `ReplayCache` | `POST /webhooks/{name}` | **Reusable precedent** — the only real crypto-authenticated ingress in the release |
| Spring Security / OIDC / JWT | `gradle/libs.versions.toml:19,49` | **Only `spring-security-crypto`** (hashing) is on the classpath. No web security, no OAuth2 resource server, no JWT decoder dependency. | — | Missing by design; 0.7.6c adds the seam |

### 2.4 Scope/capability model present

`ApiKeyScope` (`tramai-platform/.../PlatformModels.kt:18-32`): `RUN("run")`, `READ("read")`, `ADMIN("admin")`,
with `grants(required) = this == ADMIN || this == required`. Bound to `(teamId, projectId)`. Applied per
endpoint in `PlatformController` (`:45 RUN`, `:73,90 READ`, `:125,141,157,167,186,198 ADMIN`).

This is a **flat three-value scope set serving both domains at once** — see Gate 3.

### 2.5 Evidence, audit and attribution present

| Component | File | Guarantee | Gap |
|---|---|---|---|
| `ApprovalLifecycleAuditEmitter` | `tramai-core/.../core/approval/ApprovalLifecycleAuditEmitter.kt` | Emits approval lifecycle facts with normalized actor | Actor is the caller-asserted string (F1) |
| `AuditEngineApprovalLifecycleAuditEmitter` | `tramai-security/.../audit/AuditEngineApprovalLifecycleAuditEmitter.kt` | Bridges to the audit engine | Same |
| Outbox mutation boundary | `tramai-spring-boot-starter-sovereign-ops/.../outbox/InMemorySovereignOpsApprovalMutationStore.kt`, `.../persistence/jdbc/JdbcSovereignOpsApprovalMutationStore.kt` | Decision + audit fact committed together | Attribution is forgeable, so the *evidence is sound but the actor is unverified* |
| `GovernanceProjection` / `GovernanceQueryService` (0.7.4) | `tramai-security/.../governance/` | Read model over governance facts | Read-only; correct place for post-0.7.6 operator-attributed facts |

---

## 3. Gate 2 — Control-path matrix

Legend: **MISSING** = stage does not exist anywhere on the path. **NOT IMPLEMENTED** = no supported
control operation exists (an internal method is not an operational API).

### 3.1 Privileged operations found

| Operation | Entry point | Identity | Authorization | Precondition | Authority owner | Mutation | Evidence | Verdict |
|---|---|---|---|---|---|---|---|---|
| Workload registration | `POST /workloads` — `WorkloadControlPlaneController.kt:120` | **MISSING** | **MISSING** | declaration idempotency | `WorkloadRegistrationAuthority` | `WorkloadRegistrationStore.create` (atomic) | none (no actor) | Fenced create, **unauthenticated** |
| Workload metadata update | `PUT /workloads/.../metadata` — `:150` | **MISSING** | **MISSING** | `If-Match` state version | `WorkloadRegistrationAuthority` | `compareAndSet` | none | **Fenced, unauthenticated** |
| Workload lifecycle transition | `PUT /workloads/.../lifecycle` — `:179` | **MISSING** | **MISSING** | `If-Match` state version | `WorkloadRegistrationAuthority` | `compareAndSet` | none | **Fenced, unauthenticated** |
| Workload read (authoritative) | `GET /workloads/...` — `:229` | **MISSING** | **MISSING** | — | safe exposure projection | none | — | Unauthenticated read |
| Trigger run | `POST /workflows/{name}/run` — `WorkflowController.kt:64` | **MISSING** | **MISSING** | none | engine | starts execution | observability spans only | Unauthenticated execution trigger |
| Approval approve / deny | `POST /tramai/sovereign/approvals/{id}/approve\|deny` — `ApprovalControlPlaneController.kt:75,93` | **Caller-asserted body fields** (F1) | **MISSING** (F5) | `expectedVersion`, **optional and auto-detected** (F2) | `ApprovalDecisionControlPlane` → outbox store | approval transition + audit fact | actor/role recorded, **unverified** | **The core 0.7.6 target** |
| Approval resume (post-decision) | `POST .../{id}/resume` — `:111` | **MISSING** | **MISSING** | continuation state | `ApprovalResumeControlPlane` | resume | continuation record | Unauthenticated |
| Workflow run resume | `POST /workflows/{name}/runs/{id}/resume` — `WorkflowController.kt:106` | **MISSING** | deployment *entitlement* only (`serverGovernance.authorizeContinuation`, `:118`) | `runStore.requireResumable`, `:116` | server run store | moves run out of `DELAYED` | none about the caller | Unauthenticated; entitlement ≠ operator authority |
| Workflow run cancel | `DELETE /workflows/{name}/runs/{id}` — `:179` | **MISSING** | **MISSING** | existence | `WorkflowRunStore.cancel` | cancels | none | Unauthenticated |
| Approval continuation claim/cancel | `ContinuationClaimService.kt` (`tramai-engine/.../approval/`) | **MISSING** | **MISSING** | `expectedVersion` | continuation store | claim/cancel | — | Internal API, not exposed; still unfenced at the actor level |
| Replay/authorization state | `ReplayAuthorizationService.kt` (`tramai-engine/.../approval/`) | **MISSING** | **MISSING** | version | replay store | state changes | — | Internal |
| Webhook ingress | `POST /webhooks/{name}` — `WorkflowController.kt:75` | HMAC key (shared secret) | signature validity | replay cache | `GitHubWebhookSignatureVerifier` | starts execution | implied by signature | **Authenticated precedent** (machine, not operator) |
| Worker/lease ownership, takeover | — | — | — | — | — | — | — | **NOT IMPLEMENTED** as a supported operator operation. Leases are released internally (`WorkflowExecutionSupervisor.kt:288`); scheduler claims are released by the worker that owns them (`WorkflowSchedulerStore.kt:109,144`). No takeover command exists. |
| Checkpoint direct state change | — | — | — | — | — | — | — | **NOT IMPLEMENTED** as a control operation. Persistence exists; authoritative persisted cancellation/resume-fencing is **0.7.7 scope**. |
| Dashboard control actions | `tramai-dashboard/.../DashboardSettingsController.kt` only renders settings | — | — | — | — | none | — | **No control surface exists** — good; nothing to secure yet (0.7.8) |

### 3.2 Direct-store mutation paths (bypass surface)

The store interfaces are ordinary public JVM interfaces with public implementations:

- `WorkloadRegistrationStore` (+ `InMemoryWorkloadRegistrationStore`) — `tramai-control-plane/.../`
- `ApprovalStore` / `ApprovalContinuationStore` — `tramai-core/.../core/approval/` (+ in-memory impls in `tramai-security/.../approval/`)
- `WorkflowRunStore`, `InMemoryAuditLogStore`, `InMemoryWorkerRegistry` — `tramai-server/.../server/`
- JDBC variants in `tramai-persistence-jdbc`, `tramai-spring-boot-starter-sovereign-persistence-jdbc`

**Any in-process component holding a store reference can mutate authoritative state without any
command, actor or authorization.** There is no fence below the command layer and none is proposed here:
0.7.6e must ensure every *supported* control path routes through one authorized boundary, and 0.7.6g must
prove that a caller holding only the control API cannot reach a store directly. Making the stores
unreachable (module/visibility) is a 0.7.6e decision, not an audit change.

---

## 4. Gate 3 — Operator capability vs runtime capability

**Current state: the separation does not exist, and the one existing scope enum conflates the domains.**

`ApiKeyScope` (`PlatformModels.kt:18-32`) mixes them in a single flat namespace:

- `RUN` — *cause AI execution* (runtime domain);
- `READ` — *inspect runs* (operator domain);
- `ADMIN` — *mint/revoke keys, install plugins, register webhooks, read the audit log*
  (`PlatformController.kt:125-207`) — a mixture of operator administration (keys, audit) and
  **runtime-affecting configuration** (plugin install changes what the runtime can do; webhook
  registration changes what can trigger execution).

Consequences, precisely:

- Holding `RUN` does **not** grant `ADMIN` (no escalation *today*), because `grants()` compares scope
  membership. The defect is **structural, not an immediate privilege bug**: one vocabulary serves both
  authorities, so 0.7.6b cannot reuse it without inheriting the conflation.
- `ADMIN` is the only way to change runtime configuration (plugins) and it is also the only way to manage
  API keys — so "may administer operators" and "may change what the AI runtime can execute" are the same
  permission today. Under the epic invariant `operator capability != runtime capability`, that is a
  violation of the target model at the vocabulary layer.
- Scopes are **stored server-side at key creation** (`ApiKeyService.create`, `CreateApiKeyRequest.scopes`)
  and validated against the enum (`ApiKeyScope.fromWireName` throws on unknown), so a client cannot invent
  a capability name — that invariant already holds **where this mechanism is wired**, and must be preserved
  in 0.7.6b.

Runtime capability governance elsewhere in the tree is a *different* mechanism and must stay separate:
`ToolSecurityMetadata`, `ToolPermission*` evidence, policy decision evidence
(`tramai-core/.../core/policy/ToolSecurityMetadata.kt`, `tramai-security/.../evidence/ToolPermission*`).
Nothing there should ever be expressible as an operator capability.

### 4.1 Proposed minimum authority model (vocabulary only — no production types in this task)

```text
Authenticates the operator   : the adapter (OIDC/framework) → OperatorPrincipal(id, issuer, claims)
Authorizes the command       : OperatorCapability check at the ONE control boundary
Executes the mutation        : the existing authority (WorkloadRegistrationAuthority,
                               ApprovalDecisionControlPlane, run store) — unchanged, still fencing versions
```

Two distinct types, never one:

- `OperatorPrincipal` — *who is calling*: subject id, issuer, authentication time, resolved capabilities.
  Created **only** by the authentication adapter; not constructible from a request body.
- `OperatorCapability` — *what an operator may command*: a closed enum over operator operations
  (`INSPECT_GOVERNANCE`, `ADMINISTER_WORKLOAD`, `DECIDE_APPROVAL`, `CANCEL_RUN`, …). It must not gain
  members that describe AI execution resources.
- Runtime capability (`AiTool`/tool permission metadata) stays exactly where it is. The two never share a
  type, a namespace or a check function.

Four prohibitions from the brief, mapped to enforcement points:

| Prohibition | Enforced by |
|---|---|
| Tool-execution permission must not imply control-plane administration | Disjoint types: no conversion between runtime permission and `OperatorCapability` |
| Dashboard state must not grant permissions | `OperatorPrincipal` only from the adapter; no dashboard-supplied field is an input to it |
| Client-supplied capability names must not be authoritative | Capabilities resolved server-side from the principal; request bodies carry *targets and preconditions* only |
| Trusted runtime identity must not become an authenticated operator | Runtime service identity is not an `OperatorPrincipal`; machine ingress keeps its own HMAC path |

---

## 5. Gate 4 — Authentication boundary assessment

Verified facts: no web-security dependency is on the classpath (`libs.versions.toml:19,49`); the only
credential verification in the release is the webhook HMAC verifier; the only principal abstraction is
`ApiKeyAuthenticator` in an unwired module; the dashboard inspects bean names instead of enforcing anything.

**Recommendation for 0.7.6c (one concrete architecture):**

A single framework adapter in `tramai-spring-boot-starter-sovereign-ops-rest` (where the control endpoints
already live) that:

1. depends on `spring-boot-starter-oauth2-resource-server` and configures a `JwtDecoder` from
   `spring.security.oauth2.resourceserver.jwt.*` properties (issuer-uri or jwk-set-uri). **No vendor code**:
   Keycloak/Entra/Okta differ only in issuer metadata.
2. maps the validated JWT to `OperatorPrincipal` in **one** `Converter<Jwt, OperatorPrincipal>`; capabilities
   are resolved server-side from a configured claim→capability mapping, and an unmapped/absent claim yields
   an **empty** capability set (deny), never a default grant.
3. exposes the principal to the control boundary as a **method argument**, not a thread-local, so that
   headless and non-Spring callers (Java, tests) construct `OperatorPrincipal` explicitly. This keeps the
   core IdP-neutral and the headless-first requirement intact.
4. fails closed: no token / invalid signature / unknown issuer / missing expiry → `AuthenticationException`
   → 401; authenticated but insufficient capability → `AuthorizationException` → 403; **no anonymous
   fallback and no "trusted header" mode**, because a header is exactly the forgeable input F1 already
   demonstrates.

Why not reuse the alternatives:

- **`ApiKeyAuthenticator`**: keep it for machine/tenant API access, but it is the wrong shape for a human
  operator boundary — a static shared secret per tenant cannot express "this individual operator approved
  this request", which 0.7.6f must evidence. It also sits in a module nothing depends on, so adopting it
  means wiring a dead module into the release.
- **Extending `Spring Security` in `tramai-core`**: rejected — it would put framework authority semantics
  into the framework-neutral core, against `ARCHITECTURE.md` and the epic non-goals.
- **Custom token parsing in the controller**: rejected — hands us JWT validation, the exact code that must
  not be hand-rolled.

---

## 6. Gate 5 — Threat model and 0.7.6g discriminator plan

| # | Scenario | Required authoritative behaviour | Current state | Discriminator for 0.7.6g |
|---|---|---|---|---|
| 1 | Unauthenticated privileged command | 401 before any state read | **Absent** — handler executes (F1/F3) | Request with no header → 401; assert store version unchanged |
| 2 | Authenticated but unauthorized operator | 403; no mutation | **Absent** (F5) | Valid token, capability set without `DECIDE_APPROVAL` → 403, version unchanged |
| 3 | Forged capability or actor identity | Body claims are never authority | **Absent** — body `actorId`/`actorRole` are used (F1) | Send `actorId=victim` with another operator's token → decision recorded under the *token* subject; assert audit actor ≠ body actor |
| 4 | Operator controlling another target | Target-scope check server-side | **Absent** | Token scoped to deployment A, command against B → 403 |
| 5 | Stale expected version | Typed stale outcome, no overwrite | **Fail-open** (F2) | Two decisions with the same observed version → exactly one `Stale`, one success |
| 6 | Concurrent conflicting commands | One winner, loser typed | Fail-open for approvals (F2); **correct** for workload registration (`WorkloadControlPlaneContractTest`) | Parallel approve+deny → one terminal state, one `Stale` |
| 7 | Duplicate/retried control request | Idempotent or rejected | **UNRESOLVED** for decisions; `IdempotencyKeyUtil` exists in core (`tramai-core/.../approval/IdempotencyKeyUtil.kt`) but the decision path does not reference it | Same request twice → one mutation, second reports replayed/no-op |
| 8 | Direct store mutation bypass | Not reachable from the control API | **Open** — stores are public (3.2) | Test holding only the REST client cannot reach a store; module-graph check that the REST starter does not export store beans for caller mutation |
| 9 | Authorization dependency unavailable | Deny (fail closed) | **Absent** — nothing to be unavailable | Remove the capability resolver bean → control endpoints fail closed, not open |
| 10 | Audit/evidence failure | Mutation does not commit without its evidence | **Partially verified** — outbox commits decision+audit together | Force outbox failure → no decision committed |
| 11 | UI-only authorization | UI state never authority | **Absent by design** — dashboard has no control UI | Dashboard flag/JS cannot produce a capability (no input path) |
| 12 | Identity loss across async/approval/recovery | Principal carried or re-established; never defaulted | **UNRESOLVED** — resume path has no actor to lose (F3) | Resume after restart records the deciding operator, not "system" |

Scenarios 1–5 and 11 are the fail-open family the repository treats as P1; 0.7.6g must test them as
*negative* cases with an explicit assertion that state did not change, not merely a non-2xx status.

---

## 7. Gap register

**BLOCKER** (prevents the authoritative control model)

| ID | Gap | Evidence |
|---|---|---|
| B1 | No operator authentication on any control path; actor comes from the request body | F1, F3 |
| B2 | Approval decisions are fail-open on concurrency: `expectedVersion` defaults to auto-detect | F2 |
| B3 | Required approver role is declared and never enforced | F5 |
| B4 | No operator capability vocabulary; `ApiKeyScope` conflates operator and runtime authority | §4 |
| B5 | No single authoritative control boundary: privileged mutations are reachable from several controllers plus public stores | §3 |

**REQUIRED** (needed for 0.7.6 acceptance)

| ID | Gap | Evidence |
|---|---|---|
| R1 | Dashboard derives its security posture from bean presence | F6 |
| R2 | No IdP-neutral authentication seam in the release; only `spring-security-crypto` is available | §2.3 |
| R3 | Approval decision idempotency is unimplemented although `IdempotencyKeyUtil` exists | §6 #7 |
| R4 | No actor attribution on workload lifecycle, run cancel, run resume, run trigger | §3.1 |
| R5 | `requiredRole` is stored/queryable, which reads as enforcement; documentation must not imply it is enforced | F5, `JdbcApprovalInboxQueryService.kt:32,76,93` |

**DEFERRED**

| ID | Gap | Owner |
|---|---|---|
| D1 | Authoritative persisted cancellation + resume fencing | 0.7.7 |
| D2 | Dashboard control UI and its authorization | 0.7.8 |
| D3 | Retention of `model_attempt_observations` (R1-action) | release promotion review |
| D4 | Worker/lease takeover as a supported operator command | not currently planned — requires an epic decision |
| D5 | Vendor-specific IdP productization (Entra/Okta/Keycloak), SAML | later release |

**ACCEPTED LIMITATION** (owner decision required)

| ID | Limitation | Justification |
|---|---|---|
| A1 | Actor ids are format-validated only, never authenticated, for the duration of 0.7.6a–0.7.6d | Recorded, not claimed safe. `SafeActorIdPolicy` must keep saying so until 0.7.6e wires the principal. |
| A2 | In-process callers holding a store reference can mutate state | Library design constraint: TramAI is a JVM library, not a server. 0.7.6e/g must confine the claim to "the supported control path". |
| A3 | `tramai-platform` remains an unwired module | Its auth shape is the reuse candidate; wiring it into the release is out of scope here. |

---

## 8. Proposed architecture for 0.7.6

```text
        HTTP / headless caller
                │  (bearer token, or HMAC for machine ingress)
                ▼
 ┌──────────────────────────────────────────────┐
 │ Adapter (Spring resource-server, ops-rest)    │  0.7.6c
 │  • JwtDecoder (issuer/jwks from properties)   │
 │  • Jwt → OperatorPrincipal (capabilities      │
 │    resolved server-side; unknown claim = ∅)   │
 └───────────────┬──────────────────────────────┘
                 │ OperatorPrincipal (method argument, not thread-local)
                 ▼
 ┌──────────────────────────────────────────────┐
 │ Control boundary (one entry per operation)    │  0.7.6d/e
 │  1 authenticate (principal present)           │
 │  2 authorize (OperatorCapability)             │
 │  3 validate target + expected version         │
 │  4 delegate to the existing authority         │
 └───────────────┬──────────────────────────────┘
                 │ typed command + precondition
                 ▼
 ┌──────────────────────────────────────────────┐
 │ Existing authorities (unchanged semantics)    │  0.7.1/0.7.5
 │  WorkloadRegistrationAuthority (CAS)          │
 │  ApprovalDecisionControlPlane (CAS, outbox)   │
 │  run store / continuation store               │
 └───────────────┬──────────────────────────────┘
                 │ decision + audit fact (same transaction)
                 ▼
        Audit / outbox / governance projection   0.7.6f
```

Boundary ownership: authentication → adapter; authorization → control boundary; mutation authority →
existing authority; evidence → outbox/audit emitter; read model → governance projection (unchanged).

---

## 9. Refined 0.7.6 candidate boundaries

The eight candidates remain correct. Evidence-based adjustments (**recommended, not silently applied** —
the epic scope is authoritative):

| Task | Reuse | Required new behaviour | Public API impact | Non-goals |
|---|---|---|---|---|
| **0.7.6b** Principal + operator capability | `SafeActorIdPolicy` shape rules; `ApiKeyScope` *shape* only (enum + `fromWireName` pattern) | `OperatorPrincipal`, `OperatorCapability` (closed, operator-only), resolver contract; explicit statement that runtime capability vocabularies are not reused | New **core-contract** types (additive) | No HTTP, no OIDC, no policy engine |
| **0.7.6c** OIDC/Spring boundary | ops-rest module; webhook HMAC as the machine-ingress precedent | Resource-server config, `Jwt → OperatorPrincipal` converter, fail-closed behaviour, headless default (no adapter ⇒ explicit principal required) | Additive starter config | No vendor code, no SAML, no login UI |
| **0.7.6d** Typed control commands | `WorkloadStateVersion` CAS pattern; `ApprovalPairCommand.expectedVersion` **must become required** | Required target identity + **required** expected version + reason; typed `Stale`/`Denied`/`NotFound` outcomes | Changes an existing optional field → **compatibility review needed** (this is the one place 0.7.6 may need a deliberate break; see 0.7.6b stop condition) | No new lifecycle operations |
| **0.7.6e** Authorization path | `ApprovalControlPlane*`, `WorkloadRegistrationAuthority` | One boundary per operation: authenticate → authorize → validate → delegate; remove body-supplied `actorId`/`actorRole`; enforce `requiredRole` | Removes body fields → **breaking for existing clients**; requires a compatibility ADR | No store redesign |
| **0.7.6f** Privileged evidence | Outbox/audit emitters; governance projection | Actor = authenticated subject (+ issuer), previous/resulting state, decision, correlation | Additive evidence fields | No new evidence format version unless required |
| **0.7.6g** Adversarial proof | Existing negative-test style from 0.7.5g/0.7.5h | The 12 discriminators in §6 with state-unchanged assertions; one mutation per guard | None | No new feature work |
| **0.7.6h** Integration/docs | module docs, module matrix, examples | Headless control-API doc, security doc, epic gate; correct the dashboard's bean-presence claim | Docs only | No dashboard controls |

**Stop conditions for 0.7.6b:** if the capability contract cannot be defined without (a) changing a
shipped public signature, (b) touching `config/quality/0.6.0-baseline.json`, or (c) reusing a runtime
capability type, stop and report — those are owner decisions.

**Dependencies preserved:** 0.7.1 (resource identity, CAS) and 0.7.4 (evidence/read model) remain hard
prerequisites. 0.7.7 keeps authoritative cancellation/resume fencing; 0.7.6 delivers the authorized
command architecture it will use. D4 (worker takeover) is *not* silently added to 0.7.6.

---

## 10. Verification for this task

Audit-only: no production code, no test, no gate, no baseline was modified. The verification below is
what makes the document's evidence reproducible.

```
git rev-parse HEAD                       ff1d3da47d72d7c51bbcc416bcd555fde68a90c0
git status --porcelain                   only the three docs added/changed by this task
./gradlew verifyChangePolicy -PchangeClass=documentation
./gradlew spotlessCheck verifyModuleDocContract verifyModuleMatrixDrift
./gradlew verifyStaticAnalysis -PtramaiStaticAnalysisBaseRef=<base>
./gradlew verifyJUnitTestSignatures verifyNoPublishedTestFixtures
```

Runtime/public API changes: **none** (documentation-only diff). Test results: unchanged from the merged
release head; the audit adds no test and claims no new passing test.

Claims labelled **UNRESOLVED** and deliberately not closed here: §6 #7 (decision idempotency),
§6 #12 (identity across async/recovery). Both need 0.7.6d/0.7.6e implementation to be provable.

---

## 11. Definition-of-done check

| Question a successor must not have to guess | Answered in |
|---|---|
| Which identity/policy components are authoritative | §2.1–2.5 (resource identity authoritative; actor identity absent) |
| Where operator authentication will enter | §5, §8 (adapter in ops-rest) |
| Where operator authorization must be enforced | §8, §9 0.7.6e (one boundary per operation) |
| How authorized controls reach runtime authority | §8 (delegate to existing authorities, unchanged) |
| Which store mutations must be fenced | §3.2, §6 #8, §9 0.7.6d/e |
| What evidence proves the operator and result | §2.5, §9 0.7.6f |
| Which adversarial tests prove the boundary | §6 (12 scenarios with discriminators) |
