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

Example with explicit fallback routing and resilience controls:

```yaml
tramai:
  default-provider: openai
  models:
    gpt-5.1-chat-latest: openai
    gpt-5.1-mini: openai
    llama3.2: ollama
  fallbacks:
    gpt-5.1-chat-latest:
      - provider: openai
        model: gpt-5.1-mini
      - provider: ollama
        model: llama3.2
  resilience:
    circuit-breaker:
      enabled: true
      failure-threshold: 3
      open-duration-millis: 30000
    retry:
      max-retry-after-millis: 20000
      jitter-ratio: 0.1
  cost:
    token-budget:
      hard-max-tokens-per-attempt: 4000
      hard-max-tokens-per-operation: 12000
      soft-max-tokens-per-operation: 8000
  providers:
    openai:
      api-key: ${OPENAI_API_KEY}
    ollama:
      base-url: http://localhost:11434
```

This keeps the routing contract explicit:

- the primary model still resolves through `models`
- fallback order is taken from `fallbacks`
- retry pacing and circuit breaking are engine settings under `tramai.resilience`
- token budget policy is configured under `tramai.cost.token-budget`

## Secret References

Spring configuration can resolve provider credentials from secret references instead of embedding raw values.

Built-in reference schemes are:

- `env:NAME`
- `file:/absolute/path/to/secret.txt`
- `vault:path[#field]` when `tramai.secrets.vault.enabled=true`
- `aws-secretsmanager:secret-id[#field]` when `tramai.secrets.aws-secrets-manager.enabled=true`

Example:

```yaml
tramai:
  default-provider: openai
  models:
    gpt-5.1-chat-latest: openai
  providers:
    openai:
      api-key-secret-ref: env:OPENAI_API_KEY
```

Example using the bundled Vault resolver:

```yaml
tramai:
  default-provider: openai
  models:
    gpt-5.1-chat-latest: openai
  secrets:
    vault:
      enabled: true
      base-url: https://vault.example.com
      token-secret-ref: env:VAULT_TOKEN
  providers:
    openai:
      api-key-secret-ref: vault:providers/openai/api-key
```

Example using the bundled AWS Secrets Manager resolver:

```yaml
tramai:
  default-provider: openai
  models:
    gpt-5.1-chat-latest: openai
  secrets:
    aws-secrets-manager:
      enabled: true
      region: eu-west-1
      access-key-id-secret-ref: env:AWS_ACCESS_KEY_ID
      secret-access-key-secret-ref: env:AWS_SECRET_ACCESS_KEY
  providers:
    openai:
      api-key-secret-ref: aws-secretsmanager:prod/openai/api-key
```

You can still provide your own `SecretValueResolver` bean when you need a different scheme or a different client implementation.

## Response Caching

Spring can enable the built-in in-memory cache for cacheable operations:

```yaml
tramai:
  cache:
    in-memory:
      enabled: true
      max-entries: 1000
```

Cache behavior is still explicit on the operation itself through `@Operation(cacheable = true, cacheTtlMillis = ...)`.

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
