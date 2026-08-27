package dev.tramai.build.publishing

import org.gradle.api.Project

/**
 * Resolved publication metadata for Tramai modules.
 *
 * Defaults mirror the historical root build script exactly — 9.2c moves
 * publication metadata to manifest-derived values deliberately.
 */
data class TramaiPublicationMetadata(
    val projectUrl: String,
    val scmUrl: String,
    val scmConnection: String,
    val scmDeveloperConnection: String,
    val licenseName: String,
    val licenseUrl: String,
    val developerId: String,
    val developerName: String,
    val developerEmail: String,
) {
    companion object {
        fun from(project: Project): TramaiPublicationMetadata {
            fun property(name: String, default: String): String =
                project.providers.gradleProperty(name).orElse(default).get()
            return TramaiPublicationMetadata(
                projectUrl = property("tramaiProjectUrl", "https://github.com/GionaGranchelli/tramAI"),
                scmUrl = property("tramaiScmUrl", "https://github.com/GionaGranchelli/tramAI.git"),
                scmConnection = property("tramaiScmConnection", "scm:git:https://github.com/GionaGranchelli/tramAI.git"),
                scmDeveloperConnection = property("tramaiScmDeveloperConnection", "scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git"),
                licenseName = property("tramaiLicenseName", "Apache-2.0"),
                licenseUrl = property("tramaiLicenseUrl", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
                developerId = property("tramaiDeveloperId", "GionaGranchelli"),
                developerName = property("tramaiDeveloperName", "Giona"),
                developerEmail = property("tramaiDeveloperEmail", "opensource@giona.dev"),
            )
        }
    }
}

/**
 * Compatibility description policy — moved verbatim from the historical root
 * build script. Values are deliberately NOT changed in 9.2a; manifest-derived
 * descriptions land in 9.2c.
 */
fun projectDescription(projectName: String): String = when (projectName) {
    "tramai-core" -> "Core annotations, request models, provider registry, and exception types for Tramai."
    "tramai-embedding" -> "Embedding model SPI with OpenAI and Ollama implementations for Tramai."
    "tramai-engine" -> "Runtime engine that turns annotated Tramai service interfaces into executable proxies."
    "tramai-structured" -> "Structured output schema generation, parsing, and validation support for Tramai."
    "tramai-anthropic" -> "Anthropic provider integration for Tramai."
    "tramai-gemini" -> "Google Gemini provider integration for Tramai."
    "tramai-azure-openai" -> "Azure OpenAI provider integration for Tramai."
    "tramai-bedrock" -> "AWS Bedrock provider integration for Tramai."
    "tramai-deepseek" -> "Deepseek provider integration for Tramai."
    "tramai-memory" -> "In-memory memory and state helpers for Tramai (memory primitives and adapters)."
    "tramai-openai" -> "OpenAI and OpenAI-compatible provider integrations for Tramai."
    "tramai-ollama" -> "Ollama provider integration for Tramai."
    "tramai-observability" -> "OpenTelemetry-based observability hooks for Tramai."
    "tramai-orchestration" -> "Typed workflow orchestration and coordination layer for Tramai."
    "tramai-platform" -> "Platform services for plugins, tenancy, API keys, rate limiting, and audit logging."
    "tramai-standalone" -> "Minimal standalone runtime bundle for Tramai."
    "tramai-sovereign" -> "Secure embedded runtime profile for sovereign TramAI deployments."
    "tramai-spring" -> "Spring Boot auto-configuration and integration support for Tramai."
    "tramai-testing" -> "Testing utilities and deterministic assertion support for Tramai."
    "tramai-bom" -> "Bill of materials for aligning Tramai module versions."
    "tramai-vectorstore-spi" -> "Vector store SPI with data models and in-memory implementation for Tramai."
    "tramai-vectorstore-chroma" -> "ChromaDB vector store adapter for Tramai."
    "tramai-vectorstore-pgvector" -> "PostgreSQL pgvector vector store adapter for Tramai."
    "tramai-rag" -> "RAG pipeline: document loading, chunking, retrieval, and context injection for Tramai."
    else -> "Tramai module ${projectName.removePrefix("tramai-")}."
}
