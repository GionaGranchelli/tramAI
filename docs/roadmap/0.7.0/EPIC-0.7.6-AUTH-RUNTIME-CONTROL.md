# Epic 0.7.6 — Authentication, Authorization & Runtime Control

**Branch:** `epic/0.7.6-auth-runtime-control`  
**Status:** ⚪ Planned  
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
| 0.7.6a | Security/control baseline audit | Map current auth hooks, lifecycle APIs, stores and unsafe direct mutations |
| 0.7.6b | Principal + operator capability contract | IdP-neutral authenticated identity and server-side operator/control-plane capability model; terminology must not collapse operator authority with runtime capability governance |
| 0.7.6c | OIDC/Spring boundary | Generic adapter/integration seam without vendor authority semantics in core |
| 0.7.6d | Typed control commands | Target identity, expected version/precondition, reason and supported lifecycle operations |
| 0.7.6e | Runtime authorization path | Authenticate → authorize → validate → mutate authority; no bypass store path |
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

## Adversarial proof

Reject unauthenticated, unauthorized, stale-target and forged-capability controls; prove UI/client flags cannot create authority; prove direct checkpoint/store mutation is not a supported control API.
