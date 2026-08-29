package dev.tramai.build.release

import dev.tramai.build.publishing.TramaiPublishingRepositories
import dev.tramai.build.quality.ModuleCatalog
import dev.tramai.build.quality.ModuleManifest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Registers the generic release verification tasks as typed DefaultTasks
 * (Epic 9.2b). Applied to the root project. Task names and observable
 * semantics are identical to the historical root build script.
 */
class TramaiReleaseVerificationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        registerVerifyPublicationMetadata(project)
        registerVerifyPublishedLocalArtifacts(project)
        registerVerifyReleasePublishInputs(project)
        registerVerifySignedPublicationBundle(project)
        registerVerifyReleaseReadiness(project)
    }

    /**
     * Publishable module set, resolved lazily at task realization. Reads the
     * root build's manifest-derived extra (`tramai.publishableModulePaths`)
     * first (the extra is set in the root body AFTER the plugins block), with
     * a direct module-catalog fallback. Returns empty for TestKit fixtures
     * that have neither.
     */
    private fun publishableModuleNames(project: Project): List<String> {
        val fromExtra =
            (project.rootProject.extensions.extraProperties.properties["tramai.publishableModulePaths"] as? Collection<*>)
                ?.map { it.toString().removePrefix(":") }
                .orEmpty()
        if (fromExtra.isNotEmpty()) return fromExtra.sorted()
        return runCatching { ModuleManifest.publishableModulePaths(project.rootDir) }
            .getOrDefault(emptyList())
            .map { it.removePrefix(":") }
            .sorted()
    }

    private fun jarPublicationModuleNames(project: Project): List<String> = publishableModuleNames(project) - "tramai-bom"

    /**
     * Publication descriptions, resolved from the module catalog (9.2c-c).
     * The publisher reads the catalog in its own plugin code path; the
     * verifier receives this map as a typed @Input (never calling publisher
     * code), so a defect in the publisher's lookup cannot change what the
     * verifier expects. Byte-parity with the legacy policy is pinned by the
     * D5 oracle test.
     *
     * Tolerant by design: entries missing from the catalog are simply not in
     * the map, and the verifier's requireNotNull fails closed at verification
     * time. A missing/broken catalog yields an error result with an empty
     * modules map; the verifier then fails closed for every publishable
     * module. The catalog parser itself also reports
     * MODULE_CATALOG_MISSING_DESCRIPTION and ModuleManifest.catalog() throws
     * on any catalog error, so the real repo fails at configuration already;
     * TestKit fixtures without a catalog still configure cleanly.
     */
    private fun catalogDescriptions(project: Project): Map<String, String> {
        val catalog = ModuleCatalog.fromRootDir(project.rootDir).parse()
        return publishableModuleNames(project)
            .mapNotNull { moduleName ->
                val description = catalog.modules[":$moduleName"]?.description?.takeIf { it.isNotBlank() }
                description?.let { moduleName to it }
            }.toMap()
    }

    private fun registerVerifyPublicationMetadata(project: Project) {
        project.tasks.register<VerifyPublicationMetadataTask>("verifyPublicationMetadata") {
            group = "verification"
            description = "Verifies generated Maven POM metadata for every publishable Tramai module."

            val publishableModuleNames = publishableModuleNames(project)
            val jarPublicationModuleNames = jarPublicationModuleNames(project)

            expectedGroup.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            expectedProjectUrl.set(project.providers.gradleProperty("tramaiProjectUrl").orElse("https://github.com/GionaGranchelli/tramAI"))
            expectedScmUrl.set(project.providers.gradleProperty("tramaiScmUrl").orElse("https://github.com/GionaGranchelli/tramAI.git"))
            expectedScmConnection.set(
                project.providers.gradleProperty("tramaiScmConnection").orElse("scm:git:https://github.com/GionaGranchelli/tramAI.git"),
            )
            expectedScmDeveloperConnection.set(
                project.providers
                    .gradleProperty(
                        "tramaiScmDeveloperConnection",
                    ).orElse("scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git"),
            )
            expectedLicenseName.set(project.providers.gradleProperty("tramaiLicenseName").orElse("Apache-2.0"))
            expectedLicenseUrl.set(
                project.providers.gradleProperty("tramaiLicenseUrl").orElse("https://www.apache.org/licenses/LICENSE-2.0.txt"),
            )
            expectedDeveloperId.set(project.providers.gradleProperty("tramaiDeveloperId").orElse("GionaGranchelli"))
            expectedDeveloperName.set(project.providers.gradleProperty("tramaiDeveloperName").orElse("Giona"))
            expectedDeveloperEmail.set(project.providers.gradleProperty("tramaiDeveloperEmail").orElse("opensource@giona.dev"))

            this.expectedDescriptions.set(catalogDescriptions(project))
            this.publishableModules.set(publishableModuleNames)
            this.jarPublicationModules.set(jarPublicationModuleNames)
            pomFiles.from(
                publishableModuleNames.map { moduleName ->
                    project.layout.projectDirectory.file("$moduleName/build/publications/maven/pom-default.xml")
                },
            )
            dependsOn(publishableModuleNames.map { ":$it:generatePomFileForMavenPublication" })
        }
    }

    private fun registerVerifyPublishedLocalArtifacts(project: Project) {
        project.tasks.register<VerifyPublishedArtifactsTask>("verifyPublishedLocalArtifacts") {
            group = "verification"
            description = "Publishes to Maven Local and verifies POM/module/jar/sources/javadoc artifacts for every Tramai module."

            val publishableModuleNames = publishableModuleNames(project)

            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            expectedGroup.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            this.publishableModules.set(publishableModuleNames)

            // Resolved through a Provider so the task never touches System.getProperty
            repositoryDirectory.fileProvider(
                project.providers
                    .systemProperty("user.home")
                    .map { home -> File(home, ".m2/repository/${expectedGroup.get().replace('.', '/')}") },
            )
            dependsOn(publishableModuleNames.map { ":$it:publishToMavenLocal" })
        }
    }

    private fun registerVerifyReleasePublishInputs(project: Project) {
        project.tasks.register<VerifyReleasePublishInputsTask>("verifyReleasePublishInputs") {
            group = "verification"
            description = "Verifies that the properties required for a real remote release publish are present."

            releaseUrlPresent.set(
                project.providers
                    .gradleProperty("tramaiPublishReleaseUrl")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            usernamePresent.set(
                project.providers
                    .gradleProperty("tramaiPublishUsername")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            passwordPresent.set(
                project.providers
                    .gradleProperty("tramaiPublishPassword")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            signingKeyPresent.set(
                project.providers
                    .gradleProperty("signingKey")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            signingPasswordPresent.set(
                project.providers
                    .gradleProperty("signingPassword")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            tramaiVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
        }
    }

    private fun registerVerifySignedPublicationBundle(project: Project) {
        project.tasks.register<VerifySignedPublicationBundleTask>("verifySignedPublicationBundle") {
            group = "verification"
            description = "Publishes to a configured file-based Maven repository and verifies generated signature files."

            val publishableModuleNames = publishableModuleNames(project)

            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            expectedGroup.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            this.publishableModules.set(publishableModuleNames)
            signingKeyPresent.set(
                project.providers
                    .gradleProperty("signingKey")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            signingPasswordPresent.set(
                project.providers
                    .gradleProperty("signingPassword")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )

            val version = project.providers.gradleProperty("tramaiVersion").orElse("0.5.0")
            val releaseUrl = project.providers.gradleProperty("tramaiPublishReleaseUrl")
            val snapshotUrl = project.providers.gradleProperty("tramaiPublishSnapshotUrl")
            repositoryUrl.set(
                project.provider {
                    TramaiPublishingRepositories.selectRepositoryUrl(
                        version.get(),
                        releaseUrl.orNull,
                        snapshotUrl.orNull,
                    )
                },
            )
            repositoryDirectory.fileProvider(
                repositoryUrl.map { url ->
                    if (url.isNullOrBlank()) {
                        File(
                            project.layout.projectDirectory
                                .dir("build/verify-signed-publication-repo")
                                .asFile.absolutePath,
                        )
                    } else {
                        File(java.net.URI(url))
                    }
                },
            )
            dependsOn(publishableModuleNames.map { ":$it:publish" })
        }
    }

    private fun registerVerifyReleaseReadiness(project: Project) {
        project.tasks.register("verifyReleaseReadiness") {
            group = "verification"
            description = "Runs the repo-local release verification checks for publication metadata and published artifacts."

            val jarPublicationModuleNames = jarPublicationModuleNames(project)
            dependsOn(
                jarPublicationModuleNames.map { ":$it:test" },
                "verifyPublicationMetadata",
                "verifyPublishedLocalArtifacts",
                "verifySovereignOpsObservabilityDocs",
            )
        }
    }
}
