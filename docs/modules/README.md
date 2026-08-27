# Module Cards

Per-module architecture and navigation cards for TramAI. The authoritative module list, classification (layer, maturity, publishability, API stability, owner, release inclusion), and dependency policy live in the machine-readable manifest — cards link to it, they do not duplicate it.

- Classification / ownership: [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml)
- Dependency boundaries: [`config/quality/module-boundaries.yml`](../../config/quality/module-boundaries.yml)
- Generated module matrix (all 60 modules): [`docs/reference/module-matrix.md`](../reference/module-matrix.md)
- Root navigation map: [`ARCHITECTURE.md`](../../ARCHITECTURE.md)

## Card contract

Cards normalized under Epic 11.2b conform to the following architecture contract. C2b1 normalized 11 core/governance/persistence/observability/testing cards; **C2b2 normalized 20 non-Spring cards** (provider adapters, higher capabilities, vector stores, operations/observability, BOM). **C2b3a normalized `tramai-spring` (legacy facade) and created the Spring family + testing/consumer-support cards**; the remaining example/application cards and the documentation verifier follow in C2b3b.

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

Coverage is computed against the 60-module authoritative manifest (`module-catalog.yml`). `sovereign-runtime-module-matrix.md` is a reference document, not a module card, and is not counted.

| Metric | Count |
|--------|-------|
| Manifest modules | 60 |
| Module cards | 51 |
| Conforming cards (C2b1 + C2b2 + C2b3a) | 51 |
| Existing non-conforming | 0 |
| Missing cards | 9 (application/example slice — C2b3b) |
| Orphans | 0 |

### Cards (51)

- tramai-anthropic
- tramai-azure-openai
- tramai-bedrock
- tramai-bom
- tramai-core
- tramai-dashboard
- tramai-deepseek
- tramai-embedding
- tramai-engine
- tramai-gemini
- tramai-mcp
- tramai-memory
- tramai-memory-store
- tramai-observability
- tramai-ollama
- tramai-openai
- tramai-orchestration
- tramai-persistence-file
- tramai-persistence-jdbc
- tramai-platform
- tramai-rag
- tramai-scheduler
- tramai-security
- tramai-server
- tramai-sovereign
- tramai-spring
- tramai-spring-boot-starter
- tramai-spring-boot-starter-local-provider-openai
- tramai-spring-boot-starter-sovereign-ops
- tramai-spring-boot-starter-sovereign-ops-actuator
- tramai-spring-boot-starter-sovereign-ops-micrometer
- tramai-spring-boot-starter-sovereign-ops-observability
- tramai-spring-boot-starter-sovereign-ops-rest
- tramai-spring-boot-starter-sovereign-persistence-file
- tramai-spring-boot-starter-sovereign-persistence-jdbc
- tramai-spring-consumer-boundary
- tramai-spring-consumer-selective
- tramai-spring-core
- tramai-spring-provider-anthropic
- tramai-spring-provider-ollama
- tramai-spring-provider-openai
- tramai-spring-secrets-aws
- tramai-spring-secrets-file
- tramai-spring-secrets-vault
- tramai-spring-sovereign
- tramai-standalone
- tramai-structured
- tramai-testing
- tramai-vectorstore-chroma
- tramai-vectorstore-pgvector
- tramai-vectorstore-spi
