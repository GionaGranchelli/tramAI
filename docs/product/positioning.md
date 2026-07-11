# TramAI Product Positioning

> **Status:** canonical product-positioning document — supersedes any earlier product thesis.
> **Last updated:** July 2026.
> **Linked from:** [README](../../README.md), [CHANGELOG](../../CHANGELOG.md), [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Detailed technical status:** [docs/STATUS.md](../STATUS.md).

---

## Canonical Message

### Tagline

> **Governed AI workflows for the JVM.**

### One-Sentence Positioning

> TramAI is a Kotlin-first JVM runtime for governed AI workflows, combining typed AI contracts with runtime policy, human approval, sovereign routing, and verifiable execution evidence.

### Thirty-Second Description

TramAI helps JVM teams move AI governance out of prompts and scattered application glue and into the execution path. It makes model and tool policy, sensitive-data handling, human approval, replay-safe continuation, routing, and audit evidence explicit runtime concerns. It is designed for regulated, sensitive, and infrastructure-constrained environments and remains under active development.

---

## The Problem TramAI Solves

Calling an LLM is the easy part. Enterprise and regulated workloads also need:

1. **AI governance that is not spread across prompts, interceptors, controllers, and application-specific glue.** Without a dedicated runtime enforcement layer, organizations end up with inconsistent, unreviewable controls that vary per developer, per endpoint, and per deployment.

2. **Model and tool authorization that happens before side effects.** A tool that transfers money, deletes a record, or sends a notification must be denied by policy before it executes — not afterwards.

3. **Sensitive workloads that need classification-aware routing and redaction.** A document containing RESTRICTED data must stay on a local model, and PII must be redacted before it leaves the trust zone.

4. **High-risk execution that needs durable human approval and safe resume.** A payment, account closure, or legal disclosure must not execute until a human explicitly approves it, and the workflow must resume exactly once with the correct state after suspension.

5. **Denials, approvals, routing decisions, and operational recovery that need inspectable evidence.** When something goes wrong, an auditor or operator must be able to reconstruct the complete decision trail — what was allowed, what was denied, who approved what, and why.

6. **JVM teams that need these capabilities without adopting a hosted SaaS control plane.** The governance layer must be embeddable, composable, and work inside existing Spring Boot or standalone JVM applications.

---

## Product Category

**Primary category:** Governed AI workflow runtime
**Platform:** Kotlin-first JVM
**Key capability profile:** Sovereign/local-controlled execution
**Deployment model:** Embedded, standalone, or Spring Boot

TramAI is **not** primarily an "agent runtime." That term is broader, less precise, and can suggest unconstrained autonomous behaviour. Governance is the primary category; sovereignty is a key capability profile that supports it.

---

## Who TramAI Is For

| Audience | Primary need |
|----------|-------------|
| **Kotlin and Java application teams** | Add governed AI capabilities without abandoning normal JVM contracts. Typed interfaces, structured output, and deterministic testing remain first-class. |
| **Enterprise platform teams** | Standardize policy, routing, approval, evidence, and recovery across multiple AI workloads. |
| **Security and governance teams** | Enforce controls outside prompts. Inspect runtime evidence. Verify that sensitive data stayed in the allowed trust zone. |
| **Regulated or sensitive-domain teams** | Govern data, models, tools, and human decisions in finance, insurance, healthcare, legal, and infrastructure contexts. |
| **System integrators** | Embed a reusable governed runtime into customer-owned Spring Boot applications without a SaaS dependency. |
| **Restricted-infrastructure teams** | Operate with local models, controlled egress, and durable evidence in air-gapped, offline, or high-security networks. |

TramAI does not itself make an organization compliant. It provides a composable governance layer that supports compliance processes.

---

## Representative Use Cases

- Regulated claim or document triage with classification-aware routing.
- Internal knowledge workflows involving confidential data that must stay local.
- Human-approved reimbursement, disclosure, or state-changing operations.
- Local-model processing in restricted or air-gapped networks.
- Governed tool execution with durable audit evidence.
- System-integrator deployments inside customer-owned Spring Boot applications.

---

## Product Pillars

These pillars are grounded in capabilities currently described in the README and [STATUS.md](../STATUS.md).

### 1. Typed Contracts

Kotlin/JVM interfaces with typed inputs and outputs. Structured output, schema generation, validation, repair feedback, and deterministic testing — no raw prompt plumbing in application code.

### 2. Governance in the Execution Path

When governance components are configured, TramAI enforces model and tool policy in the runtime execution path outside prompts, before providers, tools, and responses proceed. Every policy decision (ALLOW, DENY, REQUIRE_APPROVAL) is explicit, auditable, and testable.

### 3. Human-Controlled Execution

Approval, suspension, denial, idempotency, and replay-safe continuation. High-risk operations are gated by human decisions that leave durable evidence.

### 4. Controlled Model Routing

Local, trusted, or approved provider zones selected based on workflow classification and data policy. Configured classification-aware routing can prevent RESTRICTED workloads from reaching unauthorized providers.

### 5. Evidence and Operational Recovery

Tamper-evident audit sequencing, safe runtime evidence export, JDBC and file persistence, outbox recovery, and worker observability. When an audit store and emitter are configured, policy and approval decisions can be emitted into tamper-evident audit and runtime-evidence pipelines.

### 6. Composable JVM Adoption

Standalone and Spring Boot entry points with optional governance and persistence modules. Teams adopt only the capabilities they need without a mandatory control plane.

---

## What TramAI Is Not

TramAI is explicitly **not**:

- a model-training framework;
- a model itself;
- a no-code agent builder;
- a hosted multi-tenant governance SaaS;
- a compliance certification product;
- a guarantee that every deployment is sovereign or air-gapped;
- a replacement for all AI integration libraries;
- a production-grade IAM or reviewer-control-plane product;
- a governed remote MCP client/connector today (an MCP workflow server exists; governed MCP tool import is not yet implemented);
- argument-aware tool authorization based on raw values such as `amount > 1000`;
- a stable 1.0 sovereign-runtime API.

---

## Current Maturity

| Boundary | Status |
|----------|--------|
| Typed AI services and structured output | Implemented / evolving |
| Policy, DLP, approval, resume, routing | Implemented on master / evolving |
| Audit, evidence, file and JDBC persistence | Implemented on master / evolving |
| Sovereign runtime | RC+ / enterprise proof milestone |
| Public sovereign release | Not yet tagged/published as a stable contract |
| Stable 1.0 API | Deferred |
| Production certification | Not claimed |
| Key rotation | Deferred |
| Enterprise IAM and production reviewer UI | Deferred |
| MCP workflow server | Implemented / evolving |
| Governed remote MCP tool connector | Not implemented |

For detailed status tracking, see [docs/STATUS.md](../STATUS.md).

---

## Claim Boundaries

The following claims are safe for the README, website, articles, talks, grants, and pilot discussions:

- ✅ TramAI supports runtime enforcement of model and tool policy when governance components are configured.
- ✅ TramAI supports human approval with replay-safe continuation.
- ✅ TramAI can route model calls based on workflow classification.
- ✅ TramAI can produce tamper-evident audit evidence when an audit store and emitter are configured.
- ✅ TramAI is embeddable in Spring Boot and standalone JVM applications.
- ✅ TramAI includes an MCP workflow server (stdio + SSE).

The following claims are **not** supported by the current codebase and **must not appear** in product messaging:

- ❌ claiming compliance or certification
- ❌ claiming production-readiness for all deployments
- ❌ claiming guaranteed sovereignty
- ❌ claiming air-gapped by default
- ❌ claiming amount-threshold authorization exists
- ❌ claiming remote MCP tools are governed
- ❌ claiming TramAI makes an organization compliant

---

## Messaging Guide

| Audience | Lead with | Avoid |
|----------|-----------|-------|
| JVM developers | Typed contracts, composable adoption, deterministic testing | Sovereignty jargon, certification claims |
| Enterprise architects | Standardized policy, routing, evidence, embeddable runtime | Agent hype, no-code framing |
| Security teams | Runtime enforcement, deny-by-default, audit evidence, classification-aware routing | Claims of automatic compliance |
| Regulated teams | Approval gates, data handling, evidence trail, local execution | "AI safety" without concrete mechanisms |
| System integrators | Embeddable, no SaaS dependency, composable modules | Platform lock-in language |
| Infrastructure teams | Local models, controlled egress, durable evidence, OCI bundles | "Cloud-first" framing |

---

## Source-of-Truth Documents

| Document | Purpose |
|----------|---------|
| [README](../../README.md) | Entry point and capability overview |
| [STATUS.md](../STATUS.md) | Detailed implementation and maturity tracking |
| [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md) | Current and planned development phases |
| [Security Model](../security/SECURITY-MODEL.md) | Threat model and abuse scenarios |
| [Tool Permission Model](../security/tool-permission-model.md) | Tool trust, risk, and permission taxonomy |
| [MCP Governance Boundary](../security/mcp-governance-boundary.md) | Future MCP connector governance design |
| [Runtime Evidence Export Model](../evidence/runtime-evidence-export-model.md) | Evidence record shape and export |
| [Runtime Evidence Bundle Map](../evidence/runtime-evidence-bundle-map.md) | Evidence-to-bundle mapping |
| [CHANGELOG](../../CHANGELOG.md) | Detailed change history |
| [Historical Enterprise Roadmap](../../ROADMAP.md) | Retained for existing links — superseded by Post-Sovereignty Roadmap |
