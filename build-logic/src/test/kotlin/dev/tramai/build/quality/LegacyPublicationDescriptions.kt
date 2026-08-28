package dev.tramai.build.quality

/**
 * Frozen oracle for the pre-B8 publication-description policy (9.2c-c).
 *
 * These are the exact strings the removed `projectDescription()` compatibility
 * function produced (24 explicit + generic fallback "Tramai module X." for the
 * remaining published modules). The catalog migration must keep every
 * published POM description byte-identical to this table — no copy editing in
 * B8. Delete this object once the catalog is the single source of truth and
 * no parity regression is possible.
 */
object LegacyPublicationDescriptions {

    private val explicit = mapOf(
        "tramai-core" to "Core annotations, request models, provider registry, and exception types for Tramai.",
        "tramai-embedding" to "Embedding model SPI with OpenAI and Ollama implementations for Tramai.",
        "tramai-engine" to "Runtime engine that turns annotated Tramai service interfaces into executable proxies.",
        "tramai-structured" to "Structured output schema generation, parsing, and validation support for Tramai.",
        "tramai-anthropic" to "Anthropic provider integration for Tramai.",
        "tramai-gemini" to "Google Gemini provider integration for Tramai.",
        "tramai-azure-openai" to "Azure OpenAI provider integration for Tramai.",
        "tramai-bedrock" to "AWS Bedrock provider integration for Tramai.",
        "tramai-deepseek" to "Deepseek provider integration for Tramai.",
        "tramai-memory" to "In-memory memory and state helpers for Tramai (memory primitives and adapters).",
        "tramai-openai" to "OpenAI and OpenAI-compatible provider integrations for Tramai.",
        "tramai-ollama" to "Ollama provider integration for Tramai.",
        "tramai-observability" to "OpenTelemetry-based observability hooks for Tramai.",
        "tramai-orchestration" to "Typed workflow orchestration and coordination layer for Tramai.",
        "tramai-platform" to "Platform services for plugins, tenancy, API keys, rate limiting, and audit logging.",
        "tramai-standalone" to "Minimal standalone runtime bundle for Tramai.",
        "tramai-sovereign" to "Secure embedded runtime profile for sovereign TramAI deployments.",
        "tramai-spring" to "Spring Boot auto-configuration and integration support for Tramai.",
        "tramai-testing" to "Testing utilities and deterministic assertion support for Tramai.",
        "tramai-bom" to "Bill of materials for aligning Tramai module versions.",
        "tramai-vectorstore-spi" to "Vector store SPI with data models and in-memory implementation for Tramai.",
        "tramai-vectorstore-chroma" to "ChromaDB vector store adapter for Tramai.",
        "tramai-vectorstore-pgvector" to "PostgreSQL pgvector vector store adapter for Tramai.",
        "tramai-rag" to "RAG pipeline: document loading, chunking, retrieval, and context injection for Tramai.",
    )

    /** The pre-B8 policy for a module name: explicit description or generic fallback. */
    fun descriptionFor(moduleName: String): String =
        explicit[moduleName] ?: "Tramai module ${moduleName.removePrefix("tramai-")}."

    /** Full map for every module name, mirroring the old `when` exhaustiveness. */
    fun byModule(): Map<String, String> {
        val names = explicit.keys + listOf(
            "tramai-persistence-file",
            "tramai-persistence-jdbc",
            "tramai-scheduler",
            "tramai-security",
            "tramai-spring-core",
            "tramai-spring-provider-anthropic",
            "tramai-spring-provider-ollama",
            "tramai-spring-provider-openai",
            "tramai-spring-secrets-aws",
            "tramai-spring-secrets-file",
            "tramai-spring-secrets-vault",
            "tramai-spring-boot-starter-local-provider-openai",
            "tramai-spring-sovereign",
            "tramai-spring-boot-starter",
            "tramai-spring-boot-starter-sovereign-ops",
            "tramai-spring-boot-starter-sovereign-ops-actuator",
            "tramai-spring-boot-starter-sovereign-ops-micrometer",
            "tramai-spring-boot-starter-sovereign-ops-observability",
            "tramai-spring-boot-starter-sovereign-ops-rest",
            "tramai-spring-boot-starter-sovereign-persistence-file",
            "tramai-spring-boot-starter-sovereign-persistence-jdbc",
        )
        return names.associateWith { descriptionFor(it) }
    }
}
