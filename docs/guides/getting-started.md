# Getting Started with TramAI

This guide will walk you through setting up **TramAI** and creating your first AI-backed service in minutes.

---

## 🏗️ What is TramAI?

TramAI allows you to define clean service boundaries using standard Kotlin or Java interfaces, then back those interfaces with LLM calls. It handles all the heavy lifting of prompt construction, model routing, structured parsing, and resilience.

### The Core Pattern

1.  **Define an Interface**: Use `@AiService` and `@Operation`.
2.  **Choose a Provider**: Map your models to providers like OpenAI, Anthropic, or Ollama.
3.  **Execute**: Call the interface methods as you would any other local service.

---

## 🛠️ Prerequisites

*   **Java 25+**: TramAI takes advantage of modern JVM features.
*   **Kotlin 2.1.0+**: Primary development language.
*   **Gradle 9.0+**: Build tool.

---

## 🚀 Installation

TramAI is currently in early-stage development and is best consumed by building from source or publishing to your local Maven repository.

```bash
git clone https://github.com/GionaGranchelli/tramAI.git
cd tramAI
./gradlew publishToMavenLocal
```

### Dependency Configuration (Gradle)

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.1.0-SNAPSHOT"))
implementation("dev.tramai:tramai-standalone")
implementation("dev.tramai:tramai-openai")
```

---

## 📝 Your First Service

### 1. Define the AI Service

```kotlin
@AiService
interface GreetingService {
    @Operation(
        prompt = "Greet the user warmly in one sentence based on their name.",
        model = "gpt-4o"
    )
    suspend fun greet(name: String): String
}
```

### 2. Configure and Run (Standalone)

```kotlin
import dev.tramai.standalone.Tramai
import dev.tramai.openai.OpenAiProvider

suspend fun main() {
    // 1. Build the TramAI engine
    val tramai = Tramai {
        provider(OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY")), name = "openai")
        model("gpt-4o", "openai")
    }

    // 2. Create the service proxy
    val greeter = tramai.create<GreetingService>()

    // 3. Call the AI!
    val message = greeter.greet("Giona")
    println(message)
}
```

---

## 🧩 Choosing Your Path

TramAI is designed to be modular. Depending on your needs, you might include different modules:

*   **Standalone**: For CLI apps, background workers, or non-Spring services.
    *   `tramai-standalone` + `tramai-openai`
*   **Spring Boot**: For modern web applications and microservices.
    *   `tramai-spring` + `tramai-anthropic`
*   **Local AI**: For offline or privacy-sensitive processing.
    *   `tramai-standalone` + `tramai-ollama`

---

## 🔍 Next Steps

Now that you have your first service running, explore the more advanced features of TramAI:

*   **[Structured Output](./structured-output.md)**: Learn how to extract typed data instead of raw strings.
*   **[Spring Boot Integration](./spring-boot.md)**: Deep-dive into Spring-native configuration.
*   **[Production Hardening](./production-hardening.md)**: Configure circuit breakers, budgets, and security hooks.
*   **[Observability](./observability.md)**: Track your AI performance with OpenTelemetry.
