# TramAI 0.6.0 — Module Dependency Graph

> **Baseline:** `v0.5.0` (`5d0ad69bb547223f8a5c8639b8398276d35eea50`)
> **Source:** Canonical filesystem mode (`MeasurementContext.fromDirectory`)
> **Schema version:** 1

This document is generated from the canonical v0.5.0 baseline.
Dependency edges require Gradle resolution and are deferred to PR #204.

## Module Inventory

| Module | Gradle path | Layer | Publishable |
|---|---|---|---:|
| `approval-resume` | `:examples:approval-resume` | applications-examples | no |
| `governed-workflow` | `:examples:governed-workflow` | applications-examples | no |
| `sovereign-document-intelligence` | `:examples:sovereign-document-intelligence` | applications-examples | no |
| `sovereign-offline-verification` | `:examples:sovereign-offline-verification` | applications-examples | no |
| `spring-sovereign-starter` | `:examples:spring-sovereign-starter` | applications-examples | no |
| `support-agent` | `:examples:support-agent` | applications-examples | no |
| `tool-governance` | `:examples:tool-governance` | applications-examples | no |
| `tramai-anthropic` | `:tramai-anthropic` | provider-adapters | yes |
| `tramai-azure-openai` | `:tramai-azure-openai` | provider-adapters | yes |
| `tramai-bedrock` | `:tramai-bedrock` | provider-adapters | yes |
| `tramai-bom` | `:tramai-bom` | core-contracts | yes |
| `tramai-core` | `:tramai-core` | core-contracts | yes |
| `tramai-dashboard` | `:tramai-dashboard` | operations-observability | no |
| `tramai-deepseek` | `:tramai-deepseek` | provider-adapters | yes |
| `tramai-embedding` | `:tramai-embedding` | higher-capabilities | yes |
| `tramai-engine` | `:tramai-engine` | runtime-execution | yes |
| `tramai-gemini` | `:tramai-gemini` | provider-adapters | yes |
| `tramai-mcp` | `:tramai-mcp` | operations-observability | no |
| `tramai-memory` | `:tramai-memory` | higher-capabilities | yes |
| `tramai-memory-store` | `:tramai-memory-store` | higher-capabilities | no |
| `tramai-observability` | `:tramai-observability` | operations-observability | yes |
| `tramai-ollama` | `:tramai-ollama` | provider-adapters | yes |
| `tramai-openai` | `:tramai-openai` | provider-adapters | yes |
| `tramai-orchestration` | `:tramai-orchestration` | runtime-execution | yes |
| `tramai-persistence-file` | `:tramai-persistence-file` | persistence | yes |
| `tramai-persistence-jdbc` | `:tramai-persistence-jdbc` | persistence | no |
| `tramai-platform` | `:tramai-platform` | operations-observability | yes |
| `tramai-rag` | `:tramai-rag` | higher-capabilities | yes |
| `tramai-scheduler` | `:tramai-scheduler` | higher-capabilities | yes |
| `tramai-security` | `:tramai-security` | governance-security | yes |
| `tramai-server` | `:tramai-server` | operations-observability | no |
| `tramai-sovereign` | `:tramai-sovereign` | governance-security | yes |
| `tramai-spring` | `:tramai-spring` | framework-integrations | yes |
| `tramai-spring-boot-starter-local-provider-openai` | `:tramai-spring-boot-starter-local-provider-openai` | framework-integrations | yes |
| `tramai-spring-boot-starter-sovereign` | `:tramai-spring-boot-starter-sovereign` | framework-integrations | yes |
| `tramai-spring-boot-starter-sovereign-ops` | `:tramai-spring-boot-starter-sovereign-ops` | framework-integrations | yes |
| `tramai-spring-boot-starter-sovereign-ops-actuator` | `:tramai-spring-boot-starter-sovereign-ops-actuator` | framework-integrations | yes |
| `tramai-spring-boot-starter-sovereign-ops-micrometer` | `:tramai-spring-boot-starter-sovereign-ops-micrometer` | framework-integrations | yes |
| `tramai-spring-boot-starter-sovereign-ops-observability` | `:tramai-spring-boot-starter-sovereign-ops-observability` | framework-integrations | yes |
| `tramai-spring-boot-starter-sovereign-ops-rest` | `:tramai-spring-boot-starter-sovereign-ops-rest` | framework-integrations | yes |
| `tramai-spring-boot-starter-sovereign-persistence-file` | `:tramai-spring-boot-starter-sovereign-persistence-file` | framework-integrations | yes |
| `tramai-spring-boot-starter-sovereign-persistence-jdbc` | `:tramai-spring-boot-starter-sovereign-persistence-jdbc` | framework-integrations | yes |
| `tramai-standalone` | `:tramai-standalone` | runtime-execution | yes |
| `tramai-structured` | `:tramai-structured` | runtime-execution | yes |
| `tramai-testing` | `:tramai-testing` | testing-support | yes |
| `tramai-vectorstore-chroma` | `:tramai-vectorstore-chroma` | higher-capabilities | yes |
| `tramai-vectorstore-pgvector` | `:tramai-vectorstore-pgvector` | higher-capabilities | yes |
| `tramai-vectorstore-spi` | `:tramai-vectorstore-spi` | higher-capabilities | yes |

**48 modules total.**

---

### Verification

Run `./gradlew verifyMaintainabilityBaseline` to check the current graph against the committed baseline.

> **Note:** Gradle-model dependency edge capture, cycle detection, and forbidden-layer enforcement
> are part of PR #204. The canonical baseline currently records module inventory only.
