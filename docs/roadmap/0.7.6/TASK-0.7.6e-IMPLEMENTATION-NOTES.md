# 0.7.6e — Runtime Authorization Enforcement: implementation notes

Companion to `TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md` (merged at `0757a620`), which is the controlling
requirement. This file records where the implementation had to interpret it, and what remains unresolved.

## Invariant

No privileged state mutation without verified operator identity, sufficient capability, and valid command
preconditions.

## Enforcement path

Verified JWT → `OperatorPrincipalFactory` (capabilities **and** approver roles) → `OperatorPrincipalResolver`
→ `OperatorAuthority` → `ApprovalDecisionControlPlane` → capability → per-approval role → version precondition → CAS.

There is exactly one production issuance site for authority. The REST controller receives the authority from the
operator argument resolver and never constructs one; a controller that built its own authority would be a second
issuance path regardless of how correct the authority type is.

The order of the checks is part of the contract, not an implementation detail:

1. Capability, **before any store read**. The refusal is existence-blind.
2. Lookup.
3. Per-approval role requirement, keyed by the approval's persisted `policyVersion`. This must follow the lookup
   because the key is only known from the stored approval.
4. Version precondition, ahead of the terminal-state shortcuts. A caller holding a stale version learns that its
   precondition is stale, not that someone else's decision already happened.
5. Mutation through the authoritative store, whose CAS remains the final authority.

## Deviations from the merged contract, and why

**`ApprovalDecisionAuthorizer` and `AllowAllApprovalDecisionAuthorizer` are deleted, not changed.** The contract
describes changing the authorizer interface. Changing it was rejected because the interface itself was the
substitution route: any host bean satisfying it could decide any approval, and no startup check distinguishes a
correct implementation from a permissive one. Removing the seam removes the question. The deletion is recorded in
the API migration registry under `:tramai-spring-boot-starter-sovereign-ops`.

**The surviving policy bean is qualified by bean name.** Removing `@ConditionalOnMissingBean` is not sufficient.
An alternative implementation proved this the hard way: with an unqualified injection point, a host bean marked
`@Primary` was resolved silently and its requirement *was* the one enforced, while the configured policy sat
unused. The injection point now names the bean, and a discriminator observes the difference — an approval at a
version only the hostile policy knows is refused with `APPROVAL_POLICY_UNAVAILABLE` when the configured policy is
enforced. See `ApprovalDecisionControlPlaneActivationTest`.

## Startup activation

Two boundaries are separate, and the fourth row is the reason the split exists:

| Configuration | Behaviour |
| --- | --- |
| REST control plane enabled, security disabled | FAIL — `tramai-rest-control-plane-requires-operator-security` |
| REST control plane enabled, security enabled, approval policy absent | FAIL — `tramai-rest-control-plane-requires-approval-policy` |
| Decision control plane active, policy bean absent | FAIL — `tramai-approval-policy-missing` |
| Mutations enabled, REST disabled, security disabled | ALLOW — infrastructure only, no decision service published |
| Security enabled, valid policy, decision boundary active | ALLOW |

`mutations-enabled=true` enables approval infrastructure. It does not, by itself, mean an operator-facing approval
decision service is configured, so it must not require operator authentication. The decision control plane is
published only when an `OperatorApprovalPolicy` exists (`@ConditionalOnBean`, not a nullable `@Bean`: a null result
still registers a bean definition, so "no decision service" would be asserted falsely).

A host-supplied policy cannot make an unauthenticated deployment reachable: the controller bean itself refuses to
be created when `security.enabled=false`, whatever policies exist.

## The `requiredRole` residual (F5, partially addressed)

`ApprovalGateway.requestApproval()` accepts `requiredRole` and forwards it to the request factory.
`ApprovalRequest` and `ApprovalBinding` do not preserve it as authoritative approval state;
`ApprovalInboxMetadata` may carry a sanitized reviewer-facing copy, which is not a decision requirement.

0.7.6e therefore enforces the requirement configured for the approval's persisted `policyVersion`, which **may
differ** from the role originally requested. This is accepted as a partial mitigation, not closed. No test fixture
was adjusted to make the two happen to agree. Persisting the requested role is deferred to an ADR covering
migration, historical approvals and versioned policy changes.

The requirement key is the value actually persisted, which is not always the value a test fixture assumes: the
approval-resume example records `0.4.0-preview`, and two plausible guesses (`v1`, then the test-fixture default
`1.0`) were refused with `APPROVAL_POLICY_UNAVAILABLE` before the stored value was read.

## Direct-mutation guard (L4) — delivered as a build-level check

The contract's L4 mechanism was an `@RequiresOptIn DirectApprovalMutation` marker on
`ApprovalStore.transition(...)`. It was implemented, made the whole tree compile with every direct caller declaring
intent, and was then **reverted**: Kotlin requires the opt-in on every *override* as well as every call site, which
touches 15 legacy files, and Spotless then rewrites each touched file completely (`ApprovalResumeEngineTest.kt`
alone: 1745 insertions / 1370 deletions) with 26 `MaxLineLength` and a set of `LongMethod` findings behind it.
Clearing those needs a formatting migration or a ceiling change, and this task may touch neither.

**Delivered instead, in its own PR (#561, squash `a6bac10b`):** `verifyDirectApprovalMutations`, a build-level guard
attached to `verifyStaticSafetyGuards` (so `check`, `verifyPr` and CI run it with no workflow change). It inventories
the reviewed production direct-mutation sites with an exact call-site count and a rationale, and fails on a new site, a
new call inside a reviewed file, a stale or unrationalised entry, and a vacuous scan.

- Detection uses **Kotlin's own parser** (PSI), not a text scanner: the parser is `compileOnly` and loaded in an
  isolated Worker classloader, so it never joins the build's plugin classpath.
- The reviewed population is two sites: `DefaultApprovalGateCoordinator` (system-initiated cancellation, not an
  operator decision) and the in-memory sovereign mutation store behind the control plane. The durable JDBC store
  writes in SQL and is not a call site.
- `examples/**` is excluded as inventory data with its rationale, not by a scanning omission.

**What L4 does and does not prove.** It is a reviewability ratchet over a *visible* population: candidate files are
selected by a textual mention of `dev.tramai.core.approval`, there is no receiver-type resolution, and aliases,
extension functions, reflection and wrapper calls are invisible. It does not close the public
`ApprovalStore.transition` bypass, Java callers are not analysed (a Java candidate file fails closed rather than
passing silently), and the enforced path remains `ApprovalDecisionControlPlane`. The authorization boundary does not
depend on L4 existing.

## Concurrency (F2) unchanged

`expectedVersion` remains the single precondition, checked before any terminal-state shortcut, and the store's CAS
remains the final authority. Authorization is an additional gate in front of the same version-fenced mutation, not
a replacement for it: an authorized caller with a stale version still receives `Stale`.

## Still unresolved

- `GET /{approvalId}` remains unauthenticated beyond the operator boundary: it returns approval status, decider and
  continuation status to any caller that reaches it. Recorded under F3.
- Resume control plane authorization, workload administration and run cancellation are out of scope for 0.7.6e.
- Privileged audit attribution for authorization *refusals* is 0.7.6f; durable resume fencing is 0.7.7.
