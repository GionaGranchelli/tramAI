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

val auroraGroup = providers.gradleProperty("auroraGroup").orElse("io.aurora")
val auroraVersion = providers.gradleProperty("auroraVersion").orElse("0.1.0-SNAPSHOT")
val auroraProjectUrl = providers.gradleProperty("auroraProjectUrl").orElse("https://github.com/gionag/aurora")
val auroraScmUrl = providers.gradleProperty("auroraScmUrl").orElse("https://github.com/gionag/aurora.git")
val auroraScmConnection = providers.gradleProperty("auroraScmConnection").orElse("scm:git:https://github.com/gionag/aurora.git")
val auroraScmDeveloperConnection = providers.gradleProperty("auroraScmDeveloperConnection").orElse("scm:git:ssh://git@github.com/gionag/aurora.git")
val auroraLicenseName = providers.gradleProperty("auroraLicenseName").orElse("Apache-2.0")
val auroraLicenseUrl = providers.gradleProperty("auroraLicenseUrl").orElse("https://www.apache.org/licenses/LICENSE-2.0.txt")
val auroraDeveloperId = providers.gradleProperty("auroraDeveloperId").orElse("gionag")
val auroraDeveloperName = providers.gradleProperty("auroraDeveloperName").orElse("Giona")
val auroraDeveloperEmail = providers.gradleProperty("auroraDeveloperEmail").orElse("opensource@giona.dev")

subprojects {
    group = auroraGroup.get()
    version = auroraVersion.get()

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

        configureAuroraPublishing(
            componentName = "java",
            artifactDescription = projectDescription(name),
            projectUrl = auroraProjectUrl.get(),
            scmUrl = auroraScmUrl.get(),
            scmConnection = auroraScmConnection.get(),
            scmDeveloperConnection = auroraScmDeveloperConnection.get(),
            licenseName = auroraLicenseName.get(),
            licenseUrl = auroraLicenseUrl.get(),
            developerId = auroraDeveloperId.get(),
            developerName = auroraDeveloperName.get(),
            developerEmail = auroraDeveloperEmail.get(),
        )
    }

    plugins.withId("java-platform") {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        configureAuroraPublishing(
            componentName = "javaPlatform",
            artifactDescription = projectDescription(name),
            projectUrl = auroraProjectUrl.get(),
            scmUrl = auroraScmUrl.get(),
            scmConnection = auroraScmConnection.get(),
            scmDeveloperConnection = auroraScmDeveloperConnection.get(),
            licenseName = auroraLicenseName.get(),
            licenseUrl = auroraLicenseUrl.get(),
            developerId = auroraDeveloperId.get(),
            developerName = auroraDeveloperName.get(),
            developerEmail = auroraDeveloperEmail.get(),
        )
    }
}

fun Project.configureAuroraPublishing(
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

        val releaseRepositoryUrl = providers.gradleProperty("auroraPublishReleaseUrl").orNull
        val snapshotRepositoryUrl = providers.gradleProperty("auroraPublishSnapshotUrl").orNull
        val targetRepositoryUrl = when {
            version.toString().endsWith("-SNAPSHOT") -> snapshotRepositoryUrl ?: releaseRepositoryUrl
            else -> releaseRepositoryUrl ?: snapshotRepositoryUrl
        }

        if (!targetRepositoryUrl.isNullOrBlank()) {
            repositories {
                maven {
                    name = "auroraRemote"
                    url = uri(targetRepositoryUrl)
                    credentials {
                        username = providers.gradleProperty("auroraPublishUsername").orNull
                        password = providers.gradleProperty("auroraPublishPassword").orNull
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
    "aurora-core" -> "Core annotations, request models, provider registry, and exception types for Aurora."
    "aurora-engine" -> "Runtime engine that turns annotated Aurora service interfaces into executable proxies."
    "aurora-structured" -> "Structured output schema generation, parsing, and validation support for Aurora."
    "aurora-anthropic" -> "Anthropic provider integration for Aurora."
    "aurora-openai" -> "OpenAI and OpenAI-compatible provider integrations for Aurora."
    "aurora-ollama" -> "Ollama provider integration for Aurora."
    "aurora-observability" -> "OpenTelemetry-based observability hooks for Aurora."
    "aurora-standalone" -> "Minimal standalone runtime bundle for Aurora."
    "aurora-spring" -> "Spring Boot auto-configuration and integration support for Aurora."
    "aurora-testing" -> "Testing utilities and deterministic assertion support for Aurora."
    "aurora-bom" -> "Bill of materials for aligning Aurora module versions."
    else -> "Aurora module ${projectName.removePrefix("aurora-")}."
}
