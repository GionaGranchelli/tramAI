# TramAI Documentation

Welcome to the **TramAI** documentation. 

TramAI is a structured-first AI integration library for the JVM, designed for backend engineers who need reliable, observable, and typed AI interactions in their Java or Kotlin applications.

---

## 🧭 Documentation Map

### 🟢 Getting Started
New to TramAI? Start here.
*   [**Getting Started Guide**](./guides/getting-started.md) — From zero to your first AI-backed service in 5 minutes.
*   [**Quickstart: Standalone**](./guides/standalone-usage.md) — Using TramAI in plain Kotlin/Java applications.
*   [**Quickstart: Spring Boot**](./guides/spring-boot.md) — Seamless integration with the Spring ecosystem.

### 🔵 Core Features
Deep dives into the primary capabilities of the engine.
*   [**Structured Output**](./guides/structured-output.md) — How TramAI handles schema generation and reliable object extraction.
*   [**Providers & Routing**](./guides/providers.md) — Configuring OpenAI, Anthropic, Ollama, and fallback strategies.
*   [**Security & Hardening**](./guides/production-hardening.md) — PII masking, token budgets, and secret management.
*   [**Observability**](./guides/observability.md) — OpenTelemetry integration for tracing and metrics.

### 🟡 Advanced Usage
Taking your integration to the next level.
*   [**Testing TramAI Code**](./guides/testing.md) — Mocking AI responses and writing deterministic tests.
*   [**Native Image**](./guides/native-image.md) — Running AI services with GraalVM Native Image.
*   [**Orchestration**](./guides/orchestration-persistence.md) — (Experimental) Multi-step workflow state management.
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
*   [**Configuration Reference**](./reference/configuration.md) — Spring Boot properties and Builder settings.
*   [**Module Overview**](./architecture/modules.md) — The project's module structure.
*   [**Limitations**](./reference/limitations.md) — What TramAI is (and isn't) built for.
*   [**Roadmap**](./roadmap.md) — Our path to 1.0.

---

## 💬 Community & Support

*   **Issues**: Found a bug? [Open an issue on GitHub](https://github.com/GionaGranchelli/tramAI/issues).
*   **Contributing**: Want to help? Check out our [Contributing Guide](../CONTRIBUTING.md).
