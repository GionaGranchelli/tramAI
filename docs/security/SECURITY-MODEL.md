# SECURITY-MODEL.md — TramAI Enterprise

This document defines the security boundary, trust model, threat actors, and abuse scenarios for TramAI Enterprise. It is the foundation for policy design, security testing, and external validation.

---

## 1. Definitions

| Term | Definition |
|------|------------|
| **Sovereign deployment** | The application runs on infrastructure the organization controls. Models, data, and policy enforcement stay within the organization's boundary. |
| **Offline deployment** | The application runs without internet connectivity at runtime. All dependencies, models, and configurations are pre-staged. Verified by automated zero-egress tests at the application level. |
| **Isolated-network deployment** | Offline deployment with network-level egress blocked by infrastructure controls (firewall, Kubernetes NetworkPolicy, proxy enforcement). |
| **Air-gapped deployment** | Isolated-network deployment with controlled, audited transfer procedures and no operational network link to external networks. |

### Enforcement Levels

TramAI enforces **application-level egress policies** — it controls which providers, HTTP destinations, and managed endpoints are reachable.

**Infrastructure-level network isolation** (container policies, firewalls, NetworkPolicy, mandatory proxies, sandboxing) is a shared responsibility. TramAI provides deployment profiles that require infrastructure-level controls for strong guarantees.

A library running in the JVM cannot reliably prevent a compromised native dependency, subprocess, or model runtime from opening a network connection. Application-level and infrastructure-level controls are complementary.

---

## 2. Assets

| Asset | Description | Criticality |
|-------|-------------|-------------|
| **Input data** | Documents, prompts, and structured data sent to AI operations | High — may contain PII, trade secrets, classified information |
| **Model responses** | Outputs from LLM inference | High — may influence business decisions or contain derived sensitive data |
| **Tool execution context** | Parameters and results of tool invocations | High — tools may access databases, APIs, file systems |
| **Policy configuration** | Rules governing data classification, provider routing, tool authorization | Critical — compromise bypasses all controls |
| **Audit events** | Append-only records of policy decisions and workflow execution | Critical — required for forensic investigation and compliance |
| **Model artifacts** | LLM weights, tokenizers, and runtime dependencies | High — tampering risks data exfiltration or incorrect outputs |
| **Workflow definitions** | Versioned workflow specifications | High — determines what agents can do |
| **Secrets and credentials** | API keys, tokens, certificates | Critical — compromise grants unauthorized access |

---

## 3. Trust Boundaries

```
┌─────────────────────────────────────────────────────────┐
│                   Organization Boundary                  │
│  ┌─────────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │ Application │  │  Policy  │  │   Audit Storage    │  │
│  │   (Spring   │──│  Engine  │──│  (append-only)     │  │
│  │    Boot)    │  │          │  │                    │  │
│  └──────┬──────┘  └────┬─────┘  └────────────────────┘  │
│         │              │                                 │
│         │    ┌─────────┴──────────┐                      │
│         │    │  Provider Router   │                      │
│         │    │  (classification-  │                      │
│         │    │   aware)           │                      │
│         │    └──┬──────────────┬──┘                      │
│         │       │              │                          │
│  ┌──────┴──────┐│     ┌────────┴──────────┐              │
│  │ Local Model ││     │ EU-Approved Cloud │              │
│  │ (Ollama/    ││     │ Provider          │              │
│  │  vLLM)      ││     │                    │              │
│  └─────────────┘│     └────────────────────┘              │
│                  │                                        │
└──────────────────┼────────────────────────────────────────┘
                   │
            ┌──────┴──────────────────────────┐
            │  Unauthorized Providers          │
            │  (public cloud, unapproved SaaS) │
            │  ← BLOCKED by egress policy      │
            └─────────────────────────────────┘
```

### Trust Boundary Rules

1. **Application → Provider:** Data classification dictates routing. RESTRICTED data never leaves the organization boundary.
2. **Application → Tool:** Every tool invocation passes through policy enforcement. Tools are deny-by-default.
3. **Policy Engine:** Runs inside the application process. Policy configuration is a protected asset.
4. **Audit Storage:** Append-only, inside the organization boundary. Exportable for external review.
5. **Model Artifacts:** Verified by checksum before loading for TramAI-managed artifacts (GGUF files, OCI bundles, pre-staged models). For remote endpoints (Ollama, vLLM), verification is limited to endpoint identity, TLS, and declared metadata.
6. **Network Egress:** Application-level deny-by-default for managed destinations (providers, HTTP tools). Infrastructure-level isolation (firewall, NetworkPolicy) is required for strong guarantees against native code, subprocesses, and compromised dependencies.

---

## 4. Threat Actors

| Actor | Motivation | Capability |
|-------|------------|------------|
| **External attacker** | Data exfiltration, service disruption | Prompt injection, credential theft, supply-chain compromise |
| **Malicious insider** | Unauthorized data access, fraud | Legitimate access to application, attempts to bypass policy |
| **Compromised model** | Data exfiltration via inference, biased/manipulated outputs | Model outputs as attack vector |
| **Compromised dependency** | Backdoor, data exfiltration, privilege escalation | Supply-chain attack via library or plugin |
| **Compromised provider** | Data collection, prompt logging, model substitution | Man-in-the-middle on cloud API calls |

---

## 5. Abuse Scenarios

### AS-01: Prompt Injection — Data Exfiltration

**Scenario:** An attacker crafts an input that causes the model to ignore its instructions and include sensitive context in its response.

**Controls (layered, not single-point):**

| Layer | Control | Function |
|-------|---------|----------|
| Input | Context minimization | Send only necessary data to the model |
| Input | Data classification + routing | RESTRICTED data stays local |
| Output | Structured output validation | Ensures correct shape (not confidentiality) |
| Output | DLP / PII redaction | Filters secrets and PII before returning |
| Output | Field-level policy | Limits which fields may contain sensitive data |
| Tool | Tool result filtering | Reduces exposure of tool outputs |
| Audit | Violation logging | Records blocks and redactions |

Structured output validation is a structural correctness guard, not a confidentiality guard. A response can be perfectly valid JSON and still contain exfiltrated data.

### AS-02: Prompt Injection — Tool Invocation

**Scenario:** An attacker injects instructions that cause the model to invoke an unauthorized tool.

**Control:** Tools are deny-by-default. The policy engine validates every tool invocation against the authorized tool list. Unknown tools are rejected before execution.

### AS-03: Sensitive Data to Unauthorized Provider

**Scenario:** A document classified RESTRICTED is routed to a public cloud provider due to misconfiguration or fallback logic.

**Control:** Data classification is enforced at the policy engine. RESTRICTED data cannot be routed to non-local providers. No silent fallback from local to cloud.

### AS-04: Excessive Agency — High-Risk Action Without Approval

**Scenario:** An agent schedules a payment or deletes records without human review.

**Control:** High-risk tools require human approval gates. The workflow suspends until approval is received. Every approval decision is audited.

### AS-05: Workflow Tampering

**Scenario:** An attacker modifies a workflow definition to add unauthorized steps or change routing.

**Control:** Workflow definitions are versioned and digest-protected. Changes produce audit events. Execution uses the versioned definition, not mutable state.

### AS-06: Model Substitution

**Scenario:** An attacker replaces an approved model file with a compromised version.

**Control:** Model registry stores expected checksums. Runtime verifies checksum before loading. Mismatch blocks execution and emits an audit event.

### AS-07: Audit Trail Tampering

**Scenario:** An attacker modifies or deletes audit events to hide malicious activity.

**Control:** Audit events are written to a durable local outbox synchronously with each policy decision. Storage is append-only. Events form a hash chain (each event references the hash of the previous event). Tampering is detectable. Export produces verifiable evidence packages.

### AS-08: Supply-Chain Compromise — Malicious Dependency

**Scenario:** A compromised library introduces a backdoor or exfiltrates data.

**Control:** SBOM documents all dependencies. Provenance links artifacts to source commits. Vulnerability scanning identifies known issues. Dependency changes between releases are auditable.

### AS-09a: Managed Egress Policy Bypass

**Scenario:** A managed provider or HTTP tool attempts to reach an unauthorized external destination.

**Control:** TramAI application-level egress policy denies the connection. Audit event records the attempt.

### AS-09b: Native/Subprocess Egress Bypass

**Scenario:** A native dependency, subprocess, or compromised model runtime opens a socket to an external destination.

**Control:** TramAI cannot prevent native code from opening sockets from the JVM. Infrastructure-level controls (firewall, Kubernetes NetworkPolicy, mandatory proxy, sandboxing) must enforce network isolation. Zero-external-egress deployment tests detect hidden traffic.

### AS-10: Token Passthrough via MCP

**Scenario:** An MCP client passes a token issued for one resource to access a different resource.

**Control:** MCP authorization validates resource audience. Token passthrough is rejected. Each MCP tool has scoped permissions.

### AS-11: Offline Claim Falsification

**Scenario:** A deployment marketed as "air-gap capable" makes hidden cloud calls at runtime.

**Control:** Automated zero-egress tests verify that no unauthorized external DNS, HTTP, or network requests occur during reference workflow execution. Loopback and explicitly approved internal endpoints (e.g., local Ollama, local vLLM) are allowed by profile.

### AS-12: Audit Event Suppression

**Scenario:** A policy decision (deny, approval) occurs but no audit event is generated.

**Control:** Audit emission is synchronous with policy decisions. If audit storage is unavailable, the system applies a configured fail mode per operation type:

| Operation Type | Fail Mode |
|----------------|-----------|
| Read-only queries (low risk) | FAIL_SAFE_READ_ONLY or configurable |
| Data-modifying tools | FAIL_CLOSED |
| Payments, deletions, disclosures | FAIL_CLOSED |
| Offline batch workflows | Durable local buffer with size limit |

Negative tests verify that every allow, deny, and approval produces an event, and that fail modes behave as configured.

### Audit Event Schema (v1)

Each event carries: `eventId`, `sequenceNumber`, `workflowRunId`, `correlationId`, `actor`, `policyVersion`, `workflowDigest`, `previousEventHash`, `eventHash`, `timestamp`, `decision`, `reasonCode`.

The hash chain makes event removal or modification detectable. WORM storage or an external integrity sink further strengthens the model.

---

## 6. Security Controls Mapping

| Control | Phase | Abuse Scenarios Addressed |
|---------|-------|--------------------------|
| Policy SPI (deny-by-default) | 1 | AS-02, AS-03, AS-04 |
| Data classification enforcement | 1 | AS-01, AS-03 |
| Provider routing by classification | 1 | AS-03 |
| Human approval gates | 1 | AS-04 |
| Audit event emission | 1 | AS-07, AS-12 |
| Model registry + checksum verification | 2 | AS-06 |
| Zero-egress verification | 2 | AS-11 |
| SBOM + provenance | 3 | AS-08 |
| Artifact signing | 3 | AS-08 |
| Workflow versioning + digest | 4 | AS-05 |
| Audit query + incident replay | 4 | AS-07 |
| MCP authorization + audience validation | 5 | AS-10 |
| External penetration test | 6 | All |

---

## 7. Residual Risk

| Risk | Residual Level | Rationale |
|------|---------------|-----------|
| Zero-day in model runtime (Ollama/vLLM) | Medium | Outside TramAI's control. Mitigated by model registry and checksum verification for TramAI-managed artifacts. |
| Novel prompt injection technique | Medium | Structured output validation is a structural guard, not a confidentiality one. Layered controls (context minimization, DLP, field-level policy) reduce but don't eliminate risk. |
| Physical access to air-gapped hardware | Low | Outside software scope. Documented in operational runbook. |
| Compromised build infrastructure | Medium | SLSA Build L1 targeted for Sovereign Preview v0.5, Build L2 for Enterprise. CycloneDX SBOM per release. Not yet "Low" — requires build platform hardening. |
| Policy misconfiguration | Medium | Deny-by-default reduces blast radius. Policy audit is a governance concern. |
| Remote model endpoint verification | Medium | For Ollama/vLLM endpoints, TramAI cannot cryptographically verify loaded weights. Relies on endpoint identity, TLS, and declared metadata. Full checksum verification is only possible for TramAI-managed bundles. |
| Native/subprocess egress bypass | Medium | A JVM library cannot prevent native code or subprocesses from opening sockets. Requires infrastructure-level network isolation for strong guarantees. |

---

*Defined June 2026. Aligned with NIST AI RMF Govern/Map/Measure/Manage functions and OWASP guidance for LLM and agentic applications.*
