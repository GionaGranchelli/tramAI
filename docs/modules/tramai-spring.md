# Module: `tramai-spring`

> **One-liner:** Legacy Spring Boot facade over `tramai-spring-core`. Retained for back-compatibility; **not** the onboarding entry point — new applications use the unified `tramai-spring-boot-starter`.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

---

## Architecture

### Responsibility

**Legacy facade** over `tramai-spring-core` (manifest rationale: "Legacy Spring facade over :tramai-spring-core retained for back-compatibility; not the onboarding entry point"). Provides the classic `@AutoConfiguration` wiring of the `Tramai` runtime with `@AiService` scanning, `@AiTool` discovery, and `tramai.*` property binding for existing 0.5.x applications.

### Public entry points

- `TramaiAutoConfiguration` — auto-configuration
- `TramaiProperties` — configuration properties
- `AiServiceBeanDefinitionRegistrar`, `AiServiceFactoryBean` — `@AiService` scanning/registration
- `AiToolScanner` — tool discovery
- `EnableTramai` — annotation opt-in
- `SpringSecretResolution`, `SpringBuiltInSecretValueResolver` — secret resolution

Verify against `tramai-spring/api/tramai-spring.api`.

### Internal extension points

- Secret-resolution chain slot (delegates to `tramai-spring-core`)

### Significant dependencies

- `api(tramai-spring-core)` (thin facade); provider and secret adapters are separate modules — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; beans managed by the container

### Thread-safety and concurrency

- Spring singletons; proxies must be safe for concurrent invocation

### Failure semantics

- Misconfiguration surfaces at context startup; provider failures normalized per provider contracts

### Contract tests / TCKs

- `TramaiAutoConfigurationTest`, `TramaiAutoConfigurationConditionsTest`, `SecurityAutoConfigurationTest`

### Do not

- Do not present legacy APIs as canonical — new integrations use the unified starter
- Do not add provider/secret adapters here — use the dedicated Spring starter modules

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
- [modules.md](../architecture/modules.md) — framework-integrations layer policy
- [module-matrix.md](../../docs/reference/module-matrix.md)

---

## L1: Quick Start (30-second read)

### What

`tramai-spring` is the 0.6.0 compatibility facade over `tramai-spring-core`. New applications should depend on the canonical [`tramai-spring-boot-starter`](tramai-spring-boot-starter.md) instead — one starter for both runtime profiles, profile selected via `tramai.profile`.

`tramai-spring` is a thin Spring Boot `@AutoConfiguration` that takes the same `Tramai` runtime you'd build manually with `tramai-standalone` and makes it available through Spring's DI container. Add the dependency, define an `@AiService` interface, and it becomes an injectable bean — no `@Bean` factory methods, no manual `Tramai.builder()` chains. Under Spring Boot, `@EnableTramai` is normally unnecessary; in annotation-driven/non-Boot Spring contexts it is the explicit opt-in.

### Why

Spring Boot developers expect framework-managed configuration, auto-discovery, and constructor injection. `tramai-spring` delivers on those expectations without reintroducing framework coupling into Tramai's core:

- **Configuration-driven** — all provider API keys, model mappings, fallback routes, resilience settings, and caching live in `application.yml` under a single `tramai:` namespace, bound via `@ConfigurationProperties`
- **Zero boilerplate** — `@AiService` interfaces are discovered by classpath scanning and registered as singleton `FactoryBean` proxies; `@AiTool` methods on any Spring bean are auto-discovered and registered as callable tools
- **Secrets management** — `SecretValueResolver` implementations for HashiCorp Vault and AWS Secrets Manager (in `tramai-spring-secrets-vault`/`tramai-spring-secrets-aws`) plug directly into the property-binding pipeline
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

> **0.6.0 migration:** `tramai-spring` remains the compatibility entry point for generic Spring integration, but **no longer bundles provider adapters or optional secret backends**. Add them explicitly. Property namespaces (`tramai.providers.*`, `tramai.secrets.*`) are unchanged.

```kotlin
// build.gradle.kts
// tramaiVersion is the canonical version property (see gradle.properties)
val tramaiVersion: String by project

dependencies {
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
    implementation("dev.tramai:tramai-spring")
    // 0.6.0: select adapters explicitly
    implementation("dev.tramai:tramai-spring-provider-openai") // or -anthropic, -ollama
    implementation("dev.tramai:tramai-spring-secrets-vault")   // optional
    implementation("dev.tramai:tramai-spring-secrets-aws")     // optional
}
```

```xml
<!-- pom.xml -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tramai</groupId>
      <artifactId>tramai-bom</artifactId>
      <version>${tramai.version}</version>
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
    <artifactId>tramai-spring-provider-openai</artifactId>
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

Three steps: add `tramai-spring` **plus the adapter(s) you select**, define an `@AiService` interface, configure `application.yml`.

```kotlin
// build.gradle.kts
// tramaiVersion is the canonical version property (see gradle.properties)
val tramaiVersion: String by project

dependencies {
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
    implementation("dev.tramai:tramai-spring")
    implementation("dev.tramai:tramai-spring-provider-openai") // 0.6.0: explicit adapter
}
```

```kotlin
@SpringBootApplication
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

`@EnableTramai` selects the Spring programming model, **not** the runtime profile. The runtime profile is always selected via `tramai.profile` (missing → standard, `sovereign` → sovereign). Under Spring Boot the annotation is normally unnecessary — auto-discovery already activates the module; it remains useful in annotation-driven/non-Boot Spring contexts as an explicit signal, matching the principle of explicitness over magic.

### Bean registration flow

```
@EnableTramai
  └─ @Import(TramaiAutoConfiguration::class)
       │
       ├─ @Bean "tramai" (Tramai instance)
       │    1. Binds TramaiProperties from application.yml
       │    2. Resolves secret values through the composed chain
       │    3. Calls AiToolScanner.fromApplicationContext() → discovers @AiTool beans
       │    4. Collects SpringConfiguredModelProvider beans contributed by the
       │       adapter auto-configurations (tramai-spring-provider-*) plus any
       │       user-defined ModelProvider beans
       │    5. Merges providers by id; an explicit bean replaces a same-id property provider
       │    6. Registers the unique provider set, then configures model routing, fallbacks, cache, interceptors
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

`TramaiProperties` is a Spring `@ConfigurationProperties("tramai")` data class that maps every generic `tramai.*` YAML key to a typed field. Provider and secret-backend sections are **not** nested here: each adapter module owns its own `@ConfigurationProperties` class (`tramai-spring-provider-openai` owns `OpenAiProperties`/`OpenAiCompatibleProperties` under `tramai.providers.*`, `tramai-spring-secrets-vault` owns `VaultSecretProperties` under `tramai.secrets.vault`, etc.) and binds them via `@EnableConfigurationProperties`. Key design choices:

- **Mutable `var` properties** — Spring Boot binds via setter reflection; Kotlin data classes with `var` fields are the idiomatic Spring Boot pattern
- **Nested data classes** — `Resilience`, `Cost`, `Cache` and other generic runtime sections map to their own subsection, keeping the top-level class readable
- **Mutual-exclusion enforcement** — the `resolveSecret()` helper in each provider adapter throws `IllegalStateException` at startup if both `apiKey` and `apiKeySecretRef` are provided for the same provider, preventing silent misconfiguration
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
2. VaultSecretValueResolver (if tramai-spring-secrets-vault is on the classpath and tramai.secrets.vault.enabled=true)
3. AwsSecretsManagerSecretValueResolver (if tramai-spring-secrets-aws is on the classpath and tramai.secrets.aws-secrets-manager.enabled=true)
4. EnvironmentSecretValueResolver (env:* references)
5. FileSecretValueResolver (file:* references, from tramai-spring-secrets-file)
```

Vault, AWS, and file resolvers only participate when their module is an explicit dependency — `tramai-spring` alone provides environment resolution.

This chain is used to resolve any `*-secret-ref` property in the provider configuration. The Vault and AWS resolvers themselves have a bootstrapping step: their own tokens/credentials can reference `env:*` or `file:*` secrets (resolved by a bootstrap `CompositeSecretValueResolver` that excludes the yet-uninitialized Vault/AWS resolvers).

**Vault reference format:** `vault:path/to/secret#field`
**AWS Secrets Manager reference format:** `aws-secretsmanager:secret-id#field`

### Dependency graph

```
tramai-spring
  Depends on:
    - tramai-spring-core (required) — TramaiAutoConfiguration, TramaiProperties, secret chain, SpringConfiguredModelProvider
    - spring-boot-autoconfigure (required) — @AutoConfiguration, @ConditionalOnMissingBean
    - spring-boot (required) — ApplicationContext, BeanFactory

  Does not depend on provider/secret adapters (0.6.0):
    - tramai-spring-provider-openai / -anthropic / -ollama — provider properties + construction
    - tramai-spring-secrets-file / -vault / -aws — secret backends

  Depended on by:
    - Application code (end-user Spring Boot apps), which adds the adapters it selects
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
