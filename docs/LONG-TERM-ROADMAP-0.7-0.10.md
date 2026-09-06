# TramAI Long-Term Product Roadmap — 0.7.0 to 0.10.0

> **Status:** Long-range product direction  
> **Purpose:** Keep each release coherent while preserving the architectural direction already explored in the 0.7 roadmap work.  
> **Rule:** A future architectural concern may constrain an earlier design without becoming an earlier release commitment.

---

## 1. Product progression

TramAI should progress through four distinct product questions rather than attempt to answer all of them in one release.

| Release | Theme | Product question |
|---|---|---|
| **0.7.0** | **Governed AI Control Plane** | Can operators see, explain, and control governed AI workloads? |
| **0.8.0** | **Governance DX & Intelligence** | Can developers define, test, simulate, and reason about governance easily? |
| **0.9.0** | **Enterprise Deployment & Security** | Can an enterprise adopt, secure, and operate TramAI without building the surrounding platform layer itself? |
| **0.10.0** | **Governed Learning & Optimization** | Can TramAI learn from and optimize governed execution without weakening governance? |

Potential **1.0** work starts only when the critical public contracts required for a stable governed-AI platform are mature enough for a long-term compatibility commitment.

---

# 2. TramAI 0.7.0 — Governed AI Control Plane

## Release thesis

> **The model proposes. TramAI decides. The control plane shows why.**

0.7.0 turns the hardened 0.6.x runtime into an operational governance surface.

The release is complete when an operator can identify a governed workload, understand the policy-constrained execution decision, inspect authoritative evidence, intervene through authorized controls, and reconstruct what happened later.

## Must-ship loop

```text
Governed workload identity/version
        ↓
Classification before provider exposure
        ↓
Named provider deployments / trust zones
        ↓
Restrictive organization ∩ environment ∩ workload policy
        ↓
Authorized provider/model candidates + stable reason paths
        ↓
Policy-constrained selection and execution
        ↓
Authoritative typed governance evidence
        ↓
Control-plane projection/query API
        ↓
Semantic execution timeline
        ↓
OIDC-backed authentication + server-side authorization
        ↓
Authorized runtime control
        ↓
Authoritative suspended-run cancellation + future-resume fencing
        ↓
Forensic reconstruction without side effects
        ↓
Dashboard 2.0 as a client of those APIs
```

A persisted suspended run that has been authoritatively cancelled must never become executable again through late approval, process restart, or a competing resume. This is a generic runtime-control requirement, not an agent-specific feature.

## 0.7.0 non-goals

Unless an implementation finding makes one necessary for P0 correctness or security, 0.7.0 does **not** require:

- Workflow DSL 2.0;
- generic policy simulation;
- deterministic policy replay as a public capability;
- developer governance debugger;
- broad governance contract-testing UX;
- governance vocabulary/facts public extension SPI;
- semantic structured-output expansion;
- provider-neutral required-tool expansion;
- approval lifetime/remediation redesign beyond current P0 safety needs;
- governed learning traces or dataset capture;
- FinOps/adaptive optimization breadth;
- enterprise IdP productization beyond the generic OIDC/RBAC contract;
- key rotation/KMS breadth;
- Docker/Helm product packaging;
- broad Spring adoption sugar;
- broad compliance-framework mapping.

Those concerns remain valuable architectural inputs, but they belong to later release themes.

---

# 3. TramAI 0.8.0 — Governance DX & Intelligence

## Release thesis

> **Define governance once. Test it before production. Explain the result everywhere.**

0.8.0 focuses on the developer and governance-authoring experience over the authoritative runtime/control-plane contracts proven in 0.7.

## Candidate scope

### Governance authoring and semantics

- Workflow DSL 2.0 and state/context ergonomics;
- canonical Governance Vocabulary + Governance Facts foundation;
- controlled organization extension vocabulary;
- explicit fact provenance and authority ordering;
- narrow metadata-classification integration where useful;
- semantic structured-output contracts;
- provider-neutral typed tool invocation contracts;
- careful tool-obligation lifecycle refinement.

### Governance intelligence

- policy simulation / decision preview;
- deterministic policy replay where evidence permits;
- governance contract testing;
- developer-local governance debugger;
- deterministic findings and richer incident analysis;
- approval-lifetime and safe-replacement refinement where still needed.

## Core invariants

```text
same governance semantics
        ↓
execution / simulation / testing / debugger / evidence
```

No authoring surface may create a second policy engine.

Future annotations, YAML/configuration, and Kotlin DSLs are presentation/authoring surfaces over one canonical governance model.

---

# 4. TramAI 0.9.0 — Enterprise Deployment & Security

## Release thesis

> **Make the governed control plane deployable and operable as an enterprise platform component.**

0.9.0 productizes the runtime/control-plane and governance-DX foundations without changing their authority model.

## Candidate scope

### Enterprise identity compatibility

- generic OIDC compatibility profile;
- Microsoft Entra ID integration guidance/profile;
- Okta integration guidance/profile;
- Keycloak reference profile;
- group/claim → TramAI capability mapping;
- actor identity propagation and privileged-action audit;
- SAML through a reviewed broker/adapter boundary where justified rather than a second auth stack in core.

### Cryptographic lifecycle

- key-provider/key-ring abstraction;
- active key vs historical decrypt keys;
- key-ID-based record decryption;
- safe key rotation;
- restart-safe re-encryption/migration;
- retirement guard while persisted data still references an old key;
- auditable privileged key lifecycle actions;
- adapters for Vault/KMS/HSM systems as separately scoped integrations.

### Reference deployment

A supported reference profile should compose, where applicable:

```text
TramAI application/runtime
TramAI control plane
Dashboard 2.0
PostgreSQL
OpenTelemetry Collector
Prometheus / Grafana
optional Keycloak
optional local model runtime
```

Candidate packaging:

1. Docker Compose reference deployment;
2. production deployment documentation;
3. Helm chart after the deployment contract is stable;
4. Kubernetes operator only if real lifecycle requirements justify one.

### Developer adoption

- Spring Boot five-minute governed-workload golden path;
- opinionated starter/configuration path;
- deployment and security runbooks;
- threat model;
- backup/recovery guidance;
- CISO/architecture guide;
- clear separation between technical-control evidence and legal/compliance claims.

---

# 5. TramAI 0.10.0 — Governed Learning & Optimization

## Release thesis

> **Learn and optimize from governed outcomes without turning governance into surveillance or allowing optimization to widen authority.**

## Candidate scope

### Governed learning

- canonical semantic learning traces;
- privacy-safe evaluation capture;
- explicit learning eligibility separate from audit retention;
- minimization/redaction;
- retention/deletion/lineage;
- controlled dataset export;
- evaluation/SFT/preference/distillation adapters where justified.

Core privacy gate:

> **No production raw-content learning capture before capture, retention, access, deletion, residency, and export boundaries are explicit and proven.**

### Policy-constrained optimization

Preserve:

```text
authorized = governance constraints
viable     = authorized ∩ operational constraints
selected   = optimization(viable)
```

Candidate signals:

- availability/health;
- quota/capacity;
- cost/budget;
- latency;
- quality;
- locality;
- provider/deployment preference;
- energy/carbon signals where trustworthy and explicitly configured.

Optimization may choose or further constrain. It may never create authority.

### FinOps

- cost/usage evidence;
- workload/provider/model attribution;
- budget-aware selection within the authorized set;
- explainable optimization reasons;
- explicit unknown/missing-price semantics.

---

# 6. Architecture commitments across releases

Later releases may influence earlier architecture only where a wrong earlier design would make the later capability unsafe or incompatible.

Examples:

| Later capability | Earlier architecture commitment |
|---|---|
| Enterprise IdPs | 0.7 authorization must be identity-provider neutral and capability based |
| Key rotation | encrypted records must retain enough key identity/version information for historical decryption |
| Workflow DSL 2.0 | canonical workflow/runtime semantics must not depend on one authoring syntax |
| Governance debugger | decisions must expose stable structured reason paths |
| Policy replay | evidence/configuration identities must preserve historical semantics |
| Governed autonomous workers | 0.7 persisted suspended-run cancellation must establish authoritative terminal state and fence future resume |
| Learning traces | audit/telemetry must not silently become training-data collection |
| Adaptive optimization | authorization, viability, and selection must remain distinct stages |
| Helm/reference deployment | Dashboard/control plane must remain headless/API-first and not own policy authority |

Core rule:

> **Design for the future capability when necessary; do not ship the future capability early merely because the architecture anticipates it.**

---

# 7. Release discipline

A proposed capability belongs in a release only when it answers that release's primary product question.

For any addition, ask:

1. Which release thesis does this capability serve?
2. Does the current release become unsafe or incoherent without it?
3. Is only an architecture boundary needed now?
4. Can implementation safely move to the next themed release?

Default to the later release when the answer is uncertain.

---

# 8. Path toward 1.0

1.0 should not be scheduled merely because 0.10 is complete.

A 1.0 decision should instead evaluate whether TramAI has stable contracts for the parts organizations must safely depend on, including:

- governance decision semantics;
- policy composition boundaries;
- provider/tool execution contracts;
- approval authority semantics;
- evidence/audit compatibility;
- workload identity and control-plane contracts;
- persistence/recovery compatibility;
- security and deployment expectations;
- upgrade/migration guarantees.

> **The goal is not to reach 1.0 quickly. The goal is to make a 1.0 compatibility promise worth trusting.**
