# TramAI 0.6.0 — Module Dependency Graph

> **Baseline:** `v0.5.0` (`494bc6856bae046d3e6f6c3611f4c8d7eb14b955`)
> **Source:** `build/reports/maintainability/module-dependencies.json`
> **Schema version:** 1

This document is generated as a complete unit by `generateModuleDependencyGraph`.

## Module Inventory

| Module | Gradle path | Layer | Publishable |
|---|---|---|---:|
| `approval-resume` | `:examples:approval-resume` | unknown | no |
| `governed-workflow` | `:examples:governed-workflow` | unknown | no |
| `sovereign-document-intelligence` | `:examples:sovereign-document-intelligence` | unknown | no |
| `sovereign-offline-verification` | `:examples:sovereign-offline-verification` | unknown | no |
| `spring-sovereign-starter` | `:examples:spring-sovereign-starter` | unknown | no |
| `support-agent` | `:examples:support-agent` | unknown | no |
| `tool-governance` | `:examples:tool-governance` | unknown | no |
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

## Dependency Graph

```mermaid
graph TD
  tramai_anthropic --> tramai_core
  tramai_azure_openai --> tramai_core
  tramai_bedrock --> tramai_core
  tramai_deepseek --> tramai_core
  tramai_deepseek --> tramai_openai
  tramai_engine --> tramai_core
  tramai_engine --> tramai_security
  tramai_engine --> tramai_engine
  tramai_engine --> tramai_engine
  tramai_engine --> tramai_structured
  tramai_gemini --> tramai_core
  tramai_mcp --> tramai_server
  tramai_mcp --> tramai_structured
  tramai_memory --> tramai_core
  tramai_memory_store --> tramai_core
  tramai_observability --> tramai_core
  tramai_observability --> tramai_orchestration
  tramai_observability --> tramai_engine
  tramai_observability --> tramai_structured
  tramai_ollama --> tramai_core
  tramai_openai --> tramai_core
  tramai_orchestration --> tramai_core
  tramai_orchestration --> tramai_engine
  tramai_orchestration --> tramai_testing
  tramai_persistence_file --> tramai_core
  tramai_persistence_file --> tramai_engine
  tramai_persistence_file --> tramai_security
  tramai_persistence_jdbc --> tramai_core
  tramai_persistence_jdbc --> tramai_engine
  tramai_persistence_jdbc --> tramai_security
  tramai_platform --> tramai_orchestration
  tramai_platform --> tramai_server
  tramai_rag --> tramai_core
  tramai_rag --> tramai_embedding
  tramai_rag --> tramai_vectorstore_spi
  tramai_scheduler --> tramai_orchestration
  tramai_security --> tramai_core
  tramai_server --> tramai_orchestration
  tramai_server --> tramai_scheduler
  tramai_sovereign --> tramai_standalone
  tramai_sovereign --> tramai_security
  tramai_spring --> tramai_standalone
  tramai_spring --> tramai_security
  tramai_spring --> tramai_anthropic
  tramai_spring --> tramai_openai
  tramai_spring --> tramai_ollama
  tramai_spring --> tramai_security
  tramai_spring_boot_starter_local_provider_openai --> tramai_openai
  tramai_spring_boot_starter_sovereign --> tramai_core
  tramai_spring_boot_starter_sovereign --> tramai_security
  tramai_spring_boot_starter_sovereign --> tramai_sovereign
  tramai_spring_boot_starter_sovereign --> tramai_spring
  tramai_spring_boot_starter_sovereign_ops --> tramai_core
  tramai_spring_boot_starter_sovereign_ops --> tramai_security
  tramai_spring_boot_starter_sovereign_ops --> tramai_sovereign
  tramai_spring_boot_starter_sovereign_ops --> tramai_spring_boot_starter_sovereign
  tramai_spring_boot_starter_sovereign_ops --> tramai_engine
  tramai_spring_boot_starter_sovereign_ops_actuator --> tramai_spring_boot_starter_sovereign_ops
  tramai_spring_boot_starter_sovereign_ops_micrometer --> tramai_spring_boot_starter_sovereign_ops
  tramai_spring_boot_starter_sovereign_ops_observability --> tramai_spring_boot_starter_sovereign_ops
  tramai_spring_boot_starter_sovereign_ops_rest --> tramai_spring_boot_starter_sovereign_ops
  tramai_spring_boot_starter_sovereign_persistence_file --> tramai_spring_boot_starter_sovereign
  tramai_spring_boot_starter_sovereign_persistence_file --> tramai_persistence_file
  tramai_spring_boot_starter_sovereign_persistence_file --> tramai_spring_boot_starter_sovereign_ops
  tramai_spring_boot_starter_sovereign_persistence_jdbc --> tramai_spring_boot_starter_sovereign
  tramai_spring_boot_starter_sovereign_persistence_jdbc --> tramai_spring_boot_starter_sovereign_ops
  tramai_spring_boot_starter_sovereign_persistence_jdbc --> tramai_persistence_jdbc
  tramai_spring_boot_starter_sovereign_persistence_jdbc --> tramai_security
  tramai_spring_boot_starter_sovereign_persistence_jdbc --> tramai_spring_boot_starter_sovereign_ops_actuator
  tramai_standalone --> tramai_core
  tramai_standalone --> tramai_engine
  tramai_standalone --> tramai_structured
  tramai_standalone --> tramai_testing
  tramai_structured --> tramai_core
  tramai_testing --> tramai_core
  tramai_testing --> tramai_standalone
  tramai_vectorstore_chroma --> tramai_vectorstore_spi
  tramai_vectorstore_pgvector --> tramai_vectorstore_spi
  approval_resume --> tramai_spring_boot_starter_sovereign
  approval_resume --> tramai_spring
  approval_resume --> tramai_spring_boot_starter_sovereign_persistence_jdbc
  approval_resume --> tramai_spring_boot_starter_sovereign_ops
  approval_resume --> tramai_engine
  governed_workflow --> tramai_orchestration
  sovereign_document_intelligence --> tramai_sovereign
  sovereign_document_intelligence --> tramai_security
  sovereign_document_intelligence --> tramai_core
  sovereign_document_intelligence --> tramai_engine
  sovereign_offline_verification --> tramai_sovereign
  sovereign_offline_verification --> tramai_security
  sovereign_offline_verification --> tramai_core
  sovereign_offline_verification --> tramai_engine
  spring_sovereign_starter --> tramai_spring_boot_starter_sovereign
  spring_sovereign_starter --> tramai_spring
  spring_sovereign_starter --> tramai_spring_boot_starter_sovereign_persistence_jdbc
  spring_sovereign_starter --> tramai_spring_boot_starter_sovereign_ops
  spring_sovereign_starter --> tramai_spring_boot_starter_sovereign_ops_rest
  spring_sovereign_starter --> tramai_openai
  spring_sovereign_starter --> tramai_spring_boot_starter_local_provider_openai
  spring_sovereign_starter --> tramai_engine
  support_agent --> tramai_standalone
  support_agent --> tramai_ollama
  support_agent --> tramai_testing
  tool_governance --> tramai_bom
  tool_governance --> tramai_engine
  tool_governance --> tramai_structured
  tool_governance --> tramai_security
```

## Dependency Edges

| From | To | Scope |
|---|---|---|
| `approval-resume` | `tramai-engine` | implementation |
| `approval-resume` | `tramai-spring` | implementation |
| `approval-resume` | `tramai-spring-boot-starter-sovereign` | implementation |
| `approval-resume` | `tramai-spring-boot-starter-sovereign-ops` | implementation |
| `approval-resume` | `tramai-spring-boot-starter-sovereign-persistence-jdbc` | implementation |
| `governed-workflow` | `tramai-orchestration` | implementation |
| `sovereign-document-intelligence` | `tramai-core` | implementation |
| `sovereign-document-intelligence` | `tramai-engine` | implementation |
| `sovereign-document-intelligence` | `tramai-security` | implementation |
| `sovereign-document-intelligence` | `tramai-sovereign` | implementation |
| `sovereign-offline-verification` | `tramai-core` | implementation |
| `sovereign-offline-verification` | `tramai-engine` | implementation |
| `sovereign-offline-verification` | `tramai-security` | implementation |
| `sovereign-offline-verification` | `tramai-sovereign` | implementation |
| `spring-sovereign-starter` | `tramai-engine` | implementation |
| `spring-sovereign-starter` | `tramai-openai` | implementation |
| `spring-sovereign-starter` | `tramai-spring` | implementation |
| `spring-sovereign-starter` | `tramai-spring-boot-starter-local-provider-openai` | implementation |
| `spring-sovereign-starter` | `tramai-spring-boot-starter-sovereign` | implementation |
| `spring-sovereign-starter` | `tramai-spring-boot-starter-sovereign-ops` | implementation |
| `spring-sovereign-starter` | `tramai-spring-boot-starter-sovereign-ops-rest` | implementation |
| `spring-sovereign-starter` | `tramai-spring-boot-starter-sovereign-persistence-jdbc` | implementation |
| `support-agent` | `tramai-ollama` | implementation |
| `support-agent` | `tramai-standalone` | implementation |
| `support-agent` | `tramai-testing` | implementation |
| `tool-governance` | `tramai-bom` | implementation |
| `tool-governance` | `tramai-engine` | implementation |
| `tool-governance` | `tramai-security` | implementation |
| `tool-governance` | `tramai-structured` | implementation |
| `tramai-anthropic` | `tramai-core` | api |
| `tramai-azure-openai` | `tramai-core` | api |
| `tramai-bedrock` | `tramai-core` | api |
| `tramai-deepseek` | `tramai-core` | api |
| `tramai-deepseek` | `tramai-openai` | api |
| `tramai-engine` | `tramai-core` | api |
| `tramai-engine` | `tramai-engine` | api |
| `tramai-engine` | `tramai-engine` | implementation |
| `tramai-engine` | `tramai-security` | implementation |
| `tramai-engine` | `tramai-structured` | implementation |
| `tramai-gemini` | `tramai-core` | api |
| `tramai-mcp` | `tramai-server` | api |
| `tramai-mcp` | `tramai-structured` | implementation |
| `tramai-memory` | `tramai-core` | api |
| `tramai-memory-store` | `tramai-core` | api |
| `tramai-observability` | `tramai-core` | api |
| `tramai-observability` | `tramai-engine` | implementation |
| `tramai-observability` | `tramai-orchestration` | implementation |
| `tramai-observability` | `tramai-structured` | implementation |
| `tramai-ollama` | `tramai-core` | api |
| `tramai-openai` | `tramai-core` | api |
| `tramai-orchestration` | `tramai-core` | api |
| `tramai-orchestration` | `tramai-engine` | implementation |
| `tramai-orchestration` | `tramai-testing` | implementation |
| `tramai-persistence-file` | `tramai-core` | api |
| `tramai-persistence-file` | `tramai-engine` | api |
| `tramai-persistence-file` | `tramai-security` | api |
| `tramai-persistence-jdbc` | `tramai-core` | api |
| `tramai-persistence-jdbc` | `tramai-engine` | api |
| `tramai-persistence-jdbc` | `tramai-security` | api |
| `tramai-platform` | `tramai-orchestration` | api |
| `tramai-platform` | `tramai-server` | implementation |
| `tramai-rag` | `tramai-core` | api |
| `tramai-rag` | `tramai-embedding` | api |
| `tramai-rag` | `tramai-vectorstore-spi` | api |
| `tramai-scheduler` | `tramai-orchestration` | api |
| `tramai-security` | `tramai-core` | api |
| `tramai-server` | `tramai-orchestration` | api |
| `tramai-server` | `tramai-scheduler` | implementation |
| `tramai-sovereign` | `tramai-security` | api |
| `tramai-sovereign` | `tramai-standalone` | api |
| `tramai-spring` | `tramai-anthropic` | implementation |
| `tramai-spring` | `tramai-ollama` | implementation |
| `tramai-spring` | `tramai-openai` | implementation |
| `tramai-spring` | `tramai-security` | compileOnly |
| `tramai-spring` | `tramai-security` | implementation |
| `tramai-spring` | `tramai-standalone` | api |
| `tramai-spring-boot-starter-local-provider-openai` | `tramai-openai` | api |
| `tramai-spring-boot-starter-sovereign` | `tramai-core` | api |
| `tramai-spring-boot-starter-sovereign` | `tramai-security` | api |
| `tramai-spring-boot-starter-sovereign` | `tramai-sovereign` | api |
| `tramai-spring-boot-starter-sovereign` | `tramai-spring` | implementation |
| `tramai-spring-boot-starter-sovereign-ops` | `tramai-core` | api |
| `tramai-spring-boot-starter-sovereign-ops` | `tramai-engine` | implementation |
| `tramai-spring-boot-starter-sovereign-ops` | `tramai-security` | api |
| `tramai-spring-boot-starter-sovereign-ops` | `tramai-sovereign` | api |
| `tramai-spring-boot-starter-sovereign-ops` | `tramai-spring-boot-starter-sovereign` | api |
| `tramai-spring-boot-starter-sovereign-ops-actuator` | `tramai-spring-boot-starter-sovereign-ops` | api |
| `tramai-spring-boot-starter-sovereign-ops-micrometer` | `tramai-spring-boot-starter-sovereign-ops` | api |
| `tramai-spring-boot-starter-sovereign-ops-observability` | `tramai-spring-boot-starter-sovereign-ops` | api |
| `tramai-spring-boot-starter-sovereign-ops-rest` | `tramai-spring-boot-starter-sovereign-ops` | api |
| `tramai-spring-boot-starter-sovereign-persistence-file` | `tramai-persistence-file` | api |
| `tramai-spring-boot-starter-sovereign-persistence-file` | `tramai-spring-boot-starter-sovereign` | api |
| `tramai-spring-boot-starter-sovereign-persistence-file` | `tramai-spring-boot-starter-sovereign-ops` | api |
| `tramai-spring-boot-starter-sovereign-persistence-jdbc` | `tramai-persistence-jdbc` | api |
| `tramai-spring-boot-starter-sovereign-persistence-jdbc` | `tramai-security` | api |
| `tramai-spring-boot-starter-sovereign-persistence-jdbc` | `tramai-spring-boot-starter-sovereign` | api |
| `tramai-spring-boot-starter-sovereign-persistence-jdbc` | `tramai-spring-boot-starter-sovereign-ops` | api |
| `tramai-spring-boot-starter-sovereign-persistence-jdbc` | `tramai-spring-boot-starter-sovereign-ops-actuator` | implementation |
| `tramai-standalone` | `tramai-core` | api |
| `tramai-standalone` | `tramai-engine` | api |
| `tramai-standalone` | `tramai-structured` | api |
| `tramai-standalone` | `tramai-testing` | implementation |
| `tramai-structured` | `tramai-core` | api |
| `tramai-testing` | `tramai-core` | api |
| `tramai-testing` | `tramai-standalone` | implementation |
| `tramai-vectorstore-chroma` | `tramai-vectorstore-spi` | api |
| `tramai-vectorstore-pgvector` | `tramai-vectorstore-spi` | api |

## Known Cycles

- `tramai-engine -> tramai-engine`
- `tramai-engine -> tramai-engine`
- `tramai-testing -> tramai-standalone -> tramai-testing`

## Verification

Run `./gradlew verifyModuleDependencyGraph` to check the current graph.
