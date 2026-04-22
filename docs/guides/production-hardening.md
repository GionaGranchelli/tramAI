# Production Hardening & Security

TramAI is built for production environments where cost control, data privacy, and reliability matter. This guide covers the hardening mechanisms that exist in the current codebase and uses snippets that match the exported APIs.

---

## Security Interceptors

`OperationInterceptor` lets you inspect and modify request messages before provider transport and provider responses before the engine continues processing them.

That makes it the right extension point for:

- PII masking
- internal identifier redaction
- request/response auditing
- policy enforcement before data leaves the JVM

### Standalone Usage

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-5.1-chat-latest", "openai")

    interceptor(object : OperationInterceptor {
        override fun interceptRequest(
            context: OperationCallContext,
            messages: List<Message>,
        ): List<Message> = messages.map { message ->
            message.copy(content = redactPii(message.content))
        }
    })
}
```

### Spring Boot Usage

Spring Boot auto-configuration composes ordered `OperationInterceptor` beans into the generated `TramAI` instance.

```kotlin
@Configuration
class TramaiSecurityConfiguration {
    @Bean
    fun piiMaskingInterceptor(): OperationInterceptor = object : OperationInterceptor {
        override fun interceptRequest(
            context: OperationCallContext,
            messages: List<Message>,
        ): List<Message> = messages.map { message ->
            message.copy(content = maskSensitiveData(message.content))
        }
    }
}
```

Current boundary:

- interceptors are engine-level request/response hooks
- they are opt-in
- Spring auto-configuration composes registered interceptor beans in order

---

## Secret Management

Provider credentials should not be hard-coded in application code. TramAI supports the `SecretValueResolver` SPI for resolving secret references.

Built-in resolvers:

- `env:NAME`
- `file:/path/to/secret.txt`
- `vault:path[#field]` through `tramai-spring` when `tramai.secrets.vault.enabled=true`
- `aws-secretsmanager:secret-id[#field]` through `tramai-spring` when `tramai.secrets.aws-secrets-manager.enabled=true`

### Standalone Usage

Standalone usage does not have a dedicated `secretResolver(...)` builder method today. Resolve the secret first, then construct the provider with the resolved value:

```kotlin
val secretResolver = CompositeSecretValueResolver(
    listOf(
        SecretValueResolver { secretRef ->
            if (!secretRef.startsWith("vault:")) {
                null
            } else {
                vaultClient.read(secretRef.removePrefix("vault:"))
            }
        },
        EnvironmentSecretValueResolver,
        FileSecretValueResolver,
    ),
)

val tramai = Tramai {
    provider(
        OpenAiProvider(
            apiKey = secretResolver.resolve("vault:providers/openai/api-key")
                ?: error("Missing OpenAI API key"),
        ),
        name = "openai",
        default = true,
    )
    model("gpt-5.1-chat-latest", "openai")
}
```

### Spring Boot Usage

Spring Boot auto-configuration composes `SecretValueResolver` beans and also ships bundled Vault and AWS Secrets Manager resolvers behind `tramai.secrets.*`.

Built-in Vault example:

```yaml
tramai:
  secrets:
    vault:
      enabled: true
      base-url: https://vault.example.com
      token-secret-ref: env:VAULT_TOKEN
  providers:
    openai:
      api-key-secret-ref: vault:providers/openai/api-key
```

Built-in AWS Secrets Manager example:

```yaml
tramai:
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

Custom resolvers still work the same way when you need a different backend or client implementation.

Current boundary:

- built-in support covers `env:` and `file:` in the shared secret SPI
- `tramai-spring` bundles Vault and AWS Secrets Manager resolvers behind `tramai.secrets.*`
- standalone usage still resolves the secret before provider construction

---

## Token Budgets

Token budgets are engine-owned controls based on provider-reported usage.

Available controls:

- hard max per attempt
- hard max per logical operation
- soft max per logical operation

### Standalone Usage

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-5.1-chat-latest", "openai")

    tokenBudget(
        TokenBudgetSettings(
            hardMaxTokensPerAttempt = 4_000,
            hardMaxTokensPerOperation = 20_000,
            softMaxTokensPerOperation = 10_000,
        ),
    )
}
```

### Spring Boot Usage

```yaml
tramai:
  cost:
    token-budget:
      hard-max-tokens-per-attempt: 4000
      hard-max-tokens-per-operation: 20000
      soft-max-tokens-per-operation: 10000
```

Practical notes:

- budgets apply across retries
- structured-output retries count toward the operation total
- tool-call loops count toward the operation total
- soft limit crossing emits an engine event instead of failing the call

---

## Resilience Controls

TramAI keeps retry pacing, fallback routing, and provider health protection in the engine rather than scattering them across provider implementations.

### Fallback Routing

Fallback routes are explicit ordered routes for a requested model.

### Circuit Breaking

Circuit breaking prevents repeated calls into an unhealthy provider after sustained failure.

### Retry Pacing

Retry pacing uses exponential backoff with jitter and honors provider retry hints such as `Retry-After`, up to the configured cap.

### Standalone Usage

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    provider(AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")), name = "anthropic")

    model("gpt-4o", "openai")
    model("claude-sonnet-4-20250514", "anthropic")

    fallbackModel("gpt-4o", "claude-sonnet-4-20250514", "anthropic")

    circuitBreaker(
        CircuitBreakerSettings(
            enabled = true,
            failureThreshold = 3,
            openDurationMillis = 30_000,
        ),
    )
    retryPolicy(
        RetryPolicySettings(
            maxRetryAfterMillis = 20_000,
            jitterRatio = 0.1,
        ),
    )
}
```

### Spring Boot Usage

```yaml
tramai:
  models:
    gpt-4o: openai
    claude-sonnet-4-20250514: anthropic
  fallbacks:
    gpt-4o:
      - provider: anthropic
        model: claude-sonnet-4-20250514
  resilience:
    circuit-breaker:
      enabled: true
      failure-threshold: 3
      open-duration-millis: 30000
    retry:
      max-retry-after-millis: 20000
      jitter-ratio: 0.1
```

Current boundary:

- fallback routing is explicit, not heuristic
- streaming failover is only allowed before the first emitted token
- once a stream has emitted user-visible output, TramAI returns a terminal error rather than stitching providers together
