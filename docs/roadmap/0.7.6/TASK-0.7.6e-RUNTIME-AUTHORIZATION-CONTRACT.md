# 0.7.6e — Runtime Authorization Enforcement (architecture and enforcement contract)

**Status:** revision 2 for review. Supersedes revision 1 (`26d0d95f`). No production code changed.

Base: `release/0.7.0` at `57ea33ebaff8ac847a2088bfb1b54c952028fd1d` (0.7.6d merged).

Revision 2 answers the five blocking questions from review — what verified authority is, which paths
are trusted, where the per-approval role requirement comes from, which operations this task secures,
and what fails at startup — and narrows the claim instead of strengthening the prose. Where a guarantee
cannot be enforced in this task, it is marked **UNRESOLVED** and carried forward, not reworded.

## Scope of this contract

This task secures **one** privileged operation family: approval decisions (`approve` / `deny`). Every
other F3 surface is inventoried in §6 and carried forward as explicitly **UNRESOLVED**. F3 is therefore
**partially addressed** by 0.7.6e, and the epic must not record it as closed.

**Invariant, scoped to what is enforced:**

> No approval decision reaches authoritative mutation without a verified operator identity, a verified
> authority handle, a sufficient capability, a satisfied per-approval role requirement, and a valid
> version precondition.

## 1. What counts as verified authority (Q1)

### 1.1 The boundary today is not pinned

`OperatorAuthenticationAutoConfiguration` marks four beans `@ConditionalOnMissingBean`:
`operatorCapabilityMapping`, `operatorClock`, `operatorPrincipalFactory`, `operatorPrincipalResolver`.
A host application can therefore substitute the role→capability mapping or the principal factory and
silently own the whole principal-to-authority conversion. The 0.7.6c chain pins its JWT decoder by
building it in-chain; it does not pin what happens after the token is verified. **This is the gap.**

### 1.2 Classified components

| Component | Classification | Change |
|---|---|---|
| `operatorJwtDecoder` (built in-chain) | immutable trust boundary | none |
| `OperatorSubjectPolicy` | immutable trust boundary | none |
| `OperatorCapabilityMapping` | immutable trust boundary | **drop `@ConditionalOnMissingBean`** |
| `OperatorPrincipalFactory` | immutable trust boundary | **drop `@ConditionalOnMissingBean`** |
| `OperatorPrincipalResolver` | immutable trust boundary | **drop `@ConditionalOnMissingBean`** |
| `OperatorAuthorityMinter` / `OperatorAuthorityVerifier` (new) | immutable trust boundary | plain `@Bean`, never conditional |
| `OperatorApprovalPolicy` (new) | immutable trust boundary | plain `@Bean`, never conditional |
| `Clock` for `authenticatedAt` | substitutable, documented | keeps `@ConditionalOnMissingBean` |
| `ApprovalDecisionAuthorizer` | policy, must be explicit | required bean when options enabled; no permissive default in production wiring |

Dropping `@ConditionalOnMissingBean` does not make substitution silently succeed — it converts it into a
bean-definition conflict or an ambiguous-injection startup failure. That is the intent: fail closed,
loudly, at startup rather than run with a substituted authority source. A **hostile-host-bean test** must
prove it: a test context that defines its own `OperatorCapabilityMapping` and `OperatorPrincipalFactory`
must fail to start, or must be proven to still receive the built-in beans. Reflection is not involved;
this is a plain application-context discriminator.

### 1.3 A principal is still data (Q2)

`OperatorPrincipal` has a public constructor and accepts arbitrary capabilities. Passing it to the control
plane and calling `principal.authorize(...)` would make the claimed invariant false for an ordinary
in-process caller — no reflection required, just `OperatorPrincipal(subject, issuer, now, setOf(DECIDE_APPROVAL))`.
Revision 1 made that mistake. The fix is a handle, not a parameter type:

**`OperatorAuthority` — an opaque, verifiable authority handle.**

- **Data:** `principal` (issuer, subject, authenticatedAt, capabilities, approverRoles), `provenance`
  (verifier id + verification instant), and `mac` — an HMAC-SHA256 over the canonical encoding of that
  data, keyed by a **per-process random key** generated when `OperatorAuthorityMinter` is constructed.
- **Construction policy:** the only supported constructor is `internal` to the minting component; the
  minter bean is created by the authentication boundary and is not exposed for injection into application
  code. There is no public factory, no `copy`, no defaulted form.
- **Lifecycle:** one handle per authenticated request, minted immediately after JWT verification and
  principal resolution, consumed by exactly one control-plane call, never stored, never serialized,
  never logged.
- **Verification:** the control plane holds the `OperatorAuthorityVerifier` (same key) and calls
  `verify(handle)` before reading authoritative state. A handle whose MAC does not verify, whose
  provenance is unknown, or that was minted by a different process is rejected as
  `Unauthorized(reason = authority-unverifiable)` — never silently upgraded to a denial of the business
  decision.
- **What this buys, precisely:** forging authority now requires extracting the process key or substituting
  the verifier bean — both explicit hostile-host actions, the second caught by the bean substituion test.
  Forging by calling a data-class constructor no longer works.
- **What it does not buy:** hostile code already executing inside the same JVM with reflective access to
  private fields. That remains **out of scope and documented**, as it was in revision 1 — but it is no
  longer the *shortest* path to authority.

### 1.4 Identity is (issuer, subject), everywhere

`OperatorPrincipal` already distinguishes operators by `(issuer, subject)`. The authorization policy and
the attribution rule must use both: `ApprovalDecisionAuthorizer.canDecide` changes from
`actorId: String` to a verified `OperatorIdentity(issuer: String, subject: String)` plus the verified
approver roles. Two identity providers issuing the same subject are two different operators, and the
policy must be able to see that. This is a declared public-API break of the authorizer fun interface.

## 2. Enforcement layers

Ordering is part of the contract because it is observable:

```
0.7.6c filter chain (JWT verified)
  → L1  principal resolved from SecurityContextHolder → authority handle minted
  → L2  handle verified, DECIDE_APPROVAL capability required        [403 authority-unverifiable / MISSING_CAPABILITY]
  → L3  per-approval role requirement satisfied                     [403 APPROVAL_POLICY_UNAVAILABLE / ROLE_NOT_PERMITTED]
  →     version precondition vs authoritative state                 [409 STALE]
  →     terminal-state shortcuts (Already*)                         [200]
  →     CAS mutation + audit outbox                                 [200]
```

Authorization precedes the version precondition so an unauthorized caller learns nothing about approval
state. **F2 is untouched**: the version check still precedes the terminal shortcuts and any mutation, and
a losing concurrent command still yields `Stale` with no outbox fact.

### L1 — Controller: authority injected, never read

`OperatorPrincipal` continues to arrive through the existing `OperatorPrincipalArgumentResolver`, but the
controller does not pass it to the control plane: it passes the **authority handle** minted by the
boundary. The request body carries no identity at all — `actorId` and `actorRole` are removed from
`ApproveDenyRequest` and are never read, even if a client still sends them.

### L2 — Control plane: verifier before state

`ApprovalDecisionControlPlane.approve/deny` take `(command, OperatorAuthority)`. First statement of the
domain method: `authorityVerifier.verify(handle)` → verified principal, or typed `Unauthorized`. Only then
`principal.authorize(OperatorCapability.DECIDE_APPROVAL)`. The control plane never trusts a bare
`OperatorPrincipal`. No overload accepts a decision without a handle.

### L3 — Per-approval role requirement (Q3)

Revision 1 was wrong to call F5 closed by trusting the operator's role: proving an operator holds
`medical-reviewer` says nothing about whether *this approval* accepts that role, and
`ApprovalRequest`/`ApprovalBinding` carry no required role (verified).

**Authoritative source for this task:** server-side configuration keyed by the approval's own persisted
binding — `policyVersion`. The approval already stores it, so no schema change is needed:

```yaml
tramai:
  sovereign:
    ops:
      security:
        role-mappings:                      # existing 0.7.6c shape, unchanged
          approval-reviewer: [decide-approval]
        approver-role-mappings:             # NEW: role claim value -> verified approver roles
          approval-reviewer: [medical-reviewer]
        approval-policies:                  # NEW: approval policyVersion -> required approver roles
          2024-06-medical-v3: [medical-reviewer]
```

- The approver-role mapping uses the **same shape** as the existing `role-mappings` (map of exact string
  key → list of exact values), so the review-thread configuration defect is fixed by construction rather
  than by inventing a second format. Names are matched exactly; there is no prefix, wildcard or case
  folding. An unknown `ApproverRole` value in configuration is a **startup failure**.
- **Multiple-role semantics:** `approver-role-mappings` yields the **union** of approver roles across all
  role-claim values the principal asserts (mirroring how capabilities are unioned). `approval-policies`
  entries are **intersection requirements**: a decision is permitted when the principal's verified
  approver roles ∩ the approval's required roles is non-empty. An approval with an empty required set is
  a configuration error, not "allow all".
- **Fail-closed when the requirement is unavailable:** no `approval-policies` entry for the approval's
  `policyVersion` → `Unauthorized(reason = approval-policy-unavailable)`. Never default to allow, never
  default to "any verified approver".

**F5 status: PARTIALLY ADDRESSED.** What is closed: client-asserted roles, permissive default policy,
unverified approver identity. What stays open: the requirement is deployment configuration keyed by
`policyVersion`, so it is only as per-approval as that key is. A durable requirement captured at
approval-request time needs a persistence change and an ADR; that is **not** in this task and F5 must not
be recorded as fully closed.

### L4 — Store paths and the residual exposure (Q4)

Inventory of approval-mutation paths in production code:

| # | Path | Externally reachable | Decision |
|---|---|---|---|
| 1 | REST `POST /{id}/approve`, `/{id}/deny` → control plane → mutation store | **yes** | secured by L1–L3 |
| 2 | `ApprovalDecisionControlPlane` interface (public API, injectable bean) | in-process | requires a valid authority handle (L2) |
| 3 | `SovereignOpsApprovalMutationStore` (public bean) `approveApprovalWithAuditIntent` / `denyApprovalWithAuditIntent` | in-process | decision methods require an `AuthorizedDecision` produced only after L2/L3 |
| 4 | `ApprovalStore.transition(approvalId, expectedVersion, ApprovalTransition.Approve/Deny)` — public **core** SPI | in-process | **trusted infrastructure port. Residual exposure recorded, not claimed closed.** |
| 5 | REST `POST /{id}/resume` (body `resumedBy`) | **yes** | **UNRESOLVED** — carried forward (§6) |

Path 4 is the honest hole. `ApprovalStore` is a core SPI implemented by persistence adapters and called
by the control plane; putting an operator authority in its signature would drag a framework/operator
concept into `tramai-core` and break every adapter and test double. It is therefore classified as a
**trusted infrastructure port** — not an authority API — and the *supported*-path claim is narrowed
accordingly.

The enforceable part of that classification, which this task will implement:

- a **source-scan discriminator test** that fails the build if production code outside the approval
  control plane and the persistence adapters calls `ApprovalStore.transition(` with an
  `Approve`/`Deny` transition. That converts "we promise nobody calls it" into a gate;
- the residual — arbitrary in-process code with a Spring context and reflective access — is recorded
  here as **accepted exposure** and must be repeated in the task's completion report. L4 is
  **not** claimed complete; it is claimed *bounded and gated*.

## 3. What fails at startup (Q1 continued)

| Condition | Result |
|---|---|
| `security.enabled=true` and issuer/audience/jwks missing | startup failure (exists today) |
| `security.enabled=true` and control-plane path not covered by `protected-paths` | startup failure (exists today) |
| `rest-control-plane-enabled=true` and `security.enabled=false` | **new startup failure** — a privileged endpoint may not exist outside the operator boundary (`security.enabled` defaults to false today) |
| `rest-control-plane-enabled=true`, security enabled, no `ApprovalDecisionAuthorizer` bean | **new startup failure** — `AllowAllApprovalDecisionAuthorizer` becomes a test double only |
| `approver-role-mappings` names an unknown `ApproverRole` | **new startup failure** |
| `approval-policies` entry has an empty role list, or a `policyVersion` has no entry | **new startup failure for the empty list**; denial (`approval-policy-unavailable`) at request time for a missing entry |
| host defines its own `OperatorCapabilityMapping` / `OperatorPrincipalFactory` / resolver / verifier / policy bean | **new startup failure** (bean conflict / ambiguous injection) |

## 4. Failure semantics

| Situation | Result | HTTP |
|---|---|---|
| no verified identity | filter chain rejects | 401 |
| unverifiable authority handle | `Unauthorized(authority-unverifiable)` | 403 |
| authenticated, missing `DECIDE_APPROVAL` | `Unauthorized(MISSING_CAPABILITY)` | 403 |
| no approval policy for the approval's `policyVersion` | `Unauthorized(APPROVAL_POLICY_UNAVAILABLE)` | 403 |
| verified roles do not satisfy the approval's required roles | `Unauthorized(ROLE_NOT_PERMITTED)` | 403 |
| authorized, stale supplied version | `Stale(expected, current)` | 409 |
| authorized, target missing | `NotFound` | 404 |
| authorized, illegal transition | `Conflict` | 409 |
| authorized, business decision to deny | `Denied` | 200 |

`Denied` never expresses an authorization failure.

## 5. Discriminator tests this contract requires

Each must be mutation-verified (break the guard, observe the failure, restore):

1. **Forged principal handle** — construct `OperatorAuthority` data with `DECIDE_APPROVAL` without the
   minter (or with a wrong MAC) → 403 `authority-unverifiable`, no store interaction.
2. **Hostile host bean** — a context defining its own `OperatorCapabilityMapping` and
   `OperatorPrincipalFactory` → startup failure (or provably the built-in beans are injected).
3. **Body-supplied identity ignored** — send `actorId`/`actorRole` naming a different operator; recorded
   `decidedBy` is the principal subject **and** the policy sees the principal's issuer.
4. **Wrong approval role** — policy requires `medical-reviewer`, principal verified as `finance-reviewer`
   → 403 `ROLE_NOT_PERMITTED`, no mutation, no outbox record.
5. **Missing policy for `policyVersion`** → 403 `APPROVAL_POLICY_UNAVAILABLE`, no mutation.
6. **Direct `ApprovalStore.transition(Approve)`** from a non-privileged module → source-scan guard fails.
7. **Unauthenticated decision** → 401 and zero control-plane interaction.
8. **Startup matrix** — each row of §3 asserted, including `rest-control-plane-enabled` with security
   disabled.
9. **F2 preserved** — authorized + stale version → 409 `STALE` with status/version/outbox untouched;
   authorized concurrent approve-vs-deny → exactly one commit, in memory and on PostgreSQL.
10. **Two issuers, same subject** — policy distinguishes them; the authorizer receives `(issuer, subject)`.

## 6. Carry-forward: F3 items this task does not close (Q5)

| Surface | Status after 0.7.6e |
|---|---|
| `POST /{id}/approve`, `/{id}/deny` | **closed** by this task |
| `POST /{id}/resume` (body `resumedBy`) | **UNRESOLVED** — same F1 defect, different endpoint |
| Workload administration (`ADMINISTER_WORKLOAD` paths) | **UNRESOLVED** — capability declared, not enforced |
| Workflow run operations, cancellation (`CANCEL_RUN`) | **UNRESOLVED** |
| `ApprovalStore.transition` direct port | **bounded and gated** (§2 L4), not closed |

The epic must record F3 as *partially addressed*. The follow-up task for the rows above is not started
here and must not be folded into 0.7.6f (attribution provenance) or 0.7.6g (adversarial campaign) by
assumption; it needs its own scope decision from the owner.

## 7. Declared compatibility impact

- `ApprovalDecisionControlPlane.approve/deny` take `(command, OperatorAuthority)` — deliberate break, no
  identity-free overload.
- `ApprovalDecisionAuthorizer.canDecide` takes `OperatorIdentity` + verified approver roles — break of a
  public fun interface.
- `OperatorPrincipal` gains `approverRoles` — binary break for Java callers (the primary constructor is
  private and the public one is the synthetic default-args bridge; Java cannot construct it either way).
- `ApproveDenyRequest` loses `actorId` and `actorRole` — REST contract break.
- `OperatorSecurityProperties` gains `approver-role-mappings` and `approval-policies`.
- New types: `OperatorAuthority`, `OperatorIdentity` (if not already present), `OperatorAuthorityMinter`,
  `OperatorAuthorityVerifier`, `OperatorApprovalPolicy`, `AuthorizedDecision`,
  `ApprovalDecisionResult.Unauthorized`.
- LANDED-preserving append entries in `config/quality/api-migrations.yml` for every affected published
  module, hashes recomputed from the committed dumps.

## 8. Non-goals

- **0.7.6f** — provenance/attribution contract for recorded audit facts.
- **0.7.6g** — comprehensive adversarial campaign across all privileged paths.
- **0.7.7** — durable resume fencing. The resume path keeps its nullable-version fallback
  (`SovereignOpsApprovalResumeControlPlane.kt:89`), unchanged and pre-existing, recorded as a 0.7.7
  architectural concern rather than a 0.7.6d defect.
- Persistence schema changes (including a stored per-approval required role) — requires an ADR first.

## 9. Stop conditions

Stop and report if enforcement requires: changing a quality gate, adding a suppression, baselining a new
finding, a persisted schema change, an overridable identity seam, or touching 0.7.7 fencing — or if the
authority-handle design cannot be verified cheaply enough to justify it over recording a narrower claim.
