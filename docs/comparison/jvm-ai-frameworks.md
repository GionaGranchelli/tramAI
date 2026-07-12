# Choosing a JVM AI Stack: TramAI, Spring AI, and LangChain4j

> **Dated comparison — July 12, 2026.**
> This document is a dated architectural comparison, not an evergreen benchmark or a declaration that one framework is universally better.
>
> It compares **Spring AI 2.0.0**, **LangChain4j 1.17.2**, and the current **TramAI** repository state as reviewed on July 12, 2026.
>
> **Canonical product boundary:** [TramAI Product Positioning](../product/positioning.md).
> **Technical maturity:** [Project Status](../STATUS.md).

---

## Scope and Method

This comparison:

- Uses official documentation and repositories for each project.
- Compares documented and implemented capabilities.
- Distinguishes stable, experimental, evolving, and not-implemented.
- Does not infer absence from missing marketing copy.
- Does not compare popularity, community size, performance, or commercial support.
- Is a **dated snapshot** — upstream versions will change.
- Must be re-reviewed when upstream major or minor versions change.

**Terminology used in this document:**

| Term | Meaning |
|---|---|
| Documented first-class capability | Explicitly listed in official docs as a designed feature |
| Extension point / application composition | Possible through framework extension mechanisms |
| Experimental | Marked as experimental / preview by the project itself |
| Implemented / evolving | Implemented but may lack stable API guarantees |
| Not identified in the official sources reviewed | Not found in the documentation surveyed |
| Not implemented | Acknowledged gap the project documents |

---

## Version and Source Snapshot

| Project | Compared boundary | Maturity note |
|---|---|---|
| Spring AI | 2.0.0 stable docs | Broad Spring-native AI integration project |
| LangChain4j | 1.17.2 stable release | Core stable; guardrails and agentic module explicitly experimental |
| TramAI | 0.4.0 published core plus master governance boundary | Sovereign runtime RC+/enterprise proof; no stable 1.0 API |

### Official sources reviewed

**Spring AI:**
- [Project overview](https://docs.spring.io/spring-ai/reference/)
- [Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Structured output](https://docs.spring.io/spring-ai/reference/api/structured-output.html)
- [Tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Observability](https://docs.spring.io/spring-ai/reference/api/observability.html)
- [MCP](https://docs.spring.io/spring-ai/reference/api/mcp.html)
- [MCP security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)
- [Building Effective Agents](https://docs.spring.io/spring-ai/reference/api/agents.html)

**LangChain4j:**
- [Project overview](https://docs.langchain4j.dev/)
- [AI Services](https://docs.langchain4j.dev/tutorials/ai-services)
- [Structured outputs](https://docs.langchain4j.dev/tutorials/structured-outputs)
- [Tools](https://docs.langchain4j.dev/tutorials/tools)
- [Guardrails](https://docs.langchain4j.dev/tutorials/guardrails)
- [Agents and Agentic AI](https://docs.langchain4j.dev/tutorials/agents)
- [MCP](https://docs.langchain4j.dev/tutorials/mcp)
- [RAG](https://docs.langchain4j.dev/tutorials/rag)

**TramAI (local):**
- [Product Positioning](../product/positioning.md)
- [Project Status](../STATUS.md)
- [Tool Permission Model](../security/tool-permission-model.md)
- [Approval Workflow Documentation](../guides/approval-workflow-ergonomics.md)
- [Runtime Evidence Export Model](../evidence/runtime-evidence-export-model.md)
- [MCP Governance Boundary](../security/mcp-governance-boundary.md)
- [Example Selection Guide](../../examples/README.md)

---

## What the Three Projects Optimize For

### Spring AI

Spring AI is a broad Spring-native AI integration framework focused on connecting enterprise data and APIs with models through portable model interfaces, `ChatClient`, Advisors, tools, RAG, memory, observability, and MCP.

It describes its central challenge as *connecting enterprise data and APIs to AI models* and provides broad portable abstractions and Spring Boot integration.

### LangChain4j

LangChain4j is an idiomatic Java toolkit for LLM applications, offering unified provider and vector-store APIs, typed AI Services, tools, RAG, guardrails, MCP, and increasingly capable agentic workflow abstractions.

It presents itself as a Java-native framework for LLM integration with a broad toolbox spanning tools, agents, RAG, model providers, and vector stores.

### TramAI

TramAI is a Kotlin-first JVM runtime for governed AI workflows, combining typed AI contracts with runtime policy, human approval, controlled model routing, and verifiable execution evidence.

**Category distinction:**

| Project | Primary category |
|---|---|
| Spring AI | Broad Spring AI integration framework |
| LangChain4j | Broad Java LLM application toolkit |
| TramAI | Narrower governed workflow runtime |

---

## Shared Capabilities

Typed outputs, tools, interception, local models, RAG, observability, and workflow composition are **not unique to TramAI**.

| Shared capability | Spring AI | LangChain4j | TramAI |
|---|---|---|---|
| Model-provider abstraction | Documented | Documented | Implemented |
| Structured output | Documented | Documented | Implemented |
| Tool calling | Documented | Documented | Implemented |
| RAG / vector integration | Extensive | Extensive | Implemented / evolving |
| Memory | Documented | Documented | Implemented / evolving |
| Observability hooks | Documented | Documented | Implemented |
| Spring integration | Native focus | Supported integration | Supported |
| Local models | Supported | Supported | Supported |

---

## Capability Comparison

| Dimension | Spring AI 2.0.0 | LangChain4j 1.17.2 | TramAI |
|---|---|---|---|
| **Primary product category** | Spring-native AI integration | Java LLM application toolkit | Governed AI workflow runtime |
| **Typed output contracts** | Structured POJO mapping | AI Service typed returns and structured outputs | Typed services plus schema validation, retry, and repair contracts |
| **Request interception** | Advisors can inspect, alter, block, or fail calls | Input/output guardrails can reject, rewrite, retry, or reprompt | Policy enforcement points with explicit ALLOW/DENY/REQUIRE_APPROVAL |
| **Tool execution control** | Framework-, advisor-, and user-controlled execution; replaceable `ToolCallingManager` | Low/high-level execution, handlers, immediate return, compensation | Exposure and execution policy evaluated at `BEFORE_TOOL_EXPOSURE` and `BEFORE_TOOL_EXECUTION` |
| **Human involvement** | Application-defined composition | Experimental `HumanInTheLoop` and `PendingResponse` | Durable approval records, approve/deny lifecycle, suspension and replay-safe resume |
| **Durable workflow recovery** | Supplied through application / Spring architecture | Experimental persistent `AgenticScope` and planner checkpoint recovery | Workflow checkpoints plus durable approval/continuation stores and outbox workers |
| **Model selection** | Multiple models and application-defined routing | Experimental dynamic model selection | Classification-based configured trust-zone routing (LOCAL, EU_CLOUD, GLOBAL_CLOUD) |
| **DLP / redaction** | Can be implemented through Advisors or application components | Can be implemented through guardrails or application components | Dedicated DLP/redaction capability in governance runtime |
| **Observability** | Micrometer observations and tracing | Listeners, monitoring, and observability integrations | OTel plus audit outbox, worker metrics, and evidence export |
| **Governance evidence** | Operational observations and application-defined records | Invocation/tool records and application-defined evidence | Tamper-evident audit sequencing and runtime-evidence export model |
| **MCP client** | Implemented | Implemented | Not implemented (governed remote MCP client) |
| **MCP server** | Implemented | Community server implementation | Workflow server implemented |
| **Provider / vector breadth** | Major strength | Major strength | Available but not primary differentiation |
| **Release maturity** | Stable 2.0.0 | Stable 1.17.2; guardrails and agentic module experimental | 0.4.0 core; governance on master under RC+/evolving boundary |

**Important qualification — Spring AI:**

Spring AI provides Advisors and replaceable tool-execution components that applications can use to implement controls. TramAI differs by shipping an explicit governance decision model, policy taxonomy, approval lifecycle, trust-zone routing, and evidence semantics as a coordinated runtime boundary.

Spring AI also avoids exporting prompts, completions, tool arguments, and tool results by default because they may contain sensitive data. Its MCP security documentation is explicitly described as work in progress.

**Important qualification — LangChain4j:**

LangChain4j's experimental agentic module now includes `HumanInTheLoop`, `PendingResponse`, persistent `AgenticScope` checkpoints, and restart recovery. TramAI differs in the surrounding governed operation lifecycle: typed approval outcomes, durable approval stores, continuation binding, idempotency proofs, audit/outbox integration, and governance evidence.

LangChain4j guardrails are experimental (both input and output). The agentic module is experimental.

---

## Choose Spring AI When

- The application is already deeply Spring-based.
- Broad Spring Boot auto-configuration is the main requirement.
- The team needs extensive model and vector-store integrations.
- `ChatClient`, Advisors, Micrometer observability, and MCP integration are central.
- Application-specific policy composition through Advisors is acceptable.
- The wider Spring ecosystem will provide security, persistence, transactions, and orchestration.

---

## Choose LangChain4j When

- The team wants an idiomatic Java AI toolkit.
- Framework portability across Spring Boot, Quarkus, Helidon, or Micronaut matters.
- Agents, RAG, AI Services, and broad provider coverage are central.
- The project benefits from experimental agentic workflows (`HumanInTheLoop`, `AgenticScope`).
- MCP client integration is needed today.
- Tool compensation and flexible tool execution are relevant.

---

## Choose TramAI When

- Policy outcomes must be explicit runtime objects (ALLOW, DENY, REQUIRE_APPROVAL).
- Tools must be evaluated at defined exposure and execution points.
- High-risk operations require durable approval records with replay-safe continuation.
- Workflow suspension and recovery must be tied to stored continuation state.
- Model routing must obey classification and trust-zone configuration.
- DLP and redaction belong in the runtime path, not in application interceptors.
- Approval, routing, and policy decisions need consistent audit and evidence models.
- Deployment must support local, offline, or restricted environments.
- The team accepts a smaller project in active development with unreleased governance APIs.

TramAI is most differentiated when policy, approval, routing, recovery, and evidence must behave as one governed execution lifecycle rather than a collection of application-specific interceptors and callbacks.

---

## Where TramAI Is Weaker Today

- Smaller provider and vector-store ecosystem.
- Smaller community and adoption footprint.
- No stable sovereign 1.0 API — governance capabilities remain on master under RC+/evolving boundary.
- Governance modules not yet published as a stable tagged release.
- No governed remote MCP client — MCP workflow server is implemented but governed client is not.
- No production-grade IAM or reviewer control plane.
- Less mature public documentation and ecosystem integration.
- Not a drop-in replacement for Spring AI or LangChain4j.
- No official Spring AI or LangChain4j adapter.

---

## Coexistence and Migration

### Pattern A — TramAI independently

```
Application → TramAI typed service/workflow → TramAI provider adapter
```

This is the supported direct path for greenfield governed workflows.

### Pattern B — Application-level coexistence

```
Spring AI or LangChain4j service → application-owned boundary → TramAI governed workflow step
```

This is **architectural composition, not a shipped adapter**. The two runtimes operate independently; TramAI governs a specific workflow while Spring AI or LangChain4j handles wider model integration.

### Pattern C — Gradual governance adoption

```
Existing model integration → identify high-risk operation →
move that workflow into TramAI → add policy → add approval →
add routing → add evidence
```

TramAI is **not a drop-in replacement** for Spring AI or LangChain4j. No official interoperability adapter is currently provided.

---

## Limitations and Non-Claims

- This comparison is a dated snapshot — upstream features may have changed.
- Absence from reviewed documentation does not prove impossibility. All three frameworks are extensible.
- Custom application code can close many capability gaps.
- The table compares documented first-class behavior, not everything theoretically implementable.
- No framework makes an organization compliant.
- No security certification is implied.
- No universal production-readiness claim is made.
- No performance or quality ranking is provided.
- No legal interpretation is provided.

---

## Source Notes

- Spring AI 2.0.0 documentation reviewed July 12, 2026.
- LangChain4j 1.17.2 [documentation](https://docs.langchain4j.dev) and [repository](https://github.com/langchain4j/langchain4j) reviewed July 12, 2026.
- TramAI repository and documentation reviewed July 12, 2026.
- Spring AI MCP security documentation explicitly states it is **work in progress**.
- LangChain4j guardrails and agentic modules are explicitly marked **experimental** in their documentation.
- LangChain4j compensation refers to the `compensating` tool-execution strategy that reverses a failed tool's prior execution.
