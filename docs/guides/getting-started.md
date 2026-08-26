# Getting Started with TramAI

This guide is the shortest path from zero to a working TramAI service.

If you are evaluating the library, read this page in order:

1. choose your dependency setup
2. copy one installation snippet
3. define one `@AiService`
4. wire one provider
5. make one call

## What TramAI Gives You

TramAI lets you write normal JVM interfaces and back them with model execution.

The core pattern is:

1. define an interface with `@AiService`
2. annotate methods with `@Operation`
3. map a model name to a provider
4. call the interface like normal application code

## Prerequisites

- Java `21+`
- Kotlin `2.3.0+` for Kotlin examples
- Gradle `9.0+` for the Gradle snippets below

## Choose Your Dependencies

Most first-time users get blocked here, so make the decision in this order.

### 1. Choose your runtime style

- use `tramai-standalone` for CLI apps, background workers, and non-Spring services
- use `tramai-spring-boot-starter` for Spring Boot applications

Do not start with `tramai-core` unless you are extending TramAI itself. It is a low-level module, not the normal entry point for application code.

### 2. Choose one provider

Add exactly the provider module you plan to call:

- `tramai-openai`
- `tramai-anthropic`
- `tramai-ollama`

For Spring Boot, add the matching Spring provider adapter instead (`tramai-spring-provider-openai`, `tramai-spring-provider-anthropic`, or `tramai-spring-provider-ollama`).

### 3. Add optional modules only when you need them

- add `tramai-observability` for OpenTelemetry integration
- add `tramai-orchestration` for typed persisted workflows
- add `tramai-testing` in tests for deterministic provider behavior

## Installation

Use the BOM so all TramAI modules stay on the same version.

### Gradle

Standalone + OpenAI:

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.5.0"))
    implementation("dev.tramai:tramai-standalone")
    implementation("dev.tramai:tramai-openai")
}
```

Spring Boot + OpenAI:

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.5.0"))
    implementation("dev.tramai:tramai-spring-boot-starter")
    implementation("dev.tramai:tramai-spring-provider-openai")
}
```

Standalone + Ollama:

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.5.0"))
    implementation("dev.tramai:tramai-standalone")
    implementation("dev.tramai:tramai-ollama")
}
```

### Maven

Import the BOM:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tramai</groupId>
      <artifactId>tramai-bom</artifactId>
      <version>0.5.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Standalone + OpenAI:

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

Spring Boot + OpenAI:

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-spring-boot-starter</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-spring-provider-openai</artifactId>
  </dependency>
</dependencies>
```

## Your First Service

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

### 2. Configure and Run

For a plain JVM application, use `tramai-standalone`:

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

For Spring Boot, the next guide is [Spring Boot Integration](./spring-boot.md).

## Module Cheat Sheet

Use this when you are not sure what to add:

| Goal | Modules |
| --- | --- |
| Plain JVM app | `tramai-standalone` + one provider |
| Spring Boot app | `tramai-spring-boot-starter` + one provider adapter |
| Structured output | already included in normal runtime paths |
| OTel observability | add `tramai-observability` |
| Workflow orchestration | add `tramai-orchestration` |
| Deterministic tests | add `tramai-testing` in test scope |

## Common Mistakes

- depending on `tramai-core` directly and expecting it to be the full runtime
- forgetting to add a provider module
- skipping the BOM and then mixing module versions manually
- adding every module "just in case" instead of starting with one runtime module and one provider

## Next Steps

Now that you have your first service running, explore the more advanced features of TramAI:

- **[Structured Output](./structured-output.md)** to return typed objects instead of raw text
- **[Spring Boot Integration](./spring-boot.md)** if your app is Spring-based
- **[Providers and Model Routing](./providers.md)** to configure OpenAI, Anthropic, or Ollama
- **[Production Hardening](./production-hardening.md)** for retries, budgets, and secret handling
- **[Observability](./observability.md)** for OpenTelemetry integration
