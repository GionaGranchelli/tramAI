# Guides

User-facing how-to documentation for Tramai.

## Core Guides

- [Getting Started](./getting-started.md)
- [30-Minute Quickstart](./quickstart.md)
- [Tutorial: Build an Invoice Analyzer](./tutorial-invoice-analyzer.md)
- [Maven Setup](./maven.md)
- [Standalone Usage](./standalone-usage.md)
- [Spring Boot Integration](./spring-boot.md)
- [Providers and Model Routing](./providers.md)
- [Structured Output](./structured-output.md)
- [Tool Calling](./tool-calling.md)
- [Streaming](./streaming.md)
- [Observability](./observability.md)
- [Testing Tramai Code](./testing.md)
- [Production Hardening](./production-hardening.md)
- [Secure Defaults Migration](./secure-defaults-migration.md)
- [Common Use Cases](./use-cases.md)
- [Extending Tramai](./extending-tramai.md)
- [Native Image](./native-image.md)
- [Orchestration](./orchestration.md)
- [Orchestration Persistence](./orchestration-persistence.md)

## Runtime & Platform Guides

- [Workflow Scheduling](./scheduling.md)
- [Workflow Server](./server.md)
- [MCP Integration](./mcp.md)
- [Platform Operations](./platform.md)

## Suggested Order

1. start with `getting-started`
2. if needed, read `quickstart` for a copy-paste setup
3. read `tutorial-invoice-analyzer` for one end-to-end example
4. if you use Maven, read `maven`
5. choose your dependency setup from `getting-started`
6. choose `standalone-usage` or `spring-boot`
7. read `providers`
8. read `structured-output`, `tool-calling`, or `streaming` when those operation shapes matter
9. add `testing`, `observability`, and `production-hardening` as the code moves toward production
10. read `secure-defaults-migration` when upgrading older configuration
11. add `orchestration` when you need explicit persisted workflows
12. add `scheduling`, `server`, `mcp`, or `platform` only when you need operational runtime features
