# Module: `tramai-spring-boot-starter-sovereign-ops-rest`

> **One-liner:** REST surface for sovereign ops — approval control-plane and inbox controllers with reviewer UI support, plus the operator OIDC/JWT authentication boundary.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

REST surface for sovereign ops: approval control-plane + inbox controllers (`ApprovalControlPlaneController`, `ApprovalInboxController`), reviewer UI controller, DTOs.

### Public entry points

- `ApprovalControlPlaneController`, `ApprovalInboxController`, `ApprovalReviewerUiController` — controllers
- `ApprovalInboxDtos`, `ApprovalControlPlaneRestDtos` — DTOs
- `ApprovalControlPlaneRestAutoConfiguration` — auto-configuration for the controllers
- `OperatorAuthenticationAutoConfiguration` — operator authentication boundary (0.7.6c), off by default
- `OperatorSecurityProperties` — its `tramai.sovereign.ops.security.*` configuration

Verify against `tramai-spring-boot-starter-sovereign-ops-rest/api/tramai-spring-boot-starter-sovereign-ops-rest.api`.

### Internal extension points

- REST transport over the ops operations

### Significant dependencies

- `api(tramai-spring-boot-starter-sovereign-ops)`, Spring Web (implementation)
- `compileOnly(spring-boot-starter-security)`, `compileOnly(spring-boot-starter-oauth2-resource-server)` —
  **optional at the dependency level**. A consumer that wants operator authentication adds
  `spring-boot-starter-oauth2-resource-server` itself; a consumer that does not is unaffected, which is why
  Spring Security is not an `api` dependency here. See [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; controllers are container singletons

### Thread-safety and concurrency

- Controllers must be safe for concurrent HTTP requests

### Failure semantics

- Approval operations surface typed HTTP error responses
- **Operator authentication (0.7.6c), when `tramai.sovereign.ops.security.enabled=true`:**
  - missing, malformed, expired, foreign-signed, wrong-issuer or wrong-audience token → **401**;
  - a verified token whose `sub` cannot be recorded as an approval actor id → **401**
    (`tramai-operator-subject-unsupported`), never a rewritten identity;
  - a verified operator without the required capability → **403** at the eventual control boundary (0.7.6e);
  - `enabled=true` with a blank `issuer`, `audience`, or neither `jwk-set-uri` nor `public-key` →
    **startup failure**
    (`tramai-operator-authentication-incomplete-configuration`). There is no anonymous fallback, no
    trusted-header mode, and no principal constructed from request data;
  - the configured control plane (`tramai.sovereign.ops.rest.base-path`) not covered by `protected-paths`,
    or an empty `protected-paths` → **startup failure**
    (`tramai-operator-authentication-unprotected-control-plane`), so moving the approval base path cannot
    quietly move it out of the operator chain;
  - `enabled=true` without Spring Security on the classpath → **startup failure**
    (`tramai-operator-authentication-missing-dependency`), because an inactive boundary that looks enabled
    would leave the routes open.

### Operator authentication configuration (0.7.6c)

| Property | Meaning |
|---|---|
| `tramai.sovereign.ops.security.enabled` | Turns the boundary on. Default `false`. |
| `…security.issuer` | Expected `iss`; validated strictly on every token. |
| `…security.audience` | Required `aud` member. |
| `…security.jwk-set-uri` | JWKS endpoint for signature verification. Configured, never token-discovered. |
| `…security.public-key` | Base64 X.509 `SubjectPublicKeyInfo` RSA key, for deployments that pin a key instead of publishing a JWKS. **Takes precedence over `jwk-set-uri`** when both are set. |
| `…security.role-claim` | Claim carrying role values. Default `roles`. |
| `…security.role-mappings.<role>` | Server-side list of `OperatorCapability` wire names a role grants. |
| `…security.protected-paths` | Paths behind the boundary. Default `/tramai/sovereign/**`. |

Semantics: signature (RS256 only), expiry presence and value, not-before, issuer and audience are all validated;
trust material is `public-key` if set, otherwise `jwk-set-uri` (`operatorJwtDecoder(properties)`); capabilities come
**only** from `role-mappings` — an absent, unknown or client-invented role (`admin`, `*`) grants nothing, and an
unknown capability wire name in configuration is a startup failure. The grant set is an immutable snapshot.
Identity is the exact `(issuer, subject)` pair; no subject is transformed. Auth0-style (`auth0|abc123`) and
URL-shaped subjects are **rejected** (audit limitation A4) because the subject becomes the approval actor id.

The decoder is **built by the chain**, not registered as a bean (`operatorJwtDecoder(properties)`), so no host
`JwtDecoder` bean — under any name or qualifier — can become the operator decoder or weaken issuer, audience,
algorithm, expiry or signature validation. The trade-off is accepted deliberately: an overridable trust source is
worse than an unconfigurable one.

**Host integration — ordering is the host's responsibility.** The chain is `@Order(1)` and scoped to
`protected-paths`, which is enough to run before a Spring Boot default chain, but it **cannot outrank a host chain
registered at a higher precedence**. That case is tested (`OperatorChainShadowingTest`) rather than assumed:

- a host chain at `@Order(0)` matching the operator paths does shadow the operator chain;
- every request to a shadowed route then gets **401**, including one carrying a **valid** token, because the wrong
  chain consumed the request before the resource server could authenticate it;
- a non-JWT identity established by the host chain (HTTP Basic) is **never** promoted to an operator.

So shadowing is a **denial a host can cause, never an authorization bypass**. The contract: **register no chain
with higher precedence than the operator chain over the operator paths.** The starter cannot enforce ordering
against an arbitrary `@Order` without owning the host's whole security configuration. These shadowing tests
establish fail-closed behaviour **for routes that consume an `OperatorPrincipal`**; they say nothing about the
legacy approval endpoints, which still do not require a principal at all (0.7.6e).

### What this module does NOT enforce

Authentication only. The approval endpoints still accept `actorId`/`actorRole` from the request body and
perform no capability check (0.7.6a finding F1); approval decisions still auto-detect the expected version
(finding F2). Authorization is 0.7.6e, concurrency fencing is 0.7.6d, actor attribution is 0.7.6f. Do not
describe these operations as authorization-safe.

### Contract tests / TCKs

- `ApprovalControlPlaneControllerTest`, `ApprovalInboxControllerTest`
- `OperatorCapabilityMappingTest` — claims are input only; unknown/absent roles and unknown capability names
- `OperatorSubjectPolicyTest` — recordable subjects, rejected A4 formats, identity-pair collision resistance,
  `authenticatedAt` is the verification instant
- `OperatorAuthenticationHttpTest` — real filter chain and MockMvc: 401/403 distinctions, foreign-key token,
  wrong issuer/audience, expiry, unmapped claims, handler not invoked on denial

### Do not

- Do not add persistence here — controllers delegate to the ops operations
- Do not let a controller read the caller's identity from a request field, and do not accept a
  caller-constructed `OperatorPrincipal` as proof of authentication; take it as a resolved method argument
- Do not put Spring Security types in `tramai-core`
- Do not widen `SafeActorIdPolicy` or transform subjects to make an identity provider fit

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer

## Approval precondition contract (0.7.6d)

| Request | Response |
|---|---|
| `expectedVersion` absent | **400** `INVALID_PRECONDITION`, `tramai-approval-expected-version-required` |
| `"expectedVersion": null` | **400**, same code — never substituted |
| negative | **400**, `tramai-approval-invalid-expected-version` |
| stale | **409** `STALE` with `expectedVersion` and `currentVersion` |
| current | unchanged (`200`/`404`/`409` per existing semantics) |

The JSON body field is the single precondition representation; no `If-Match` header was introduced that could
disagree with it. A rejected precondition never becomes a control-plane command, so it cannot reach the mutation
store. Error bodies carry a stable code and no approval metadata, token material or stack trace. `actorId` and
`actorRole` remain untrusted claims (0.7.6e).
