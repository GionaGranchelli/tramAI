# Aurora — Project Roadmap
### A structured-first, observability-native AI workflow library for the JVM
**Language:** Kotlin | **Serialization:** Jackson | **Framework policy:** Agnostic core, optional Spring adapter  
**Target:** Maven Central, open source, solo maintainer

---

## North Star

Aurora is the library a backend engineer reaches for when they want to add AI to an existing JVM application without learning a new mental model. One annotated interface. Typed inputs and outputs. Every call visible in your traces. Works with Spring, Quarkus, plain Java, or nothing at all.

---

## Phases at a Glance

```
Phase 1 — Foundation       M1 → M3    Weekends 1–3     Core engine works, two providers ship
Phase 2 — Production-ready M4 → M6    Weekends 4–6     Observable, standalone, Spring-wired
Phase 3 — Ecosystem        M7 → M8    Weekends 7–8     Tested, documented, published
Phase 4 — Growth           M9+        Post-launch       Community, v2 features, new providers
```

---

## Phase 1 — Foundation

### M1 · Core Engine
**Goal:** Annotated Kotlin interfaces generate working proxies. Raw String operations execute end-to-end.

**Deliverables:**
- `aurora-core` module: `@AiService`, `@Operation`, `@SystemPrompt` annotations
- `aurora-engine` module: JDK Dynamic Proxy generation via `java.lang.reflect.Proxy`
- Method dispatch: detect return type (`String`, `Unit`, data class), route accordingly
- `suspend fun` detection at proxy generation time
- `ModelProvider` interface + `ModelRequest` / `ModelResponse` data classes
- `AuroraException` sealed hierarchy: `StructuredOutputException`, `ProviderException`, `ConfigurationException`, `TimeoutException`
- Unit tests: proxy creation, method dispatch routing, exception propagation

**What does NOT exist yet:** structured output, real providers, observability.

**Done when:** a proxy backed by a stub provider executes a `suspend fun` method and returns a hardcoded `String`.

---

### M2 · Structured Output Pipeline
**Goal:** Any Kotlin data class or Java POJO can be a return type. Parse failures retry automatically.

**Deliverables:**
- `aurora-structured` module
- Custom Jackson-based JSON schema generator that reads `@AiDescription`, `@AiRange`, `@AiMinItems`
- Schema cached per method at startup — not generated per call
- Schema injected into system prompt as a typed contract
- Response parser: strip markdown fences, extract JSON, deserialize with Jackson kotlin-module
- Kotlin nullability → JSON schema `required` mapping (non-null = required, nullable = optional)
- Validation feedback loop: on parse failure, append error to conversation and retry
- Configurable `maxRetries` on `@Operation`, default 2
- `StructuredOutputException` carries: original prompt, last raw response, validation error, attempt count
- Unit tests: schema generation for data classes, nullable fields, nested types, retry loop

**Done when:** a method returning a data class executes against a stub provider, parses the response, and retries correctly on a malformed response.

---

### M3 · First Providers
**Goal:** Real AI calls work. Local-first (Ollama) and cloud (Anthropic) both ship.

**Deliverables:**
- `aurora-ollama` module: HTTP client via `ktor-client`, coroutine-native, `/api/chat` endpoint
- `aurora-anthropic` module: Anthropic Messages API, `claude-*` model prefix auto-routing
- Provider registry: prefix-based resolution (`claude-*` → Anthropic, model-agnostic → Ollama fallback)
- Timeout configuration per provider and per operation
- Provider-level retry on 429 / 503 with exponential backoff, configurable `providerRetries`
- Integration tests against local Ollama (CI-skippable) and Anthropic API (env-gated)

**Done when:** a real `@AiService` interface executes against both Ollama and Anthropic and returns a typed data class.

**Phase 1 checkpoint:** the core value proposition works end-to-end. Everything after this is quality and reach.

---

## Phase 2 — Production-Ready

### M4 · Observability
**Goal:** Every AI call emits an OTel span automatically. Zero config if OTel is on the classpath.

**Deliverables:**
- `aurora-observability` module
- `ObservabilityInterceptor` wraps every provider call in an OTel span
- OTel GenAI semantic conventions attributes:

| Attribute | Source |
|---|---|
| `gen_ai.system` | Provider ID |
| `gen_ai.request.model` | `@Operation` model string |
| `gen_ai.response.model` | Actual model from response |
| `gen_ai.usage.input_tokens` | Provider response metadata |
| `gen_ai.usage.output_tokens` | Provider response metadata |
| `aurora.operation.interface` | Fully qualified interface name |
| `aurora.operation.method` | Method name |
| `aurora.retry.attempt` | Current attempt number |
| `aurora.structured.parse_success` | Boolean |

- Span events for structured output failures (not exceptions — events)
- Auto-detection: if `opentelemetry-api` is absent, no-op tracer used silently
- No-op path adds zero overhead when OTel is not present
- Tests: span attribute assertions using OTel SDK test utilities

**Done when:** a real provider call produces a span visible in a local Jaeger instance with all GenAI attributes populated.

---

### M5 · Standalone Module + Java API
**Goal:** Aurora works with zero framework. Java consumers have a first-class entry point.

**Deliverables:**
- `aurora-standalone` module: assembles core + engine + structured + observability
- Kotlin DSL builder:
```kotlin
val aurora = Aurora {
    provider(AnthropicProvider(apiKey = "..."))
    defaultModel("claude-sonnet-4-20250514")
    defaults {
        maxTokens = 2048
        temperature = 0.3
        timeout = 30.seconds
    }
}
val analyzer = aurora.create<InvoiceAnalyzer>()
```
- Java-friendly `Aurora.builder()` static entry point
- Blocking adapter generation: every `suspend fun` gets a `*Blocking` Java-callable counterpart
- `aurora-bom` module: bill of materials for consumers managing multiple Aurora artifacts
- README quickstart covering both Kotlin and Java standalone usage
- Example project: plain `main()` using Ollama locally, zero framework

**Done when:** a Java engineer can add Aurora to a non-Spring project and call AI operations with no framework dependency.

---

### M6 · Spring Adapter
**Goal:** Spring Boot users get zero-boilerplate injection of `@AiService` proxies.

**Deliverables:**
- `aurora-spring` module
- `AuroraAutoConfiguration`: classpath-triggered, registers proxies as Spring beans
- `application.yml` configuration namespace (`aurora.*`)
- Full YAML schema:
```yaml
aurora:
  default-model: claude-sonnet-4-20250514
  providers:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
    openai:
      api-key: ${OPENAI_API_KEY}
    ollama:
      base-url: http://localhost:11434
  defaults:
    max-tokens: 2048
    temperature: 0.3
    max-retries: 2
    timeout: PT30S
```
- `@EnableAurora` annotation for explicit opt-in (autoconfiguration is default)
- Spring Boot `ConfigurationProperties` with IDE autocompletion metadata
- Integration test: Spring Boot test context with `@AiService` bean injected and executing

**Done when:** a Spring Boot app with Aurora on the classpath injects `@AiService` interfaces with zero `@Bean` declarations.

**Phase 2 checkpoint:** Aurora is production-usable. Observable, framework-flexible, Java-compatible.

---

## Phase 3 — Ecosystem

### M7 · Testing Module
**Goal:** Engineers can test AI-dependent code without network calls, without Mockito, without HTTP stubs.

**Deliverables:**
- `aurora-testing` module
- `MockAiProvider` with Kotlin DSL:
```kotlin
val mock = MockAiProvider {
    onMethod("analyze") respondWith """{ "totalSpend": 1200.50 }"""
    onMethod("classify") respondWith """{ "category": "infrastructure" }"""
}
```
- `@MockAiResponse` annotation for Spring Boot test contexts
- Capture mode: record what was sent to the provider (prompt content, parameters)
- `AuroraAssertions` fluent API:
```kotlin
AuroraAssertions.assertThat(analyzer)
    .whenCalled("analyze")
    .emittedSpanWithAttribute("gen_ai.system", "anthropic")
    .andRetried(0)
    .andParsedSuccessfully()
```
- `SimulatedFailureProvider`: returns malformed JSON on demand to test retry logic
- Unit and integration tests for all assertion paths

**Done when:** an `@AiService` method can be tested in a standard JUnit 5 + coroutines test with zero external dependencies.

---

### M8 · Documentation, Live Proof, Publish
**Goal:** Aurora is publicly available on Maven Central with a README that sells itself.

**Deliverables:**

**Documentation:**
- README: one-sentence pitch, installation, quickstart (Kotlin + Java), comparison table vs LangChain4j
- `CONTRIBUTING.md`: how to add a provider, how to run tests, PR expectations
- `CHANGELOG.md`: v0.1.0 entry
- KDoc on all public API surface
- GitHub Wiki: structured output guide, observability guide, testing guide, provider configuration reference

**Live proof:**
- ddog-finops integration: replace ad-hoc Anthropic calls with Aurora `@AiService` interfaces
- Documented in README as "used in production by the author"
- This is the credibility anchor — not a toy example

**Publishing:**
- Maven Central via Sonatype OSSRH
- Group ID: `io.github.{username}` or custom domain if available
- Signed artifacts, sources JAR, javadoc JAR
- GitHub Actions CI: build, test, publish on tag

**GitHub hygiene:**
- Issue templates: bug report, feature request, new provider
- `good first issue` labels on non-critical tasks
- Topics: `kotlin`, `ai`, `llm`, `opentelemetry`, `spring-boot`, `jvm`

**Done when:** `implementation("io.aurora:aurora-standalone:0.1.0")` works from Maven Central.

**Phase 3 checkpoint:** Aurora is public. The work shifts from building to growing.

---

## Phase 4 — Growth (Post-Launch)

These are not scheduled. They activate when there is evidence of demand — GitHub stars, issues, or direct feedback.

### v2 Features (demand-gated)

**Streaming responses**
`Flow<String>` return type for token-by-token streaming. Requires provider-level SSE support. Anthropic and OpenAI both support it.

**Tool / function calling**
Model-initiated tool calls. The interface method becomes a tool the model can invoke. Significant design work — not an incremental change.

**Conversation memory**
Multi-turn stateful conversations. Thread-local or explicit `ConversationContext` parameter. Needed for chatbot-style use cases.

**KSP compile-time proxy generation**
Replace JDK Dynamic Proxy with KSP-generated implementations. Faster startup, GraalVM native image friendly, better error messages at compile time.

### New providers (community-gated)

- `aurora-openai` (v1 deferred, ship here if demand exists)
- `aurora-google` (Gemini)
- `aurora-azure-openai` (Azure OpenAI Service — natural fit given your background)
- `aurora-mistral`

### Framework adapters (community-gated)

- `aurora-quarkus`: CDI extension
- `aurora-micronaut`: Micronaut factory

### Potential monetization (if traction)

Aurora itself stays open source and free. If the library gains meaningful adoption, a hosted observability dashboard for Aurora-instrumented applications is a natural paid extension — Aurora already emits all the data needed. This is speculative and not a goal for v1.

---

## Constraints and Ground Rules

**Solo maintainer rules:**
- Never start M(n+1) before M(n) has passing tests
- One module at a time — no parallel feature branches
- If a milestone feels too large, split it before starting, not during
- The live proof (ddog-finops integration) is non-negotiable for launch — it's the difference between a library and a toy

**Scope discipline:**
- Any feature not in this plan requires a written justification before work starts
- "It would be cool" is not a justification
- "A user asked for it" is a justification

**Quality bar:**
- Every public API has KDoc before the milestone closes
- Every module has at least 80% unit test coverage before the milestone closes
- No milestone ships with a known bug in the happy path

---

## Timeline Estimate

| Phase | Milestones | Weekends | Calendar (1 weekend/week) |
|---|---|---|---|
| Foundation | M1–M3 | 3 | Weeks 1–3 |
| Production-ready | M4–M6 | 3 | Weeks 4–6 |
| Ecosystem | M7–M8 | 2 | Weeks 7–8 |
| **Public launch** | | | **~Week 8–9** |

Realistic with one focused weekend per week. Two missed weekends puts launch at week 10–11. That is still fast for a library of this scope.

---

*Aurora v0.1.0 target: 8–10 weekends from start of M1*
