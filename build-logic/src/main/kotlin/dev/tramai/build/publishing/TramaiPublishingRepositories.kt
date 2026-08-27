package dev.tramai.build.publishing

import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Tramai publication repository policy.
 *
 * Remote repository selection and the dedicated sovereignBundleLocal
 * verification repository. Moved from the root build script as behavior-
 * preserving extraction (9.2a); membership is NOT redesigned in this slice.
 */
object TramaiPublishingRepositories {

    const val TRAMAI_REMOTE_NAME = "tramaiRemote"
    const val SOVEREIGN_BUNDLE_LOCAL_NAME = "sovereignBundleLocal"

    /**
     * Modules excluded from the sovereign signed runtime bundle. The bundle is
     * the published manifest set minus modules outside its signed runtime scope.
     */
    val sovereignBundleExcludedProjectNames = setOf(
        "tramai-anthropic", "tramai-azure-openai", "tramai-bedrock", "tramai-deepseek", "tramai-embedding",
        "tramai-gemini", "tramai-memory", "tramai-observability", "tramai-ollama", "tramai-openai",
        "tramai-orchestration", "tramai-platform", "tramai-rag", "tramai-scheduler", "tramai-spring",
        "tramai-spring-provider-anthropic", "tramai-spring-provider-ollama", "tramai-spring-provider-openai",
        "tramai-spring-secrets-aws", "tramai-spring-secrets-file", "tramai-spring-secrets-vault", "tramai-testing",
        "tramai-vectorstore-chroma", "tramai-vectorstore-pgvector", "tramai-vectorstore-spi"
    )

    /**
     * Sovereign bundle module names for the root build: the publishable set
     * (published by the root build script as the `tramai.publishableModulePaths`
     * extra property, manifest-derived) minus the excluded set.
     */
    fun sovereignBundleModuleNames(rootProject: Project): Set<String> {
        val publishable = (rootProject.extensions.extraProperties.properties["tramai.publishableModulePaths"] as? Collection<*>)
            ?.map { it.toString().removePrefix(":") }
            .orEmpty()
        return publishable.toSet() - sovereignBundleExcludedProjectNames
    }

    /**
     * Always local file path for the sovereign bundle verification repository.
     * Never remote — this is only used for dry-run signing verification and
     * consumer smoke resolution. Do not use tramaiPublishReleaseUrl here.
     */
    fun sovereignBundleRepoUrl(rootProject: Project): Provider<String> =
        rootProject.layout.buildDirectory.dir("sovereign-runtime-release-verification-repo")
            .map { "file://${it.asFile.absolutePath}" }

    /**
     * Version-based remote repository selection (unchanged from the historical
     * root script): SNAPSHOT → snapshot URL → release URL fallback; release
     * version → release URL → snapshot URL fallback.
     */
    fun selectRepositoryUrl(version: String, releaseUrl: String?, snapshotUrl: String?): String? =
        if (version.endsWith("-SNAPSHOT")) snapshotUrl ?: releaseUrl else releaseUrl ?: snapshotUrl
}
