import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
}

val tramaiGroup = providers.gradleProperty("tramaiGroup").orElse("dev.tramai")
val tramaiVersion = providers.gradleProperty("tramaiVersion").orElse("0.1.0-SNAPSHOT")
val tramaiProjectUrl = providers.gradleProperty("tramaiProjectUrl").orElse("https://github.com/GionaGranchelli/tramAI")
val tramaiScmUrl = providers.gradleProperty("tramaiScmUrl").orElse("https://github.com/GionaGranchelli/tramAI.git")
val tramaiScmConnection = providers.gradleProperty("tramaiScmConnection").orElse("scm:git:https://github.com/GionaGranchelli/tramAI.git")
val tramaiScmDeveloperConnection = providers.gradleProperty("tramaiScmDeveloperConnection").orElse("scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git")
val tramaiLicenseName = providers.gradleProperty("tramaiLicenseName").orElse("Apache-2.0")
val tramaiLicenseUrl = providers.gradleProperty("tramaiLicenseUrl").orElse("https://www.apache.org/licenses/LICENSE-2.0.txt")
val tramaiDeveloperId = providers.gradleProperty("tramaiDeveloperId").orElse("GionaGranchelli")
val tramaiDeveloperName = providers.gradleProperty("tramaiDeveloperName").orElse("Giona")
val tramaiDeveloperEmail = providers.gradleProperty("tramaiDeveloperEmail").orElse("opensource@giona.dev")

subprojects {
    group = tramaiGroup.get()
    version = tramaiVersion.get()

    repositories {
        mavenCentral()
    }

    plugins.withId("java-library") {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        extensions.configure(JavaPluginExtension::class.java) {
            withJavadocJar()
        }

        tasks.withType(Javadoc::class.java).configureEach {
            isFailOnError = false
        }

        configureTramaiPublishing(
            componentName = "java",
            artifactDescription = projectDescription(name),
            projectUrl = tramaiProjectUrl.get(),
            scmUrl = tramaiScmUrl.get(),
            scmConnection = tramaiScmConnection.get(),
            scmDeveloperConnection = tramaiScmDeveloperConnection.get(),
            licenseName = tramaiLicenseName.get(),
            licenseUrl = tramaiLicenseUrl.get(),
            developerId = tramaiDeveloperId.get(),
            developerName = tramaiDeveloperName.get(),
            developerEmail = tramaiDeveloperEmail.get(),
        )
    }

    plugins.withId("java-platform") {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        configureTramaiPublishing(
            componentName = "javaPlatform",
            artifactDescription = projectDescription(name),
            projectUrl = tramaiProjectUrl.get(),
            scmUrl = tramaiScmUrl.get(),
            scmConnection = tramaiScmConnection.get(),
            scmDeveloperConnection = tramaiScmDeveloperConnection.get(),
            licenseName = tramaiLicenseName.get(),
            licenseUrl = tramaiLicenseUrl.get(),
            developerId = tramaiDeveloperId.get(),
            developerName = tramaiDeveloperName.get(),
            developerEmail = tramaiDeveloperEmail.get(),
        )
    }
}

fun Project.configureTramaiPublishing(
    componentName: String,
    artifactDescription: String,
    projectUrl: String,
    scmUrl: String,
    scmConnection: String,
    scmDeveloperConnection: String,
    licenseName: String,
    licenseUrl: String,
    developerId: String,
    developerName: String,
    developerEmail: String,
) {
    extensions.configure<PublishingExtension> {
        val publication = publications.create("maven", MavenPublication::class.java)
        publication.from(components.getByName(componentName))
        publication.artifactId = project.name

        publication.pom {
            name.set(project.name)
            description.set(artifactDescription)
            url.set(projectUrl)

            licenses {
                license {
                    name.set(licenseName)
                    url.set(licenseUrl)
                }
            }

            developers {
                developer {
                    id.set(developerId)
                    name.set(developerName)
                    email.set(developerEmail)
                }
            }

            scm {
                url.set(scmUrl)
                connection.set(scmConnection)
                developerConnection.set(scmDeveloperConnection)
            }
        }

        val releaseRepositoryUrl = providers.gradleProperty("tramaiPublishReleaseUrl").orNull
        val snapshotRepositoryUrl = providers.gradleProperty("tramaiPublishSnapshotUrl").orNull
        val targetRepositoryUrl = when {
            version.toString().endsWith("-SNAPSHOT") -> snapshotRepositoryUrl ?: releaseRepositoryUrl
            else -> releaseRepositoryUrl ?: snapshotRepositoryUrl
        }

        if (!targetRepositoryUrl.isNullOrBlank()) {
            repositories {
                maven {
                    name = "tramaiRemote"
                    url = uri(targetRepositoryUrl)
                    credentials {
                        username = providers.gradleProperty("tramaiPublishUsername").orNull
                        password = providers.gradleProperty("tramaiPublishPassword").orNull
                    }
                }
            }
        }
    }

    extensions.configure<SigningExtension> {
        val signingKey = providers.gradleProperty("signingKey").orNull
        val signingPassword = providers.gradleProperty("signingPassword").orNull
        if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(extensions.getByType(PublishingExtension::class.java).publications)
        }
    }
}

fun projectDescription(projectName: String): String = when (projectName) {
    "tramai-core" -> "Core annotations, request models, provider registry, and exception types for Tramai."
    "tramai-engine" -> "Runtime engine that turns annotated Tramai service interfaces into executable proxies."
    "tramai-structured" -> "Structured output schema generation, parsing, and validation support for Tramai."
    "tramai-anthropic" -> "Anthropic provider integration for Tramai."
    "tramai-openai" -> "OpenAI and OpenAI-compatible provider integrations for Tramai."
    "tramai-ollama" -> "Ollama provider integration for Tramai."
    "tramai-observability" -> "OpenTelemetry-based observability hooks for Tramai."
    "tramai-orchestration" -> "Typed workflow orchestration and coordination layer for Tramai."
    "tramai-standalone" -> "Minimal standalone runtime bundle for Tramai."
    "tramai-spring" -> "Spring Boot auto-configuration and integration support for Tramai."
    "tramai-testing" -> "Testing utilities and deterministic assertion support for Tramai."
    "tramai-bom" -> "Bill of materials for aligning Tramai module versions."
    else -> "Tramai module ${projectName.removePrefix("tramai-")}."
}
