import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
}

val tramaiGroup = providers.gradleProperty("tramaiGroup").orElse("dev.tramai")
val tramaiVersion = providers.gradleProperty("tramaiVersion").orElse("0.2.0")
val tramaiProjectUrl = providers.gradleProperty("tramaiProjectUrl").orElse("https://github.com/GionaGranchelli/tramAI")
val tramaiScmUrl = providers.gradleProperty("tramaiScmUrl").orElse("https://github.com/GionaGranchelli/tramAI.git")
val tramaiScmConnection = providers.gradleProperty("tramaiScmConnection").orElse("scm:git:https://github.com/GionaGranchelli/tramAI.git")
val tramaiScmDeveloperConnection = providers.gradleProperty("tramaiScmDeveloperConnection").orElse("scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git")
val tramaiLicenseName = providers.gradleProperty("tramaiLicenseName").orElse("Apache-2.0")
val tramaiLicenseUrl = providers.gradleProperty("tramaiLicenseUrl").orElse("https://www.apache.org/licenses/LICENSE-2.0.txt")
val tramaiDeveloperId = providers.gradleProperty("tramaiDeveloperId").orElse("GionaGranchelli")
val tramaiDeveloperName = providers.gradleProperty("tramaiDeveloperName").orElse("Giona")
val tramaiDeveloperEmail = providers.gradleProperty("tramaiDeveloperEmail").orElse("opensource@giona.dev")
val publishableProjectNames = listOf(
    "tramai-anthropic",
    "tramai-bom",
    "tramai-core",
    "tramai-engine",
    "tramai-observability",
    "tramai-ollama",
    "tramai-openai",
    "tramai-orchestration",
    "tramai-spring",
    "tramai-standalone",
    "tramai-structured",
    "tramai-testing",
)
val jarPublishingProjectNames = publishableProjectNames - "tramai-bom"

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
                    if (!targetRepositoryUrl.startsWith("file:")) {
                        credentials {
                            username = providers.gradleProperty("tramaiPublishUsername").orNull
                            password = providers.gradleProperty("tramaiPublishPassword").orNull
                        }
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
    "tramai-platform" -> "Platform services for plugins, tenancy, API keys, rate limiting, and audit logging."
    "tramai-standalone" -> "Minimal standalone runtime bundle for Tramai."
    "tramai-spring" -> "Spring Boot auto-configuration and integration support for Tramai."
    "tramai-testing" -> "Testing utilities and deterministic assertion support for Tramai."
    "tramai-bom" -> "Bill of materials for aligning Tramai module versions."
    else -> "Tramai module ${projectName.removePrefix("tramai-")}."
}

fun Element.directChild(name: String): Element? {
    val children = childNodes
    for (index in 0 until children.length) {
        val node = children.item(index)
        if (node is Element && node.tagName == name) {
            return node
        }
    }
    return null
}

fun Element.directChildText(name: String): String? = directChild(name)?.textContent?.trim()

fun parseXml(file: File): Element {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance()
    documentBuilderFactory.isNamespaceAware = false
    return documentBuilderFactory.newDocumentBuilder().parse(file).documentElement
}

fun releaseRepositoryUrlFor(version: String): String? {
    val releaseRepositoryUrl = providers.gradleProperty("tramaiPublishReleaseUrl").orNull
    val snapshotRepositoryUrl = providers.gradleProperty("tramaiPublishSnapshotUrl").orNull
    return when {
        version.endsWith("-SNAPSHOT") -> snapshotRepositoryUrl ?: releaseRepositoryUrl
        else -> releaseRepositoryUrl ?: snapshotRepositoryUrl
    }
}

tasks.register("verifyPublicationMetadata") {
    group = "verification"
    description = "Verifies generated Maven POM metadata for every publishable Tramai module."
    notCompatibleWithConfigurationCache("Release metadata verification inspects generated publication files at execution time.")
    dependsOn(publishableProjectNames.map { ":$it:generatePomFileForMavenPublication" })

    doLast {
        val expectedGroup = tramaiGroup.get()
        val expectedVersion = tramaiVersion.get()
        val expectedProjectUrl = tramaiProjectUrl.get()
        val expectedScmUrl = tramaiScmUrl.get()
        val expectedScmConnection = tramaiScmConnection.get()
        val expectedScmDeveloperConnection = tramaiScmDeveloperConnection.get()
        val expectedLicenseName = tramaiLicenseName.get()
        val expectedLicenseUrl = tramaiLicenseUrl.get()
        val expectedDeveloperId = tramaiDeveloperId.get()
        val expectedDeveloperName = tramaiDeveloperName.get()
        val expectedDeveloperEmail = tramaiDeveloperEmail.get()

        publishableProjectNames.forEach { projectName ->
            val pomFile = rootProject.file("$projectName/build/publications/maven/pom-default.xml")
            require(pomFile.isFile) { "Missing generated POM for $projectName at ${pomFile.absolutePath}" }

            val project = parseXml(pomFile)
            require(project.directChildText("groupId") == expectedGroup) { "Unexpected groupId in $projectName POM" }
            require(project.directChildText("artifactId") == projectName) { "Unexpected artifactId in $projectName POM" }
            require(project.directChildText("version") == expectedVersion) { "Unexpected version in $projectName POM" }
            require(project.directChildText("name") == projectName) { "Unexpected name in $projectName POM" }
            require(project.directChildText("description") == projectDescription(projectName)) {
                "Unexpected description in $projectName POM"
            }
            require(project.directChildText("url") == expectedProjectUrl) { "Unexpected project URL in $projectName POM" }

            val license = requireNotNull(project.directChild("licenses")?.directChild("license")) {
                "Missing license section in $projectName POM"
            }
            require(license.directChildText("name") == expectedLicenseName) { "Unexpected license name in $projectName POM" }
            require(license.directChildText("url") == expectedLicenseUrl) { "Unexpected license URL in $projectName POM" }

            val developer = requireNotNull(project.directChild("developers")?.directChild("developer")) {
                "Missing developer section in $projectName POM"
            }
            require(developer.directChildText("id") == expectedDeveloperId) { "Unexpected developer id in $projectName POM" }
            require(developer.directChildText("name") == expectedDeveloperName) { "Unexpected developer name in $projectName POM" }
            require(developer.directChildText("email") == expectedDeveloperEmail) { "Unexpected developer email in $projectName POM" }

            val scm = requireNotNull(project.directChild("scm")) { "Missing SCM section in $projectName POM" }
            require(scm.directChildText("url") == expectedScmUrl) { "Unexpected SCM URL in $projectName POM" }
            require(scm.directChildText("connection") == expectedScmConnection) { "Unexpected SCM connection in $projectName POM" }
            require(scm.directChildText("developerConnection") == expectedScmDeveloperConnection) {
                "Unexpected SCM developer connection in $projectName POM"
            }

            val packaging = project.directChildText("packaging")
            if (projectName == "tramai-bom") {
                require(packaging == "pom") { "The BOM must publish as packaging=pom" }
                val dependencyManagement = requireNotNull(project.directChild("dependencyManagement")) {
                    "Missing dependencyManagement section in tramai-bom POM"
                }
                val dependencies = requireNotNull(dependencyManagement.directChild("dependencies")) {
                    "Missing dependencyManagement dependencies in tramai-bom POM"
                }
                val managedArtifactIds = buildList {
                    val children = dependencies.childNodes
                    for (index in 0 until children.length) {
                        val child = children.item(index)
                        if (child is Element && child.tagName == "dependency") {
                            add(child.directChildText("artifactId").orEmpty())
                        }
                    }
                }
                val expectedManagedArtifacts = jarPublishingProjectNames.toSet()
                require(managedArtifactIds.toSet() == expectedManagedArtifacts) {
                    "Unexpected BOM contents. Expected $expectedManagedArtifacts but found ${managedArtifactIds.toSet()}"
                }
            } else {
                require(packaging == null || packaging == "jar") { "Unexpected packaging in $projectName POM: $packaging" }
            }
        }
    }
}

tasks.register("verifyPublishedLocalArtifacts") {
    group = "verification"
    description = "Publishes to Maven Local and verifies POM/module/jar/sources/javadoc artifacts for every Tramai module."
    notCompatibleWithConfigurationCache("Local Maven repository verification inspects published artifacts at execution time.")
    dependsOn(publishableProjectNames.map { ":$it:publishToMavenLocal" })

    doLast {
        val expectedVersion = tramaiVersion.get()
        val baseRepository = File(System.getProperty("user.home"))
            .resolve(".m2/repository")
            .resolve(tramaiGroup.get().replace('.', '/'))
        require(baseRepository.isDirectory) {
            "Missing local Maven repository root for ${tramaiGroup.get()} at ${baseRepository.absolutePath}"
        }

        publishableProjectNames.forEach { projectName ->
            val moduleDirectory = baseRepository.resolve("$projectName/$expectedVersion")
            require(moduleDirectory.isDirectory) {
                "Missing local Maven module directory for $projectName at ${moduleDirectory.absolutePath}"
            }

            val baseName = "$projectName-$expectedVersion"
            val requiredFiles = mutableListOf(
                "$baseName.pom",
                "$baseName.module",
            )
            if (projectName != "tramai-bom") {
                requiredFiles += listOf(
                    "$baseName.jar",
                    "$baseName-sources.jar",
                    "$baseName-javadoc.jar",
                )
            }

            requiredFiles.forEach { fileName ->
                val artifact = moduleDirectory.resolve(fileName)
                require(artifact.isFile) { "Missing local Maven artifact for $projectName: ${artifact.absolutePath}" }
                require(artifact.length() > 0) { "Published artifact is empty for $projectName: ${artifact.absolutePath}" }
            }
        }
    }
}

tasks.register("verifyReleasePublishInputs") {
    group = "verification"
    description = "Verifies that the properties required for a real remote release publish are present."
    notCompatibleWithConfigurationCache("Release input verification reads Gradle properties directly at execution time.")

    doLast {
        val requiredProperties = listOf(
            "tramaiPublishReleaseUrl",
            "tramaiPublishUsername",
            "tramaiPublishPassword",
            "signingKey",
            "signingPassword",
        )
        requiredProperties.forEach { propertyName ->
            require(!providers.gradleProperty(propertyName).orNull.isNullOrBlank()) {
                "Missing required Gradle property for remote release publishing: $propertyName"
            }
        }
        require(!tramaiVersion.get().endsWith("-SNAPSHOT")) {
            "Remote release validation expects a non-SNAPSHOT tramaiVersion, but found ${tramaiVersion.get()}"
        }
    }
}

tasks.register("verifySignedPublicationBundle") {
    group = "verification"
    description = "Publishes to a configured file-based Maven repository and verifies generated signature files."
    notCompatibleWithConfigurationCache("Signed publication verification inspects a published file repository at execution time.")
    dependsOn(publishableProjectNames.map { ":$it:publish" })

    doFirst {
        require(!providers.gradleProperty("signingKey").orNull.isNullOrBlank()) {
            "Missing signingKey. Provide -PsigningKey=<ascii-armored-private-key>."
        }
        require(!providers.gradleProperty("signingPassword").orNull.isNullOrBlank()) {
            "Missing signingPassword. Provide -PsigningPassword=<key-password>."
        }

        val repositoryUrl = releaseRepositoryUrlFor(tramaiVersion.get())
        require(!repositoryUrl.isNullOrBlank()) {
            "Missing publish repository URL. Provide -PtramaiPublishReleaseUrl=file:///... or -PtramaiPublishSnapshotUrl=file:///..."
        }
        require(repositoryUrl.startsWith("file:")) {
            "verifySignedPublicationBundle only supports file:// repositories for local verification, but got $repositoryUrl"
        }
    }

    doLast {
        val repositoryUrl = requireNotNull(releaseRepositoryUrlFor(tramaiVersion.get()))
        val repositoryDirectory = File(URI(repositoryUrl))
        val expectedVersion = tramaiVersion.get()
        val groupPath = tramaiGroup.get().replace('.', '/')

        publishableProjectNames.forEach { projectName ->
            val moduleDirectory = repositoryDirectory.resolve("$groupPath/$projectName/$expectedVersion")
            require(moduleDirectory.isDirectory) {
                "Missing published module directory for $projectName at ${moduleDirectory.absolutePath}"
            }
            val publishedFiles = moduleDirectory.listFiles()?.filter(File::isFile).orEmpty()

            fun requirePublishedArtifact(
                description: String,
                predicate: (String) -> Boolean,
            ) {
                val matchingFiles = publishedFiles.filter { predicate(it.name) }
                require(matchingFiles.isNotEmpty()) {
                    "Missing published $description for $projectName in ${moduleDirectory.absolutePath}"
                }
                require(matchingFiles.all { it.length() > 0 }) {
                    "Published $description is empty for $projectName in ${moduleDirectory.absolutePath}"
                }
            }

            requirePublishedArtifact("POM", { it.endsWith(".pom") })
            requirePublishedArtifact("POM signature", { it.endsWith(".pom.asc") })
            requirePublishedArtifact("Gradle module metadata", { it.endsWith(".module") })
            requirePublishedArtifact("Gradle module metadata signature", { it.endsWith(".module.asc") })

            if (projectName != "tramai-bom") {
                requirePublishedArtifact(
                    "binary jar",
                    { it.endsWith(".jar") && !it.endsWith("-sources.jar") && !it.endsWith("-javadoc.jar") },
                )
                requirePublishedArtifact(
                    "binary jar signature",
                    { it.endsWith(".jar.asc") && !it.endsWith("-sources.jar.asc") && !it.endsWith("-javadoc.jar.asc") },
                )
                requirePublishedArtifact("sources jar", { it.endsWith("-sources.jar") })
                requirePublishedArtifact("sources jar signature", { it.endsWith("-sources.jar.asc") })
                requirePublishedArtifact("javadoc jar", { it.endsWith("-javadoc.jar") })
                requirePublishedArtifact("javadoc jar signature", { it.endsWith("-javadoc.jar.asc") })
            }
        }
    }
}

tasks.register("verifyReleaseReadiness") {
    group = "verification"
    description = "Runs the repo-local release verification checks for publication metadata and published artifacts."
    notCompatibleWithConfigurationCache("Release readiness aggregates execution-time verification tasks.")
    dependsOn(
        jarPublishingProjectNames.map { ":$it:test" },
        "verifyPublicationMetadata",
        "verifyPublishedLocalArtifacts",
    )
}
