# Module Cards

Per-module architecture and navigation cards for TramAI. The authoritative module list, classification (layer, maturity, publishability, API stability, owner, release inclusion), and dependency policy live in the machine-readable manifest — cards link to it, they do not duplicate it.

- Classification / ownership: [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml)
- Dependency boundaries: [`config/quality/module-boundaries.yml`](../../config/quality/module-boundaries.yml)
- Generated module matrix (all 58 modules): [`docs/reference/module-matrix.md`](../reference/module-matrix.md)
- Root navigation map: [`ARCHITECTURE.md`](../../ARCHITECTURE.md)

## Card contract

Cards normalized under Epic 11.2b conform to the following architecture contract. C2b1 normalized 11 core/governance/persistence/observability/testing cards; **C2b2 normalized 20 non-Spring cards** (provider adapters, higher capabilities, vector stores, operations/observability, BOM). Remaining cards are migrated in subsequent slices.

Each conforming card starts with an `## Architecture` section using these headings:

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

| Metric | Count |
|--------|-------|
| Manifest modules | 58 |
| Module cards | 32 |
| Conforming cards (C2b1 + C2b2) | 31 |
| Existing non-conforming | 1 (`tramai-spring`, Spring slice) |
| Missing cards | 26 |
| Orphans | 0 |

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
