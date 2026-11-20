# TASK 0.7.6b — Principal and Operator Capability Contract

**Epic:** 0.7.6 — Authentication, Authorization & Runtime Control
**Predecessor:** 0.7.6a audit (`AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md`)
**Branch:** `task/0.7.6b-principal-operator-capability`
**Class:** `public-api` (additive core contracts) — not `runtime-behaviour`
**Dependencies:** 0.7.1 (resource identity, CAS), 0.7.4 (evidence/read model) — both merged on `release/0.7.0`

## Mission

Define the two types every later 0.7.6 task depends on: who the operator is, and what an operator may
command. **Contracts only.** No HTTP, no OIDC, no controller change, no policy engine, no store change.

The audit established (F1–F5) that no operator authentication or operator capability model exists, and that
the one existing scope enum (`ApiKeyScope`: `RUN`/`READ`/`ADMIN`, `tramai-platform/.../PlatformModels.kt:18`)
mixes operator authority (`ADMIN` = manage keys + audit) with runtime authority (`RUN` = cause AI execution,
`ADMIN` = install plugins). 0.7.6b must not inherit that conflation.

## Deliverables

### 1. `OperatorPrincipal` — authenticated operator identity

Additive core contract, framework-neutral, Java-friendly (no Kotlin-only idioms in the public shape):

```kotlin
public data class OperatorPrincipal(
    public val subject: String,          // stable IdP subject, already validated for shape
    public val issuer: String,           // token issuer; two operators are equal only if subject AND issuer match
    public val authenticatedAt: Instant, // when the adapter verified the credential
    public val capabilities: Set<OperatorCapability>,
)
```

Requirements:

- Constructible **only** as a value: there is no factory that reads a request, header or thread-local.
- `capabilities` is resolved by the authentication adapter (0.7.6c) from server-side configuration, never
  from a request body. An empty set is valid and means "authenticated, authorized for nothing".
- Equality includes `issuer`, so a subject from one IdP cannot impersonate the same subject in another.
- If the repository's existing shape rules fit, reuse `SafeActorIdPolicy.validateActorId`
  (`tramai-core/.../core/approval/SafeActorIdPolicy.kt:29`) for `subject` rather than writing a second
  validator; do not widen its allowed character set.

### 2. `OperatorCapability` — closed operator vocabulary

```kotlin
public enum class OperatorCapability(public val wireName: String) {
    INSPECT_GOVERNANCE("inspect-governance"),
    ADMINISTER_WORKLOAD("administer-workload"),
    DECIDE_APPROVAL("decide-approval"),
    CANCEL_RUN("cancel-run"),
    ;
    // mirror ApiKeyScope's wire-name pattern: fromWireName throws on an unknown value
}
```

Requirements:

- **Closed** and **operator-only**. Membership is justified by a privileged operation found in the audit's
  control-path matrix (§3.1). Do not add a member without a corresponding operation.
- Must not contain, alias or convert to anything describing AI execution resources — no tools, MCP
  operations, remote agents, models, providers or network destinations. Runtime authority stays in
  `ToolSecurityMetadata` / tool-permission evidence and is never expressible here.
- No wildcard/`ADMIN`-style super-capability: `ApiKeyScope.grants()` shows how `ADMIN` collapses every
  distinction; capability sets are enumerable grants, and a caller that wants all four states all four.
- Unknown wire names throw (fail closed), matching `ApiKeyScope.fromWireName`.

### 3. Authorization result types

Typed outcomes, no booleans:

```kotlin
public sealed interface OperatorAuthorizationResult {
    public data object Authorized : OperatorAuthorizationResult
    public data class Denied(public val missing: OperatorCapability) : OperatorAuthorizationResult
}
```

Rationale: 0.7.6g must assert *which* capability was missing, not merely that a call failed. A `Boolean`
would let a test pass on an unrelated failure.

### 4. Documentation

`docs/modules/` entry for whichever module owns these contracts (choose the module that owns actor/approval
semantics today — `tramai-core` — and state explicitly in the module card that these types are **contracts
with no enforcement**; 0.7.6e supplies enforcement). A doc that implies enforcement is a finding (audit F6/R5).

## Non-goals

- No Spring/OIDC/JWT code, no dependency additions.
- No change to any controller, store, approval path or evidence format.
- No change to `ApiKeyScope` or `tramai-platform` (recorded as A3).
- No policy engine, no expression language, no role hierarchy, no claim-mapping configuration format
  (that is 0.7.6c).
- No enforcement: nothing in this task may claim that a boundary is now protected.

## Tests required for qualification

Small, behavioural, and discriminating — not coverage for its own sake:

1. `OperatorPrincipal` equality: same subject with different issuers are **not** equal;
   same subject+issuer with different capability sets are not equal.
2. Empty capability set constructs successfully and authorizes nothing.
3. `OperatorCapability.fromWireName("admin")` **throws** — proof the flat `ApiKeyScope` vocabulary was not
   inherited, and that unknown names fail closed.
4. Reflection/hygiene test (mirroring `GovernanceTelemetryExporterTest`'s style): `OperatorCapability` has no
   member whose name or wire name references a runtime resource class (tool/mcp/model/provider/agent/network).
   This is the executable form of "operator capability != runtime capability" and is the one test that must
   survive refactoring.
5. `OperatorAuthorizationResult.Denied` carries the missing capability (asserted exactly, not just non-null).

Each test must fail if its invariant is violated; run the revert-and-observe check for tests 1, 3 and 4 and
record both transcripts.

## Stop conditions (report, do not proceed)

1. The contract cannot be defined without changing a **shipped public signature** on `release/0.7.0`.
2. Defining it requires touching `config/quality/0.6.0-baseline.json` or raising a deviation ceiling.
3. The only workable shape requires reusing a runtime capability type.
4. Any temptation to add enforcement "while we are here" — enforcement is 0.7.6e and its absence is audited
   as A1.

## Public API impact

Additive only: three new types in `tramai-core`. No constructor, `copy`, `componentN`, getter or static
member on an existing public type changes. `:tramai-core:apiCheck` must stay green with no accepted-API
change; if it reports a break, that is stop condition 1.

## Verification

```
./gradlew :tramai-core:test :tramai-core:apiCheck
./gradlew spotlessCheck verifyStaticAnalysis -PtramaiStaticAnalysisBaseRef=$(git rev-parse origin/release/0.7.0)
./gradlew verifyChangePolicy -PchangeClass=public-api -PchangePolicyBase=$(git rev-parse origin/release/0.7.0)
./gradlew verifyModuleDocContract verifyModuleMatrixDrift verifyJUnitTestSignatures verifyCompilerWarnings
```

JUnit test methods need block bodies (`= runBlocking {}` silently skips; `verifyJUnitTestSignatures` catches
it). Keep new lines under 120 characters; any function with ≥6 parameters gets refactored, never baselined.

## Handoff to 0.7.6c

After 0.7.6b, 0.7.6c consumes exactly two things: `OperatorPrincipal` (to construct from a validated JWT) and
`OperatorCapability` (to resolve server-side from claims). If 0.7.6c needs a third concept from this
contract, that is a design gap in this task and must be reported rather than patched downstream.
