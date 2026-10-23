# TASK 0.7.6c — OIDC/Spring Authentication Boundary

**Epic:** 0.7.6 — Authentication, Authorization & Runtime Control
**Predecessor:** 0.7.6b (merged, `61277823`) — `OperatorPrincipal`, `OperatorCapability`,
`OperatorAuthorizationResult` in `tramai-core`
**Baseline commit for this task:** `6127782354b75d349a7d8c454eb78e1305741673` (`release/0.7.0`)
**Change class:** `runtime-behaviour` + `public-api` (new auto-configuration and public adapter types in the
Spring starter) + build (`gradle/libs.versions.toml`). No `tramai-core` contract changed.

## Mission

Turn a verified OIDC JWT into an immutable `OperatorPrincipal`, with capabilities resolved from
**server-controlled** configuration, and fail closed on anything else.

The invariant this task establishes: *a client may assert an identity, but only the trusted authentication
boundary may establish that identity; a client may present claims, but those claims grant only what
server-side configuration permits.*

**What this does not establish:** authorization. The existing approval endpoints are unchanged and are not
protected by this task. Enforcement is 0.7.6e; the approval concurrency defect from the 0.7.6a audit (F2,
`expectedVersion` auto-detect) is still open until 0.7.6d.

---

## Phase 0 — Integration surface as found (evidence)

| Question | Finding | Evidence |
|---|---|---|
| Where do operator control routes live? | `tramai-spring-boot-starter-sovereign-ops-rest`: approval control plane, approval inbox, reviewer UI | `ApprovalControlPlaneController.kt:39` (`${tramai.sovereign.ops.rest.base-path:/tramai/sovereign/approvals}`), `ApprovalInboxController.kt:26`, `ApprovalReviewerUiController.kt:11` (`/tramai/sovereign` + `/reviewer`) |
| When are those routes active? | Only with `tramai.sovereign.ops.rest-control-plane-enabled=true` **and** the decision/resume control-plane beans present; reviewer UI additionally needs `reviewer-ui-enabled=true` | `ApprovalControlPlaneRestAutoConfiguration.kt` |
| Existing authentication in the starter | **None.** No `SecurityFilterChain`, no `JwtDecoder`, no authentication converter | `grep -rn 'SecurityFilterChain\|JwtDecoder\|AuthenticationProvider' tramai-spring-boot-starter-sovereign-ops-rest` → no matches before this task |
| Spring Security dependencies available | Only `spring-security-crypto` (hashing) repo-wide; no web, no resource server, no JOSE | `gradle/libs.versions.toml:19,49` before this task |
| Existing principal shape | `dev.tramai.core.operator.OperatorPrincipal` (0.7.6b), immutable snapshot, subject+issuer identity | `tramai-core/src/main/kotlin/dev/tramai/core/operator/OperatorPrincipal.kt` |
| Actor-id rule that limits subjects | `SafeActorIdPolicy.SAFE_ACTOR_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")` | `tramai-core/.../approval/SafeActorIdPolicy.kt:23` |
| Existing host-app security configuration in the repo | None; the starters are the only Spring surface. No consumer chain to inherit a gap from in-tree, but a consumer *will* bring its own | `grep -rn 'EnableWebSecurity\|SecurityFilterChain' → only the new adapter` |

### Design decisions taken from that inventory

1. **Adapter location:** the starter that owns the routes (`...-sovereign-ops-rest`). Spring Security imports
   stay out of `tramai-core`, which stays framework-neutral.
2. **Path scoping:** the chain matches `tramai.sovereign.ops.security.protected-paths` (default
   `/tramai/sovereign/**`), which covers all three controllers' prefixes, and is `@Order(1)` so a host chain
   cannot leave those routes less protected by accident.
3. **Decoder:** Spring's `NimbusJwtDecoder` with a **configured** JWKS URI and an explicit `RS256` allow-list —
   no custom cryptography, no key discovery driven by the token.
4. **Validators:** `JwtValidators.createDefaultWithIssuer(issuer)` (timestamp + strict issuer) delegating a
   required `aud` member. Extracted as `operatorJwtValidators(...)` so tests exercise the production rule set.
5. **Capability mapping:** a deliberately limited `role-claim` → `role-mappings` map. Claims are input only;
   unknown role/claim → no capability; unknown capability wire name → **startup failure**; no wildcard.
6. **Subject policy (A4):** reject, never transform. Identity is the exact `(issuer, subject)` pair, so no
   stripping/case-folding/separator rewriting means collisions are impossible by construction. Auth0-style and
   URL-shaped subjects are rejected with a 401, and A4 stays recorded.
7. **`authenticatedAt`:** the boundary's verification instant from an injected `Clock`, never the token's `iat`.

## Compatibility risks recorded

| Risk | Decision |
|---|---|
| Adding Spring Security to the starter makes a consumer's existing unauthenticated local setup fail once `enabled=true` | Authentication is **off by default**; turning it on is an explicit operator action |
| A consumer's own `SecurityFilterChain` could shadow ours | Ours is `@Order(1)` and path-scoped; documented as a requirement for consumer chains |
| Two `SecurityFilterChain` beans (consumer + adapter) | Supported by Spring (ordered chains); documented rather than worked around |
| Spring Security as a hard dependency would silently add Boot's default HTTP-Basic chain to every consumer of the REST starter (it broke five pre-existing reviewer-UI tests exactly that way) | **Optional (`compileOnly`)**: consumers opt in by adding `spring-boot-starter-oauth2-resource-server`. `enabled=true` without it fails startup (`tramai-operator-authentication-missing-dependency`) instead of leaving the routes open |
| Real IdP subjects outside the actor-id charset | Rejected (A4). 0.7.6c does **not** claim broad IdP-neutral subject support |
| `jwk-set-uri` requires operator configuration; a wrong value fails at first request, not startup | Startup validates presence and non-blankness; reachability is a deployment concern (fail closed: it cannot authenticate anyone) |

## Phases 1–7 — implementation and proof

Implementation (all in `tramai-spring-boot-starter-sovereign-ops-rest`):

| File | Responsibility |
|---|---|
| `security/OperatorSecurityProperties.kt` | Server-controlled configuration: enabled, issuer, audience, jwk-set-uri, role-claim, role-mappings, protected-paths |
| `security/OperatorAuthenticationAutoConfiguration.kt` | Decoder + validator set + path-scoped `SecurityFilterChain` + bean wiring; `operatorJwtValidators(issuer, audience)` |
| `security/OperatorCapabilityMapping.kt` | Claims → closed `OperatorCapability` set, from configuration only |
| `security/OperatorSubjectPolicy.kt` | A4: recordable-subject rule + typed `UnsupportedOperatorSubjectException` |
| `security/OperatorPrincipalFactory.kt` | The single verified-JWT → `OperatorPrincipal` path |
| `security/OperatorPrincipalResolver.kt` | Security-context read at the boundary; `OperatorPrincipalArgumentResolver` for explicit downstream hand-off |

### Trust model

```text
bearer token
  -> Spring Security resource-server filter   (signature, alg allow-list RS256, expiry, nbf)
  -> JwtDecoder with operatorJwtValidators    (strict iss, required aud)
  -> verified Jwt                             (unobtainable from a request; only the decoder mints it)
  -> OperatorPrincipalFactory.fromVerified    (issuer re-check, recordable subject, Clock-instantiated authenticatedAt)
  -> OperatorCapabilityMapping.capabilitiesFrom (server-side role map; unknown = nothing)
  -> OperatorPrincipal                        (immutable grant snapshot)
  -> OperatorPrincipalArgumentResolver        (explicit method argument; no thread-local below the boundary)
```

Nothing in that chain reads a request body, query parameter, client header or caller-built principal.

### Mutation campaign

Every mutant is applied to this tree, the security tests are run, and the tree is restored from a snapshot
taken before the first run. A mutant counts as killed only when a named test fails.

### Mutation results

| Mutant | Intent | Verdict | Killing test(s) |
|---|---|---|---|
| M1 | issuer validation removed (`createDefaultWithIssuer` → `createDefault`) | **killed** | `a token with the wrong issuer is rejected with 401` |
| M2 | audience validation removed | **killed** | `a token with the wrong audience is rejected with 401` |
| M3 (first attempt) | client claim read as *role names* | **survived — weak mutant, not a guard** | none: capability wire names are not role names, so the mutant was semantically incapable of granting. Replaced. |
| M3 (sharpened) | client claim read as *capability wire names* | **killed** | `claims the mapping does not name cannot grant a capability` |
| M4 | empty capability set replaced by a privileged default | **killed** | `a missing role claim grants nothing`, `claims the mapping does not name cannot grant a capability`, `a claim of the wrong type grants nothing instead of being coerced` |
| M5 | subject compatibility disabled (`SafeActorIdPolicy` bypassed) | **killed** | `an auth0-style subject is rejected…`, `a url-shaped subject is rejected…`, `empty and malformed subjects are rejected`, `an unrecordable subject is rejected with 401…` |
| M6 | `authenticatedAt` taken from the token's `iat` | **killed** | `authenticatedAt is the verification instant and never the token issue time` |
| M7 | factory issuer re-check removed | **killed** | `a token whose issuer differs from the configured issuer cannot produce a principal` |
| M8 | decoder wired to the untrusted key (signature trust removed) | **killed** | `a token signed by a foreign key is rejected with 401` + 4 more |
| CONTROL | unmutated tree | 32/32 pass | — |

The M3 first attempt is reported as a **survivor that was a bad mutant**, not as a passing guard: it mapped
client-supplied strings through the role table, so it could never grant anything the configuration did not
already grant. The sharpened mutant grants directly from the claim and was killed immediately.

Signature verification is Spring's, so no mutant claims to remove framework cryptography; M8 mutates the
*trusted key wiring*, which is the part this adapter actually decides.

Signature verification itself is Spring's; the detector for it is the foreign-key token test. M8 mutates the
*trusted key wiring* rather than claiming to remove framework cryptography.

### One pre-existing test file touched

`ApprovalReviewerUiControllerTest` gained `@AutoConfigureMockMvc(addFilters = false)`. Those five tests assert
HTML/JS payload content, and with Spring Security on the *test* classpath Boot's default chain intercepted
their requests. Disabling filters in a test about payload rendering keeps the assertion honest; the transport
behaviour is covered by `OperatorAuthenticationHttpTest`, which exercises the real chain.

## What authentication is now established, and what is not

**Established:** requests to the protected paths must carry a valid RS256 JWT from the configured issuer for
the configured audience; a verified token yields an immutable operator principal whose capabilities come only
from server configuration; invalid credentials, wrong issuer/audience, expired tokens, foreign signatures and
unrecordable subjects all fail closed with 401.

**Not established — do not claim otherwise:**

- **Authorization (0.7.6e).** The approval endpoints still take `actorId`/`actorRole` from the request body and
  enforce no capability. This task does not change them.
- **Approval concurrency (0.7.6d).** `expectedVersion` still defaults to auto-detect, so stale decisions still
  overwrite newer state.
- **Actor attribution from the principal (0.7.6f).** Approval evidence still records the caller-supplied actor.
- **Adversarial qualification of the boundary as a whole (0.7.6g).**
