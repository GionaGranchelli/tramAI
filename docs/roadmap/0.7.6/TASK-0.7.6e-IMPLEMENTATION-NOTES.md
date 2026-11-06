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
| Decision control plane active, policy bean absent | NO DECISION SERVICE — the plane is not published, so callers see no bean rather than a startup failure |
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

## Delivery

Merged into `release/0.7.0` as squash `440d63fc294cfbd928df397f25250247bf1bfc55` on 2026-10-28, from reviewed head
`e240bf95a1e304acaa2247fa2308c009a993d927` (19/19 exact-head jobs green, base `a6bac10b`).

What shipped, in the order the review demanded it:

- **Verified authority, single issuance boundary.** `OperatorPrincipalFactory` derives capabilities and approver
  roles from verified JWT claims plus server configuration; `OperatorPrincipalResolver.issueAuthority` is the only
  place an `OperatorAuthority` is minted; the controller never builds one.
- **No substitutable trust seam.** Capability mapping, approver-role mapping, principal factory and resolver are not
  beans. A published bean of those types was substitutable — a `@Primary` host bean won type-based injection even
  with `@ConditionalOnMissingBean` present, which the hostile-wiring test demonstrates by promotion
  (`403` becoming `200` on the mutation run). The chain is constructed inline from configuration.
- **Activation split.** `mutations-enabled` is infrastructure; the decision service publishes only with a policy
  bean. REST without operator security fails startup; REST without a resolvable policy fails startup.
- **Role configuration validated at startup.** Shape (non-blank, no whitespace, no control characters, bounded
  length) and satisfiability: an `approval-policies` role no `approver-role-mappings` value can grant is a startup
  failure rather than a permanent runtime refusal.
- **L4 inventory ratchet** over direct `ApprovalStore.transition` calls, as a Kotlin PSI scan in an isolated Worker.

Residuals carried forward unchanged, each deliberately not solved here:

- F3: `GET /{approvalId}` is not authenticated beyond the operator boundary.
- F5: the originally required role is not persisted as authoritative approval state; the gateway `requiredRole` is a
  parameter, and the policy is keyed on the approval's persisted `policyVersion` (concrete value in the resume
  example: `0.4.0-preview`). Preserving the required role in authoritative state is future work with an ADR.
- L4 does not close the public `ApprovalStore.transition` bypass, resolves no receiver types, and does not analyse
  Java call sites.
- A host that registers its own `WebMvcConfigurer` or `HandlerMethodArgumentResolver` runs in-process code that a
  Spring boundary cannot police; the guarantee is about bean substitution of the trusted types.
- `ToolExecutionDenialEvidenceIntegrationTest` remains a recorded timing flake (base and head both green in
  isolation and on rerun), not waived.

Next: 0.7.6f privileged audit attribution. 0.7.7 owns durable resume fencing.
