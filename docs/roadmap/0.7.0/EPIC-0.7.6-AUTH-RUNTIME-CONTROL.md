# Epic 0.7.6 — Authentication, Authorization & Runtime Control

**Branch:** `epic/0.7.6-auth-runtime-control`  
**Status:** 🟡 Active — 0.7.6a–0.7.6e delivered; 0.7.6f next  
**Dependencies:** 0.7.1 HARD; 0.7.4 HARD

## Executive decision

Expose privileged control-plane commands only through an identity-provider-neutral authentication boundary, server-side capability authorization, target/precondition validation, runtime authority, and attributable evidence.

## Scope

- generic OIDC/Spring Security integration boundary;
- authenticated control-plane principal;
- operator/control-plane capability-based server-side authorization, explicitly distinct from runtime capabilities such as tools, MCP operations, remote agents, models, or network destinations;
- command/target/version/precondition contracts;
- typed lifecycle/control commands for supported P0 operations;
- actor/reason/previous/resulting-state/evidence correlation;
- denial/stale/precondition/concurrency semantics;
- headless API support.

## Non-goals

- Entra/Okta/Keycloak productized compatibility;
- native SAML stack;
- policy authoring UX;
- dashboard-local authorization.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.6a | Security/control baseline audit | ✅ **Delivered** — [AUDIT-0.7.6a](../0.7.6/AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md). Inventory, control-path matrix, threat model, gap register (5 BLOCKER / 5 REQUIRED / 5 DEFERRED / 3 ACCEPTED) and the [0.7.6b handoff spec](../0.7.6/TASK-0.7.6b-PRINCIPAL-OPERATOR-CAPABILITY.md). |
| 0.7.6b | Principal + operator capability contract | ✅ **Delivered** — `dev.tramai.core.operator`: `OperatorPrincipal` (subject+issuer identity, typed `authorize`), closed `OperatorCapability` (four audited operations, no wildcard), `OperatorAuthorizationResult`. Contracts only: **no enforcement**, and the module card says so. Spec: [TASK-0.7.6b](../0.7.6/TASK-0.7.6b-PRINCIPAL-OPERATOR-CAPABILITY.md) |
| 0.7.6c | OIDC/Spring boundary | ✅ **Delivered** — `OperatorAuthenticationAutoConfiguration` in the ops-rest starter: RS256-only `NimbusJwtDecoder` over a configured JWKS, strict issuer + required audience, server-controlled `role-claim`/`role-mappings` → closed `OperatorCapability`, recordable-subject policy (A4), `OperatorPrincipal` handed to handlers as an explicit argument. Off by default; fail-closed when on. **Authentication only — the approval endpoints remain unauthorized until 0.7.6e.** Spec/evidence: [TASK-0.7.6c](../0.7.6/TASK-0.7.6c-OIDC-SPRING-BOUNDARY.md) |
| 0.7.6d | Typed control commands | ✅ **Delivered** — F2 closed: `ApprovalDecisionCommand.expectedVersion` is a **required** caller-observed precondition (no default, no nullable overload, negative rejected at construction), `ApprovalDecisionResult.Stale(expected,current)` reports a stale precondition ahead of terminal-state idempotency, and the mutation store receives the caller's version verbatim so its CAS stays the final authority. REST answers 400 for a missing/null/negative version and 409 `STALE` otherwise; a rejected command leaves approval state and the audit outbox untouched. **Version fencing only — operator authorization is still 0.7.6e.** Spec: [TASK-0.7.6d](../0.7.6/TASK-0.7.6d-TYPED-CONTROL-COMMANDS.md) |
| 0.7.6e | Runtime authorization path | ✅ **Delivered** — squash `440d63fc` (reviewed head `e240bf95`, base `a6bac10b`). Authenticate → authorize → validate → mutate: an approval decision requires a verified operator, `OperatorPrincipalFactory` derives capabilities and approver roles from verified claims plus server configuration, `OperatorAuthority` is minted at a single boundary, and the mutation is version-fenced behind the authorization gate. REST without operator security or without a resolvable policy fails startup; approver-role configuration is validated for shape and satisfiability at startup. The four trust collaborators are not beans — a `@Primary` host bean won type-based injection even with `@ConditionalOnMissingBean`, so the chain is built inline. **Still unresolved, explicitly recorded:** `GET /{approvalId}` authentication (F3), the originally required role not persisted as authoritative state (F5), and the public `ApprovalStore.transition` bypass, which the L4 inventory ratchet flags but does not close. Spec: [TASK-0.7.6e contract](../0.7.6/TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md), [implementation notes](../0.7.6/TASK-0.7.6e-IMPLEMENTATION-NOTES.md) |
| 0.7.6f | Privileged evidence | Actor, decision, previous/resulting state and runtime/audit correlation |
| 0.7.6g | Adversarial/security/mutation proof | Denied/stale/forged/direct-store/concurrent command discriminators |
| 0.7.6h | Integration/docs | Headless control API/security docs and Epic gate |

## Core invariants

```text
UI visibility != authorization
privileged mutation requires authenticated actor + server-side authorization
stale command cannot silently overwrite newer authoritative state
dashboard/client cannot directly mutate authoritative persistence
operator capability != runtime capability
```

## Acceptance criteria

- Supported privileged operations are usable headlessly.
- Authorization is enforced server-side and is IdP-neutral at the governance boundary.
- Successful controls are attributable and reconstructable.
- Direct-store/client bypasses are absent from supported control paths.
- Operator authorization vocabulary is unambiguous and does not become the canonical representation for runtime tool/MCP/model capabilities.

## Audit baseline (0.7.6a, delivered)

Recorded, not remediated. Full evidence: [AUDIT-0.7.6a](../0.7.6/AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md).

What already exists and is reusable:

- fenced workload mutation — `WorkloadRegistrationAuthority` with `WorkloadStateVersion` compare-and-set, and
  a port (`WorkloadControlPlaneCommands`) with no unconditional write operation;
- approval optimistic concurrency and a transactionally coupled decision+audit outbox;
- shape validation for actor identifiers (`SafeActorIdPolicy`), explicitly format-only;
- one real authenticated ingress: GitHub HMAC webhook verification with a replay cache;
- a working authentication + scope-authorization *shape* in `tramai-platform` (`ApiKeyAuthenticator`,
  `requireScope`), which no module depends on.

What does not exist (BLOCKERs gating 0.7.6e):

1. operator authentication on any control path — approval decisions take `actorId`/`actorRole` from the
   request body (`ApprovalControlPlaneController.kt:75-108`);
2. fail-closed concurrency on approvals — `ApprovalDecisionCommand.expectedVersion` defaults to `null` and
   `null` means auto-detect (`SovereignOpsApprovalDecisionControlPlane.kt:109,118`), so the store CAS always
   succeeds and a concurrent decision silently overwrites;
3. enforcement of the declared approver role (`requiredRole` is requested and stored, never compared);
4. an operator capability vocabulary — the only scope enum mixes operator and runtime authority;
5. a single authoritative control boundary — privileged mutations are reachable from several controllers
   and from public store interfaces.

These findings do **not** change the epic's acceptance criteria, and none of the criteria below may be
marked complete because a contract exists. `docs/modules` documentation must not describe these paths as
authorized until 0.7.6e enforces it.

## Adversarial proof

Reject unauthenticated, unauthorized, stale-target and forged-capability controls; prove UI/client flags cannot create authority; prove direct checkpoint/store mutation is not a supported control API.
