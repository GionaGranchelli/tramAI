# PRODUCT-THESIS.md — TramAI Enterprise

## What TramAI Becomes

> TramAI is a sovereign agent runtime for JVM enterprises. It enables typed, observable, and policy-controlled AI workflows that can run across cloud, European sovereign infrastructure, and fully offline environments without changing application contracts.

TramAI started as a typed AI integration library. It is now evolving into infrastructure that lets organizations adopt AI agents while retaining **verifiable control** over data, models, actions, software dependencies, and infrastructure.

## Who This Is For

| Segment | Need | Why TramAI |
|---------|------|------------|
| **Regulated enterprises** (finance, insurance, healthcare) | AI agents that auditors can inspect | Policy-enforced execution, append-only audit trail, data classification enforcement |
| **European public institutions** | Sovereignty over data, models, and infrastructure | Local-first routing, EU-only provider policies, offline deployment |
| **Industrial organizations** | AI in air-gapped or restricted networks | OCI offline bundles, zero-egress verification, model allowlist |
| **System integrators** | Embeddable AI runtime for controlled environments | Embeddable in any Spring Boot app, no SaaS dependency required |

## The Problem

Enterprise adoption of AI agents is blocked by a specific set of risks:

1. **Where does data go?** — Sensitive documents sent to cloud models without classification controls.
2. **What can the agent do?** — Tools and subprocesses executing without explicit authorization.
3. **Who approved that?** — High-risk actions (payments, deletions, disclosures) without human gates.
4. **Can we prove what happened?** — No audit trail, no incident replay, no forensic evidence.
5. **What software are we actually running?** — Unverifiable artifacts, unknown dependencies, supply-chain blind spots.
6. **Does it work offline?** — "Air-gap" claims that collapse when the network cable is pulled.

Existing JVM frameworks primarily solve the AI integration problem. TramAI Enterprise focuses on an opinionated, verifiable control layer for policy-enforced execution, sovereign deployments, approval gates, audit evidence, and offline operational readiness. With TramAI, organizations don't need to design and assemble these controls internally.

## The Promise

TramAI Enterprise is designed to enforce and demonstrate:

- **Data stays where policy says it stays** — classification-driven routing prevents RESTRICTED data from reaching unauthorized providers.
- **Agents act within authorized boundaries** — tools, network destinations, and models are deny-by-default.
- **High-risk actions require human approval** — configurable gates per tool, per risk level, per amount threshold.
- **Every decision leaves evidence** — versioned, append-only audit events for every allow, deny, and approval.
- **Artifacts are verifiable** — SBOM, provenance, and signed releases traceable to source commits.
- **Offline means offline** — reproducible installation without internet, validated by automated zero-egress tests.

## Reference Workflow

**Sovereign Document Intelligence:** A workflow that receives a sensitive document, classifies its data, selects exclusively an approved local model, produces a typed output, requests human approval for any high-risk action, and records every step in an audit trail.

This single workflow demonstrates the entire value proposition in one concrete, testable scenario.

## Competitive Differentiation

| Capability | LangChain4j / Spring AI | TramAI Enterprise |
|------------|--------------------------|-------------------|
| Typed contracts | Partial or manual | Native — `@AiService` with typed return types |
| Tool authorization | Registration-based, no policy | Deny-by-default, risk-classified, approval-gated |
| Data classification | Not in scope | Built-in — PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED |
| Provider routing by classification | Not in scope | Automatic — local-only, EU-only, approved-cloud |
| Audit trail | Logging, not structured | Versioned AuditEvent, append-only, queryable |
| Offline deployment | Manual, unverified | OCI bundles, verification manifest, zero-egress tests |
| Human approval gates | Not in scope | Per-tool, per-risk-level, per-threshold |
| SBOM and provenance | Varies by build | CycloneDX per release, signed artifacts |
| Embeddable (no SaaS dependency) | Yes | Yes — policy enforcement works embedded or centralized |

## Non-Goals (v1.0)

These are explicitly out of scope until the sovereign runtime is validated:

- General-purpose skill marketplace (increases supply-chain attack surface)
- Full SaaS billing and multi-tenant platform
- Visual workflow editor (high cost, low initial value)
- Broad plugin ecosystem (security risk before policy enforcement is mature)
- Certifications (premature without clients and threat model)
- "European AI ecosystem" narrative without measurable criteria

## Success Definition

TramAI Enterprise v1.0 is successful when:

1. A regulated enterprise can run the reference workflow in an air-gapped environment.
2. Every in-scope abuse scenario is blocked, detected, or delegated to an explicitly documented infrastructure control.
3. An auditor can reconstruct the complete decision trail for any workflow execution. Exact replay is supported when the configured retention policy preserves the required payloads.
4. At least one pilot customer has run a real workload.
5. Every release ships with SBOM, provenance, and signed artifacts.
