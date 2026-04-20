# TramAI

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

**TramAI** is a structured-first AI integration library for the JVM. 

Built for backend engineers who want to add AI to Kotlin or Java services without adopting complex "agent" or "chain" frameworks, TramAI treats LLMs as typed, observable, and resilient components of your existing architecture.

---

## 🚀 Key Features

*   **Typed Interface Mapping**: Turn annotated interfaces (`@AiService`) into AI-backed proxies.
*   **Structured-First**: Native support for JSON schema generation, extraction, and validation with automatic retry-on-failure.
*   **Production-Ready Resilience**: Built-in Circuit Breakers, Exponential Backoff, and Fallback Routing.
*   **Deep Observability**: First-class OpenTelemetry integration for tracing and token-usage metrics.
*   **Security & Governance**: Pluggable redaction hooks (PII masking), secret-store integration, and token-usage budgets.
*   **Framework Agnostic**: Lightweight core with first-class Spring Boot autoconfiguration.
*   **Native-Image Ready**: Optimized for GraalVM Native Image with pre-generated proxy metadata.

---

## 🧠 Why TramAI?

Most AI libraries try to wrap your entire application logic in new abstractions. **TramAI stays at the boundary.** 

It focuses on what matters for production JVM services:
1.  **Strict Typing**: AI responses should be objects, not strings.
2.  **Explicit Control**: You decide exactly how prompts are built and which models are used.
3.  **Reliability**: LLMs are unreliable. TramAI provides the resilience patterns (Retries, Circuit Breakers) to handle their failure modes gracefully.
4.  **No Magic**: No hidden prompts, no complex agents—just clean code.

---

## 📦 Installation

TramAI is currently in **Alpha (moving toward 0.1.0 MVP)**. You can build and install it to your local Maven repository:

```bash
git clone https://github.com/GionaGranchelli/tramAI.git
cd tramAI
./gradlew publishToMavenLocal
```

### Dependency Management (Gradle)

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.1.0-SNAPSHOT"))
implementation("dev.tramai:tramai-standalone")
implementation("dev.tramai:tramai-openai") // or trami-anthropic, trami-ollama
```

---

## 🏁 Quick Start

### 1. Define your Service

```kotlin
@AiService
interface CustomerSupport {
    @Operation(
        prompt = "Classify this support request and extract the priority.",
        model = "gpt-4o"
    )
    suspend fun triage(requestText: String): TriageResult
}

data class TriageResult(
    val category: String,
    val priority: Int,
    val summary: String
)
```

### 2. Usage (Standalone)

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(apiKey = "your-key"), name = "openai")
    model("gpt-4o", "openai")
}

val support = tramai.create<CustomerSupport>()
val result = support.triage("My order #123 never arrived!")
```

### 3. Usage (Spring Boot)

Simply add the starter and configure your `application.yaml`:

```yaml
tramai:
  providers:
    openai:
      api-key: "env:OPENAI_API_KEY"
  models:
    gpt-4o: openai
```

Then inject your service anywhere:

```kotlin
@Service
class TriageService(private val support: CustomerSupport) {
    suspend fun process(text: String) = support.triage(text)
}
```

---

## 🛡️ Production Hardening

### Resilience & Cost Control
Configure budgets and circuit breakers globally or per-operation:

```kotlin
val tramai = Tramai {
    tokenBudget {
        hardMaxTokensPerOperation = 10_000
    }
    circuitBreaker {
        failureThreshold = 3
        openDurationMillis = 60_000
    }
}
```

### Security Hooks (PII Masking)
Protect sensitive data before it reaches the provider:

```kotlin
val tramai = Tramai {
    interceptor(object : OperationInterceptor {
        override fun interceptRequest(context: OperationCallContext, messages: List<Message>): List<Message> {
            return messages.map { it.copy(content = maskEmails(it.content)) }
        }
    })
}
```

---

## 📂 Module Overview

| Module | Description |
| :--- | :--- |
| `tramai-core` | Core annotations, models, and SPIs. |
| `tramai-engine` | The runtime execution engine and resilience logic. |
| `tramai-structured` | JSON Schema generation and extraction (Jackson-based). |
| `tramai-standalone` | Minimal builder for non-Spring environments. |
| `tramai-spring` | Spring Boot Starters and Auto-configuration. |
| `tramai-openai` | OpenAI & OpenAI-Compatible (vLLM, Groq) providers. |
| `tramai-anthropic` | Anthropic Claude provider. |
| `tramai-ollama` | Local model support via Ollama. |
| `tramai-observability` | OpenTelemetry Tracing and Metrics. |
| `tramai-testing` | Mock providers and deterministic assertion support. |

---

## 📖 Documentation

*   [Getting Started Guide](docs/guides/getting-started.md)
*   [Spring Boot Integration](docs/guides/spring-boot.md)
*   [Structured Output Deep-Dive](docs/guides/structured-output.md)
*   [Observability & Monitoring](docs/guides/observability.md)
*   [ADR Index (Architecture Decisions)](docs/adr/README.md)

---

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct and the process for submitting pull requests.

## 📄 License

TramAI is released under the [Apache License 2.0](LICENSE).
