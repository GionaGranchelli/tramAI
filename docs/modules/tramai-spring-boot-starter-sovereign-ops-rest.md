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
  - `enabled=true` with a blank `issuer`, `audience` or `jwk-set-uri` → **startup failure**
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
| `…security.role-claim` | Claim carrying role values. Default `roles`. |
| `…security.role-mappings.<role>` | Server-side list of `OperatorCapability` wire names a role grants. |
| `…security.protected-paths` | Paths behind the boundary. Default `/tramai/sovereign/**`. |

Semantics: signature (RS256 only), expiry, not-before, issuer and audience are all validated; capabilities come
**only** from `role-mappings` — an absent, unknown or client-invented role (`admin`, `*`) grants nothing, and an
unknown capability wire name in configuration is a startup failure. The grant set is an immutable snapshot.
Identity is the exact `(issuer, subject)` pair; no subject is transformed. Auth0-style (`auth0|abc123`) and
URL-shaped subjects are **rejected** (audit limitation A4) because the subject becomes the approval actor id.

The chain is `@Order(1)` and scoped to `protected-paths`, so a consumer's own `SecurityFilterChain` can coexist;
a consumer chain must not widen access to those paths.

**Host integration:** when the host application defines its own `SecurityFilterChain`, it must not remove the
operator boundary from the protected paths. The starter's chain takes precedence for those matchers.

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
