# Module Cards

Per-module architecture and navigation cards for TramAI. The authoritative module list, classification (layer, maturity, publishability, API stability, owner, release inclusion), and dependency policy live in the machine-readable manifest — cards link to it, they do not duplicate it.

- Classification / ownership: [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml)
- Dependency boundaries: [`config/quality/module-boundaries.yml`](../../config/quality/module-boundaries.yml)
- Generated module matrix (all 58 modules): [`docs/reference/module-matrix.md`](../reference/module-matrix.md)
- Root navigation map: [`ARCHITECTURE.md`](../../ARCHITECTURE.md)

## Card contract

Each module card starts with an `## Architecture` section using these headings:

1. Responsibility
2. Public entry points
3. Internal extension points
4. Significant dependencies
5. Lifecycle ownership
6. Thread-safety and concurrency
7. Failure semantics
8. Contract tests / TCKs
9. Do not
10. Related architecture

Below the architecture section, cards may carry long-form usage/design content (quick start, configuration reference, threat model, etc.) — that content is preserved, not replaced.

## Coverage

Coverage is computed against the 58-module authoritative manifest (`module-catalog.yml`). `sovereign-runtime-module-matrix.md` is a reference document, not a module card, and is not counted.

| Slice | Cards added | Total cards | Remaining |
|-------|-------------|-------------|-----------|
| Baseline (pre-11.2b) | — | 30 | 28 |
| 11.2b1 (core/governance/persistence/observability/testing) | 2 | **32** | 26 |

### Cards (32)

- tramai-bom
- tramai-core
- tramai-engine
- tramai-structured
- tramai-standalone
- tramai-orchestration
- tramai-sovereign
- tramai-security
- tramai-persistence-file
- tramai-persistence-jdbc
- tramai-observability
- tramai-testing
- tramai-anthropic
- tramai-azure-openai
- tramai-bedrock
- tramai-deepseek
- tramai-gemini
- tramai-ollama
- tramai-openai
- tramai-embedding
- tramai-memory
- tramai-memory-store
- tramai-rag
- tramai-scheduler
- tramai-vectorstore-spi
- tramai-vectorstore-chroma
- tramai-vectorstore-pgvector
- tramai-platform
- tramai-server
- tramai-mcp
- tramai-dashboard
- tramai-spring

### Missing (26, next slices)

- tramai-spring-core
- tramai-spring-sovereign
- tramai-spring-boot-starter
- tramai-spring-boot-starter-local-provider-openai
- tramai-spring-boot-starter-sovereign-ops (+ actuator, micrometer, observability, rest)
- tramai-spring-boot-starter-sovereign-persistence-file
- tramai-spring-boot-starter-sovereign-persistence-jdbc
- tramai-spring-provider-anthropic / ollama / openai
- tramai-spring-secrets-aws / file / vault
- tramai-spring-consumer-boundary
- tramai-spring-consumer-selective
- examples (approval-resume, governed-workflow, sovereign-document-intelligence, sovereign-offline-verification, spring-sovereign-starter, support-agent, tool-governance)
