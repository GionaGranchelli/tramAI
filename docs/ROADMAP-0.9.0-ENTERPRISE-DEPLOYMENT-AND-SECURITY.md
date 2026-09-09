# TramAI 0.9.0 — Enterprise Deployment & Security

> **Status:** Long-range roadmap target  
> **Target release:** TramAI 0.9.0  
> **Relationship:** Follows the 0.7 Governed AI Control Plane and 0.8 Governance DX & Intelligence releases.  
> **Purpose:** Turn TramAI's governed runtime/control-plane contracts into an enterprise-deployable platform component without introducing a second source of governance truth.

---

## 1. Release thesis

> **Make the governed control plane deployable, securable, and operable in enterprise environments without changing its authority model.**

0.7 establishes the operational control plane.

0.8 improves governance authoring, testing, simulation, and developer reasoning.

0.9 productizes those foundations for real enterprise deployment.

Canonical direction:

```text
AUTHORITATIVE TRAMAI GOVERNANCE CONTRACTS
                  │
      ┌───────────┼────────────┐
      ▼           ▼            ▼
  Identity     Key lifecycle   Deployment
  integration  + crypto ops    productization
      │           │            │
      └───────────┼────────────┘
                  ▼
       ENTERPRISE-OPERABLE PROFILE
```

Core principle:

> **Productization may make TramAI easier to deploy and integrate, but it must never create a second source of governance truth.**

---

# 2. Enterprise identity compatibility

0.7 owns the generic OIDC/authentication and capability-based authorization boundary.

0.9 productizes compatibility around that contract.

## Candidate integrations

- Microsoft Entra ID;
- Okta;
- Keycloak reference profile;
- generic OIDC provider compatibility;
- group/claim → TramAI capability mapping;
- tenant/organization mapping where required by supported deployment profiles.

SAML may be supported through a reviewed broker/adapter boundary where justified. TramAI core should not grow a second independent authentication stack merely to claim SAML support.

## Invariants

- IdP-specific groups/claims do not become core TramAI authorization semantics;
- capability checks remain server-side;
- UI visibility never substitutes for authorization;
- actor identity propagates into approvals, runtime controls, sensitive reveals, evidence exports, and governance changes;
- privileged actions remain auditable.

---

# 3. Cryptographic key lifecycle

Key rotation is an operational lifecycle, not simply a configuration switch.

Canonical model:

```text
KeyProvider / KeyRing
       │
       ├── active encryption key
       ├── historical readable keys
       ├── retirable keys
       └── retired keys
               ↓
Persisted encrypted record
       ├── key identity
       ├── algorithm/version
       └── ciphertext
```

## Candidate capabilities

- active-key switching for new encryption;
- key-ID-based historical decryption;
- restart-safe re-encryption/migration;
- bounded batch rotation;
- verification before old-key retirement;
- retirement guard while supported persisted state still references a key;
- audit/evidence for privileged key lifecycle operations;
- safe failure if required historical key material is unavailable.

## Critical continuity requirement

Rotation must not silently invalidate:

- suspended workflows;
- approvals/continuations;
- encrypted resume credentials;
- audit/evidence records that require supported decryption;
- restart/recovery semantics.

## Integration breadth

Potential adapters include:

- local/environment/file-backed secret material for development/reference use;
- HashiCorp Vault;
- AWS KMS;
- Azure Key Vault;
- Google Cloud KMS;
- PKCS#11/HSM integrations where justified.

Not every backend must ship in the first 0.9 slice.

---

# 4. Reference deployment productization

TramAI remains embeddable and API-first. A reference deployment is a supported composition, not a mandatory centralized architecture.

## Reference profile

Where applicable:

```text
TramAI governed application/runtime
TramAI control plane
Dashboard 2.0
PostgreSQL
OpenTelemetry Collector
Prometheus
Grafana
optional Keycloak
optional local model runtime
```

## Packaging sequence

1. Docker Compose reference deployment;
2. production deployment/security documentation;
3. Helm chart after deployment contracts stabilize;
4. Kubernetes operator only if real lifecycle requirements justify one.

## Deployment invariants

- packaging cannot bypass runtime policy or authorization;
- Dashboard remains a client rather than authority;
- secrets are not embedded into distributable images/configuration examples;
- persistent state, backup, recovery, migration, and upgrade semantics are documented;
- observability is not treated as the authoritative governance store;
- deployment profiles do not make legal/compliance claims.

---

# 5. Spring Boot adoption profile

0.9 should provide a clearly supported path from a normal Spring Boot service to one governed workload without requiring developers to understand every internal TramAI subsystem.

Target experience:

```text
add TramAI BOM/starter
        ↓
configure workload identity + provider/model
        ↓
declare governance policy
        ↓
use existing @AiService or workflow API
        ↓
run
        ↓
inspect deterministic allow/deny/approval reason
        ↓
optionally enable control plane / Dashboard
```

This is a golden path, not a second runtime.

## Authoring rule

```text
explicit API / Kotlin DSL / configuration / optional annotations
                            ↓
                 canonical governance semantics
                            ↓
                     TramAI runtime
```

A broad annotation catalogue is optional. If introduced, annotations may reference or configure canonical governance concepts but must not create a parallel policy language.

---

# 6. Enterprise operator/security documentation

Candidate deliverables:

- production threat model;
- identity/RBAC deployment guide;
- Entra/Okta/Keycloak mapping guides;
- key-rotation runbook;
- backup/recovery runbook;
- migration/upgrade guidance;
- sensitive reveal/export authorization guidance;
- production configuration checklist;
- network/egress deployment guidance;
- CISO architecture guide;
- explicit technical-control/evidence claim boundaries.

TramAI may describe and verify technical controls. It must not claim that installation alone guarantees legal or regulatory compliance.

---

# 7. Explicit non-goals

0.9 does not require:

- a hosted multi-tenant TramAI SaaS;
- billing/account-management infrastructure;
- every enterprise IdP connector;
- a native SAML identity provider;
- every KMS/Vault/HSM adapter;
- a Kubernetes operator without demonstrated need;
- cloud marketplace packaging for every provider;
- a broad annotation-driven replacement for stable APIs;
- policy semantics implemented in deployment tooling;
- automatic compliance certification.

---

# 8. Success profile

A credible 0.9 enterprise deployment should demonstrate:

```text
Spring/JVM workload
        ↓
enterprise identity provider
        ↓
TramAI capability-based authorization
        ↓
governed runtime + control plane
        ↓
persistent encrypted state
        ↓
safe key lifecycle
        ↓
reference deployment observability
        ↓
Dashboard / headless APIs
        ↓
backup, recovery, upgrade, security runbooks
```

The deployment remains governed by the same runtime semantics proven in 0.7 and made easier to author/test in 0.8.
