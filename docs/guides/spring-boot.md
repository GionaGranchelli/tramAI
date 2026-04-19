# Spring Boot Integration

Use `tramai-spring` when you want Tramai service interfaces injected as Spring beans.

## What Spring Support Provides

The Spring adapter currently does these things:

- binds `tramai.*` configuration properties
- creates an `Tramai` instance automatically
- scans for `@AiService` interfaces
- registers proxies for those interfaces as beans

## Minimal Setup

Add the module to your app and configure at least one provider.

Example application code:

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a raw status",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun analyze(invoiceId: String): String
}

@Service
class BillingService(
    private val analyzer: InvoiceAnalyzer,
) {
    suspend fun process(invoiceId: String): String = analyzer.analyze(invoiceId)
}
```

## YAML Configuration

Example using OpenAI:

```yaml
tramai:
  default-provider: openai
  models:
    gpt-5.1-chat-latest: openai
  providers:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1
```

Example using Anthropic:

```yaml
tramai:
  default-provider: anthropic
  models:
    claude-sonnet-4-20250514: anthropic
  providers:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
```

Example using Ollama:

```yaml
tramai:
  default-provider: ollama
  models:
    llama3.2: ollama
  providers:
    ollama:
      base-url: http://localhost:11434
```

## Providing Your Own Provider Bean

You can also register a `ModelProvider` bean directly.

Tramai merges:

- property-defined providers
- explicit `ModelProvider` beans

If both define the same provider name, the bean-backed provider wins because it is treated as the explicit application override.

## OpenAI-Compatible Configuration

For a generic compatible endpoint:

```yaml
tramai:
  default-provider: my-compatible
  models:
    gpt-oss-compatible: my-compatible
  providers:
    openai-compatible:
      provider-name: my-compatible
      bearer-token: ${COMPATIBLE_API_TOKEN}
      base-url: https://my-endpoint.example.com/v1
```

## Experimental Codex Auth-File Path

Spring can also construct OpenAI providers from the local Codex auth file.

Example:

```yaml
tramai:
  default-provider: openai
  models:
    gpt-5.1-chat-latest: openai
  providers:
    openai:
      codex-auth:
        enabled: true
        auth-file: /home/you/.codex/auth.json
```

This path is experimental and intended for:

- local testing
- exploratory internal tools
- wrapper-style applications that run close to a logged-in Codex environment

It is not the default recommended production authentication flow.

## Current Spring Limitations

The Spring adapter is intentionally thin. It does not currently provide:

- advanced bean scopes for AI services
- per-bean provider overrides outside the annotation and model mapping system
- automatic test slices beyond normal Spring testing plus `tramai-testing`
- custom proxy generation strategies

For current property details, see [Configuration Reference](../reference/configuration.md).
