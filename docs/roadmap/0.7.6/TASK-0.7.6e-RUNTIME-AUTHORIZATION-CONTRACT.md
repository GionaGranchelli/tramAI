# 0.7.6e — Runtime Authorization Enforcement (architecture and enforcement contract)

**Status:** revision 3 for review. Supersedes revision 2 (`7b0b02fa`). No production code changed.

Base: `release/0.7.0` at `57ea33ebaff8ac847a2088bfb1b54c952028fd1d` (0.7.6d merged).

Revision 3 changes exactly four things review asked for — the authority issuance model, the L4
enforcement specification, the Spring wiring acceptance tests, and two factual errors — and leaves the
agreed parts untouched: principal-resolver reuse, authenticate-before-authorize ordering, F2 guarantees,
`policyVersion`-based role checks, and the F3/F5 carry-forward.

**Trust model, stated before anything else so no later paragraph can overpromise:** this task secures a
**shared Spring/JVM process**, and in a shared process every privileged bean is reachable by code running
inside it. The guarantee is therefore about **paths, not code**: no *supported* path lets an
unauthenticated request, or an identity carried on the wire, reach an authoritative approval mutation.
Any in-process component that can inject the authentication adapter or mint its own authority is **inside
the trust domain and outside the guarantee** — that is recorded as an accepted limitation, not defended
by custom cryptography.

## Scope

This task secures **one** privileged operation family: approval decisions (`approve` / `deny`). Every
other F3 surface is inventoried in §6 and carried forward as explicitly **UNRESOLVED**, so the epic must
record F3 as *partially addressed*.

**Invariant, scoped to what is enforced:**

> No approval decision reaches authoritative mutation through a supported path without a verified
> operator identity, the `DECIDE_APPROVAL` capability, a satisfied per-approval role requirement, and a
> valid version precondition.

## 1. Authority issuance: bounded in-process trust model (option A)

Revision 2 proposed an HMAC handle and claimed a forging boundary it could not establish: the minter was a
Spring bean (any component could inject it), MAC validity proved only that *the minter* produced the
contents, and no replay mechanism existed to back the "consumed exactly once" claim. Custom cryptography
made an in-process type *look* unforgeable without changing who can reach it. **Dropped.**

### 1.1 What replaces it

`OperatorAuthority` is the boundary's statement of a verified identity:

- **Contents:** `OperatorIdentity(issuer, subject)`, `authenticatedAt`, verified `capabilities`, verified
  `approverRoles`, and the verifying boundary's id. Plain data, no signature, no secret.
- **Issuance:** exactly one component — the 0.7.6c authentication adapter — produces it, immediately after
  JWT verification and principal resolution, inside the request-scoped argument resolver path. There is no
  second minting site, and a structural check (§5, test 1b) asserts that no other production code
  constructs one.
- **What it is worth:** it is not proof. Its value is that identity never travels on the wire, that the
  only *supported* producer is the adapter, and that the control plane refuses to act without one.
- **Replay is not a security property, and is no longer claimed as one.** A handle is minted per request
  and not stored. Re-deciding with a replayed handle is prevented by 0.7.6d's version precondition and
  CAS — a second decision on the same observed version returns `Stale` — not by the token. If a
  deployment threat model needs replay-resistant capability tokens (restricted issuance, expiry,
  operation scope, binding to a request nonce), that is a separate design with its own review; it is not
  smuggled into this task.

### 1.2 Trust-boundary classification

| Component | Classification | Change |
|---|---|---|
| `operatorJwtDecoder` (built in-chain) | immutable trust boundary | none |
| `OperatorSubjectPolicy` | immutable trust boundary | none |
| `OperatorCapabilityMapping` | immutable trust boundary | **drop `@ConditionalOnMissingBean`** |
| `OperatorPrincipalFactory` | immutable trust boundary | **drop `@ConditionalOnMissingBean`** |
| `OperatorPrincipalResolver` | immutable trust boundary | **drop `@ConditionalOnMissingBean`** |
| `OperatorApprovalPolicy` (new) | immutable trust boundary | plain `@Bean`, never conditional |
| `Clock` for `authenticatedAt` | substitutable, documented | keeps `@ConditionalOnMissingBean` |
| `ApprovalDecisionAuthorizer` | policy, must be explicit | required bean when options enabled; no permissive default in production wiring |

Removing `@ConditionalOnMissingBean` is a **necessary but insufficient** step: a differently *named* bean
with `@Primary`, or a qualifier, can still change resolution. §3 makes that an executable requirement
rather than an assumption about Spring's behaviour.

## 2. Enforcement layers

Ordering is observable and fixed:

```
0.7.6c filter chain (JWT verified)
  → L1  principal resolved from SecurityContextHolder → OperatorAuthority produced by the adapter
  → L2  DECIDE_APPROVAL capability required                          [403 MISSING_CAPABILITY]
  → L3  per-approval role requirement (policyVersion → roles)        [403 APPROVAL_POLICY_UNAVAILABLE / ROLE_NOT_PERMITTED]
  →     version precondition vs authoritative state                  [409 STALE]
  →     terminal-state shortcuts (Already*)                          [200]
  →     CAS mutation + audit outbox                                  [200]
```

Authorization precedes the version precondition. **Precisely what that hides, and what it does not:** the
capability check (L2) runs before any state lookup, so a caller lacking `DECIDE_APPROVAL` receives a 403
that is identical whether or not the approval exists — that path discloses nothing. The per-approval role
check (L3) needs the approval's `policyVersion`, so it runs **after** the internal lookup; a caller that
*does* hold `DECIDE_APPROVAL` can therefore distinguish an existing approval (403 `ROLE_NOT_PERMITTED` or
403 `APPROVAL_POLICY_UNAVAILABLE`) from a nonexistent one (404 `NotFound`). That is a **limited
existence disclosure, accepted and documented**, not eliminated.

It is accepted because it adds nothing to what a capability-holding operator can already learn: the
existing read endpoint `GET /{approvalId}` returns approval status and decision metadata for any
authenticated caller and is itself still unauthorized (§6). Uniform-outcome alternatives were considered
and rejected: answering 403 for a nonexistent approval means fabricating a policy decision for a record
that does not exist, and answering 404 for an existing-but-wrong-role approval merges two distinct
failures into one that no client can act on. If a deployment needs uniform outcomes, the correct fix is to
authorize the read endpoint and treat approval identifiers as unguessable — a scope decision for §6, not a
silent behaviour change here.

**F2 is untouched:** the version check still precedes the terminal shortcuts and any mutation, and a
losing concurrent command still yields `Stale` with no outbox fact.

### L1 — Controller: identity from the context, never from the body

`OperatorPrincipal` arrives through the existing `OperatorPrincipalArgumentResolver`; the controller passes
on the `OperatorAuthority` produced by the adapter. `ApproveDenyRequest` loses `actorId` and `actorRole`,
which are never read even if a client still sends them. Rejected alternatives, recorded so they are not
re-proposed: an overridable principal supplier; comparing a body `actorId` against the principal;
`@PreAuthorize` alone (an annotation on one method is bypassed by every other caller of that method).

### L2 — Control plane: authority required, capability checked before state, and the actor is derived

`ApprovalDecisionControlPlane.approve/deny` take `(command, OperatorAuthority)`. The domain method
requires the handle, then checks `principal.authorize(OperatorCapability.DECIDE_APPROVAL)`, before reading
authoritative state. No overload accepts a decision without a handle. The framework-neutral layer enforces
this because the controller is only one caller.

**The effective actor comes from the authority, never from the command.** Removing `actorId` and
`actorRole` from the HTTP DTO is not enough on its own: `ApprovalDecisionCommand` still carries both, and
`SovereignOpsApprovalDecisionControlPlane` currently feeds them into three places — the authorization
policy (`authorizer.canDecide(approval, command.actorId, command.actorRole, decisionType)`), the audit
intent (`auditIntent.actor`), and the authoritative mutation (`actor = command.actorId`). Passing an
`OperatorAuthority` alongside does not automatically eliminate any of them. The rule:

> Once authority is verified, the control plane derives the effective actor, the approver role and the
> policy identity **exclusively** from that authority. No supported path may let command-supplied identity
> values override, supplement or shadow verified identity.

Enforcement, in order of preference:

1. **Remove `actorId` and `actorRole` from `ApprovalDecisionCommand`.** They carry nothing the authority
   does not, and a field that still exists is a field the next change reads. The constructor already
   changes in this task, so removing them costs nothing extra and makes the rule structural rather than
   disciplinary. Authorization, `auditIntent.actor` and the store's `actor` all take the authority's
   subject; the approver role comes from the authority's verified approver roles.
2. If implementation review decides to retain the fields for source compatibility, they must be
   **explicitly ignored** for authorization, audit attribution and persistence — read once at construction
   and discarded, never forwarded — and the discriminator below becomes mandatory.

**Discriminator (required either way).** Invoke the control plane **directly** — not through the
controller — with a legitimate `OperatorAuthority` and a command deliberately naming a different actor:
assert the policy receives the verified identity, `decidedBy` and the outbox attribution record the
verified subject, and the store's `actor` is the verified subject. With option 1 the equivalent
discriminator is that the mismatching field cannot be supplied at all: the command carries no actor, so a
caller wanting to act as someone else has nowhere to put it.


### L3 — Per-approval role requirement (unchanged from revision 2)

No required role is persisted (verified absent from `ApprovalRequest` and `ApprovalBinding`), so trusting
the operator's role does not prove *this approval* accepts it. The authoritative source for this task is
server configuration keyed by the approval's own persisted `policyVersion`:

```yaml
tramai.sovereign.ops.security:
  role-mappings:                    # existing 0.7.6c shape, unchanged
    approval-reviewer: [decide-approval]
  approver-role-mappings:           # NEW, same shape: role claim value -> verified approver roles
    approval-reviewer: [medical-reviewer]
  approval-policies:                # NEW: approval policyVersion -> required approver roles
    2024-06-medical-v3: [medical-reviewer]
```

- **Multi-role semantics:** `approver-role-mappings` yields the **union** of approver roles across asserted
  role-claim values (mirroring capability union); `approval-policies` entries are **intersection
  requirements** — permitted when verified approver roles ∩ required roles is non-empty.
- **Fail-closed:** no `approval-policies` entry for the approval's `policyVersion` →
  `Unauthorized(approval-policy-unavailable)`. Never default to allow, never "any verified approver". An
  empty required list is a startup failure.

**F5 status: PARTIALLY ADDRESSED.** Closed: client-asserted roles, permissive default policy, unverified
approver identity. Open: the requirement is deployment configuration keyed by `policyVersion`, so it is
only as per-approval as that key is; a durable requirement captured at request time is a persistence
change needing an ADR. F5 must not be recorded as fully closed.

### L4 — Store paths: bounded, gated, and honestly limited

| # | Path | Reachable | Decision |
|---|---|---|---|
| 1 | REST `POST /{id}/approve`, `/{id}/deny` → control plane → mutation store | external | secured (§2 L1–L3) |
| 2 | `ApprovalDecisionControlPlane` bean (public API) | in-process | requires an `OperatorAuthority` (L2) |
| 3 | `SovereignOpsApprovalMutationStore` decision methods | in-process | require `AuthorizedDecision`, minted only after L2/L3 |
| 4 | `ApprovalStore.transition(approvalId, expectedVersion, ApprovalTransition.Approve/Deny)` — public **core** SPI | in-process | **trusted infrastructure port. Bounded, not closed.** |
| 5 | REST `POST /{id}/resume` (body `resumedBy`) | external | **UNRESOLVED** (§6) |

Path 4 is the real hole and revision 2 overstated what a text scan proves. Two corrections:

**(a) Examples are explicitly exempt, and named.** `examples/**` is demonstration code, not the library's
authority surface. Verified existing call outside any allowlist:
`examples/sovereign-document-intelligence/src/main/kotlin/dev/tramai/examples/sovereign/DocumentIntelligenceExampleSupport.kt:136`
calls `approvalStore.transition(..., ApprovalTransition.Approve(decidedBy = "demo-operator", ...))`. It
stays exempt, and gains a one-line comment stating that it auto-approves without operator authority
because it is a demonstration. Any future *production* module is in scope.

**(b) The gate is compiler-backed where possible, heuristic where not, and says which.** A textual scan
for `ApprovalStore.transition(` cannot see calls through a variable, and also matches unrelated types
(`fallbackGate.transition(`, `walk.transition(` both exist today). So:

- **Preferred mechanism:** mark the authority-free transitions with Kotlin's `@RequiresOptIn`
  (`DirectApprovalMutation`). Every Kotlin call site — control plane, persistence adapter, example — must
  then opt in explicitly. **This is a compile-time acknowledgement and reviewability mechanism, not access
  control.** Its limits, stated so no later claim can widen them:
  - a Kotlin caller can add `@OptIn(DirectApprovalMutation::class)` anywhere, including in a
    non-privileged module — the marker makes the call *visible in review*, it does not forbid it;
  - **Java source callers are not checked at all**: the released JVM API exposes `ApprovalStore.transition`
    and Java-callable constructors for `ApprovalTransition.Approve`/`Deny`. Kotlin's opt-in is documented
    as explicit-consent enforced by the Kotlin compiler, not as cross-language authorization;
  - no project-wide compiler opt-in flag may be enabled, because a global `-opt-in` would hide every
    individual call site and destroy the only thing the marker provides.

  What the marker buys: every direct mutation is named in a diff, and an unannotated Kotlin call fails
  compilation (the discriminator asserts exactly that, nothing stronger).
- **Accepted residuals, recorded rather than defended:** Java invocation of `ApprovalStore.transition`,
  and Kotlin invocation that explicitly opts in. Both are inside the shared-process trust domain described
  at the top of this document.
- **Fallback if the opt-in proves invasive:** a source scan over production modules only (examples
  excluded by construction), documented as **heuristic**, with its blind spot named: aliasing through a
  variable or a wrapper defeats it.

L4's status is **bounded and gated**, never "closed". The residual — in-process code that injects a store
bean or uses reflection — is accepted exposure and must be repeated in the completion report.

### Attribution boundary

Authorization policy receives `(issuer, subject)`. The approval record and outbox store only an actor
string, so **durable issuer attribution is 0.7.6f**, unless a separate supported encoding is designed.
This task does not silently widen the recorded actor format.

## 3. Spring wiring: executable acceptance requirements

Removing `@ConditionalOnMissingBean` proves nothing by itself. The following are **tests**, not
assumptions:

1. A host context defining an alternative `OperatorCapabilityMapping` (same type, different bean **name**)
   must fail startup or be provably ignored.
2. The same with `@Primary` on the hostility bean.
3. The same with a `@Qualifier` on the injection point and no matching bean → startup failure.
4. Each security-sensitive collaborator resolves to the intended implementation: a test asserts the bean
   actually injected into the filter chain, resolver and control plane is the framework-built instance
   (identity assertion, not type assertion).
5. Ambiguous or unexpected wiring fails startup with a message naming the collaborator.
6. Every row of the startup matrix below is asserted in a context test.

| Condition | Result |
|---|---|
| `security.enabled=true`, issuer/audience/jwks missing | startup failure (exists) |
| `security.enabled=true`, control-plane path not covered by `protected-paths` | startup failure (exists) |
| `rest-control-plane-enabled=true`, `security.enabled=false` (today's default) | **new startup failure** |
| `rest-control-plane-enabled=true`, security enabled, no `ApprovalDecisionAuthorizer` bean | **new startup failure** |
| `approval-policies` entry with an empty role list | **new startup failure** |
| `approver-role-mappings` or `approval-policies` names a **blank or malformed** approver role | **new startup failure** (see §7.2) |
| host bean substitution per tests 1–3 | **startup failure** |

## 4. Failure semantics

| Situation | Result | HTTP |
|---|---|---|
| no verified identity | filter chain rejects | 401 |
| authenticated, missing `DECIDE_APPROVAL` | `Unauthorized(MISSING_CAPABILITY)` | 403 |
| no approval policy for the approval's `policyVersion` | `Unauthorized(APPROVAL_POLICY_UNAVAILABLE)` | 403 |
| capability held, approval absent | `NotFound` (discloses existence to capability holders — §2, accepted) | 404 |
| verified roles do not satisfy the approval's required roles | `Unauthorized(ROLE_NOT_PERMITTED)` | 403 |
| authorized, stale supplied version | `Stale(expected, current)` | 409 |
| authorized, target missing | `NotFound` | 404 |
| authorized, illegal transition | `Conflict` | 409 |
| authorized, business decision to deny | `Denied` | 200 |

`Denied` never expresses an authorization failure.

## 5. Discriminator tests

1. **No authority, no mutation** — a control-plane call without an `OperatorAuthority` does not compile;
   the runtime path that bypasses the adapter (direct invocation with a self-built handle) is asserted to
   be *outside the supported set* by test 1b.
2. **1b — single minting site** — structural check: only the authentication adapter constructs
   `OperatorAuthority`; any other production construction fails the check. Declared heuristic; its blind
   spot is named.
3. **Body identity ignored** — send `actorId`/`actorRole` naming another operator; the recorded
   `decidedBy` is the principal subject and the policy sees the principal's issuer.
4. **Wrong approval role** — policy requires `medical-reviewer`, principal verified as `finance-reviewer`
   → 403, no mutation, no outbox record.
5. **Missing policy for `policyVersion`** → 403 `APPROVAL_POLICY_UNAVAILABLE`, no mutation.
6. **Stacked wiring** — tests 1–5 of §3.
7. **Unauthenticated decision** → 401 with zero control-plane interaction.
8. **Startup matrix** — every row of §3 asserted.
9. **F2 preserved** — authorized + stale version → 409 `STALE`, status/version/outbox untouched;
   authorized concurrent approve-vs-deny → exactly one commit, in memory and on PostgreSQL.
10. **Two issuers, same subject** — policy distinguishes them.
11. **Direct `ApprovalStore.transition(Approve)` from a non-privileged production module** — an
    unannotated Kotlin call fails compilation under the opt-in marker (preferred), or the heuristic scan
    fails the build (fallback). The discriminator asserts *compilation failure for an unannotated Kotlin
    call*, not that every caller is blocked: Java invocation and explicitly opted-in Kotlin invocation are
    accepted residuals (§2 L4).
12. **Attribution sinks receive the verified identity** — required regardless of whether the command keeps
    or drops its identity fields. Invoke the control plane **directly**, with a legitimate
    `OperatorAuthority`, and assert all four sinks:
    - the policy receives the verified `(issuer, subject)` **and** the verified approver roles;
    - `decidedBy` equals the verified subject;
    - the outbox record's actor equals the verified subject;
    - the mutation store receives the verified subject as its `actor`.

    When the command fields are removed (§2 L2 option 1), the absence of a mismatched field is *not* a
    substitute for these assertions — the sinks are checked against an authority whose identity differs
    from every other string in the fixture, so a control plane that fell back to a literal, a default or a
    stale field still fails.

## 6. Carry-forward: F3 items this task does not close

| Surface | Status after 0.7.6e |
|---|---|
| `POST /{id}/approve`, `/{id}/deny` | **closed** by this task |
| `POST /{id}/resume` (body `resumedBy`) | **UNRESOLVED** — same F1 defect, different endpoint |
| `GET /{approvalId}` (approval status, decider, continuation status) | **UNRESOLVED** — unauthenticated-for-authorization read; discloses existence and decision metadata to any authenticated caller and is the reason the §2 disclosure is acceptable |
| Workload administration (`ADMINISTER_WORKLOAD`) | **UNRESOLVED** |
| Workflow run operations, cancellation (`CANCEL_RUN`) | **UNRESOLVED** |
| `ApprovalStore.transition` direct port | **bounded and gated** (§2 L4), not closed |

The epic must record F3 as *partially addressed*. A follow-up task for the rows above needs its own scope
decision from the owner and must not be folded into 0.7.6f or 0.7.6g by assumption.

## 7. Compatibility impact (corrected)

### 7.1 `OperatorPrincipal` — public constructor, so additive means additive

Revision 2 wrongly claimed the primary constructor is private. Verified in `tramai-core/api/tramai-core.api`
at `release/0.7.0`:

```
public final class dev/tramai/core/operator/OperatorPrincipal {
  public fun <init> (Ljava/lang/String;Ljava/lang/String;Ljava/time/Instant;Ljava/util/Set;)V
```

That is an ordinary public JVM constructor, callable from Java. Adding `approverRoles` therefore changes
its signature unless compatibility is kept explicitly: **retain the 4-argument constructor** delegating to
`approverRoles = emptySet()` and add a 5-argument form. Additive, no binary break, no migration entry for
this type.

### 7.2 `ApproverRole` — no closed vocabulary, so no "unknown role" rule

Verified: `@JvmInline value class ApproverRole(val value: String)` with `require(value.isNotBlank())` and
nothing else. There is no registry of valid roles, so "unknown approver role is a startup failure" is not
implementable. The rule is narrowed to what the type and configuration can actually decide:

- **blank** → rejected (already true of the type);
- **malformed** → rejected by a shape policy defined in this task (non-blank, no control characters, no
  whitespace, bounded length) with the exact rule stated where it is implemented;
- **unconfigured reference** → an approver role named in `approval-policies` that appears in no
  `approver-role-mappings` value is a **startup failure**, because no principal could ever satisfy it.
  That is self-consistency, not a vocabulary claim.

### 7.3 Other declared breaks

- `ApprovalDecisionControlPlane.approve/deny` take `(command, OperatorAuthority)` — deliberate break, no
  identity-free overload.
- `ApprovalDecisionCommand` loses `actorId` and `actorRole` (preferred per §2 L2): the authority is the only
  source of effective identity. Call sites that named an actor move to naming the authority.
- `ApprovalDecisionAuthorizer.canDecide` takes `OperatorIdentity` + verified approver roles — break of a
  public fun interface.
- `ApproveDenyRequest` loses `actorId` and `actorRole` — REST contract break.
- `OperatorSecurityProperties` gains `approver-role-mappings` and `approval-policies`.
- New types: `OperatorAuthority`, `OperatorIdentity`, `OperatorApprovalPolicy`, `AuthorizedDecision`,
  `ApprovalDecisionResult.Unauthorized`, and the `@RequiresOptIn` marker `DirectApprovalMutation`.
- LANDED-preserving append entries in `config/quality/api-migrations.yml` for every affected published
  module, hashes recomputed from the committed dumps.

## 8. Non-goals

- **0.7.6f** — durable attribution provenance (including issuer encoding in records).
- **0.7.6g** — comprehensive adversarial campaign.
- **0.7.7** — durable resume fencing; the resume path keeps its nullable-version fallback
  (`SovereignOpsApprovalResumeControlPlane.kt:89`), recorded as a 0.7.7 architectural concern.
- Persistence schema changes, including a stored per-approval required role — ADR first.
- Replay-resistant capability tokens — separate design if a threat model requires it.

## 9. Stop conditions

Stop and report if enforcement requires: changing a quality gate, adding a suppression, baselining a new
finding, a persisted schema change, an overridable identity seam, touching 0.7.7 fencing, or if the
`@RequiresOptIn` gate proves invasive enough to force a core SPI signature change.
