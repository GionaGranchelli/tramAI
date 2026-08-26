# 30-Minute Quickstart

This guide is for developers who want a working TramAI project quickly and do not want to assemble the docs mentally.

It gives you two paths:

- plain JVM application
- Spring Boot application

Choose one and copy it first. Optimize later.

## Path A: Plain JVM Application

### 1. Add dependencies

Gradle:

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.5.0"))
    implementation("dev.tramai:tramai-standalone")
    implementation("dev.tramai:tramai-openai")
}
```

Maven:

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

### 2. Define one service

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation

@AiService
interface GreetingService {
    @Operation(
        prompt = "Greet the user warmly in one sentence based on their name.",
        model = "gpt-4o",
    )
    suspend fun greet(name: String): String
}
```

### 3. Configure one provider

```kotlin
import dev.tramai.openai.OpenAiProvider
import dev.tramai.standalone.Tramai

suspend fun main() {
    val tramai = Tramai {
        provider(
            OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY")),
            name = "openai",
        )
        model("gpt-4o", "openai")
    }

    val greetingService = tramai.create<GreetingService>()
    println(greetingService.greet("Ada"))
}
```

### 4. Run it

Set `OPENAI_API_KEY` and run the program normally.

## Path B: Spring Boot Application

### 1. Add dependencies

Gradle:

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.5.0"))
    implementation("dev.tramai:tramai-spring-boot-starter")
    implementation("dev.tramai:tramai-spring-provider-openai")
}
```

Maven:

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

### 2. Define one AI service and one normal Spring service

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import org.springframework.stereotype.Service

@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a short status.",
        model = "gpt-4o",
    )
    suspend fun analyze(invoiceText: String): String
}

@Service
class BillingService(
    private val invoiceAnalyzer: InvoiceAnalyzer,
) {
    suspend fun process(invoiceText: String): String = invoiceAnalyzer.analyze(invoiceText)
}
```

### 3. Configure `application.yml`

```yaml
tramai:
  default-provider: openai
  models:
    gpt-4o: openai
  providers:
    openai:
      api-key: ***
      base-url: https://api.openai.com/v1
```

### 4. Inject and use it

TramAI will register the `@AiService` interface as a Spring bean automatically.

## After The First Call

Once the basic path works, the usual next steps are:

1. switch one operation from `String` to a typed DTO
2. add tests with `tramai-testing`
3. add `tramai-observability` if the application is production-bound
4. add `tramai-orchestration` only if you truly need multi-step persisted workflows

## If You Are Unsure Which Modules To Use

Use this minimum-default rule:

- non-Spring: `tramai-standalone` + one provider
- Spring Boot: `tramai-spring-boot-starter` + one provider adapter

Everything else is additive.
