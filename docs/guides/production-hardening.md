# Production Hardening & Security

TramAI is built for production environments where cost control, data privacy, and reliability are critical. This guide covers the built-in mechanisms for hardening your AI integration.

---

## 🛡️ Security Interceptors (PII Masking)

When sending data to external LLM providers, you may need to redact sensitive information (PII, secrets, internal IDs). TramAI provides an `OperationInterceptor` SPI that can inspect and modify request messages and provider responses.

### Usage (Standalone)

```kotlin
val tramai = Tramai {
    interceptor(object : OperationInterceptor {
        override fun interceptRequest(
            context: OperationCallContext, 
            messages: List<Message>
        ): List<Message> {
            return messages.map { it.copy(content = redactPII(it.content)) }
        }
    })
}
```

### Usage (Spring Boot)

Register a bean of type `OperationInterceptor` and TramAI will automatically pick it up:

```kotlin
@Component
class PiiMaskingInterceptor : OperationInterceptor {
    override fun interceptRequest(context: OperationCallContext, messages: List<Message>): List<Message> {
        return messages.map { it.copy(content = maskSensitiveData(it.content)) }
    }
}
```

---

## 🔐 Secret Management

Provider API keys should never be hardcoded or stored in plain text. TramAI uses a `SecretValueResolver` to safely source secrets at runtime.

### Supported Schemes

*   `env:VARIABLE_NAME`: Loads the secret from an environment variable.
*   `file:/path/to/secret`: Loads the secret from a local file.

### Custom Resolvers

You can implement your own `SecretValueResolver` to integrate with external stores like HashiCorp Vault, AWS Secrets Manager, or Azure Key Vault.

```kotlin
class VaultSecretResolver : SecretValueResolver {
    override fun resolve(secretRef: String): String? {
        if (!secretRef.startsWith("vault:")) return null
        return vaultClient.read(secretRef.removePrefix("vault:"))
    }
}

// Register it
val tramai = Tramai {
    // Custom resolvers are tried first
    secretResolver(VaultSecretResolver())
}
```

---

## 💰 Token Budgets & Cost Control

To prevent runaway costs or accidental infinite tool-calling loops, TramAI allows you to set strict budgets for token consumption.

### Configuration

You can set budgets globally for the engine or override them per operation:

*   **Hard Max (Attempt)**: If a single provider response exceeds this, the call fails immediately.
*   **Hard Max (Operation)**: If the cumulative tokens for an operation (including retries and tool calls) exceed this, the call fails.
*   **Soft Max (Operation)**: If exceeded, an engine event is emitted for observability/alerting, but the call continues.

```kotlin
val tramai = Tramai {
    tokenBudget {
        hardMaxTokensPerOperation = 20_000
        softMaxTokensPerOperation = 10_000
    }
}
```

---

## 🔌 Resilience Patterns

Reliability is a first-class citizen in the TramAI engine.

### Circuit Breakers
Prevents overloading an unhealthy provider. If a provider fails too many times consecutively, the breaker opens, and TramAI automatically routes requests to fallback providers.

### Exponential Backoff
Handles transient failures (like 429 Rate Limiting) with jittered exponential backoff. TramAI also honors `Retry-After` headers sent by providers.

### Fallback Routing
Define a prioritized list of model/provider routes. If your primary route fails, the engine seamlessly switches to a fallback.

```kotlin
val tramai = Tramai {
    model("gpt-4o", "openai")
    fallbackModel("gpt-4o", "claude-3-5-sonnet", "anthropic")
}
```
