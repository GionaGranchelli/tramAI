# TramAI v1.0 Enterprise Roadmap: The Digital Sovereignty Milestone

This roadmap defines the trajectory for TramAI's next major milestone — transforming from a typed AI integration library into a sovereign, air-gap-capable digital infrastructure framework for European public institutions, regulated enterprises, and the open-source commons.

---

## Phase 1: The Sovereign Integration Layer (Months 1–2)

**Goal:** Eliminate provider lock-in by making local European LLMs a first-class, zero-rewrite target.

**Actions:**
- Build native adapters for local execution engines (Ollama, vLLM) via the `ModelProvider` SPI
- First-class support for leading open-weight European models (Mistral)
- Provider swap requires zero business logic changes — change one config line, keep every `@AiService` interface identical
- Test suite using `SimulatedFailureProvider` to prove identical orchestration behavior across local and cloud providers

**Deliverable:** `tramai-sovereign` module published to Maven Central. An `@AiService` that calls Mistral via Ollama in one test and OpenAI in another, with identical `TramaiAssertions` verification.

---

## Phase 2: The Air-Gapped Reference Architecture (Months 3–4)

**Goal:** Prove TramAI operates in fully offline, government-grade infrastructure.

**Actions:**
- Provision enterprise-grade European bare-metal GPU instances (Scaleway H100 or L40S)
- Deploy TramAI in an air-gapped environment — no internet connectivity, no external API calls
- Run full test suite + `tramai-sovereign` tests to prove functional parity with cloud providers
- Measure and publish latency, throughput, and structured output accuracy benchmarks

**Deliverable:** Reproducible deployment blueprint (Terraform/container configurations) demonstrating local-only operation. Performance benchmarks comparing local Mistral inference against cloud-based alternatives.

---

## Phase 3: Enterprise Hardening & Sovereign Ecosystem Integration (Months 5–6)

**Goal:** Harden every failure boundary and integrate TramAI into the broader European digital commons ecosystem.

**Actions:**
- Aggressive stress-testing: worker pool resilience under concurrent load, lease conflict resolution, checkpoint store contention
- Adversarial injection-attack validation against `JacksonStructuredOutputHandler` — prompt injection, JSON boundary evasion, annotation-driven constraint bypass attempts
- Edge-case provider failure recovery: network partitions, partial responses, streaming truncation, malformed HTTP responses
- Stabilize the experimental MCP (Model Context Protocol) adapter for ecosystem connectivity

**Deliverables:**
1. **TramAI v1.0 Enterprise release** — core + `tramai-sovereign` modules published to Maven Central
2. **Public security validation report** — injection-attack surface documentation, mitigation strategies, residual risk assessment
3. **Sovereign Ecosystem Runbook** — a turnkey integration guide demonstrating how TramAI securely orchestrates other open-source digital commons using MCP. Features a tested workflow connecting a local Mistral LLM to a privacy-first search engine (e.g., SearXNG) entirely offline, positioning TramAI as the secure routing layer for the broader European intelligence ecosystem.

---

*Roadmap adopted May 2026. See [README.md](README.md) for current stable release.*
