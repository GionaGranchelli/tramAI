package dev.tramai.build.publishing

import dev.tramai.build.quality.ModuleCatalog
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.plugins.signing.SigningExtension

/**
 * Tramai publishing convention plugin.
 *
 * Applies Maven publication/signing configuration to subprojects that apply
 * `java-library` or `java-platform`, preserving the historical root build
 * script behavior exactly (9.2a extraction, behavior-preserving).
 */
class TramaiPublishingPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java-library") {
            project.pluginManager.apply("maven-publish")
            project.pluginManager.apply("signing")

            project.extensions.configure(JavaPluginExtension::class.java) {
                withJavadocJar()
            }
            project.tasks.withType(Javadoc::class.java).configureEach {
                isFailOnError = false
            }

            configurePublication(project, componentName = "java")
            configureSovereignBundleLocalRepo(project)
        }

        project.pluginManager.withPlugin("java-platform") {
            project.pluginManager.apply("maven-publish")
            project.pluginManager.apply("signing")

            configurePublication(project, componentName = "javaPlatform")
            configureSovereignBundleLocalRepo(project)
        }
    }

    private fun configurePublication(project: Project, componentName: String) {
        val metadata = TramaiPublicationMetadata.from(project)

        project.extensions.configure(PublishingExtension::class.java) {
            val publication = publications.create("maven", MavenPublication::class.java)
            publication.from(project.components.getByName(componentName))
            publication.artifactId = project.name

            publication.pom {
                name.set(project.name)
                catalogDescription(project)?.let { description.set(it) }
                url.set(metadata.projectUrl)

                licenses {
                    license {
                        name.set(metadata.licenseName)
                        url.set(metadata.licenseUrl)
                    }
                }
                developers {
                    developer {
                        id.set(metadata.developerId)
                        name.set(metadata.developerName)
                        email.set(metadata.developerEmail)
                    }
                }
                scm {
                    url.set(metadata.scmUrl)
                    connection.set(metadata.scmConnection)
                    developerConnection.set(metadata.scmDeveloperConnection)
                }
            }

            val releaseRepositoryUrl = project.providers.gradleProperty("tramaiPublishReleaseUrl").orNull
            val snapshotRepositoryUrl = project.providers.gradleProperty("tramaiPublishSnapshotUrl").orNull
            val targetRepositoryUrl = TramaiPublishingRepositories.selectRepositoryUrl(
                project.version.toString(),
                releaseRepositoryUrl,
                snapshotRepositoryUrl,
            )

            if (!targetRepositoryUrl.isNullOrBlank()) {
                repositories {
                    maven {
                        name = TramaiPublishingRepositories.TRAMAI_REMOTE_NAME
                        url = project.uri(targetRepositoryUrl)
                        if (!targetRepositoryUrl.startsWith("file:")) {
                            credentials {
                                username = project.providers.gradleProperty("tramaiPublishUsername").orNull
                                password = project.providers.gradleProperty("tramaiPublishPassword").orNull
                            }
                        }
                    }
                }
            }
        }

        project.extensions.configure(SigningExtension::class.java) {
            val signingKey = project.providers.gradleProperty("signingKey").orNull
            val signingPassword = project.providers.gradleProperty("signingPassword").orNull
            if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
                useInMemoryPgpKeys(null, signingKey, signingPassword)
                sign(project.extensions.getByType(PublishingExtension::class.java).publications)
            }
        }
    }

    /**
     * Reads the publication description from the module catalog (9.2c-c).
     *
     * Published modules must carry a non-blank description (enforced by the
     * catalog parser via MODULE_CATALOG_MISSING_DESCRIPTION), so a missing
     * description here is a real regression — but the parser already reported
     * it, so this lookup returns null and the POM simply omits the element
     * rather than throwing a second, confusing error. Internal/excluded
     * modules may legitimately have no description and also get null.
     */
    private fun catalogDescription(project: Project): String? {
        val catalog = ModuleCatalog(project.rootProject.projectDir).parse()
        return catalog.modules[":${project.name}"]?.description?.takeIf { it.isNotBlank() }
    }

    private fun configureSovereignBundleLocalRepo(project: Project) {
        val sovereignBundleModules = TramaiPublishingRepositories.sovereignBundleModuleNames(project.rootProject)
        if (project.name in sovereignBundleModules) {
            project.extensions.configure(PublishingExtension::class.java) {
                repositories {
                    maven {
                        name = TramaiPublishingRepositories.SOVEREIGN_BUNDLE_LOCAL_NAME
                        url = project.uri(TramaiPublishingRepositories.sovereignBundleRepoUrl(project.rootProject).get())
                    }
                }
            }
        }
    }
}
