# Getting Started

This guide gets Aurora running from the current repository state.

## What Aurora Is

Aurora lets you write typed interfaces and back them with LLM calls.

You define an interface:

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a structured status",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun analyze(invoiceId: String): InvoiceStatus
}

data class InvoiceStatus(
    val status: String,
)
```

Then you choose a provider, map the model to that provider, and create the service.

## Requirements

Aurora currently assumes:

- Java 25
- Kotlin 2.3.0
- Gradle wrapper from this repository

## Clone and Build

From the repository root:

```bash
./gradlew test
```

That is the best first check because it compiles every module and runs the current test suite.

## How To Consume Aurora Right Now

Aurora is still repository-first. The simplest setup today is one of these:

1. work directly in this repository
2. include Aurora modules as project dependencies in a multi-project build
3. publish to a local Maven repository yourself if you want to consume it from another repo

The documentation below assumes you are working from source or a local publication.

## Which Modules To Add

For a simple Kotlin application:

- always start with `aurora-standalone`
- add one or more provider modules
- optionally add `aurora-observability`
- add `aurora-testing` in tests

Typical combinations:

- `aurora-standalone` + `aurora-anthropic`
- `aurora-standalone` + `aurora-openai`
- `aurora-standalone` + `aurora-ollama`
- `aurora-standalone` + one provider + `aurora-observability`

For Spring Boot:

- start with `aurora-spring`
- add the provider modules you want to use if they are not already brought in through your build layout

## Minimal Gradle Example

If your app is in the same multi-project build:

```kotlin
dependencies {
    implementation(project(":aurora-standalone"))
    implementation(project(":aurora-openai"))
    testImplementation(project(":aurora-testing"))
}
```

## Your First Standalone Program

```kotlin
import io.aurora.core.annotations.AiService
import io.aurora.core.annotations.Operation
import io.aurora.openai.OpenAiProvider
import io.aurora.standalone.Aurora
import io.aurora.standalone.create

@AiService
interface HelloService {
    @Operation(
        prompt = "Say hello to the user in one sentence",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun hello(name: String): String
}

suspend fun main() {
    val aurora = Aurora {
        provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
        model("gpt-5.1-chat-latest", "openai")
    }

    val service = aurora.create<HelloService>()
    println(service.hello("Giona"))
}
```

## Choose Your Next Guide

- If you want plain Kotlin usage, continue with [Standalone Usage](./standalone-usage.md)
- If you want Spring Boot, continue with [Spring Boot Integration](./spring-boot.md)
- If you want typed return values, continue with [Structured Output](./structured-output.md)
- If you want to compare providers, continue with [Providers and Model Routing](./providers.md)
