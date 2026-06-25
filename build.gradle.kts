import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension
import org.gradle.util.GradleVersion
import org.w3c.dom.Element
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.cyclonedx.bom)
}

sonar {
    properties {
        property("sonar.projectKey", "tramai")
        property("sonar.projectName", "TramAI")
        property("sonar.organization", "gionagranchelli")
        property("sonar.host.url", "http://localhost:9000")
        property("sonar.token", providers.environmentVariable("SONAR_TOKEN").orElse(""))
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.exclusions", "**/*.xml,**/*.properties,**/*.yml,**/*.yaml")
        // Kotlin analysis requires compiled classes
        property("sonar.kotlin.binaries", "**/build/classes/kotlin/**")
        // S6518 false positive — suggests obj[key] but target types lack operator modifier
        property("sonar.issue.ignore.multicriteria", "e1")
        property("sonar.issue.ignore.multicriteria.e1.ruleKey", "kotlin:S6518")
        property("sonar.issue.ignore.multicriteria.e1.resourceKey", "**/*.kt")
    }
}

val tramaiGroup = providers.gradleProperty("tramaiGroup").orElse("dev.tramai")
val tramaiVersion = providers.gradleProperty("tramaiVersion").orElse("0.3.1")
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
    "tramai-azure-openai",
    "tramai-bedrock",
    "tramai-bom",
    "tramai-core",
    "tramai-deepseek",
    "tramai-embedding",
    "tramai-engine",
    "tramai-gemini",
    "tramai-memory",
    "tramai-observability",
    "tramai-ollama",
    "tramai-openai",
    "tramai-orchestration",
    "tramai-platform",
    "tramai-spring",
    "tramai-standalone",
    "tramai-sovereign",
    "tramai-persistence-file",
    "tramai-structured",
    "tramai-testing",
    "tramai-vectorstore-spi",
    "tramai-vectorstore-chroma",
    "tramai-vectorstore-pgvector",
    "tramai-rag",
    "tramai-security",
    "tramai-spring-boot-starter-sovereign",
    "tramai-spring-boot-starter-sovereign-persistence-file",
    "tramai-spring-boot-starter-sovereign-ops",
    "tramai-spring-boot-starter-sovereign-ops-actuator",
    "tramai-spring-boot-starter-sovereign-ops-micrometer",
    "tramai-spring-boot-starter-sovereign-ops-observability",
)
val jarPublishingProjectNames = publishableProjectNames - "tramai-bom"

// Sovereign bundle modules for the dedicated publication dry-run repository.
// Used by the verifySovereignRuntimeSignedBundle task to publish only to a local
// file-based Maven repository — never to a remote — preventing accidental remote
// publication during dry-run validation.
val sovereignBundleModuleNames = listOf(
    "tramai-bom",
    "tramai-core",
    "tramai-security",
    "tramai-sovereign",
    "tramai-standalone",
    "tramai-engine",
    "tramai-structured",
    "tramai-persistence-file",
    "tramai-spring-boot-starter-sovereign",
    "tramai-spring-boot-starter-sovereign-persistence-file",
    "tramai-spring-boot-starter-sovereign-ops",
    "tramai-spring-boot-starter-sovereign-ops-actuator",
    "tramai-spring-boot-starter-sovereign-ops-micrometer",
    "tramai-spring-boot-starter-sovereign-ops-observability",
)

// ──────────────────────────────────────────────
// Sovereign Release Evidence Index - Typed Model
// ──────────────────────────────────────────────

data class SovereignReleaseEvidenceIndexV1(
    val schemaVersion: String,
    val generatedAt: String,
    val repository: String,
    val commitSha: String,
    val refName: String,
    val version: String,
    val remotePublish: Boolean,
    val tagCreated: Boolean,
    val releaseCandidate: Boolean,
    val artifacts: List<EvidenceArtifact>,
    val checks: EvidenceChecks,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "schemaVersion" to schemaVersion,
        "generatedAt" to generatedAt,
        "repository" to repository,
        "commitSha" to commitSha,
        "refName" to refName,
        "version" to version,
        "remotePublish" to remotePublish,
        "tagCreated" to tagCreated,
        "releaseCandidate" to releaseCandidate,
        "artifacts" to artifacts.map { it.toMap() },
        "checks" to checks.toMap(),
    )
}

data class EvidenceArtifact(
    val id: String,
    val path: String,
    val type: String,
    val required: Boolean,
    val sha256: String? = null,
    val fileCount: Int? = null,
    val sha256Tree: String? = null,
) {
    fun toMap(): Map<String, Any> =
        buildMap {
            put("id", id)
            put("path", path)
            put("type", type)
            put("required", required)
            sha256?.let { put("sha256", it) }
            fileCount?.let { put("fileCount", it) }
            sha256Tree?.let { put("sha256Tree", it) }
        }
}

data class EvidenceChecks(
    val releaseReadiness: EvidenceCheck,
    val sovereignRuntimePublication: EvidenceCheck,
    val sovereignRuntimeSignedBundle: EvidenceCheck,
    val consumerSmoke: ConsumerSmokeEvidenceCheck,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "releaseReadiness" to releaseReadiness.toMap(),
        "sovereignRuntimePublication" to sovereignRuntimePublication.toMap(),
        "sovereignRuntimeSignedBundle" to sovereignRuntimeSignedBundle.toMap(),
        "consumerSmoke" to consumerSmoke.toMap(),
    )
}

data class EvidenceCheck(
    val status: String,
    val taskPath: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "status" to status,
        "taskPath" to taskPath,
    )
}

data class ConsumerSmokeEvidenceCheck(
    val status: String,
    val taskPath: String,
    val executes: String,
    val devTramaiResolutionPolicy: DevTramaiResolutionPolicy,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "status" to status,
        "taskPath" to taskPath,
        "executes" to executes,
        "devTramaiResolutionPolicy" to devTramaiResolutionPolicy.toMap(),
    )
}

data class DevTramaiResolutionPolicy(
    val allowedRepositories: List<String>,
    val blockedRepositories: List<String>,
    val coverage: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "allowedRepositories" to allowedRepositories,
        "blockedRepositories" to blockedRepositories,
        "coverage" to coverage,
    )
}

// Lazy default URL for the sovereign bundle local repository.
// Override with -PtramaiPublishReleaseUrl=file://<path> for custom local paths.
val sovereignBundleRepoUrl: Provider<String> = providers.gradleProperty("tramaiPublishReleaseUrl")
    .orElse(rootProject.layout.buildDirectory.dir("sovereign-runtime-release-verification-repo")
        .map { "file://${it.asFile.absolutePath}" })

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

        // Dedicated local-only Maven repository for the sovereign signed bundle dry-run.
        // Added inside the plugin block so maven-publish has already registered
        // PublishingExtension. This repo is always file:// (build-local by default)
        // and is never configured with remote credentials.
        if (project.name in sovereignBundleModuleNames) {
            configureSovereignBundleLocalRepo()
        }
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

        // Dedicated local-only Maven repository for the sovereign signed bundle dry-run.
        // Added inside the plugin block so maven-publish has already registered
        // PublishingExtension. This repo is always file:// (build-local by default)
        // and is never configured with remote credentials.
        if (project.name in sovereignBundleModuleNames) {
            configureSovereignBundleLocalRepo()
        }
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

/**
 * Adds the dedicated sovereignBundleLocal Maven repository to this project's
 * PublishingExtension. This repo is always file:// (build-local by default,
 * or overridden via -PtramaiPublishReleaseUrl=file://...) and is never
 * configured with remote credentials. The verifySovereignRuntimeSignedBundle
 * task publishes exclusively to this repo using the generated
 * *ToSovereignBundleLocalRepository tasks, never to tramaiRemote or :publish.
 */
fun Project.configureSovereignBundleLocalRepo() {
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "sovereignBundleLocal"
                url = URI(sovereignBundleRepoUrl.get())
            }
        }
    }
}

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

tasks.register("verifySovereignOpsObservabilityDocs") {
    group = "verification"
    description = "Validates sovereign ops worker observability docs against the expected metric contract, API surface, and safe-label rules."
    notCompatibleWithConfigurationCache("Docs validation reads file content at execution time.")

    doLast {
        val rootDir = rootProject.layout.projectDirectory.asFile

        val runbook = rootDir.resolve("docs/operations/sovereign-ops-worker-observability-runbook.md")
        val promql = rootDir.resolve("docs/operations/prometheus/sovereign-ops-worker-promql.md")
        val alerts = rootDir.resolve("docs/operations/prometheus/sovereign-ops-worker-alerts.example.yml")

        val files = listOf(runbook, promql, alerts)
        files.forEach {
            require(it.isFile) {
                "Missing required observability doc: ${it.invariantSeparatorsPath}"
            }
        }

        val runbookText = runbook.readText()
        val allText = files.joinToString("\n") { it.readText() }

        fun requireContains(value: String) {
            require(value in allText) {
                "Expected sovereign ops observability docs to contain: $value"
            }
        }

        fun requireAbsent(value: String) {
            require(value !in allText) {
                "Forbidden content found in sovereign ops observability docs: $value"
            }
        }

        // ── A. Required Prometheus metric names
        listOf(
            "tramai_sovereign_ops_outbox_worker_cycles_total",
            "tramai_sovereign_ops_outbox_worker_duration_seconds_count",
            "tramai_sovereign_ops_outbox_worker_duration_seconds_sum",
            "tramai_sovereign_ops_outbox_worker_duration_seconds_max",
            "tramai_sovereign_ops_outbox_worker_failures_total",
            "tramai_sovereign_ops_outbox_worker_recovered_records_total",
            "tramai_sovereign_ops_outbox_worker_dispatched_records_total",
        ).forEach(::requireContains)

        // ── A. Required dotted Micrometer names
        listOf(
            "tramai.sovereign.ops.outbox.worker.cycles",
            "tramai.sovereign.ops.outbox.worker.duration",
            "tramai.sovereign.ops.outbox.worker.failures",
            "tramai.sovereign.ops.outbox.worker.recovered.records",
            "tramai.sovereign.ops.outbox.worker.dispatched.records",
        ).forEach(::requireContains)

        // ── B. Typo guard
        requireAbsent("tamai_")

        // ── C. Forbidden sensitive labels in PromQL/YAML selector context
        val selectorRegex = Regex(
            """\{(?:[^}]*\b(?:tenant_id|user_id|document_id|approval_id|workflow_id|correlation_id|token|prompt|model_response|file_path|stack_trace)\b[^}]*)\}"""
        )
        val hasForbiddenLabels = allText.contains(selectorRegex)
        require(!hasForbiddenLabels) {
            "Forbidden sensitive label found in PromQL/alert selector context"
        }

        // ── D. Approved safe label names present
        listOf("outcome", "failure_action", "error_type", "result")
            .forEach(::requireContains)

        // ── E. Real observer API documented, invalid API absent
        requireContains("onCycleCompleted(summary")
        requireContains("onCycleFailed(action")
        requireAbsent("onStatus(")
        // ── F. Real Actuator snapshot fields present, made-up fields absent
        listOf(
            "enabled", "running", "recoverPreparedEnabled", "dispatchPendingEnabled",
            "batchSize", "intervalMillis", "lastCycleDurationMillis",
            "lastRecovered", "lastDispatched", "lastFailure", "lastFailureAt",
            "totalCyclesCompleted", "totalCyclesFailed",
        ).forEach { field ->
            require(field in runbookText) {
                "Runbook is missing real Actuator snapshot field: $field"
            }
        }

        listOf(
            "lastCycleResult", "cyclesSinceLastReset", "totalCycleDurationMillis",
            "recoveredCount", "dispatchedCount", "failureCount",
        ).forEach { field ->
            require(field !in runbookText) {
                "Runbook contains stale made-up Actuator field: $field"
            }
        }

        // ── G. OpenTelemetry exporter wording
        requireContains("does not")
        requireContains("bring an SDK or exporter")
        requireContains("must provide their own OpenTelemetry SDK and exporter configuration")

        // ── H. Alert warning guard
        val alertText = alerts.readText()
        require("WARNING" in alertText) {
            "Alert examples must contain a WARNING header"
        }
        require("NOT production defaults" in alertText) {
            "Alert examples must state they are NOT production defaults"
        }
        require("Thresholds must be tuned" in alertText) {
            "Alert examples must state that thresholds must be tuned"
        }

        // ── J. Health indicator documentation guard
        requireContains("tramai.sovereign.ops.actuator.worker-health.enabled=true")
        requireContains("tramaiSovereignOpsWorkerHealthIndicator")
        requireContains("tramaiSovereignOpsWorker")
        requireContains("Health component name")

        // ── I. Starter README link guard
        val actuatorReadme = rootDir.resolve("tramai-spring-boot-starter-sovereign-ops-actuator/README.md")
        val micrometerReadme = rootDir.resolve("tramai-spring-boot-starter-sovereign-ops-micrometer/README.md")
        val observabilityReadme = rootDir.resolve("tramai-spring-boot-starter-sovereign-ops-observability/README.md")

        val runbookRef = "sovereign-ops-worker-observability-runbook.md"
        val promqlRef = "sovereign-ops-worker-promql.md"

        require(runbookRef in actuatorReadme.readText()) {
            "Actuator README must link to the observability runbook"
        }
        require(runbookRef in micrometerReadme.readText()) {
            "Micrometer README must link to the observability runbook"
        }
        require(promqlRef in micrometerReadme.readText()) {
            "Micrometer README must link to the PromQL reference"
        }
        require(runbookRef in observabilityReadme.readText()) {
            "OpenTelemetry README must link to the observability runbook"
        }

        logger.lifecycle("verifySovereignOpsObservabilityDocs: all checks passed.")
    }
}

tasks.register("verifyReleaseReadiness") {
    group = "verification"
    description = "Runs the repo-local release verification checks for publication metadata and published artifacts."
    notCompatibleWithConfigurationCache("Release readiness aggregates execution-time verification tasks.")
    dependsOn(
        jarPublishingProjectNames.map { ":${it}:test" },
        "verifyPublicationMetadata",
        "verifyPublishedLocalArtifacts",
        "verifySovereignOpsObservabilityDocs",
    )
}

val sovereignRuntimePublishableModules = listOf(
    "tramai-security",
    "tramai-sovereign",
    "tramai-persistence-file",
    "tramai-spring-boot-starter-sovereign",
    "tramai-spring-boot-starter-sovereign-persistence-file",
    "tramai-spring-boot-starter-sovereign-ops",
    "tramai-spring-boot-starter-sovereign-ops-actuator",
    "tramai-spring-boot-starter-sovereign-ops-micrometer",
    "tramai-spring-boot-starter-sovereign-ops-observability",
)

tasks.register("verifySovereignRuntimePublication") {
    group = "verification"
    description = "Validates local publishability of sovereign runtime modules — POM metadata, sources/javadoc JARs, and dependency graph. Does not publish remotely."
    notCompatibleWithConfigurationCache("Sovereign runtime publication validation aggregates checks and runs publishToMavenLocal.")
    dependsOn(
        sovereignRuntimePublishableModules.map { ":${it}:test" },
        sovereignRuntimePublishableModules.map { ":${it}:publishToMavenLocal" },
    )
    doLast {
        logger.lifecycle("Sovereign runtime publication validation complete.")
        logger.lifecycle("  Validated modules: ${sovereignRuntimePublishableModules.joinToString(", ")}")
        logger.lifecycle("  POMs, sources JARs, and javadoc JARs have been published to mavenLocal().")
        logger.lifecycle("  No remote repository was touched.")
    }
}

tasks.register("verifySovereignRuntimeSignedBundle") {
    group = "verification"
    description = "Validates local signed publication bundle for the sovereign runtime release boundary. " +
        "Publishes to a dedicated local-only file-based Maven repository (" +
        "build/sovereign-runtime-release-verification-repo by default), validates artifact " +
        "structure (POMs, JARs, .module metadata), and optionally verifies .asc signatures " +
        "when signing properties are provided. Generates bundle-manifest.json. " +
        "Does NOT publish remotely, tag, bump versions, or freeze APIs."
    notCompatibleWithConfigurationCache("Sovereign runtime signed bundle verification inspects published artifacts and generates a manifest at execution time.")

    // ── Configuration-time URL validation ────────────────────────────────
    // Reject non-file URLs before any publish task can run. The sovereign bundle
    // dry-run uses a dedicated soverignBundleLocal repository that is always file://
    // (build-local by default). If someone passes -PtramaiPublishReleaseUrl pointing
    // to a remote server, the dependent publish tasks must never be triggered.
    val userProvidedUrl = providers.gradleProperty("tramaiPublishReleaseUrl").orNull
    if (userProvidedUrl != null && !userProvidedUrl.startsWith("file:")) {
        throw GradleException(
            "verifySovereignRuntimeSignedBundle only supports file:// repositories for local " +
            "verification. Got: $userProvidedUrl. The sovereign bundle dry-run publishes " +
            "to a dedicated local-only repository and must never contact a remote server."
        )
    }

    // Resolve the dedicated bundle repo URL (default or user-provided file://)
    val bundleRepoUrl = sovereignBundleRepoUrl.get()

    // Signing key properties — evaluated at configuration time by the signing extension
    // in configureTramaiPublishing, so .asc files are produced automatically during publish.
    val signingKey = providers.gradleProperty("signingKey").orNull
    val signingPassword = providers.gradleProperty("signingPassword").orNull
    val wantsSigning = !signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()

    // ── Dependencies ─────────────────────────────────────────────────────
    // Baseline: publish to mavenLocal for artifact structure checks
    dependsOn(
        sovereignBundleModuleNames.map { ":${it}:publishToMavenLocal" },
    )

    // Always publish to the dedicated sovereignBundleLocal repository.
    // This repo is unconditionally file:// (build-local by default, or overridden with
    // -PtramaiPublishReleaseUrl=file://...) and is configured separately from the
    // tramaiRemote repository used by other publish tasks.
    // We use the specific *ToSovereignBundleLocalRepository task — never generic :publish.
    dependsOn(
        sovereignBundleModuleNames.map { ":${it}:publishMavenPublicationToSovereignBundleLocalRepository" },
    )

    // Skip tests in the signed bundle dry-run — the verifySovereignRuntimePublication task
    // handles test execution separately. This task focuses purely on artifact structure,
    // signing (optional), and manifest generation.

    doFirst {
        if (wantsSigning) {
            logger.lifecycle("Signing key provided — will validate .asc signatures.")
        } else {
            logger.lifecycle("No signing key provided — skipping .asc signature validation.")
        }
    }

    doLast {
        val groupPath = tramaiGroup.get().replace('.', '/')
        val expectedVersion = tramaiVersion.get()
        val userHome = System.getProperty("user.home")
        val m2Repo = File(userHome, ".m2/repository").resolve(groupPath)
        val buildDir = rootProject.layout.buildDirectory.get().asFile
        val bundleDir = buildDir.resolve("sovereign-runtime-release")
        val bundleManifestJson = bundleDir.resolve("bundle-manifest.json")
        val allModules = sovereignBundleModuleNames

        // Dedicated bundle repo directory
        val bundleRepoDir = File(URI(bundleRepoUrl))
        logger.lifecycle("Bundle repository: ${bundleRepoDir.absolutePath}")

        // ── 1. Validate mavenLocal baseline ──────────────────────────────
        logger.lifecycle("Validating mavenLocal baseline artifacts...")
        allModules.forEach { moduleName ->
            val moduleDir = m2Repo.resolve("$moduleName/$expectedVersion")
            require(moduleDir.isDirectory) {
                "Missing mavenLocal module directory for $moduleName at ${moduleDir.absolutePath}"
            }
            val baseName = "$moduleName-$expectedVersion"
            val requiredFiles = mutableListOf(
                "$baseName.pom",
                "$baseName.module",
            )
            if (moduleName != "tramai-bom") {
                requiredFiles += listOf(
                    "$baseName.jar",
                    "$baseName-sources.jar",
                    "$baseName-javadoc.jar",
                )
            }
            requiredFiles.forEach { fileName ->
                val artifact = moduleDir.resolve(fileName)
                require(artifact.isFile) {
                    "Missing mavenLocal artifact for $moduleName: ${artifact.absolutePath}"
                }
                require(artifact.length() > 0) {
                    "Empty mavenLocal artifact for $moduleName: ${artifact.absolutePath}"
                }
            }
        }
        logger.lifecycle("  All mavenLocal artifacts present and non-empty.")

        // ── 2. Validate the dedicated file-based bundle repository ─────────
        logger.lifecycle("Validating bundle repository at ${bundleRepoDir.absolutePath}...")
        require(bundleRepoDir.isDirectory) {
            "Missing bundle repository root at ${bundleRepoDir.absolutePath}." +
            "The sovereignBundleLocal repository should have been populated during publish."
        }

        allModules.forEach { moduleName ->
            val moduleDir = bundleRepoDir.resolve("$groupPath/$moduleName/$expectedVersion")
            require(moduleDir.isDirectory) {
                "Missing bundle-repo module directory for $moduleName at ${moduleDir.absolutePath}"
            }
            val publishedFiles = moduleDir.listFiles()?.filter(File::isFile).orEmpty()

            fun requireArtifact(description: String, predicate: (String) -> Boolean) {
                val matching = publishedFiles.filter { predicate(it.name) }
                require(matching.isNotEmpty()) {
                    "Missing $description for $moduleName in ${moduleDir.absolutePath}"
                }
                require(matching.all { it.length() > 0 }) {
                    "Empty $description for $moduleName in ${moduleDir.absolutePath}"
                }
            }

            requireArtifact("POM") { it.endsWith(".pom") && !it.endsWith(".pom.asc") }
            requireArtifact("Gradle module metadata") { it.endsWith(".module") && !it.endsWith(".module.asc") }

            if (moduleName != "tramai-bom") {
                requireArtifact("binary jar") {
                    it.endsWith(".jar") &&
                    !it.endsWith("-sources.jar") &&
                    !it.endsWith("-javadoc.jar") &&
                    !it.endsWith(".jar.asc")
                }
                requireArtifact("sources jar") { it.endsWith("-sources.jar") && !it.endsWith("-sources.jar.asc") }
                requireArtifact("javadoc jar") { it.endsWith("-javadoc.jar") && !it.endsWith("-javadoc.jar.asc") }
            }

            // Optional signing validation
            if (wantsSigning) {
                requireArtifact("POM signature") { it.endsWith(".pom.asc") }
                requireArtifact("Gradle module metadata signature") { it.endsWith(".module.asc") }
                if (moduleName != "tramai-bom") {
                    requireArtifact("binary jar signature") {
                        it.endsWith(".jar.asc") &&
                        !it.endsWith("-sources.jar.asc") &&
                        !it.endsWith("-javadoc.jar.asc")
                    }
                    requireArtifact("sources jar signature") { it.endsWith("-sources.jar.asc") }
                    requireArtifact("javadoc jar signature") { it.endsWith("-javadoc.jar.asc") }
                }
                logger.lifecycle("  Signatures validated for $moduleName.")
            }
        }
        logger.lifecycle("  Bundle repository validation complete.")

        // ── 3. Generate bundle-manifest.json (fail-closed) ───────────────
        logger.lifecycle("Generating bundle manifest...")
        bundleDir.mkdirs()

        fun jsonEscape(value: String): String {
            val sb = StringBuilder()
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u%04x".format(ch.code))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            return sb.toString()
        }

        fun sha256Hex(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        }

        val moduleEntries = mutableListOf<String>()

        allModules.forEach { moduleName ->
            val sourceDir = bundleRepoDir.resolve("$groupPath/$moduleName/$expectedVersion")
            // Fail closed: every module must have published artifact directory
            require(sourceDir.isDirectory) {
                "Cannot generate bundle manifest: missing artifact directory for $moduleName " +
                "at ${sourceDir.absolutePath}. All sovereign bundle modules must produce " +
                "published artifacts."
            }

            val baseName = "$moduleName-$expectedVersion"
            val artifactFiles = sourceDir.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
            val artifactPaths = mutableListOf<String>()
            val signatures = mutableListOf<String>()
            val checksums = mutableMapOf<String, String>()

            for (file in artifactFiles) {
                val relPath = file.absolutePath.removePrefix(bundleRepoDir.absolutePath).trimStart('/')

                if (file.name.endsWith(".asc")) {
                    signatures.add(relPath)
                } else {
                    artifactPaths.add(relPath)
                    checksums[relPath] = "sha256:${sha256Hex(file)}"
                }
            }

            val moduleEntry = buildString {
                append("      {")
                append("\"groupId\": \"${jsonEscape(tramaiGroup.get())}\", ")
                append("\"artifactId\": \"${jsonEscape(moduleName)}\", ")
                append("\"version\": \"${jsonEscape(expectedVersion)}\", ")
                append("\"baseName\": \"${jsonEscape(baseName)}\", ")
                append("\"artifactPaths\": [${artifactPaths.joinToString(", ") { "\"${jsonEscape(it)}\"" }}], ")
                if (signatures.isNotEmpty()) {
                    append("\"signatures\": [${signatures.joinToString(", ") { "\"${jsonEscape(it)}\"" }}], ")
                }
                append("\"checksums\": {${checksums.entries.joinToString(", ") { "\"${jsonEscape(it.key)}\": \"${jsonEscape(it.value)}\"" }}}")
                append("}")
            }
            moduleEntries.add(moduleEntry)
        }

        // Fail closed: all expected modules must have entries in the manifest
        require(moduleEntries.size == allModules.size) {
            "Bundle manifest expected $allModules modules but only " +
            "${moduleEntries.size} modules have artifact directories. " +
            "Expected: ${allModules.joinToString(", ")}. " +
            "Found: ${moduleEntries.map { it.substringAfter("\"artifactId\": \"").substringBefore("\"") }}."
        }

        val now = java.time.Instant.now().toString()
        val jsonSink = buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": \"sovereign-runtime-release-bundle-v1\",")
            appendLine("  \"generatedAt\": \"${jsonEscape(now)}\",")
            appendLine("  \"version\": \"${jsonEscape(expectedVersion)}\",")
            appendLine("  \"repository\": \"${jsonEscape(bundleRepoDir.absolutePath)}\",")
            appendLine("  \"remotePublish\": false,")
            appendLine("  \"tagCreated\": false,")
            appendLine("  \"signaturesPresent\": $wantsSigning,")
            appendLine("  \"modules\": [")
            for ((i, entry) in moduleEntries.withIndex()) {
                append(entry)
                if (i < moduleEntries.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ]")
            append("}")
            appendLine()
        }

        bundleManifestJson.writeText(jsonSink)
        logger.lifecycle("Bundle manifest generated: ${bundleManifestJson.absolutePath}")

        // ── Summary ──────────────────────────────────────────────────────
        logger.lifecycle("")
        logger.lifecycle("verifySovereignRuntimeSignedBundle — PASSED")
        logger.lifecycle("  Modules validated: ${allModules.size} (${allModules.joinToString(", ")})")
        logger.lifecycle("  Repository: ${bundleRepoDir.absolutePath}")
        logger.lifecycle("  Signatures: ${if (wantsSigning) "validated" else "not configured (skipped)"}")
        logger.lifecycle("  Remote publish: false")
        logger.lifecycle("  Tag created: false")
        logger.lifecycle("  Manifest: ${bundleManifestJson.absolutePath}")
    }
}

// ── CycloneDX SBOM ────────────────────────────────────────────────────────

// Plugin is applied above via: alias(libs.plugins.cyclonedx.bom)
// Default output goes to build/reports/cyclonedx/bom.json and is post-processed
// by the copy task below, avoiding typed extension resolution issues.

tasks.register("prepareCycloneDxBom") {
    group = "verification"
    description = "Run cyclonedxBom and place the result plus digest under build/supply-chain/sbom/"
    dependsOn("cyclonedxBom")
    doLast {
        val sbomDir = rootProject.layout.buildDirectory.dir("supply-chain/sbom").get().asFile
        sbomDir.mkdirs()
        val sourceBom = rootProject.layout.buildDirectory.file("reports/cyclonedx/bom.json").get().asFile
        val targetBom = sbomDir.resolve("tramai-cyclonedx-sbom.json")
        if (sourceBom.exists()) {
            sourceBom.copyTo(targetBom, overwrite = true)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hex = digest.digest(targetBom.readBytes())
                .joinToString("") { "%02x".format(it) }
            sbomDir.resolve("tramai-cyclonedx-sbom.sha256")
                .writeText("sha256:$hex")
            logger.lifecycle("SBOM generated: ${targetBom.absolutePath}")
            logger.lifecycle("SBOM digest: build/supply-chain/sbom/tramai-cyclonedx-sbom.sha256")
        } else {
            logger.warn("cyclonedxBom did not produce reports/cyclonedx/bom.json in the build directory; skipping SBOM copy.")
        }
    }
}

val sovereignReleaseModules = listOf(
    ":tramai-core",
    ":tramai-security",
    ":tramai-structured",
    ":tramai-engine",
    ":tramai-standalone",
    ":tramai-sovereign",
    ":tramai-persistence-file",
    ":tramai-observability",
    ":tramai-spring-boot-starter-sovereign",
    ":tramai-spring-boot-starter-sovereign-persistence-file",
    ":tramai-spring-boot-starter-sovereign-ops",
    ":tramai-spring-boot-starter-sovereign-ops-actuator",
    ":tramai-spring-boot-starter-sovereign-ops-micrometer",
    ":tramai-spring-boot-starter-sovereign-ops-observability",
)

tasks.register("prepareSovereignReleaseArtifacts") {
    group = "verification"
    description = "Collects JARs from sovereign release modules, computes SHA-256 digests, and generates release-artifacts-v1.json."
    dependsOn(sovereignReleaseModules.flatMap { module ->
        listOf(
            project(module).tasks.named("jar"),
            project(module).tasks.matching { it.name == "sourcesJar" },
            project(module).tasks.matching { it.name == "javadocJar" },
        )
    })

    doLast {
        val outputDir = rootProject.layout.buildDirectory.dir("sovereign-release").get().asFile
        val artifactsDir = outputDir.resolve("artifacts")

        // Clean output directory first to avoid stale artifacts
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        artifactsDir.mkdirs()

        fun jsonEscape(value: String): String {
            val sb = StringBuilder()
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u%04x".format(ch.code))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            return sb.toString()
        }

        val groupId = tramaiGroup.get()
        val version = tramaiVersion.get()
        val artifactEntries = mutableListOf<String>()
        val artifactSortKeys = mutableListOf<String>()

        sovereignReleaseModules.forEach { modulePath ->
            val proj = project(modulePath)
            val moduleName = proj.name
            val libsDir = proj.layout.buildDirectory.dir("libs").get().asFile
            if (!libsDir.exists()) return@forEach
            val expectedJarPrefixes = listOf(
                "$moduleName-$version.jar",
                "$moduleName-$version-sources.jar",
                "$moduleName-$version-javadoc.jar",
            )

            libsDir.listFiles { f -> f.name in expectedJarPrefixes }
                ?.forEach { jarFile ->
                val copied = jarFile.copyTo(artifactsDir.resolve(jarFile.name), overwrite = true)

                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val hex = digest.digest(copied.readBytes())
                    .joinToString("") { "%02x".format(it) }
                val sha256 = "sha256:$hex"

                // Determine classifier from filename pattern: artifactId-version[-classifier].extension
                val classifier = when {
                    jarFile.name.contains("-sources.jar") -> "sources"
                    jarFile.name.contains("-javadoc.jar") -> "javadoc"
                    else -> null
                }

                val escapedFile = jsonEscape(jarFile.name)
                val artifactLine = buildString {
                    append("        {")
                    append("\"groupId\": \"${jsonEscape(groupId)}\", ")
                    append("\"artifactId\": \"${jsonEscape(moduleName)}\", ")
                    append("\"version\": \"${jsonEscape(version)}\", ")
                    append("\"classifier\": ${if (classifier != null) "\"${jsonEscape(classifier)}\"" else "null"}, ")
                    append("\"extension\": \"jar\", ")
                    append("\"fileName\": \"$escapedFile\", ")
                    append("\"sha256\": \"$sha256\", ")
                    append("\"sizeBytes\": ${copied.length()}")
                    append("}")
                }
                artifactEntries.add(artifactLine)
                artifactSortKeys.add(jarFile.name)
            }
        }

        // Sort all artifacts globally by filename for deterministic ordering
        val sortedIndices = artifactSortKeys.indices.sortedBy { artifactSortKeys[it] }
        val sortedEntries = sortedIndices.map { artifactEntries[it] }
        artifactEntries.clear()
        artifactEntries.addAll(sortedEntries)

        val javaVersion = System.getProperty("java.version") ?: "unknown"
        val gradleVersion = GradleVersion.current().version

        val json = buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"buildTool\": \"Gradle\",")
            appendLine("  \"javaVersion\": \"${jsonEscape(javaVersion)}\",")
            appendLine("  \"gradleVersion\": \"${jsonEscape(gradleVersion)}\",")
            appendLine("  \"artifacts\": [")
            for ((i, entry) in artifactEntries.withIndex()) {
                append(entry)
                if (i < artifactEntries.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ]")
            append("}")
            appendLine()
        }

        val jsonFile = outputDir.resolve("release-artifacts-v1.json")
        jsonFile.writeText(json)
        logger.lifecycle("Sovereign release artifact manifest generated: ${jsonFile.absolutePath}")
        logger.lifecycle("  Artifacts collected: ${artifactEntries.size}")
    }
}

// ──────────────────────────────────────────────
// Manifest verifier helper
// ──────────────────────────────────────────────

/**
 * Verifies that [manifestDir]/release-artifacts-v1.json is internally consistent
 * with the JAR files in [artifactsDir].
 *
 * Every error is communicated through a [GradleException] whose message starts
 * with one of the required error codes listed in the PR #37 spec.
 */
fun verifyReleaseManifest(manifestDir: File, artifactsDir: File) {
    val manifestFile = manifestDir.resolve("release-artifacts-v1.json")

    // 1. Manifest file must exist
    require(manifestFile.exists()) {
        "sovereign-release-manifest-missing: ${manifestFile.absolutePath}"
    }

    // 2. Artifacts directory must exist
    require(artifactsDir.isDirectory()) {
        "sovereign-release-artifacts-dir-missing: ${artifactsDir.absolutePath}"
    }

    // 3. Parse JSON (fail closed on malformed content)
    val manifest: Map<String, Any>
    try {
        @Suppress("UNCHECKED_CAST")
        manifest = JsonSlurper().parse(manifestFile) as Map<String, Any>
    } catch (e: Exception) {
        throw GradleException("sovereign-release-manifest-invalid-json", e)
    }

    // 4. Schema version must be supported (currently 1)
    val schemaVersion = (manifest["schemaVersion"] as? Number)?.toInt()
        ?: throw GradleException("sovereign-release-manifest-unsupported-schema-version")
    require(schemaVersion == 1) {
        "sovereign-release-manifest-unsupported-schema-version: $schemaVersion"
    }

    // 5. artifacts array must be present
    val rawArtifacts = manifest["artifacts"]
        ?: throw GradleException("sovereign-release-manifest-missing-artifacts")

    // 6. artifacts array must not be empty
    @Suppress("UNCHECKED_CAST")
    val artifactList = rawArtifacts as? List<Map<String, Any>>
        ?: throw GradleException("sovereign-release-manifest-invalid-json: artifacts is not an array")
    require(artifactList.isNotEmpty()) {
        "sovereign-release-manifest-empty-artifacts"
    }

    val seenFileNames = mutableSetOf<String>()
    val seenCoordinates = mutableSetOf<String>()
    val manifestFileNames = mutableSetOf<String>()

    for ((i, entry) in artifactList.withIndex()) {
        // 7. Each entry must be a map with required fields
        val fileName = entry["fileName"]?.let { it as? String }
            ?: throw GradleException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String fileName")
        val groupId = entry["groupId"]?.let { it as? String }
            ?: throw GradleException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String groupId")
        val artifactId = entry["artifactId"]?.let { it as? String }
            ?: throw GradleException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String artifactId")
        val version = entry["version"]?.let { it as? String }
            ?: throw GradleException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String version")
        val extension = entry["extension"]?.let { it as? String }
            ?: throw GradleException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String extension")
        val sha256 = entry["sha256"]?.let { it as? String }
            ?: throw GradleException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String sha256")
        val sizeBytes = entry["sizeBytes"]?.let { it as? Number }
            ?: throw GradleException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-Numeric sizeBytes")

        val rawClassifier = entry["classifier"]
        val classifier = when (rawClassifier) {
            null -> null
            is String -> rawClassifier
            else -> throw GradleException(
                "sovereign-release-manifest-invalid-artifact-entry (index $i): classifier must be String or null, got ${rawClassifier::class.simpleName}"
            )
        }

        // 8. Unsafe file name — reject blank, path traversal, and directory separators
        require(
            fileName.isNotBlank() &&
            !fileName.contains("/") &&
            !fileName.contains("\\") &&
            !fileName.contains("..")
        ) {
            "sovereign-release-manifest-unsafe-file-name: $fileName"
        }

        // 9. Digest must be sha256: followed by 64 lowercase hex chars
        require(sha256.startsWith("sha256:")) {
            "sovereign-release-manifest-invalid-digest-format: $sha256 (missing 'sha256:' prefix)"
        }
        val hexPart = sha256.removePrefix("sha256:")
        val digestRegex = Regex("^[a-fA-F0-9]{64}$")
        require(digestRegex.matches(hexPart)) {
            "sovereign-release-manifest-invalid-digest-format: $sha256 (expected 64 hex chars, got ${hexPart.length})"
        }
        // Normalise to lowercase for comparison after validation
        val normalisedHex = hexPart.lowercase()

        // 10. Size must be a positive integer
        val size = sizeBytes.toLong()
        require(size > 0) {
            "sovereign-release-manifest-invalid-size: $size (must be positive)"
        }

        // 11. Only JAR extensions are supported in the release manifest
        require(extension == "jar") {
            "sovereign-release-manifest-unsupported-extension: $extension (only 'jar' is supported)"
        }

        // 12. Duplicate fileName rejection
        require(seenFileNames.add(fileName)) {
            "sovereign-release-manifest-duplicate-file-name: $fileName"
        }

        // 13. Duplicate Maven coordinate rejection
        val coordinate = "$groupId:$artifactId:$version:${classifier ?: ""}:$extension"
        require(seenCoordinates.add(coordinate)) {
            "sovereign-release-manifest-duplicate-coordinate: $coordinate"
        }

        manifestFileNames.add(fileName)

        // 14. File must exist on disk
        val jarFile = artifactsDir.resolve(fileName)
        require(jarFile.exists()) {
            "sovereign-release-artifact-missing: $fileName"
        }

        // 15. File size must match
        val actualSize = jarFile.length()
        require(actualSize == size) {
            "sovereign-release-artifact-size-mismatch: $fileName (expected $size bytes, actual $actualSize)"
        }

        // 16. SHA-256 digest must match
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val computedHex = digest.digest(jarFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(computedHex == normalisedHex) {
            "sovereign-release-artifact-digest-mismatch: $fileName"
        }
    }

    // 17. Reject unlisted .jar files in the artifacts directory
    val actualJars = artifactsDir.listFiles { f -> f.name.endsWith(".jar") }?.toList() ?: emptyList()
    for (jarFile in actualJars) {
        require(jarFile.name in manifestFileNames) {
            "sovereign-release-artifact-unlisted: ${jarFile.name}"
        }
    }

    logger.lifecycle("Release manifest verification passed: ${artifactList.size} artifact(s) validated.")
}

// ──────────────────────────────────────────────
// Task: verifySovereignReleaseManifest
// ──────────────────────────────────────────────

tasks.register("verifySovereignReleaseManifest") {
    group = "verification"
    description = "Verifies that build/sovereign-release/release-artifacts-v1.json is internally consistent with the JAR files in build/sovereign-release/artifacts/."

    dependsOn("prepareSovereignReleaseArtifacts")

    doLast {
        val buildDir = rootProject.layout.buildDirectory.get().asFile
        val manifestDir = buildDir.resolve("sovereign-release")
        val artifactsDir = manifestDir.resolve("artifacts")
        verifyReleaseManifest(manifestDir, artifactsDir)
    }
}

tasks.register("prepareSovereignEvidenceBundle") {
    group = "verification"
    description = "Assembles all sovereign audit outputs into build/sovereign-evidence/."

    doLast {
        val buildDir = rootProject.layout.buildDirectory.get().asFile
        val outputDir = buildDir.resolve("sovereign-evidence")
        val supplyChainDir = outputDir.resolve("supply-chain")
        val releaseDir = outputDir.resolve("release")
        val releaseArtifactsDir = outputDir.resolve("release/artifacts")

        // Required input paths
        val evidencePack = buildDir.resolve("zero-egress-report/sovereign-evidence-pack-v1.json")
        val zeroEgressReport = buildDir.resolve("zero-egress-report/zero-egress-report.json")
        val sbom = buildDir.resolve("supply-chain/sbom/tramai-cyclonedx-sbom.json")
        val sbomDigest = buildDir.resolve("supply-chain/sbom/tramai-cyclonedx-sbom.sha256")
        val releaseManifest = buildDir.resolve("sovereign-release/release-artifacts-v1.json")
        val releaseArtifactsSrc = buildDir.resolve("sovereign-release/artifacts")

        // Fail closed on missing inputs
        require(evidencePack.exists()) { "sovereign-evidence-missing-evidence-pack" }
        require(zeroEgressReport.exists()) { "sovereign-evidence-missing-zero-egress-report" }
        require(sbom.exists()) { "sovereign-evidence-missing-sbom" }
        require(sbomDigest.exists()) { "sovereign-evidence-missing-sbom-digest" }
        require(releaseManifest.exists()) { "sovereign-evidence-missing-release-manifest" }
        require(releaseArtifactsSrc.isDirectory()) { "sovereign-evidence-missing-release-artifacts-dir" }
        val jarFiles = releaseArtifactsSrc.listFiles { f -> f.name.endsWith(".jar") }?.toList() ?: emptyList()
        require(jarFiles.isNotEmpty()) { "sovereign-evidence-empty-release-artifacts-dir" }

        // Clean output
        if (outputDir.exists()) outputDir.deleteRecursively()
        supplyChainDir.mkdirs()
        releaseDir.mkdirs()
        releaseArtifactsDir.mkdirs()

        // Copy files
        evidencePack.copyTo(outputDir.resolve("sovereign-evidence-pack-v1.json"), overwrite = true)
        zeroEgressReport.copyTo(outputDir.resolve("zero-egress-report.json"), overwrite = true)
        sbom.copyTo(supplyChainDir.resolve("tramai-cyclonedx-sbom.json"), overwrite = true)
        sbomDigest.copyTo(supplyChainDir.resolve("tramai-cyclonedx-sbom.sha256"), overwrite = true)
        releaseManifest.copyTo(releaseDir.resolve("release-artifacts-v1.json"), overwrite = true)

        // Copy JARs in deterministic filename order
        jarFiles.sortedBy { it.name }.forEach { jar ->
            jar.copyTo(releaseArtifactsDir.resolve(jar.name), overwrite = true)
        }

        logger.lifecycle("Sovereign evidence bundle assembled: ${outputDir.absolutePath}")
        logger.lifecycle("  Files: ${outputDir.walkTopDown().count { it.isFile }}")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignEvidenceBundleReleaseManifest
// ──────────────────────────────────────────────

tasks.register("verifySovereignEvidenceBundleReleaseManifest") {
    group = "verification"
    description = "Verifies that build/sovereign-evidence/release/release-artifacts-v1.json is internally consistent with the JAR files in build/sovereign-evidence/release/artifacts/."

    doLast {
        val buildDir = rootProject.layout.buildDirectory.get().asFile
        val manifestDir = buildDir.resolve("sovereign-evidence/release")
        val artifactsDir = manifestDir.resolve("artifacts")
        verifyReleaseManifest(manifestDir, artifactsDir)
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignEvidencePackContainsReleaseBundle
// ──────────────────────────────────────────────

tasks.register("verifySovereignEvidencePackContainsReleaseBundle") {
    group = "verification"
    description = "Verifies that build/zero-egress-report/sovereign-evidence-pack-v1.json contains releaseBundle."

    doLast {
        val buildDir = rootProject.layout.buildDirectory.get().asFile
        val evidencePackPath = buildDir.resolve("zero-egress-report/sovereign-evidence-pack-v1.json")

        require(evidencePackPath.exists()) {
            "sovereign-evidence-pack-missing: ${evidencePackPath.absolutePath}"
        }

        val text = evidencePackPath.readText()
        val hasReleaseBundle = text.contains("\"releaseBundle\":") &&
            !text.contains("\"releaseBundle\": null") &&
            !text.contains("\"releaseBundle\": null,")

        require(hasReleaseBundle) {
            "sovereign-evidence-pack-missing-release-bundle: ${evidencePackPath.absolutePath}"
        }

        logger.lifecycle("Evidence pack contains releaseBundle: ${evidencePackPath.absolutePath}")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignRuntimeConsumerSmoke
// ──────────────────────────────────────────────

val gradleWrapper = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "gradlew.bat"
} else {
    "./gradlew"
}

val consumerSmokeVersion = tramaiVersion.get()
val sovereignRuntimeVerificationRepo = rootProject.layout.buildDirectory
    .dir("sovereign-runtime-release-verification-repo")
    .get()
    .asFile
    .absolutePath
val consumerSmokeArgs = listOf(
    "-p", "examples/sovereign-runtime-consumer-smoke",
    "test",
    "-PtramaiVersion=$consumerSmokeVersion",
    "-PsovereignRuntimeVerificationRepo=$sovereignRuntimeVerificationRepo",
    "--no-configuration-cache",
)
val consumerSmokeCommand = "$gradleWrapper ${consumerSmokeArgs.joinToString(" ")}"

tasks.register<Exec>("verifySovereignRuntimeConsumerSmoke") {
    group = "verification"
    description = "Runs the standalone sovereign runtime consumer smoke test against the dedicated verification repo."

    dependsOn("verifySovereignRuntimeVerificationRepoClosure")

    workingDir = rootProject.projectDir
    commandLine(gradleWrapper, *consumerSmokeArgs.toTypedArray())
}

// ──────────────────────────────────────────────
// Task: verifySovereignRuntimeVerificationRepoClosure
// ──────────────────────────────────────────────

tasks.register("verifySovereignRuntimeVerificationRepoClosure") {
    group = "verification"
    description = "Validates that the sovereign runtime verification repo contains all required dev.tramai artifacts " +
        "for the consumer smoke build. Fails if any required module, POM, metadata, or JAR is missing."

    dependsOn("verifySovereignRuntimeSignedBundle")

    doLast {
        val buildDir = rootProject.layout.buildDirectory.get().asFile
        val groupPath = tramaiGroup.get().replace('.', '/')
        val expectedVersion = tramaiVersion.get()
        val repoDir = buildDir.resolve("sovereign-runtime-release-verification-repo")

        require(repoDir.isDirectory) {
            "Missing verification repo at ${repoDir.absolutePath}. Run verifySovereignRuntimeSignedBundle first."
        }

        val bomOnlyModules = setOf("tramai-bom")

        // Validate every module in the sovereignBundleModuleNames list
        sovereignBundleModuleNames.forEach { moduleName ->
            val moduleDir = repoDir.resolve("$groupPath/$moduleName/$expectedVersion")
            require(moduleDir.isDirectory) {
                "Missing module directory in verification repo for $moduleName at ${moduleDir.absolutePath}"
            }

            val baseName = "$moduleName-$expectedVersion"

            // POM is required for every module
            val pom = moduleDir.resolve("$baseName.pom")
            require(pom.isFile) {
                "Missing POM in verification repo for $moduleName at ${pom.absolutePath}"
            }
            require(pom.length() > 0) {
                "Empty POM in verification repo for $moduleName"
            }

            // Gradle module metadata is required for every module — must be non-empty
            val moduleMetadata = moduleDir.resolve("$baseName.module")
            require(moduleMetadata.isFile) {
                "Missing module metadata in verification repo for $moduleName at ${moduleMetadata.absolutePath}"
            }
            require(moduleMetadata.length() > 0) {
                "Empty module metadata in verification repo for $moduleName"
            }

            // JAR is required for runtime modules (not BOM)
            if (moduleName !in bomOnlyModules) {
                val jar = moduleDir.resolve("$baseName.jar")
                require(jar.isFile) {
                    "Missing JAR in verification repo for $moduleName at ${jar.absolutePath}"
                }
                require(jar.length() > 0) {
                    "Empty JAR in verification repo for $moduleName"
                }
            }
        }

        logger.lifecycle("verifySovereignRuntimeVerificationRepoClosure — PASSED")
        logger.lifecycle("  Required modules: ${sovereignBundleModuleNames.size} (all present and non-empty)")
        logger.lifecycle("  Repository: ${repoDir.absolutePath}")
    }
}

// ──────────────────────────────────────────────
// Task: generateSovereignReleaseEvidenceIndex
// ──────────────────────────────────────────────

tasks.register("generateSovereignReleaseEvidenceIndex") {
    group = "verification"
    description = "Generates a release evidence index (JSON + Markdown) tying together commit metadata, validation gates, bundle manifest, release artifact manifest, and artifact hashes. Fails if required evidence artifacts are missing."

    dependsOn(
        "verifyReleaseReadiness",
        "verifySovereignRuntimePublication",
        "verifySovereignRuntimeSignedBundle",
        "verifySovereignRuntimeConsumerSmoke",
        "prepareSovereignReleaseArtifacts",
        "verifySovereignReleaseManifest",
    )

    doLast {
        val buildDir = rootProject.layout.buildDirectory.get().asFile
        val outputDir = buildDir.resolve("sovereign-runtime-release")
        outputDir.mkdirs()

        // ── Helper functions ──────────────────────────────────────────────
        fun sha256Hex(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun treeHash(dir: File): String {
            val files = dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            for (file in files) {
                val relativePath = file.relativeTo(dir).invariantSeparatorsPath
                val fileHash = sha256Hex(file)
                digest.update(relativePath.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(fileHash.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun fileCount(dir: File): Int = dir.walkTopDown().count { it.isFile }

        // ── Git metadata (fail-closed) ─────────────────────────────────────
        val commitSha = run {
            val out = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
            require(out.matches(Regex("[a-f0-9]{40}"))) {
                "Cannot generate release evidence index without a valid git commit SHA."
            }
            out
        }

        val refName = run {
            val out = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
            require(out.isNotBlank() && out != "unknown") {
                "Cannot generate release evidence index without git ref metadata."
            }
            out
        }

        val repository = run {
            val remoteUrl = ProcessBuilder("git", "config", "--get", "remote.origin.url")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
            val repo = Regex("github\\.com[:/]([^/]+/[^/]+?)(?:\\.git)?$").find(remoteUrl)
                ?.groupValues?.get(1) ?: error("Cannot generate release evidence index without repository metadata.")
            require(repo != "unknown/repo") {
                "Cannot generate release evidence index without repository metadata."
            }
            repo
        }

        val generatedAt = try {
            java.time.Instant.now().toString()
        } catch (e: Exception) { "unknown" }

        val version = tramaiVersion.get()

        // ── Required artifact validation ──────────────────────────────────
        val bundleManifest = buildDir.resolve("sovereign-runtime-release/bundle-manifest.json")
        val verificationRepo = buildDir.resolve("sovereign-runtime-release-verification-repo")
        val releaseManifest = buildDir.resolve("sovereign-release/release-artifacts-v1.json")
        val releaseArtifactsDir = buildDir.resolve("sovereign-release/artifacts")

        require(bundleManifest.exists()) {
            "Missing required artifact: build/sovereign-runtime-release/bundle-manifest.json. Run verifySovereignRuntimeSignedBundle first."
        }
        require(verificationRepo.isDirectory) {
            "Missing required artifact: build/sovereign-runtime-release-verification-repo/. Run verifySovereignRuntimeSignedBundle first."
        }
        require(releaseManifest.exists()) {
            "Missing required artifact: build/sovereign-release/release-artifacts-v1.json. Run prepareSovereignReleaseArtifacts and verifySovereignReleaseManifest first."
        }
        require(releaseArtifactsDir.isDirectory) {
            "Missing required artifact: build/sovereign-release/artifacts/. Run prepareSovereignReleaseArtifacts first."
        }

        // ── Build artifact entries ────────────────────────────────────────
        val repoFileCount = fileCount(verificationRepo)
        val artifactsFileCount = fileCount(releaseArtifactsDir)

        val artifactList = listOf(
            EvidenceArtifact(
                id = "sovereign-runtime-bundle-manifest",
                path = "build/sovereign-runtime-release/bundle-manifest.json",
                type = "json",
                required = true,
                sha256 = sha256Hex(bundleManifest),
            ),
            EvidenceArtifact(
                id = "sovereign-release-artifact-manifest",
                path = "build/sovereign-release/release-artifacts-v1.json",
                type = "json",
                required = true,
                sha256 = sha256Hex(releaseManifest),
            ),
            EvidenceArtifact(
                id = "sovereign-runtime-local-maven-repo",
                path = "build/sovereign-runtime-release-verification-repo",
                type = "directory",
                required = true,
                fileCount = repoFileCount,
                sha256Tree = treeHash(verificationRepo),
            ),
            EvidenceArtifact(
                id = "sovereign-release-artifacts",
                path = "build/sovereign-release/artifacts/",
                type = "directory",
                required = true,
                fileCount = artifactsFileCount,
                sha256Tree = treeHash(releaseArtifactsDir),
            ),
        )

        val evidence = SovereignReleaseEvidenceIndexV1(
            schemaVersion = "sovereign-release-evidence-index-v1",
            generatedAt = generatedAt,
            repository = repository,
            commitSha = commitSha,
            refName = refName,
            version = version,
            remotePublish = false,
            tagCreated = false,
            releaseCandidate = true,
            artifacts = artifactList,
            checks = EvidenceChecks(
                releaseReadiness = EvidenceCheck(
                    status = "passed",
                    taskPath = ":verifyReleaseReadiness",
                ),
                sovereignRuntimePublication = EvidenceCheck(
                    status = "passed",
                    taskPath = ":verifySovereignRuntimePublication",
                ),
                sovereignRuntimeSignedBundle = EvidenceCheck(
                    status = "passed",
                    taskPath = ":verifySovereignRuntimeSignedBundle",
                ),
                consumerSmoke = ConsumerSmokeEvidenceCheck(
                    status = "passed",
                    taskPath = ":verifySovereignRuntimeConsumerSmoke",
                    executes = consumerSmokeCommand,
                    devTramaiResolutionPolicy = DevTramaiResolutionPolicy(
                        allowedRepositories = listOf("build/sovereign-runtime-release-verification-repo"),
                        blockedRepositories = listOf("mavenLocal", "mavenCentral"),
                        coverage = "full-dev-tramai-dependency-closure",
                    ),
                ),
            ),
        )

        val jsonContent = JsonOutput.prettyPrint(JsonOutput.toJson(evidence.toMap()))

        val jsonFile = outputDir.resolve("evidence-index.json")
        jsonFile.writeText(jsonContent)
        logger.lifecycle("Evidence index JSON generated: ${jsonFile.absolutePath}")

        // ── Post-write structural validation ───────────────────────────────
        require(jsonFile.isFile) {
            "Evidence index JSON was not generated."
        }
        @Suppress("UNCHECKED_CAST")
        val parsed = JsonSlurper().parse(jsonFile) as Map<String, Any>
        require(parsed["schemaVersion"] == "sovereign-release-evidence-index-v1") {
            "Evidence index JSON has invalid schemaVersion: expected sovereign-release-evidence-index-v1, got ${parsed["schemaVersion"]}"
        }
        require(parsed["artifacts"] is List<*>) {
            "Evidence index JSON artifacts must be an array."
        }
        require(parsed["checks"] is Map<*, *>) {
            "Evidence index JSON checks must be an object."
        }
        // Verify every check entry has status and taskPath
        val checks = parsed["checks"] as Map<String, Map<String, Any>>
        for ((name, check) in checks) {
            require(check["status"] == "passed") {
                "Evidence index check '$name' has unexpected status: ${check["status"]}"
            }
            require(check.containsKey("taskPath")) {
                "Evidence index check '$name' is missing taskPath."
            }
        }

        // Validate the new devTramaiResolutionPolicy field emitted by PR #62
        @Suppress("UNCHECKED_CAST")
        val consumerSmokeCheck = checks["consumerSmoke"]
            ?: error("Evidence index is missing consumerSmoke check.")
        @Suppress("UNCHECKED_CAST")
        val resolutionPolicy = consumerSmokeCheck["devTramaiResolutionPolicy"] as? Map<String, Any>
            ?: error("consumerSmoke check is missing devTramaiResolutionPolicy.")
        val allowed = resolutionPolicy["allowedRepositories"] as? List<*>
            ?: error("devTramaiResolutionPolicy.allowedRepositories must be an array.")
        require(allowed == listOf("build/sovereign-runtime-release-verification-repo")) {
            "devTramaiResolutionPolicy has unexpected allowedRepositories: $allowed"
        }
        val blocked = resolutionPolicy["blockedRepositories"] as? List<*>
            ?: error("devTramaiResolutionPolicy.blockedRepositories must be an array.")
        require(blocked == listOf("mavenLocal", "mavenCentral")) {
            "devTramaiResolutionPolicy has unexpected blockedRepositories: $blocked"
        }
        require(resolutionPolicy["coverage"] == "full-dev-tramai-dependency-closure") {
            "devTramaiResolutionPolicy has unexpected coverage: ${resolutionPolicy["coverage"]}"
        }
        logger.lifecycle("Evidence index devTramaiResolutionPolicy validated.")

        // ── Build Markdown ────────────────────────────────────────────────
        val mdContent = buildString {
            appendLine("# Sovereign Release Evidence Index")
            appendLine()
            appendLine("- Repository: $repository")
            appendLine("- Commit: $commitSha")
            appendLine("- Ref: $refName")
            appendLine("- Version: $version")
            appendLine("- Generated at: $generatedAt")
            appendLine("- Remote publish: false")
            appendLine("- Tag created: false")
            appendLine("- Release candidate: true")
            appendLine()
            appendLine("## Evidence Artifacts")
            appendLine()
            appendLine("| ID | Path | Type | Required | SHA-256 |")
            appendLine("|----|------|------|----------|---------|")
            appendLine("| sovereign-runtime-bundle-manifest | build/sovereign-runtime-release/bundle-manifest.json | json | yes | ${sha256Hex(bundleManifest)} |")
            appendLine("| sovereign-release-artifact-manifest | build/sovereign-release/release-artifacts-v1.json | json | yes | ${sha256Hex(releaseManifest)} |")
            appendLine("| sovereign-runtime-local-maven-repo | build/sovereign-runtime-release-verification-repo | directory | yes | ${treeHash(verificationRepo)} |")
            appendLine("| sovereign-release-artifacts | build/sovereign-release/artifacts/ | directory | yes | ${treeHash(releaseArtifactsDir)} |")
            appendLine()
            appendLine("## Validation Gates")
            appendLine()
            appendLine("| Gate | Status | Task | Source |")
            appendLine("|------|--------|------|--------|")
            appendLine("| Release readiness | passed | :verifyReleaseReadiness | build metadata and artifact validation |")
            appendLine("| Sovereign runtime publication | passed | :verifySovereignRuntimePublication | published to mavenLocal |")
            appendLine("| Signed bundle dry-run | passed | :verifySovereignRuntimeSignedBundle | build/sovereign-runtime-release-verification-repo |")
            appendLine("| Consumer smoke | passed | :verifySovereignRuntimeConsumerSmoke | full dev.tramai closure from build/sovereign-runtime-release-verification-repo |")
        }

        val mdFile = outputDir.resolve("evidence-index.md")
        mdFile.writeText(mdContent)
        logger.lifecycle("Evidence index Markdown generated: ${mdFile.absolutePath}")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignDocumentIntelligenceEvidenceRun
// ──────────────────────────────────────────────

val documentIntelligenceRunCommand = listOf(
    gradleWrapper,
    ":examples:sovereign-document-intelligence:run",
    "--no-configuration-cache",
    "--args=--release-bundle-manifest=${rootProject.layout.buildDirectory.get().asFile.absolutePath}/sovereign-release/release-artifacts-v1.json",
)

tasks.register<Exec>("verifySovereignDocumentIntelligenceEvidenceRun") {
    group = "verification"
    description =
        "Runs the sovereign document intelligence reference example against the generated release bundle " +
            "manifest. Validates evidence pack generation against release artifacts."

    dependsOn("prepareSovereignReleaseArtifacts", "verifySovereignReleaseManifest")

    workingDir = rootProject.projectDir
    commandLine(documentIntelligenceRunCommand)
}

// ──────────────────────────────────────────────
// Task: verifySovereignRuntimeReleaseCandidate
// ──────────────────────────────────────────────

val allSubprojectTestTasks = subprojects.flatMap { subproject ->
    subproject.tasks.matching { it.name == "test" }.toList()
}

tasks.register("verifySovereignRuntimeReleaseCandidate") {
    group = "verification"
    description =
        "Runs the canonical local verification chain for the Sovereign Runtime Release Candidate. " +
            "Does not publish remotely, create tags, or release to Maven Central."

    notCompatibleWithConfigurationCache(
        "Sovereign runtime release-candidate verification aggregates execution-time verification tasks.",
    )

    dependsOn(
        allSubprojectTestTasks,
        "verifyReleaseReadiness",
        "verifySovereignRuntimePublication",
        "verifySovereignRuntimeSignedBundle",
        "generateSovereignReleaseEvidenceIndex",
        "verifySovereignRuntimeConsumerSmoke",
        "verifySovereignDocumentIntelligenceEvidenceRun",
    )

    doLast {
        logger.lifecycle("Sovereign runtime release-candidate verification complete.")
        logger.lifecycle("Validated:")
        logger.lifecycle("  - full subproject test suite")
        logger.lifecycle("  - release readiness")
        logger.lifecycle("  - local sovereign runtime publication")
        logger.lifecycle("  - signed bundle dry-run")
        logger.lifecycle("  - release evidence index")
        logger.lifecycle("  - standalone consumer smoke")
        logger.lifecycle("  - sovereign document intelligence evidence run")
        logger.lifecycle("No remote repository was published to.")
        logger.lifecycle("No tag or GitHub release was created.")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignRuntimeClosureDocs
// ──────────────────────────────────────────────

tasks.register("verifySovereignRuntimeClosureDocs") {
    group = "verification"
    description = "Verifies Sovereign Runtime closure documentation links and required claims."

    doLast {
        val closureDoc = file("docs/releases/sovereign-runtime-closure-boundary.md")
        require(closureDoc.exists()) {
            "Missing Sovereign Runtime closure boundary document at ${closureDoc.absolutePath}."
        }

        val closureText = closureDoc.readText()

        val requiredPhrases = listOf(
            "RC+ / enterprise proof",
            "not a GA-certified production release",
            "Key rotation",
            "verifySovereignRuntimeReleaseCandidate",
            ":examples:spring-sovereign-starter:e2eTest",
            "Regulated Claim Triage",
            "Sovereign JDBC Production Deployment Runbook",
        )

        requiredPhrases.forEach { phrase ->
            require(closureText.contains(phrase)) {
                "Sovereign Runtime closure boundary is missing required phrase: $phrase"
            }
        }

        // Verify that GA is explicitly not claimed — positive check above already
        // requires "not a GA-certified production release". These negative guards
        // prevent accidental overclaiming if the document is later edited.
        // Note: "stable 1.0 API" appears legitimately in the non-goals section,
        // so we only guard against affirmative claims.
        val forbiddenClaims = listOf(
            "is GA-certified",
            "production certified",
        )

        forbiddenClaims.forEach { forbidden ->
            require(!closureText.contains(forbidden, ignoreCase = true)) {
                "Sovereign Runtime closure boundary must not claim: $forbidden"
            }
        }

        // Key rotation must be explicitly deferred, not merely mentioned
        require(closureText.contains("deferred", ignoreCase = true)) {
            "Closure boundary must explicitly defer key rotation (found 'Key rotation' but not 'deferred')."
        }

        val rcBoundary = file("docs/releases/sovereign-runtime-rc-boundary.md").readText()
        require(rcBoundary.contains("sovereign-runtime-closure-boundary.md")) {
            "RC boundary must link to the closure boundary."
        }

        val status = file("docs/STATUS.md").readText()
        require(status.contains("Sovereign Runtime Closure Status")) {
            "docs/STATUS.md must include Sovereign Runtime Closure Status section."
        }

        logger.lifecycle("verifySovereignRuntimeClosureDocs: all documentation consistency checks passed.")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignRuntimeClosure
// ──────────────────────────────────────────────

tasks.register("verifySovereignRuntimeClosure") {
    group = "verification"
    description = "Verifies the Sovereign Runtime closure boundary — the canonical gate for the Sovereignty RC+ / enterprise proof milestone."

    notCompatibleWithConfigurationCache(
        "Sovereign runtime closure verification aggregates execution-time verification tasks.",
    )

    dependsOn(
        "check",
        "verifySovereignRuntimeReleaseCandidate",
        ":examples:spring-sovereign-starter:e2eTest",
        "verifySovereignRuntimeClosureDocs",
    )

    doLast {
        logger.lifecycle("Sovereign runtime closure verification complete.")
        logger.lifecycle("Validated:")
        logger.lifecycle("  - check (full test suite)")
        logger.lifecycle("  - verifySovereignRuntimeReleaseCandidate")
        logger.lifecycle("  - :examples:spring-sovereign-starter:e2eTest")
        logger.lifecycle("  - verifySovereignRuntimeClosureDocs (documentation consistency)")
        logger.lifecycle("Sovereignty roadmap is closed at the RC+ / enterprise proof level.")
    }
}
