# TramAI

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

**TramAI** is a structured-first AI integration library for the JVM.

---

## 🧵 The Name

**TramAI** is an Italian word (*Tramai*). It means **I wove**.

In Italian, *trama* is the weft — the horizontal thread that passes through the vertical threads of a loom to create fabric. Without the *trama*, you have parallel threads that never touch. With it, you have structure.

*Tramai* is the past tense, first person. *I wove*. The developer speaking. The act already completed. The name carries two readings simultaneously, and both are intentional.

---

## 🏁 Quick Start

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a structured status",
        model = "claude-3-5-sonnet-20240620"
    )
    suspend fun analyze(invoiceText: String): InvoiceStatus
}

data class InvoiceStatus(val status: String, val amount: Double?)

// Initialize the engine
val tramai = Tramai {
    provider(AnthropicProvider(apiKey = "your-key"), name = "anthropic")
    model("claude-3-5-sonnet-20240620", "anthropic")
}

// Create and use your service
val analyzer = tramai.create<InvoiceAnalyzer>()
val result = analyzer.analyze("Vendor: ACME, Total: $150.00")
```

---

## 🧠 The Philosophy

Most AI integration libraries ask you to learn a new programming model. Chains. Agents. Pipelines. Prompt templates with their own syntax. Memory stores with their own lifecycle. You stop writing your service and start writing framework code.

**TramAI starts from a different premise.**

You already know how to define a typed interface. You already know how to inject a dependency. You already know how to write a unit test. TramAI does not replace those skills — it weaves AI capability into the fabric of code you already know how to write.

It is the programming model you already have, extended by one annotation. The weft passing through existing threads.

---

## 🏗️ What TramAI Is

TramAI is built on four core convictions:

1.  **Typed contracts over prompt-heavy application code.** The model's output should map to a type your compiler knows about. Parse failures are not exceptions to handle in application code — they are a runtime concern that TramAI resolves (via retries and feedback) before your method returns.
2.  **Explicit over implicit.** Provider routing is declared. Tool access is declared per operation. Orchestration is an optional module for coordinated tasks, not a black-box autonomous reasoning loop.
3.  **Observability is not optional.** AI calls are the most expensive, most variable, and most failure-prone operations in any system. TramAI instruments every operation with OpenTelemetry semantic conventions from the first release, not as an afterthought.
4.  **The core runtime must be testable without a network.** `tramai-testing` is not a utility module. It is a first-class module that ships with the runtime, because AI-dependent code that cannot be tested deterministically is not production code.

---

## 🧱 What TramAI is NOT

*   **TramAI is not a chain framework.** It does not have a pipeline abstraction or "chain" objects that hide your logic.
*   **TramAI is not an autonomous agent framework.** It does not own an open-ended reasoning loop or autonomous swarms. Orchestration in TramAI is explicit, bounded, and deterministic.
*   **TramAI is not a RAG toolkit.** It does not manage embeddings or vector stores.

Those are composable concerns that belong in application code or in dedicated libraries. TramAI is the thread that connects your typed interfaces to AI providers — nothing more, and nothing less. The boundary is intentional. **The weft does not try to become the loom.**

---

## 🚀 Key Features

*   **Typed Interface Mapping**: Turn annotated interfaces into AI-backed proxies.
*   **Structured-First**: Native support for JSON schema generation, extraction, and validation.
*   **Explicit Orchestration**: Coordinated multi-step workflows (plan-execute-review) with typed state and checkpoint/resume support.
*   **Production Resilience**: Built-in Circuit Breakers, Exponential Backoff, and Fallback Routing.
*   **Security & Governance**: Pluggable redaction hooks (PII masking), secret-store integration, and token-usage budgets.
*   **Native-Image Ready**: Optimized for GraalVM Native Image with pre-generated metadata.

---

## 📦 Installation & Modules

### Dependency Management (Gradle)

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.1.0-SNAPSHOT"))
implementation("dev.tramai:tramai-standalone")
implementation("dev.tramai:tramai-openai") 
```

| Module | Description |
| :--- | :--- |
| `tramai-core` | Core annotations, models, and SPIs. |
| `tramai-engine` | The runtime execution engine and resilience logic. |
| `tramai-standalone` | Minimal builder for non-Spring environments. |
| `tramai-spring` | Spring Boot Starters and Auto-configuration. |
| `tramai-observability` | OpenTelemetry Tracing and Metrics. |
| `tramai-orchestration` | Experimental typed workflow coordination with checkpoint/resume support. |
| `tramai-testing` | Mock providers and deterministic assertion support. |

---

## 📖 Documentation

*   [Getting Started Guide](docs/guides/getting-started.md)
*   [Spring Boot Integration](docs/guides/spring-boot.md)
*   [Streaming](docs/guides/streaming.md)
*   [Tool Calling](docs/guides/tool-calling.md)
*   [Production Hardening & Security](docs/guides/production-hardening.md)
*   [Structured Output Deep-Dive](docs/guides/structured-output.md)
*   [Native Image](docs/guides/native-image.md)
*   [Orchestration](docs/guides/orchestration.md)
*   [Observability & Monitoring](docs/guides/observability.md)

---

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## 📄 License

TramAI is released under the [Apache License 2.0](LICENSE).
