# TramAI Documentation

Welcome to the **TramAI** documentation. 

TramAI is a structured-first AI integration library for the JVM, designed for backend engineers who need reliable, observable, and typed AI interactions in their Java or Kotlin applications.

---

## 🧭 Documentation Map

### 🟢 Getting Started
New to TramAI? Start here.
*   [**Getting Started Guide**](./guides/getting-started.md) — From zero to your first working AI-backed service.
*   [**30-Minute Quickstart**](./guides/quickstart.md) — Copy-paste paths for standalone and Spring Boot applications.
*   [**Tutorial: Build an Invoice Analyzer**](./guides/tutorial-invoice-analyzer.md) — One realistic end-to-end tutorial from dependencies to tests.
*   [**Maven Setup**](./guides/maven.md) — BOM usage and full Maven dependency snippets.
*   [**Quickstart: Standalone**](./guides/standalone-usage.md) — Using TramAI in plain Kotlin/Java applications.
*   [**Quickstart: Spring Boot**](./guides/spring-boot.md) — Seamless integration with the Spring ecosystem.

### 🟠 Choose Your Modules
If you are deciding what to depend on:
*   [**Getting Started: Choose Your Dependencies**](./guides/getting-started.md#choose-your-dependencies)
*   [**Module Overview**](./architecture/modules.md) — What each published module is responsible for.
*   [**Maven Setup**](./guides/maven.md) — Maven-first installation guidance.

### 🔵 Core Features
Deep dives into the primary capabilities of the engine.
*   [**Structured Output**](./guides/structured-output.md) — How TramAI handles schema generation and reliable object extraction.
*   [**Streaming**](./guides/streaming.md) — Raw text streaming, cancellation, and terminal error semantics.
*   [**Tool Calling**](./guides/tool-calling.md) — Engine-owned tool execution loops and registration patterns.
*   [**Providers & Routing**](./guides/providers.md) — Configuring OpenAI, Anthropic, Ollama, and fallback strategies.
*   [**Security & Hardening**](./guides/production-hardening.md) — PII masking, token budgets, and secret management.
*   [**Observability**](./guides/observability.md) — OpenTelemetry integration for tracing and metrics.

### 🟡 Advanced Usage
Taking your integration to the next level.
*   [**Testing TramAI Code**](./guides/testing.md) — Mocking AI responses and writing deterministic tests.
*   [**Native Image**](./guides/native-image.md) — Running AI services with GraalVM Native Image.
*   [**Orchestration**](./guides/orchestration.md) — Typed workflow coordination above the core engine.
*   [**Orchestration Persistence**](./guides/orchestration-persistence.md) — Checkpoint, resume, and lease-aware ownership.
*   [**Extending TramAI**](./guides/extending-tramai.md) — Writing custom providers, observers, or interceptors.

---

## 🏗️ Architecture & Philosophy

TramAI is built on a few core architectural principles:
*   **Boundaries over Chains**: We don't believe in complex "chains". We believe in clean service boundaries defined by interfaces.
*   **Types are Safety**: String-in/String-out is for scripts. Enterprise apps need typed objects and schemas.
*   **Engine-Owned Resilience**: Retries, timeouts, and circuit breakers belong in the infrastructure layer, not your business logic.

Learn more about our decisions in the [**ADR Index**](./adr/README.md).

---

## 📚 Reference

*   [**Annotation Reference**](./reference/annotations.md) — `@AiService`, `@Operation`, `@SystemPrompt`.
*   [**Standalone Builder Reference**](./reference/standalone-builder.md) — Exact `Tramai` builder methods and current behavior.
*   [**Configuration Reference**](./reference/configuration.md) — Spring Boot properties and Builder settings.
*   [**API Stability**](./reference/api-stability.md) — Stable vs experimental public surface for `0.1.x`.
*   [**Module Overview**](./architecture/modules.md) — The project's module structure.
*   [**Limitations**](./reference/limitations.md) — What TramAI is (and isn't) built for.
*   [**Release Validation**](./reference/release-validation.md) — Concrete proof points for the published `0.1.0` release path.
*   [**Roadmap**](./roadmap.md) — Our path to 1.0.

---

## 💬 Community & Support

*   **Issues**: Found a bug? [Open an issue on GitHub](https://github.com/GionaGranchelli/tramAI/issues).
*   **Contributing**: Want to help? Check out our [Contributing Guide](../CONTRIBUTING.md).
