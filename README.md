# TramAI

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

**TramAI** is a structured-first AI integration library for the JVM.

---

## Start Here

If you are evaluating TramAI for the first time, do not start by reading the whole repository.

Choose one path:

- I want the fastest copy-paste setup:
  [30-Minute Quickstart](docs/guides/quickstart.md)
- I use Maven:
  [Maven Setup](docs/guides/maven.md)
- I use Gradle and want the dependency rules first:
  [Getting Started](docs/guides/getting-started.md)
- I have a plain JVM app:
  [Standalone Usage](docs/guides/standalone-usage.md)
- I have a Spring Boot app:
  [Spring Boot Integration](docs/guides/spring-boot.md)

Use this minimum-default rule:

- non-Spring app: `tramai-standalone` + one provider
- Spring Boot app: `tramai-spring` + one provider
- add `tramai-observability` only if you want OpenTelemetry
- add `tramai-orchestration` only if you want typed persisted workflows

Do not start from `tramai-core` unless you are extending TramAI itself.

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
        model = "gpt-4o"
    )
    suspend fun analyze(invoiceText: String): InvoiceStatus
}

data class InvoiceStatus(val status: String, val amount: Double?)

// Initialize the engine
val tramai = Tramai {
    provider(OpenAiProvider(apiKey = "your-key"), name = "openai")
    model("gpt-4o", "openai")
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

TramAI `0.1.x` targets Java `25+`.

### Start Here

Most developers do not want every module. They want the smallest correct setup for their application style.

Use this rule:

- plain JVM application: `tramai-standalone`
- Spring Boot application: `tramai-spring`
- then add one provider module such as `tramai-openai`, `tramai-anthropic`, or `tramai-ollama`
- add `tramai-observability` only if you want OpenTelemetry integration
- add `tramai-orchestration` only if you want persisted multi-step workflows

### Gradle

Use the BOM to keep all TramAI modules on the same version:

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.1.0"))
    implementation("dev.tramai:tramai-standalone")
    implementation("dev.tramai:tramai-openai")
}
```

### Maven

Import the BOM once:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tramai</groupId>
      <artifactId>tramai-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Then add the modules you want:

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-standalone</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-openai</artifactId>
  </dependency>
</dependencies>
```

### Common Setups

Standalone + OpenAI:

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.1.0"))
implementation("dev.tramai:tramai-standalone")
implementation("dev.tramai:tramai-openai")
```

Spring Boot + OpenAI:

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.1.0"))
implementation("dev.tramai:tramai-spring")
implementation("dev.tramai:tramai-openai")
```

Standalone + Ollama:

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.1.0"))
implementation("dev.tramai:tramai-standalone")
implementation("dev.tramai:tramai-ollama")
```

Spring Boot + Anthropic:

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.1.0"))
implementation("dev.tramai:tramai-spring")
implementation("dev.tramai:tramai-anthropic")
```

| Module | Description |
| :--- | :--- |
| `tramai-core` | Core annotations, models, and SPIs. |
| `tramai-engine` | The runtime execution engine and resilience logic. |
| `tramai-standalone` | Minimal builder for non-Spring environments. |
| `tramai-spring` | Spring Boot Starters and Auto-configuration. |
| `tramai-observability` | OpenTelemetry Tracing and Metrics. |
| `tramai-orchestration` | Typed workflow coordination with checkpoint/resume and optional lease-aware execution. |
| `tramai-testing` | Mock providers and deterministic assertion support. |

---

## 📖 Documentation

*   [Getting Started Guide](docs/guides/getting-started.md)
*   [30-Minute Quickstart](docs/guides/quickstart.md)
*   [Maven Setup](docs/guides/maven.md)
*   [Choosing Modules and Dependencies](docs/guides/getting-started.md#choose-your-dependencies)
*   [Spring Boot Integration](docs/guides/spring-boot.md)
*   [Standalone Usage](docs/guides/standalone-usage.md)
*   [Streaming](docs/guides/streaming.md)
*   [Tool Calling](docs/guides/tool-calling.md)
*   [Production Hardening & Security](docs/guides/production-hardening.md)
*   [Structured Output Deep-Dive](docs/guides/structured-output.md)
*   [Native Image](docs/guides/native-image.md)
*   [Orchestration](docs/guides/orchestration.md)
*   [Observability & Monitoring](docs/guides/observability.md)
*   [API Stability](docs/reference/api-stability.md)
*   [Release Validation](docs/reference/release-validation.md)

---

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## 📄 License

TramAI is released under the [Apache License 2.0](LICENSE).
