# Tramai Documentation

This documentation set covers Tramai as it exists in the repository today.

Tramai is a structured-first AI integration library for the JVM. Its core model is:

1. define an interface with `@AiService`
2. annotate methods with `@Operation`
3. map models to providers explicitly
4. call the interface as normal Kotlin or Java code

Tramai is already usable for real development, but it is still an early-stage project. The guides below document the implemented features and call out the current limitations where they matter.

## Start Here

If you are new to the project, read these in order:

1. [Getting Started](./guides/getting-started.md)
2. [Standalone Usage](./guides/standalone-usage.md)
3. [Providers and Model Routing](./guides/providers.md)
4. [Structured Output](./guides/structured-output.md)
5. [Testing Tramai Code](./guides/testing.md)
6. [Native Image](./guides/native-image.md)

If you are using Spring Boot instead of the standalone builder:

1. [Getting Started](./guides/getting-started.md)
2. [Spring Boot Integration](./guides/spring-boot.md)
3. [Providers and Model Routing](./guides/providers.md)
4. [Structured Output](./guides/structured-output.md)

## Guides

- [Getting Started](./guides/getting-started.md)
- [Standalone Usage](./guides/standalone-usage.md)
- [Spring Boot Integration](./guides/spring-boot.md)
- [Providers and Model Routing](./guides/providers.md)
- [Structured Output](./guides/structured-output.md)
- [Observability](./guides/observability.md)
- [Native Image](./guides/native-image.md)
- [Orchestration Persistence](./guides/orchestration-persistence.md)
- [Testing Tramai Code](./guides/testing.md)
- [Common Use Cases](./guides/use-cases.md)
- [Extending Tramai](./guides/extending-tramai.md)

## Reference

- [Configuration Reference](./reference/configuration.md)
- [Annotation Reference](./reference/annotations.md)
- [Provider Reference](./reference/providers.md)
- [Testing Reference](./reference/testing.md)
- [Release 0.1.0 Scope and Checklist](./reference/release-0.1.0.md)
- [Current Limitations](./reference/limitations.md)

## Architecture and Delivery Docs

These are useful once you understand how to use Tramai and want to understand why it is shaped this way:

- [Architecture Overview](./architecture/overview.md)
- [Module Overview](./architecture/modules.md)
- [Roadmap Summary](./roadmap.md)
- [Specs Index](./specs/README.md)
- [ADR Index](./adr/README.md)
- [Execution Board](./board/board.md)

## What Is Implemented Today

The repository currently contains working implementations for:

- `tramai-core`
- `tramai-engine`
- `tramai-structured`
- `tramai-anthropic`
- `tramai-openai`
- `tramai-ollama`
- `tramai-observability`
- `tramai-orchestration`
- `tramai-standalone`
- `tramai-spring`
- `tramai-testing`
- `tramai-bom`

## What This Documentation Tries To Do

This documentation is intentionally practical:

- how to set Tramai up from source
- how to choose modules
- how to define services
- how to configure providers
- how to build structured workflows
- how to integrate with Spring Boot
- how to test AI-dependent code without live network calls
- how to understand current gaps before you depend on a feature
