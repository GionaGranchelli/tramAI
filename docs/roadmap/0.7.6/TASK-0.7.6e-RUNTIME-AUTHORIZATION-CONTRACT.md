# 0.7.6e — Runtime Authorization Enforcement (architecture and enforcement contract)

**Status:** contract for review. No production code has been changed by this document.

Base: `release/0.7.0` at `57ea33ebaff8ac847a2088bfb1b54c952028fd1d` (0.7.6d merged).

## Why this task exists

0.7.6c established *who is calling* (verified JWT → `OperatorPrincipal` with server-mapped capabilities).
0.7.6d established *what state the caller saw* (`expectedVersion` required, typed `Stale`, CAS as the
final authority). Neither made a privileged operation check that the caller may perform it. Approval
decisions are concurrency-safe, not authorization-safe.

Findings from `AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md` this task must close:

| Finding | Current fact in the tree | Required outcome |
|---|---|---|
| **F1** client-controlled actor identity | `ApprovalControlPlaneController` builds the command from `request.actorId` and `ApproverRole(request.actorRole)` — the caller states who it is | identity derived from the authenticated principal |
| **F3** unprotected privileged endpoints | the controller has no principal parameter and no authorization call; the filter chain authenticates but nothing authorizes | enforced server-side authorization before any decision |
| **F5** unenforced approval roles | `ApprovalDecisionAuthorizer` defaults to `AllowAllApprovalDecisionAuthorizer`, and the role it receives is the caller's claim | validated against trusted operator authority |
| direct-store bypass | `SovereignOpsApprovalMutationStore` is an injectable bean; any component in the process can mutate authoritative approval state without touching the control plane | no *supported* privileged path bypasses the boundary |

**Invariant for the whole task:**

> No privileged state mutation without verified operator identity, a sufficient capability, and valid
> command preconditions.

## Trust model — what is authority and what is input

Authority is produced in exactly one place: the 0.7.6c authentication boundary, which validates a
credential and maps roles to capabilities **server-side**. Everything arriving on the wire is input.

- `OperatorPrincipal` is data. Constructing one grants nothing; `tramai-core` says so and it stays true.
  Its `capabilities` are an immutable snapshot resolved from server configuration, never from token claims
  alone.
- The request **body is never identity**. `actorId` and `actorRole` are removed from the decision request
  contract; they cannot be trusted, and leaving them readable invites a regression.
- An authenticated principal with an empty capability set is a valid, expected state: 401 is for "no
  verified identity", 403 is for "verified identity, insufficient authority".

In-JVM honesty: no Kotlin type can be unforgeable inside one process — reflection reaches every
constructor. The guarantee this task makes is therefore precise and testable: **no supported call path**
reaches an authoritative mutation without a verified principal, and the unsupported ones (reflection,
classloader tricks) are documented as out of scope, not claimed as blocked.

## Enforcement placement

Enforcement is ordered outward-in; each layer is non-overridable where trust is at stake.

### L1 — Controller: identity injected, never read

`OperatorPrincipal` is taken as a handler parameter, resolved by the existing
`OperatorPrincipalArgumentResolver` from `SecurityContextHolder.getAuthentication()`. That resolver
already exists (0.7.6c) and is compiled into the controller signature, so there is no seam a host
application can replace with something that trusts a header or a body field.

Rejected alternatives, recorded so they are not re-proposed:

- **A `@ConditionalOnMissingBean` principal supplier**: an overridable seam is not trust ownership. A
  host bean would silently become the identity source.
- **Reading `request.actorId` and comparing it to the principal**: keeps the forgery on the wire and
  makes correctness depend on remembering to compare.
- **`@PreAuthorize` alone**: registers a policy DSL where the requirement is that no privileged call
  exists without a capability; an annotation on a method is bypassed by any other caller of the same
  method, including the direct-store path below.

### L2 — Control plane: capability required before the store

`ApprovalDecisionControlPlane.approve/deny` take the verified principal alongside the command. Before
any read of authoritative state they require `OperatorCapability.DECIDE_APPROVAL` via
`OperatorPrincipal.authorize`, which returns the typed `OperatorAuthorizationResult` (already shipped:
`Authorized` / `Denied(missingCapability)`) so a denial can name what was missing.

The framework-neutral layer enforces this, not the controller: the controller is one caller, the
control plane is the boundary every caller routes through.

**Order of checks (explicit, because it is observable):**

```
authenticate (0.7.6c filter chain)  →  L1 principal resolution
  →  authorize: DECIDE_APPROVAL capability          [403, typed Unauthorized]
  →  authorize: role policy (L3)                     [403, typed Unauthorized]
  →  precondition: expectedVersion vs authoritative  [409 STALE]
  →  terminal-state shortcuts (Already*)             [200]
  →  CAS mutation + audit outbox                     [200]
```

Authorization precedes the version precondition on purpose: an unauthenticated or unauthorized caller
learns nothing about approval state, including whether its version was current. **This does not weaken
F2** — the version check still runs before the terminal shortcuts and before any mutation, and a losing
concurrent command still yields `Stale` with no outbox fact. What changes is only which of two rejections
wins for a caller that fails both; the 0.7.6d tests that pin F2 are re-stated for authorized callers
rather than deleted.

Result type: a new `ApprovalDecisionResult.Unauthorized(missingCapability: OperatorCapability?)`,
distinct from `Denied` (a *successful* business decision to deny an approval) and from `Conflict`
(illegal transition). The REST adapter maps it to 403 carrying the missing capability wire name.

### L3 — Role policy: the role is verified, the requirement stays policy

The commanded `actorRole` is no longer accepted from the body. It is read from the principal, whose
authority comes from `OperatorSecurityProperties` role mappings, extended so a role can also grant
approver roles: `tramai.sovereign.ops.security.roles.<claim>.approver-roles: [...]`. A principal carries
`approverRoles: Set<ApproverRole>` resolved server-side, exactly like `capabilities`.

**Fact worth stating plainly:** neither `ApprovalRequest` nor `ApprovalBinding` stores a required role
today, so "validate `requiredRole` against the approval" is not implementable against persisted state
without a schema change. F5 closes by making the role *trusted* — the framework supplies the verified
role and `ApprovalDecisionAuthorizer` decides with it. A deployment that needs a per-approval
requirement implements it in that policy using the `ApprovalRequest` it is already given; the framework
does not invent a persisted requirement it cannot back with data.

`AllowAllApprovalDecisionAuthorizer` stays as a named test double, and the wiring **fails startup** when
`tramai.sovereign.ops.rest-control-plane-enabled=true` and protection is enabled but no authorizer bean
is configured — the same fail-closed shape 0.7.6c uses for missing security configuration. A permissive
default that runs in production is the vulnerability, not the convenience.

### L4 — Direct-store bypass

Inventory of supported mutation paths for approval state:

1. `ApprovalControlPlaneController` → control plane → `SovereignOpsApprovalMutationStore` (the intended path).
2. Direct injection of `SovereignOpsApprovalMutationStore` — any component can currently call
   `approveApprovalWithAuditIntent` / `denyApprovalWithAuditIntent` with an arbitrary version.
3. `ApprovalStore.update`-style writes, if any remain — to be enumerated before implementation.

Contract: the store's decision methods require an `AuthorizedDecision` value that only the control plane
produces after L2/L3 pass, so path 2 stops being *supported*. The bean is not hidden and reflection is
not blocked; that ceiling is documented rather than papered over. Path 3 is closed by making the
version-fenced mutation methods the only mutation entry points, and by a test that asserts an
authority-free call is refused at compile time where possible and at runtime with a typed failure
otherwise.

## Failure semantics

| Situation | Result | HTTP |
|---|---|---|
| no verified identity | filter chain rejects | 401 |
| authenticated, missing `DECIDE_APPROVAL` | `Unauthorized(DECIDE_APPROVAL)` | 403 `MISSING_CAPABILITY` |
| authenticated, role not permitted by policy | `Unauthorized(null)` + policy reason | 403 `ROLE_NOT_PERMITTED` |
| authorized, stale supplied version | `Stale(expected, current)` | 409 `STALE` |
| authorized, target missing | `NotFound` | 404 |
| authorized, illegal transition | `Conflict` | 409 |
| authorized, business decision to deny | `Denied` | 200 |

`Denied` never expresses an authorization failure.

## Compatibility impact (declared, not disguised)

- `ApprovalDecisionControlPlane.approve/deny` gain a required `OperatorPrincipal` parameter — a deliberate
  Kotlin/JVM break. No overload accepts a decision without an identity; an overload that did would be the
  vulnerability.
- `OperatorPrincipal` gains `approverRoles` — additive for Kotlin source, but the primary constructor
  changes for Java, so it is a binary break and needs its own API migration entry.
- `ApproveDenyRequest` loses `actorId` and `actorRole` — REST contract break; clients must stop sending
  them (ignored if sent, never read).
- `OperatorSecurityProperties` gains `approver-roles` per role.
- New `ApprovalDecisionResult.Unauthorized`.
- Both published modules (`:tramai-spring-boot-starter-sovereign-ops`,
  `:tramai-spring-boot-starter-sovereign-ops-rest`) and `:tramai-core` may need LANDED-preserving append
  entries in `config/quality/api-migrations.yml`, hashes recomputed from the committed dumps.

## Tests this contract requires (each mutation-verified)

1. Unauthenticated decision → 401 and **zero** control-plane or store interaction.
2. Authenticated with no capabilities → 403 `MISSING_CAPABILITY`, no mutation, no outbox record.
3. Body-supplied `actorId`/`actorRole` are ignored: the recorded `decidedBy` equals the principal subject,
   proven by sending a different body value.
4. Role policy denial → 403, no mutation (discriminating: policy returns false for the verified role).
5. Authorized + stale version → 409 `STALE`, status/version/outbox untouched (F2 preserved).
6. Authorized concurrent approve-vs-deny → exactly one commit (0.7.6d parity retained in memory and JDBC).
7. Authority-free direct store call → refused.
8. Startup fails when the control plane is enabled with protection on and no authorizer configured.

## Non-goals (explicitly out of scope)

- **0.7.6f** — provenance of audit attribution: recording that a decision came from a verified principal
  rather than trusting the string is this task; the audit attribution contract is 0.7.6f.
- **0.7.6g** — the comprehensive adversarial campaign over all privileged paths.
- **0.7.7** — durable resume fencing. The resume path keeps its nullable-version fallback
  (`SovereignOpsApprovalResumeControlPlane.kt:89`, `command.expectedApprovalVersion ?: approval.version`),
  unchanged and pre-existing, because after a restart there is no caller-observed version to bind to.
  **Recorded here as a 0.7.7 architectural concern, not a 0.7.6d defect.**
- Run cancellation and workload administration authorization beyond declaring their capability
  requirements — those paths are inventoried, not implemented, in this task unless the contract review
  extends the scope.

## Stop conditions

Stop and report if the enforcement requires: changing a quality gate, adding a suppression, baselining a
new finding, or a persisted schema change for approval roles; if the principal cannot be resolved without
an overridable bean; or if closing the direct-store path requires touching 0.7.7 fencing.
