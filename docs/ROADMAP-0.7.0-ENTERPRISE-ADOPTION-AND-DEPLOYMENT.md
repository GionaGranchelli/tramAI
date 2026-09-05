# TramAI 0.7.0 / 0.7.x — Enterprise Adoption and Deployment Companion

> **Status:** P1 / first-0.7.x productization direction with explicit architecture commitments  
> **Target:** TramAI 0.7.0 where bounded slices fit safely; otherwise first 0.7.x follow-up  
> **Relationship:** Complements `ROADMAP-0.7.0-RELEASE-CUT.md` P0.9/P0.11, `ROADMAP-0.7.0.md` Phase 6, and `ROADMAP-0.7.0-WORKFLOW-DX-AND-DSL.md`.  
> **Purpose:** Close the gap between a technically complete governed-AI control plane and something a JVM team or enterprise platform team can adopt, secure, and operate without building the surrounding integration layer from scratch.

---

## 1. Decision

The 0.7.0 control-plane roadmap already owns the authoritative governance loop:

```text
identify → classify → constrain → select → execute → approve
        → observe → control → reconstruct → prove
```

It also already requires generic authentication/OIDC, server-side authorization/RBAC, actor propagation, privileged-action audit, Dashboard 2.0, and Workflow DSL 2.0 direction.

What remains is **enterprise productization around those contracts**, not a second governance architecture.

Canonical direction:

```text
AUTHORITATIVE TRAMAI GOVERNANCE CONTRACTS
                  │
      ┌───────────┼────────────┐
      ▼           ▼            ▼
  Identity     Key lifecycle   Developer DX
  adapters        +            +
              deployment       golden path
      │           │            │
      └───────────┼────────────┘
                  ▼
       ENTERPRISE-ADOPTABLE PROFILE
```

Core principle:

> **Productization may make TramAI easier to integrate, but it must never create a second source of governance truth.**

This companion does **not** expand the 0.7.0 P0 release loop. If a capability below is not required to make P0 safe and coherent, it may move to first 0.7.x.

---

## 2. Scope and priority

### P1 / 0.7.x target

1. Enterprise identity compatibility profile over the P0 OIDC/RBAC boundary.
2. Cryptographic key lifecycle and rotation foundation.
3. Supported reference deployment profile.
4. Spring Boot five-minute governed-workload golden path.
5. Enterprise security/operator integration documentation.

### Architecture commitment now, breadth later

- identity-provider neutrality;
- one capability/authorization model regardless of IdP;
- key-id-aware decryptability across rotation;
- no key rotation that invalidates suspended/replayable work;
- deployment packaging that cannot bypass runtime policy or authorization;
- one governance semantic model shared by explicit APIs, Kotlin DSL, configuration, and any future annotations;
- headless/API-first operation remains possible without Dashboard 2.0.

### Explicitly not required for 0.7.0 P0

- first-party SDKs for every identity vendor;
- a native SAML implementation in TramAI core;
- every cloud KMS/Vault adapter;
- a Kubernetes operator;
- cloud marketplace images;
- hosted SaaS account/billing infrastructure;
- a broad annotation catalogue duplicating policy semantics.

---

# 3. Enterprise Identity Compatibility Profile

## 3.1 Baseline already owned by P0

The main 0.7.0 roadmap already requires:

- OIDC / Spring Security integration boundary;
- authenticated actor propagation into commands and approvals;
- capability-based authorization;
- server-side authorization independent of UI visibility;
- separate permissions for approvals, runtime control, sensitive reveal, evidence export, and governance configuration;
- audit of privileged actions.

This companion must **reuse** that model rather than introduce vendor-specific authorization semantics.

## 3.2 P1 productization target

Provide a documented and tested compatibility profile for common enterprise identity deployments:

- generic standards-compliant OIDC;
- Microsoft Entra ID;
- Okta;
- Keycloak.

The first slice should prefer configuration and conformance tests over vendor SDK dependencies in core.

Candidate mapping boundary:

```text
OIDC principal / claims
        ↓
trusted identity normalization
        ↓
organization-configured group/claim mapping
        ↓
TramAI capabilities
        ↓
server-side authorization decision
```

Example capability families:

```text
approval.decide
workload.view
workload.control
policy.view
governance.configure
evidence.export
sensitive.reveal
audit.view
```

Exact names are not frozen by this document.

## 3.3 Requirements

- Issuer, audience, signature, expiry, and other token validation remain delegated to a trusted standards-compliant security layer such as Spring Security.
- TramAI authorization consumes a normalized authenticated principal; it does not trust arbitrary frontend role strings.
- Group/claim mapping is organization configuration, not a hard-coded Entra/Okta/Keycloak semantic in runtime core.
- Missing or malformed identity claims fail closed for privileged operations.
- Actor identity used for approvals and commands is stable enough to support audit/reconstruction.
- UI route visibility is never authority.
- IdP migration must not require changing the core governance decision model.

## 3.4 SAML boundary

SAML may be supported later through an identity broker or Spring Security-compatible integration, but TramAI should not become a general SAML implementation.

Preferred initial enterprise pattern:

```text
SAML enterprise IdP
      ↓
OIDC-capable broker / security integration
      ↓
TramAI normalized principal + capabilities
```

A direct SAML adapter is justified only if real adopter demand proves the brokered approach insufficient.

## 3.5 Acceptance criteria

- One integration test profile proves generic OIDC end to end.
- At least one realistic enterprise IdP configuration is exercised in CI or a deterministic integration harness.
- Claim/group mapping cannot grant a capability absent from the configured mapping policy.
- Approval/control audit evidence records authenticated actor identity and authorization outcome without copying raw tokens.
- Switching between supported IdPs does not change TramAI policy semantics.

---

# 4. Cryptographic Key Lifecycle and Rotation

## 4.1 Problem

TramAI already stores key identifiers/version metadata in encrypted persistence paths, but enterprise operation requires a lifecycle rather than a single configured encryption key.

Rotation must not break:

- suspended workflows;
- approval resume credentials;
- encrypted persistence records;
- restart/recovery;
- evidence/reconstruction identities;
- long-running or delayed approval flows.

## 4.2 Canonical key model

Introduce or converge on a provider-neutral key-resolution boundary similar to:

```text
KeyProvider
  ├── activeEncryptionKey()
  ├── resolve(keyId)
  └── metadata(keyId)

EncryptedRecord
  ├── keyId
  ├── algorithm
  ├── formatVersion
  └── ciphertext
```

The exact API is not frozen here.

Core invariant:

```text
record encrypted with key K
    => remains decryptable while K is within its declared recovery lifetime
```

Changing the active key affects **new writes**; it must not silently make historical records unreadable.

## 4.3 Rotation semantics

A safe rotation lifecycle should distinguish:

```text
ACTIVE
  key used for new encryption

READABLE
  key not used for new encryption but retained for decryption/recovery

RETIRABLE
  no live/recoverable records depend on the key, or migration proof exists

RETIRED
  key is no longer accepted
```

Exact naming may change.

Candidate rotation flow:

```text
K1 ACTIVE
   ↓ introduce K2
K2 ACTIVE + K1 READABLE
   ↓ optionally re-encrypt bounded record families
verify migrated records / dependency inventory
   ↓
K1 RETIRABLE
   ↓ explicit operator action
K1 RETIRED
```

## 4.4 Requirements

- Key IDs are explicit and persisted with encrypted records.
- Decryption resolves by record key ID; it never assumes the current active key.
- Rotation is crash/restart safe.
- Partial re-encryption cannot corrupt or orphan a record family.
- Failed rotation leaves a deterministic recoverable state.
- Key material is never written to audit, telemetry, evidence, or control-plane projections.
- Key retirement is an explicit privileged operation with audit evidence.
- The control plane may expose safe key metadata/status but never raw material.
- Rotation cannot revive expired/revoked approval authority or change governance semantics.
- Backup/restore and disaster-recovery requirements are documented before declaring old keys safely retired.

## 4.5 Adapter boundary

The core should remain provider neutral. Later adapters may include:

- environment/file-backed development key sources;
- HashiCorp Vault;
- AWS KMS;
- Azure Key Vault;
- Google Cloud KMS;
- PKCS#11/HSM where justified.

No single vendor becomes the key-management semantic model.

## 4.6 Acceptance criteria

- New records use the newly active key after rotation.
- Old records remain readable through their recorded key ID until explicit retirement.
- A suspended approval/workflow created under K1 can safely resume after K2 becomes active while K1 remains readable.
- Re-encryption is idempotent or otherwise restart-safe with deterministic recovery semantics.
- A key cannot be retired while declared live dependencies still require it unless an explicitly reviewed forced-retirement path exists.
- Rotation/retirement operations are authorization-protected and auditable.
- Tests prove no key material reaches logs, telemetry, evidence, REST responses, or Dashboard 2.0.

---

# 5. Supported Reference Deployment Profile

## 5.1 Goal

Make it possible for an evaluator or platform team to stand up the governed control-plane profile without reverse-engineering module wiring.

The reference deployment is **packaging of existing authority boundaries**, not a new hosted control plane.

## 5.2 First supported profile

A bounded Docker Compose profile should be considered the first target:

```text
TramAI application / reference workload
        │
        ├── TramAI control-plane APIs
        ├── Dashboard 2.0
        └── governed runtime

PostgreSQL
OpenTelemetry Collector
Prometheus
Grafana
optional Keycloak test/enterprise-identity profile
optional local model runtime (for example Ollama) for sovereignty demos
```

Exact observability components may remain optional, but the topology should prove the integration contract.

## 5.3 Requirements

- Secure defaults: privileged control endpoints are not anonymously writable.
- Secrets are injected through supported secret/configuration mechanisms, never committed example credentials.
- PostgreSQL migrations and readiness ordering are explicit.
- Health/readiness probes distinguish process health from authoritative governance readiness where applicable.
- Persistent state locations are documented.
- Network exposure is explicit; internal databases/collectors are not published unnecessarily.
- Reference deployment does not imply production certification.
- Telemetry is not authority and does not receive raw sensitive payloads by default.
- Dashboard and control plane use the same server-side authentication/authorization boundary as non-UI clients.

## 5.4 Kubernetes / Helm direction

A Helm chart is a valuable follow-up after the Docker/reference profile proves the deployment contract.

Initial Helm scope should remain bounded:

- Deployments/Services;
- configuration and secret references;
- ingress/TLS integration points;
- probes;
- persistence/database references;
- optional observability wiring;
- documented identity-provider integration points.

A Kubernetes operator, automatic database provisioning, or cloud-specific infrastructure controller is not required.

## 5.5 Acceptance criteria

- A clean machine can launch the documented reference profile with one bounded setup path.
- The reference workload reaches Dashboard 2.0 and control-plane APIs through authenticated paths.
- PostgreSQL-backed approval/evidence/control-plane state survives component restart according to declared semantics.
- A restricted/confidential example proves that deployment packaging cannot bypass provider/trust policy.
- The profile can run without external cloud AI when configured for a local-only demo.
- Documentation states which parts are demonstration/reference defaults versus production recommendations.

---

# 6. Spring Boot Five-Minute Governed Golden Path

## 6.1 Goal

A Spring Boot developer should be able to add TramAI to an existing JVM service, declare one governed workload, run a deterministic local example, and understand **why** a governance decision occurred without wiring low-level SPIs manually.

This is an adoption objective, not a new workflow runtime.

## 6.2 Principles

- Reuse stable `@AiService` and existing runtime contracts.
- Reuse Workflow DSL 2.0 where orchestration is needed.
- Keep governance semantics in one authoritative model.
- Configuration, Kotlin DSL, explicit APIs, and any future annotations are **authoring surfaces**, not independent policy engines.
- Advanced explicit APIs remain available.

Preferred layering:

```text
Configuration / Kotlin DSL / optional annotations
                    ↓
          canonical governance model
                    ↓
             TramAI runtime
                    ↓
       evidence + control-plane projection
```

## 6.3 Minimum golden path

The onboarding path should aim for:

1. add the TramAI BOM/starter;
2. configure one provider or local model;
3. declare workload identity/environment/purpose;
4. attach or reference one governance policy;
5. run one `@AiService` or workflow;
6. inspect a deterministic allow/deny/approval reason locally;
7. optionally enable the control-plane/dashboard profile.

The exact syntax is deliberately not frozen here.

## 6.4 Annotation boundary

Annotations such as `@Governed`, `@GovernedTool`, or approval-related sugar may be useful later, but only when they lower into the same canonical governance vocabulary/facts/policy model.

Do **not** create an annotation taxonomy that independently redefines:

- data classification;
- residency/trust zone;
- tool risk;
- approval authority;
- provider eligibility;
- egress policy.

A narrow reference annotation such as `@Governed("policy-id")` is preferable to many overlapping semantic annotations unless real usage proves otherwise.

## 6.5 Acceptance criteria

- A new Spring Boot sample uses published TramAI coordinates and no repository-local implementation reach-through.
- The basic governed call requires no direct construction of internal store/worker/control-plane implementation classes.
- The sample includes one policy-allowed and one policy-denied or approval-required path.
- The reason path shown locally matches the authoritative runtime decision/evidence model.
- The same policy semantics survive when the sample enables Dashboard 2.0.
- Java interoperability remains viable; Kotlin ergonomics do not make the governance model Kotlin-only.

---

# 7. Enterprise Security and Operator Documentation

## 7.1 Required technical docs

Productization should include concise, technically reviewable documentation for:

- reference deployment architecture;
- trust boundaries and threat model;
- OIDC/RBAC capability mapping;
- privileged control/action audit model;
- key lifecycle/rotation runbook;
- backup/restore considerations for encrypted state;
- sensitive reveal/evidence-export authorization;
- telemetry versus authoritative evidence boundaries;
- local/EU/global deployment examples;
- secure production configuration checklist.

## 7.2 CISO / architecture guide boundary

A CISO/security architecture guide is useful, but it must remain evidence-based and avoid certification claims.

It should explain:

- which exfiltration paths TramAI can technically constrain;
- where policy is authoritative;
- how human approval is bound to actions;
- what evidence is retained;
- what the control plane can and cannot prove;
- which deployment responsibilities remain with the operator;
- how TramAI differs from relying on application-level checks/prompts alone.

This is a documentation/GTM deliverable, not a new runtime authority layer.

---

# 8. Dependency order

```text
0.7 P0 OIDC/RBAC + control-plane authority
        │
        ├──────────────► enterprise IdP compatibility profile
        │
        ├──────────────► reference deployment identity wiring
        │
        └──────────────► Spring Boot golden path

existing encrypted persistence/key IDs
        │
        ▼
key-provider / multi-key resolution contract
        │
        ▼
rotation + retirement semantics
        │
        ▼
Vault/KMS/HSM adapter breadth

0.7 P0 control-plane + Dashboard 2.0
        │
        ▼
Docker/reference deployment
        │
        ▼
Helm/Kubernetes packaging
```

The P0 control plane remains independently testable without vendor-specific IdPs, KMS products, Helm, or optional annotations.

---

# 9. Recommended implementation sequence

## Wave EA — adoption contract

1. Define enterprise-adoption supported profile and explicit non-goals.
2. Add a Spring Boot governed golden-path example and deterministic adoption test.
3. Document generic OIDC capability mapping and normalized actor contract.

## Wave EB — identity productization

1. Generic OIDC conformance/integration harness.
2. Keycloak reference profile for deterministic/local enterprise identity testing.
3. Entra ID and Okta configuration/claim-mapping recipes and compatibility tests where feasible without secrets/live tenants.
4. Sensitive reveal/evidence-export authorization proof.

## Wave EC — key lifecycle

1. Key-provider/multi-key resolution contract.
2. Active/readable/retirement semantics.
3. Resume/restart tests across active-key change.
4. Bounded re-encryption/rotation workflow.
5. Retirement dependency proof and audit.
6. External KMS/Vault adapters only after the core semantics are stable.

## Wave ED — deployment productization

1. Docker Compose reference profile.
2. Restart/readiness/security verification.
3. Optional local-model and Keycloak profiles.
4. Helm chart after the Compose/reference contract is stable.
5. Operator/security architecture documentation.

---

# 10. Release-cut classification

These capabilities are intentionally classified so they do not destabilize the 0.7.0 control-plane thesis:

```text
0.7.0 P0
  generic authentication/OIDC boundary
  capability-based server-side authorization
  actor identity propagation
  privileged-action audit
  Dashboard 2.0 + control-plane API authority

0.7.0 / first 0.7.x P1
  Spring Boot governed golden path
  enterprise OIDC compatibility profile
  Keycloak reference identity profile
  Entra/Okta mapping recipes
  key-provider + multi-key resolution foundation
  safe key rotation/retirement semantics
  Docker/reference deployment profile
  enterprise security/operator documentation

0.7.x / later breadth
  direct SAML adapter if justified
  broad Vault/KMS/HSM adapter catalogue
  Helm/Kubernetes packaging after reference deployment stabilizes
  cloud marketplace packaging
  Kubernetes operator
  broad governance annotation catalogue
```

If implementation reveals that the existing single-key model can make required P0 approval/recovery data unrecoverable under a normal supported configuration change, that narrow correctness defect must be promoted and fixed independently of the broader P1 key-lifecycle feature.

---

# 11. Release discipline

- IdP integration cannot grant authority outside the configured TramAI capability model.
- UI visibility never substitutes for server-side authorization.
- Raw bearer tokens, credentials, or key material never enter generic logs/evidence/telemetry.
- Rotation cannot make still-supported historical/suspended state silently unreadable.
- New packaging cannot bypass classification, provider eligibility, approval, tool, egress, or evidence boundaries.
- Deployment defaults must fail safely rather than expose privileged control surfaces anonymously.
- Golden-path convenience cannot create hidden policy semantics.
- Future annotations lower into canonical governance semantics; they do not become a second policy engine.
- Vendor integrations remain adapters around stable TramAI contracts.

> **0.7 proves the governed control-plane loop. Enterprise productization makes that loop adoptable without redefining it.**
