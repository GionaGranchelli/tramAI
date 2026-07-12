# Module: `tramai-spring`

> **One-liner:** Spring Boot auto-configuration that wires Tramai's `Tramai` runtime, scans `@AiService` interfaces, discovers `@AiTool` beans, and binds `tramai.*` application properties — all with zero manual bean declarations.
> **Module type:** `framework-adapter`
> **Source files:** 9 — `EnableTramai.kt`, `TramaiAutoConfiguration.kt`, `TramaiProperties.kt`, `AiServiceBeanDefinitionRegistrar.kt`, `AiServiceFactoryBean.kt`, `AiToolScanner.kt`, `secret/VaultSecretValueResolver.kt`, `secret/AwsSecretsManagerSecretValueResolver.kt`, `secret/AwsSecretsManagerLookupClient.kt`
> **Test files:** 2 — `VaultSecretValueResolverTest.kt`, `AwsSecretsManagerSecretValueResolverTest.kt`
> **Build:** `dev.tramai:tramai-spring:0.4.0`
> **Depends on:** `tramai-core`, `tramai-engine`, `tramai-structured`, `tramai-standalone`, plus one or more provider modules (`tramai-openai`, `tramai-anthropic`, `tramai-ollama`)

---

## L1: Quick Start (30-second read)

### What

`tramai-spring` is a thin Spring Boot `@AutoConfiguration` that takes the same `Tramai` runtime you'd build manually with `tramai-standalone` and makes it available through Spring's DI container. Add the dependency, annotate your application class with `@EnableTramai`, define an `@AiService` interface, and it becomes an injectable bean — no `@Bean` factory methods, no manual `Tramai.builder()` chains.

### Why

Spring Boot developers expect framework-managed configuration, auto-discovery, and constructor injection. `tramai-spring` delivers on those expectations without reintroducing framework coupling into Tramai's core:

- **Configuration-driven** — all provider API keys, model mappings, fallback routes, resilience settings, and caching live in `application.yml` under a single `tramai:` namespace, bound via `@ConfigurationProperties`
- **Zero boilerplate** — `@AiService` interfaces are discovered by classpath scanning and registered as singleton `FactoryBean` proxies; `@AiTool` methods on any Spring bean are auto-discovered and registered as callable tools
- **Secrets management** — bundled `SecretValueResolver` implementations for HashiCorp Vault and AWS Secrets Manager plug directly into the property-binding pipeline
- **Same engine underneath** — the auto-configured `Tramai` bean is the same `dev.tramai.standalone.Tramai` that standalone users build manually; behavior, retry policy, structured output, and provider routing are identical

### When to use

```
Use this module when:
- You already have a Spring Boot application and want AI capabilities
- You want @AiService interfaces injected as Spring beans via @Autowired / constructor injection
- You prefer application.yml over builder chains for configuration
- You want Spring's auto-scanning to discover @AiTool beans in your existing service layer
- You need Vault or AWS Secrets Manager for credential resolution at startup

Don't use this module when:
- You don't use Spring Boot (use tramai-standalone instead)
- You need full control over the Tramai builder chain without Spring property binding
- You want to avoid Spring Boot's startup overhead in a CLI tool or script
```

### How to add

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.4.0"))
    implementation("dev.tramai:tramai-spring")
    implementation("dev.tramai:tramai-openai") // or tramai-anthropic, tramai-ollama
}
```

```xml
<!-- pom.xml -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tramai</groupId>
      <artifactId>tramai-bom</artifactId>
      <version>0.4.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-spring</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-openai</artifactId>
  </dependency>
</dependencies>
```

### Where to go next

- [Spring Boot Integration Guide](../guides/spring-boot.md) — Full walkthrough with code
- [Getting Started](../guides/getting-started.md) — Pick your setup path
- [Configuration Reference](../reference/configuration.md) — All `tramai.*` properties
- [Production Hardening](../guides/production-hardening.md) — Vault/AWS Secrets Manager, circuit breakers, caching
- [tramai-standalone](./tramai-standalone.md) — The same runtime without Spring
- [tramai-core](./tramai-core.md) — Annotations and SPI contracts

---

## L2: Usage Guide (5-minute read)

### Minimal setup

Three steps: add `tramai-spring`, define an `@AiService` interface, configure `application.yml`.

```kotlin
@SpringBootApplication
@EnableTramai
class InvoiceApplication

fun main() = runApplication<InvoiceApplication>()
```

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze this invoice and return a one-line status.",
        model = "gpt-4o",
    )
    suspend fun analyze(invoiceText: String): String
}
```

```yaml
# application.yml
tramai:
  default-provider: openai
  models:
    gpt-4o: openai
  providers:
    openai:
      api-key: ${OPENAI_API_KEY}
```

The `InvoiceAnalyzer` interface can now be injected anywhere:

```kotlin
@Service
class BillingService(
    private val invoiceAnalyzer: InvoiceAnalyzer,
) {
    suspend fun process(invoiceText: String): String =
        invoiceAnalyzer.analyze(invoiceText)
}
```

### application.yml reference

#### Providers

```yaml
tramai:
  providers:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      api-key-secret-ref: vault:providers/anthropic/api-key   # mutual exclusive with api-key
      base-url: https://api.anthropic.com

    openai:
      api-key: ${OPENAI_API_KEY}
      api-key-secret-ref: vault:providers/openai/api-key
      bearer-token: ${OPENAI_BEARER_TOKEN}
      bearer-token-secret-ref: vault:providers/openai/bearer-token
      base-url: https://api.openai.com/v1
      organization: org-xxx
      project: proj_xxx
      codex-auth:
        enabled: false
        auth-file: /home/user/.codex/auth.json

    openai-compatible:
      provider-name: my-local
      api-key: ${COMPATIBLE_API_KEY}
      base-url: https://my-endpoint.example.com/v1

    ollama:
      base-url: http://localhost:11434
```

#### Model routing and fallbacks

```yaml
tramai:
  default-provider: openai
  models:
    gpt-4o: openai
    gpt-4o-mini: openai
    llama3.2: ollama
  fallbacks:
    gpt-4o:
      - provider: openai
        model: gpt-4o-mini
      - provider: ollama
        model: llama3.2
```

#### Resilience, cost control, and caching

```yaml
tramai:
  resilience:
    circuit-breaker:
      enabled: true
      failure-threshold: 5
      open-duration-millis: 30000
    retry:
      max-retry-after-millis: 20000
      jitter-ratio: 0.1
  cost:
    token-budget:
      hard-max-tokens-per-attempt: 4000
      hard-max-tokens-per-operation: 12000
      soft-max-tokens-per-operation: 8000
  cache:
    in-memory:
      enabled: true
      max-entries: 1000
```

#### Secret resolvers (Vault / AWS Secrets Manager)

```yaml
tramai:
  secrets:
    vault:
      enabled: true
      base-url: https://vault.example.com
      token-secret-ref: env:VAULT_TOKEN
      mount-path: secret
      kv-version: 2
      namespace: my-namespace
      default-field: value
    aws-secrets-manager:
      enabled: true
      region: eu-west-1
      access-key-id-secret-ref: env:AWS_ACCESS_KEY_ID
      secret-access-key-secret-ref: env:AWS_SECRET_ACCESS_KEY
      endpoint: https://secretsmanager.eu-west-1.amazonaws.com
      default-field: value
```

### @AiTool bean scanning

Any Spring bean with methods annotated `@AiTool` is automatically discovered and registered as a callable tool on the `Tramai` runtime:

```kotlin
@Component
class WeatherTools {

    data class GetTemperatureInput(val city: String, val unit: String = "celsius")

    @AiTool(
        name = "get_temperature",
        description = "Get the current temperature for a city",
        idempotent = true,
        sideEffectLevel = SideEffectLevel.NONE,
    )
    suspend fun getTemperature(input: GetTemperatureInput): String {
        // Call a weather API...
        return "The temperature in ${input.city} is 22° ${input.unit}"
    }
}
```

Tool methods must satisfy:
- Exactly **one parameter**, which must be a **data class**
- The method may be `suspend` or blocking; the runtime resolves invocation strategy via reflection

The scanner (`AiToolScanner`) iterates all Spring beans, inspects each for `@AiTool`-annotated methods via `kotlin.reflect.full`, validates the signature, and wraps them into `TramaiTool` instances that are registered with the `Tramai` builder before `build()` is called.

---

## L3: Architecture & Mechanics (15-minute read)

### Auto-configuration entry point

The module is activated by either:

1. `@EnableTramai` — a meta-annotation that `@Import(TramaiAutoConfiguration::class)`, allowing explicit opt-in
2. Spring Boot's automatic `@AutoConfiguration` discovery via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

`@EnableTramai` exists as an explicit signal — even though Spring Boot would auto-discover the configuration class — to make the dependency visible in code, matching the principle of explicitness over magic.

### Bean registration flow

```
@EnableTramai
  └─ @Import(TramaiAutoConfiguration::class)
       │
       ├─ @Bean "tramai" (Tramai instance)
       │    1. Binds TramaiProperties from application.yml
       │    2. Resolves secret values (env, file, Vault, AWS)
       │    3. Calls AiToolScanner.fromApplicationContext() → discovers @AiTool beans
       │    4. Registers property-defined providers (Anthropic, OpenAI, Ollama, OpenAI-compatible)
       │    5. Collects user-defined ModelProvider beans via ObjectProvider
       │    6. Configures model routing, fallbacks, cache, interceptors
       │    7. Builds and returns Tramai instance
       │
       └─ @Bean "aiServiceBeanDefinitionRegistrar" (AiServiceBeanDefinitionRegistrar)
            implements BeanDefinitionRegistryPostProcessor
             1. Scans AutoConfigurationPackages for @AiService interfaces
             2. For each interface, registers a RootBeanDefinition(AiServiceFactoryBean)
             3. AiServiceFactoryBean.getObject() calls tramai.create(serviceType.kotlin)
             4. Returns a JDK dynamic proxy that delegates to TramaiEngine
```

### TramaiProperties — property binding

`TramaiProperties` is a Spring `@ConfigurationProperties("tramai")` data class that maps every `tramai.*` YAML key to a typed field. Key design choices:

- **Mutable `var` properties** — Spring Boot binds via setter reflection; Kotlin data classes with `var` fields are the idiomatic Spring Boot pattern
- **Nested data classes** — `Providers`, `Resilience`, `Cost`, `Cache`, `Secrets` each map to their own subsection, keeping the top-level class readable
- **Mutual-exclusion enforcement** — the `resolveSecret()` helper in `TramaiAutoConfiguration` throws `IllegalStateException` at startup if both `apiKey` and `apiKeySecretRef` are provided for the same provider, preventing silent misconfiguration
- **Graceful absence** — if a provider block is missing, no provider is registered (e.g., omitting `tramai.providers.ollama` simply skips Ollama registration)

### AiServiceBeanDefinitionRegistrar — @AiService scanning

This `BeanDefinitionRegistryPostProcessor` runs during Spring's bean-definition phase, before any beans are instantiated:

1. Checks that `AutoConfigurationPackages` is available (the Spring Boot application has a base package)
2. Creates a `ClassPathScanningCandidateComponentProvider` filtered to interfaces annotated with `@AiService`
3. Iterates all auto-configuration base packages, finds candidate `@AiService` interfaces
4. For each, registers a `RootBeanDefinition(AiServiceFactoryBean::class.java)` with the interface class as a constructor argument
5. The `AiServiceFactoryBean` lazily resolves the `Tramai` bean from the `BeanFactory` and calls `tramai.create(serviceType)` when Spring asks for the proxy

This approach keeps the scanning decoupled from the `Tramai` bean lifecycle — the registrar works at definition time, the factory bean works at dependency-injection time.

### AiToolScanner — method-level tool discovery

The scanner uses `kotlin.reflect.full` to introspect each Spring bean at `Tramai` construction time:

1. Iterates all bean names (skipping the `"tramai"` bean to avoid circular resolution)
2. For each bean, checks whether any method carries `@AiTool`
3. Validates the tool method signature (exactly one parameter, must be a data class)
4. Wraps each `@AiTool` method into a `MethodBackedTramaiTool` that delegates to the bean via `KFunction.call()` or `callSuspend()`
5. Returns the list to `TramaiAutoConfiguration`, which passes it to `builder.tools(...)`

Because scanning happens at the `Tramai` bean construction phase (not at bean-definition time), all Spring beans — including those created by `@Bean` factory methods — are available for tool discovery.

### Secret resolution chain

The auto-configuration composes a `CompositeSecretValueResolver` with this priority order:

```
1. User-provided SecretValueResolver beans (highest priority)
2. VaultSecretValueResolver (if tramai.secrets.vault.enabled=true)
3. AwsSecretsManagerSecretValueResolver (if tramai.secrets.aws-secrets-manager.enabled=true)
4. EnvironmentSecretValueResolver (env:* references)
5. FileSecretValueResolver (file:* references)
```

This chain is used to resolve any `*-secret-ref` property in the provider configuration. The Vault and AWS resolvers themselves have a bootstrapping step: their own tokens/credentials can reference `env:*` or `file:*` secrets (resolved by a bootstrap `CompositeSecretValueResolver` that excludes the yet-uninitialized Vault/AWS resolvers).

**Vault reference format:** `vault:path/to/secret#field`
**AWS Secrets Manager reference format:** `aws-secretsmanager:secret-id#field`

### Dependency graph

```
tramai-spring
  Depends on:
    - tramai-core (required) — annotations, ProviderRegistry, ModelProvider, SecretValueResolver
    - tramai-engine (required) — TramaiEngine, CircuitBreakerSettings, RetryPolicySettings, etc.
    - tramai-structured (required) — JacksonStructuredOutputHandler (for tool schema generation)
    - tramai-standalone (required) — Tramai builder (wires core + engine + structured)
    - tramai-openai (optional at runtime) — OpenAiProvider, OpenAiCompatibleProvider
    - tramai-anthropic (optional at runtime) — AnthropicProvider
    - tramai-ollama (optional at runtime) — OllamaProvider
    - spring-boot-autoconfigure (required) — @AutoConfiguration, @ConditionalOnMissingBean
    - spring-boot (required) — ApplicationContext, BeanFactory

  Depended on by:
    - Application code (end-user Spring Boot apps)
```

### Error model

| Situation | Exception | When |
|-----------|-----------|------|
| `apiKey` + `apiKeySecretRef` both set | `IllegalStateException` | `TramaiAutoConfiguration.resolveSecret()` at startup |
| Vault enabled but `baseUrl` missing | `IllegalStateException` | `createVaultSecretValueResolver()` at startup |
| Vault/AWS token unresolvable | `IllegalStateException` | `resolveSecret()` in resolver factory |
| AWS enabled but `region` missing | `IllegalStateException` | `createAwsSecretsManagerSecretValueResolver()` at startup |
| Unknown provider in fallback route | `IllegalStateException` | `TramaiAutoConfiguration` at startup |
| @AiTool method with != 1 parameter | `IllegalStateException` | `AiToolScanner` at `Tramai` construction |
| @AiTool parameter not a data class | `IllegalStateException` | `AiToolScanner` at `Tramai` construction |
| @AiService interface not found | No bean registered (silent) | Bean-definition phase |

### Testing strategy

- `TramaiAutoConfiguration` is verified through Spring Boot integration tests that assert correct bean registration, property binding, and tool scanning
- `VaultSecretValueResolver` and `AwsSecretsManagerSecretValueResolver` are tested against embedded HTTP servers and mock clients
- Core runtime correctness (proxy dispatch, retry, structured output) is verified in `tramai-engine` and `tramai-structured` — the Spring adapter is a thin wiring layer and does not re-test engine behavior
