# TramAI Enterprise Roadmap — The Sovereign Agent Runtime Milestone

TramAI is evolving from a typed AI integration library into a sovereign agent runtime for JVM enterprises.

The objective is not merely to support local models. TramAI must enable organizations to control where data flows, which models are used, which actions agents may perform, how sensitive operations are approved, how software artifacts are verified, and how incidents are reconstructed.

The target environments are regulated enterprises, European public institutions, industrial organizations, and system integrators delivering AI solutions into controlled infrastructures.

---

## Product Principles

1. **Typed contracts over prompt plumbing** — AI operations remain normal JVM interfaces with typed inputs and outputs.
2. **Deny by default** — Tools, subprocesses, providers, network destinations, and plugins are unavailable until explicitly authorized.
3. **Local-first, not local-only** — Applications can use local, European sovereign, or approved cloud providers without changing business logic.
4. **Security outside the prompt** — Critical controls are enforced by runtime policy, not by instructions sent to the model.
5. **Evidence over claims** — Sovereignty, security, and reliability are demonstrated through reproducible tests, manifests, audit events, benchmarks, and external validation.
6. **Composable architecture** — The core library remains lightweight. Governance, platform, MCP, and control-plane capabilities remain optional modules.

---

## Reference Use Case: Sovereign Document Intelligence

The reference workflow receives a sensitive document, classifies its data, routes it exclusively to an approved local model, generates a typed result, requests human approval before any high-risk action, and records every decision in an append-only audit trail.

This workflow is used across releases to prove functional behavior, policy enforcement, offline deployment, incident replay, and operational readiness.

---

## Phase 0: Product Boundary and Threat Model

**Weeks 1–2**

**Goal:** Define the security boundary before expanding the platform.

### Actions

- Define the Sovereign Document Intelligence reference workflow.
- Document assets, actors, trust boundaries, data flows, and attack surfaces.
- Produce an initial threat model covering prompt injection, sensitive-data leakage, excessive agency, tool misuse, provider compromise, plugin compromise, workflow tampering, and supply-chain risks.
- Define the meaning of sovereign deployment, offline deployment, and air-gapped deployment.
- Create an ADR separating security enforcement from SaaS platform concerns.
- Define v1.0 non-goals.

### Deliverables

- `docs/security/PRODUCT-THESIS.md` — position, problem, promise, target segments, non-goals
- `docs/security/SECURITY-MODEL.md` — trust boundaries, threat model, abuse scenarios, controls mapping, residual risk
- Initial architecture diagram
- Initial risk register (`docs/security/RISK-REGISTER.md`)
- Prioritized epic backlog

### Exit Criteria

- At least 10 documented abuse scenarios.
- Clear module boundaries.
- Reference workflow accepted as the primary demonstration path.
- Scope explicitly excludes non-essential platform features.

---

## Phase 1: Policy-Controlled Execution

**Months 1–2**

**Goal:** Ensure that AI agents cannot execute actions outside explicit authorization boundaries.

### Actions

- Introduce `tramai-security`.
- Add a policy SPI for request, provider, tool, workflow, and network decisions.
- Add tool metadata for permission, risk level, approval mode, audit mode, and egress policy.
- Add data classification levels: `PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, and `RESTRICTED`.
- Add provider policies: `local-only`, `eu-only`, and `approved-cloud`.
- Add approval gates for high-risk actions.
- Emit versioned audit events for policy decisions.
- Add negative tests proving that denied operations never reach the provider or tool executor.
- Generate initial CycloneDX SBOM in CI and begin dependency scanning.
- Begin design partner outreach: 2 design partners by end of Phase 1.

### Deliverables

- `tramai-security` — single module with policy, approval, audit and model-registry packages
- `tramai-sovereign` — dependency aggregator and secure deployment profile
- `AuditEvent v1`
- Security test suite
- Updated reference workflow
- Initial CycloneDX SBOM
- 2 design partner commitments

### Exit Criteria

- Unknown tools are denied.
- Missing permissions cause explicit failures.
- Restricted data cannot reach unauthorized providers.
- High-risk actions require human approval.
- Every allow, deny, and approval decision emits an audit event.

---

## Phase 2: Sovereign Runtime Profile

**Months 3–4**

**Goal:** Prove that TramAI can run locally without hidden cloud dependencies.

### Actions

- Harden Ollama support for production profiles.
- Validate vLLM integration through the OpenAI-compatible provider or introduce a dedicated adapter only where required.
- Add an approved model registry with model identifier, version, checksum, origin, and allowed usage classifications.
- Add strict local-only routing with no cloud fallback.
- Build exportable OCI image bundles.
- Build verified model artifact bundles.
- Generate a verification manifest containing checksums for images, models, configuration, and runtime dependencies.
- Add automated zero-egress tests.
- Publish latency, throughput, and structured-output reliability benchmarks.

### Deliverables

- `tramai-sovereign`
- Offline installation bundle
- Verification manifest
- Benchmark report
- Zero-egress test report

### Exit Criteria

- The reference workflow installs and runs without internet connectivity.
- No unauthorized external DNS or HTTP request occurs during execution.
- Model and image checksums are validated.
- Local execution preserves typed-output behavior.

---

## Phase 3: Supply-Chain and Operational Sovereignty

**Months 5–6**

**Goal:** Make releases verifiable and operationally manageable in controlled environments.

### Actions

- Generate CycloneDX SBOMs for every release.
- Produce build provenance linked to source commits.
- Sign release artifacts and OCI images.
- Add dependency and vulnerability scanning.
- Document offline installation, update, rollback, backup, and restore procedures.
- Add configurable audit retention and export.
- Produce an evidence pack for each release.

### Deliverables

- SBOM
- Provenance metadata
- Signed release artifacts
- Offline update runbook
- Backup and restore runbook
- Release evidence pack

### Exit Criteria

- Every artifact is traceable to a source commit.
- Release contents are machine-readable.
- Offline update and rollback are reproducible.
- Known vulnerabilities are documented and assessed.

**Milestone:** TramAI Sovereign Preview v0.5

---

## Phase 4: Enterprise Governance

**Months 7–8**

**Goal:** Provide governance controls required by internal platform and security teams.

### Actions

- Add RBAC and OIDC-based authentication.
- Introduce workflow registry and model registry APIs.
- Version workflow definitions and preserve definition digests.
- Add queryable append-only audit logs.
- Add incident replay.
- Add retention policies and evidence exports.
- Provide a minimal operations UI only for governance-critical functions.

### Deliverables

- `tramai-control-plane`
- RBAC and OIDC integration
- Workflow registry
- Model registry
- Audit query API
- Incident replay
- Minimal governance dashboard

### Exit Criteria

- Roles and permissions are enforced.
- Workflow changes are versioned and auditable.
- Approved models are centrally managed.
- Incidents can be reconstructed from recorded evidence.

---

## Phase 5: Secure MCP Boundary

**Months 9–10**

**Goal:** Integrate MCP without bypassing policy enforcement.

### Actions

- Apply policy enforcement to every MCP-exposed workflow.
- For remote HTTP-based MCP transports: OAuth-based authorization, resource indicator validation, audience binding, token passthrough rejection.
- For local stdio MCP: environment-provided credentials, subprocess allowlist, filesystem and network sandbox guidance.
- Introduce scoped permissions for MCP tools.
- Require explicit approval for sensitive MCP actions.
- Add TLS, timeouts, rate limits, and audit events for remote transports.
- Add plugin allowlists and subprocess sandboxing guidance.

### Deliverables

- Hardened `tramai-mcp`
- MCP security guide
- MCP authorization test suite
- Secure local integration demo

### Exit Criteria

- Unauthorized MCP calls are denied.
- Tokens issued for other resources are rejected.
- High-risk MCP actions require approval.
- MCP events are fully auditable.

---

## Phase 6: External Validation and Enterprise Release

**Months 11–12**

**Goal:** Produce sufficient evidence for an enterprise-ready release.

### Actions

- Commission an external penetration test.
- Map controls against OWASP guidance for LLM and agentic applications.
- Run load, resilience, and failure-injection tests.
- Validate deployment with at least one real pilot workflow.
- Produce Docker, Helm, and air-gap deployment references.
- Publish a security white paper.
- Define support, patching, and vulnerability-disclosure policies.

### Deliverables

- External penetration-test report
- Security controls matrix
- Load and resilience report
- Pilot case study
- Operator runbook
- Security white paper
- Vulnerability disclosure policy

### Exit Criteria

- No unresolved critical vulnerabilities.
- At least one completed pilot.
- At least two design partners.
- Reproducible installation in a controlled environment.
- Documented support and patch process.

**Milestone:** TramAI Enterprise v1.0

---

## Deferred Beyond v1.0

The following items are intentionally deferred until the sovereign runtime has been validated:

- General-purpose skill marketplace
- Full SaaS billing
- Broad plugin ecosystem
- Visual workflow editor
- Large catalog of provider adapters
- Public-sector procurement integrations
- Advanced multi-tenant SaaS packaging

---

## Success Metrics

| Metric | Target |
|--------|--------|
| Unauthorized tool executions reaching an executor | 0 |
| Restricted-data requests reaching unauthorized providers | 0 |
| Unauthorized external network egress during offline reference workflow | 0 |
| Releases with SBOM and provenance | 100% |
| High-risk operations without approval event | 0 |
| Reproducible offline installations | 100% |
| Design partners before v1.0 | 2+ |
| Completed pilot workflows before v1.0 | 1+ |

---

## Parallel Commercial Workstream (90-day target)

| Workstream | Output |
|------------|--------|
| Product | Reference use case validated |
| Engineering | Policy-controlled vertical slice |
| Security | Threat model corrected, negative tests passing |
| Market validation | 10 exploratory interviews, 2 design partners |
| Open source | README and public demo aligned with pivot |

---

*Roadmap adopted June 2026. See [README.md](README.md) for the current stable library release and [SECURITY-MODEL.md](docs/security/SECURITY-MODEL.md) for the current threat model.*
