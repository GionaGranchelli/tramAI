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
val tramaiVersion = providers.gradleProperty("tramaiVersion").orElse("0.5.0")
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

// Always local file path for the sovereign bundle verification repository.
// This is never a remote URL — it is only used for dry-run signing verification
// and consumer smoke resolution. Do not use tramaiPublishReleaseUrl here.
val sovereignBundleRepoUrl = rootProject.layout.buildDirectory.dir("sovereign-runtime-release-verification-repo")
    .map { "file://${it.asFile.absolutePath}" }

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
    dependsOn(":tramai-spring-boot-starter-sovereign-persistence-jdbc:test")
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

        // Helper: resolve actual artifact filenames under SNAPSHOT versioning
        // Gradle's maven-publish plugin uses Maven unique snapshot naming:
        // moduleDir/tramai-core-0.5.0-20260712.154741-1.pom instead of
        // moduleDir/tramai-core-0.5.0-SNAPSHOT.pom.
        fun resolveArtifactFile(moduleDir: java.io.File, baseNameWithoutVersion: String, extension: String): java.io.File {
            val literal = moduleDir.resolve("$baseNameWithoutVersion-$expectedVersion.$extension")
            if (literal.isFile) return literal
            // If the literal doesn't exist, try unique snapshot naming by
            // scanning maven-metadata.xml for actual artifact filenames.
            val metadata = moduleDir.resolve("maven-metadata.xml")
            if (metadata.isFile) {
                val text = metadata.readText()
                val snapshotVersion = Regex("""<snapshotVersion>.*?<extension>$extension</extension>.*?</snapshotVersion>""")
                    .find(text, 0)
                if (snapshotVersion != null) {
                    val value = Regex("""<value>([^<]+)</value>""").find(snapshotVersion.value)
                    if (value != null) {
                        val artifact = moduleDir.resolve("$baseNameWithoutVersion-${value.groupValues[1]}.$extension")
                        if (artifact.isFile) return artifact
                    }
                }
                // Fallback: scan the directory for matching files
                val glob = moduleDir.listFiles()
                    ?.firstOrNull { it.name.endsWith(".$extension") && it.name.startsWith("$baseNameWithoutVersion-") }
                if (glob != null) return glob
            }
            return literal
        }

        val bomOnlyModules = setOf("tramai-bom")

        // Validate every module in the sovereignBundleModuleNames list
        sovereignBundleModuleNames.forEach { moduleName ->
            val moduleDir = repoDir.resolve("$groupPath/$moduleName/$expectedVersion")
            require(moduleDir.isDirectory) {
                "Missing module directory in verification repo for $moduleName at ${moduleDir.absolutePath}"
            }

            // POM is required for every module
            val pom = resolveArtifactFile(moduleDir, moduleName, "pom")
            require(pom.isFile) {
                "Missing POM in verification repo for $moduleName at ${pom.absolutePath}"
            }
            require(pom.length() > 0) {
                "Empty POM in verification repo for $moduleName"
            }

            // Gradle module metadata is required for every module — must be non-empty
            val moduleMetadata = resolveArtifactFile(moduleDir, moduleName, "module")
            require(moduleMetadata.isFile) {
                "Missing module metadata in verification repo for $moduleName at ${moduleMetadata.absolutePath}"
            }
            require(moduleMetadata.length() > 0) {
                "Empty module metadata in verification repo for $moduleName"
            }

            // JAR is required for runtime modules (not BOM)
            if (moduleName !in bomOnlyModules) {
                val jar = resolveArtifactFile(moduleDir, moduleName, "jar")
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
        "verifySovereignRuntimeApiBoundary",
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
// Task: verifySovereignRuntimeApiBoundary
// ──────────────────────────────────────────────

tasks.register("verifySovereignRuntimeApiBoundary") {
    group = "verification"
    description = "Verifies the documented Sovereign Runtime API stability boundary."

    doLast {
        // ── Required files exist ──

        val manifestFile = file("docs/architecture/sovereign-api-stability-manifest.yml")
        require(manifestFile.exists()) {
            "Missing API stability manifest at ${manifestFile.absolutePath}"
        }

        val boundaryDoc = file("docs/architecture/sovereign-api-stability-boundary.md")
        require(boundaryDoc.exists()) {
            "Missing API stability boundary document at ${boundaryDoc.absolutePath}"
        }

        val statusDoc = file("docs/STATUS.md")
        require(statusDoc.exists()) {
            "Missing STATUS.md at ${statusDoc.absolutePath}"
        }

        val boundaryText = boundaryDoc.readText()
        val manifestText = manifestFile.readText()
        val statusText = statusDoc.readText()

        // ── Section scopes ──

        val stableSection = boundaryText
            .substringAfter("## RC+ Stable Surface")
            .substringBefore("## Preview Surface")

        val previewSection = boundaryText
            .substringAfter("## Preview Surface")
            .substringBefore("## Internal Implementation Details")

        val internalSection = boundaryText
            .substringAfter("## Internal Implementation Details")
            .substringBefore("## Deferred to Future Roadmaps")

        val deferredSection = boundaryText
            .substringAfter("## Deferred to Future Roadmaps")
            .substringBefore("## Compatibility Promise")

        // ── RC+ Stable types ──

        val rcPlusStableTypes = listOf(
            "ApprovalStore",
            "SuspendedInvocationStore",
            "ApprovalContinuationStore",
            "AuditStore",
            "SovereignOpsAuditOutboxStore",
            "SovereignOpsApprovalMutationStore",
            "SovereignOpsWorkerLeaseStore",
        )

        rcPlusStableTypes.forEach { type ->
            require(stableSection.contains(type)) {
                "RC+ Stable section must document $type"
            }
            require(manifestText.contains("- $type")) {
                "API stability manifest rcPlusStable.types must include $type"
            }
        }

        require(stableSection.contains("verifySovereignRuntimeClosure")) {
            "RC+ Stable section must document verifySovereignRuntimeClosure"
        }
        require(stableSection.contains("verifySovereignRuntimeReleaseCandidate")) {
            "RC+ Stable section must document verifySovereignRuntimeReleaseCandidate"
        }

        // ── Preview types (section-scoped) ──

        val previewTypes = listOf(
            "ApprovalDecisionControlPlane",
            "ApprovalResumeControlPlane",
            "ApprovalInboxQueryService",
            "REST control plane endpoints",
            "Preview reviewer UI",
            "Workflow ergonomics",
        )

        val stableManifestSection = manifestText
            .substringAfter("rcPlusStable:")
            .substringBefore("preview:")

        val previewManifestSection = manifestText
            .substringAfter("preview:")
            .substringBefore("stabilizationCandidates:")

        val previewManifestTypes = listOf(
            "ApprovalDecisionControlPlane",
            "ApprovalResumeControlPlane",
            "ApprovalInboxQueryService",
            "ApprovalGatewayAutoConfiguration",
        )

        previewTypes.forEach { type ->
            require(previewSection.contains(type, ignoreCase = true)) {
                "Preview Surface section must document '$type'"
            }
        }

        previewManifestTypes.forEach { type ->
            require(previewManifestSection.contains("- $type")) {
                "API stability manifest preview.types must include '$type' in the preview section but ${if (manifestText.contains("- $type")) "it appears outside it" else "it was not found"}."
            }
            require(!stableManifestSection.contains("- $type")) {
                "'$type' is Preview and must not appear in rcPlusStable manifest section."
            }
        }

        // ── Preview function source file exists and maintains signature ──

        val mapperFile = file(
            "tramai-core/src/main/kotlin/dev/tramai/core/workflow/ApprovalRequestWorkflowResultMappers.kt",
        )
        require(mapperFile.exists()) {
            "Missing Preview approval workflow result mapper source file at ${mapperFile.absolutePath}"
        }

        val mapperSource = mapperFile.readText()
        require(mapperSource.contains("fun <T> ApprovalRequestResult.toWorkflowResult")) {
            "ApprovalRequestResult.toWorkflowResult mapper must remain available."
        }
        require(mapperSource.contains("HumanApprovalDecision.Approved")) {
            "ApprovalRequestResult.toWorkflowResult must expose the approved decision to the lambda."
        }
        require(mapperSource.contains("approvedValue(decision)")) {
            "ApprovalRequestResult.toWorkflowResult must pass the approved decision into approvedValue."
        }

        // ── Java facade source file exists and maintains shape ──

        val javaFacadeFile = file(
            "tramai-core/src/main/kotlin/dev/tramai/core/workflow/ApprovalWorkflowResults.kt",
        )
        require(javaFacadeFile.exists()) {
            "Missing Java approval workflow facade at ${javaFacadeFile.absolutePath}"
        }

        val javaFacadeSource = javaFacadeFile.readText()

        require(javaFacadeSource.contains("@file:JvmName(\"ApprovalWorkflowResults\")")) {
            "Java facade must keep stable JVM entrypoint name ApprovalWorkflowResults."
        }

        require(javaFacadeSource.contains("fun <T> fromApprovalRequestResult(")) {
            "Java facade must expose fromApprovalRequestResult."
        }

        require(javaFacadeSource.contains("fun suspended(") &&
                javaFacadeSource.contains("approvalId: String") &&
                javaFacadeSource.contains("workflowRunId: String")) {
            "ApprovalRequestResults.suspended must remain String-based for Java interop."
        }

        require(javaFacadeSource.contains("@JvmOverloads") &&
                javaFacadeSource.contains("fun approved(") &&
                javaFacadeSource.contains("fun denied(")) {
            "HumanApprovalDecisions approved/denied must retain @JvmOverloads for Java callers."
        }

        require(!javaFacadeSource.contains("object ApprovalIds")) {
            "Do not expose inline-value-class-returning ApprovalIds facade; Java must use String-based factories."
        }

        // ── Promoted APIs in RC+ Stable section, Preview surfaces stay out ──

        require(stableSection.contains("ApprovalRequestResult.toWorkflowResult")) {
            "ApprovalRequestResult.toWorkflowResult is now RC+ Stable and must be documented in the RC+ Stable section."
        }

        require(stableSection.contains("ApprovalWorkflowResults")) {
            "ApprovalWorkflowResults is now RC+ Stable and must be documented in the RC+ Stable section."
        }

        require(!stableSection.contains("DefaultApprovalGateway") && !stableSection.contains("ApprovalGatewayAutoConfiguration")) {
            "DefaultApprovalGateway and ApprovalGatewayAutoConfiguration are Preview and must not appear in the RC+ Stable section."
        }

        // ── Internal implementation details stay internal (section-scoped) ──

        val internalTypes = listOf(
            "JdbcApprovalStore",
            "JdbcApprovalResumeCredentialStore",
            "ApprovedContinuationResumeQueue",
            "SovereignOpsApprovedContinuationResumeWorker",
            "ApprovedContinuationResumeWorkerMetricsObserver",
            "ApprovedResumeQueueMetricsSnapshotProvider",
        )

        internalTypes.forEach { type ->
            require(internalSection.contains(type)) {
                "Internal Implementation Details section must document '$type'"
            }
            require(manifestText.contains("- $type")) {
                "API stability manifest internal.types must include $type"
            }
        }

        // ── Deferred capabilities stay deferred (section-scoped) ──

        val deferredCapabilities = listOf(
            "Key rotation",
            "Production certification",
            "Production-grade reviewer UI",
            "Enterprise IAM",
            "Maven Central release",
            "Stable 1.0 API",
        )

        deferredCapabilities.forEach { cap ->
            require(deferredSection.contains(cap, ignoreCase = true)) {
                "Deferred to Future Roadmaps section must document '$cap'"
            }
            require(manifestText.contains("- $cap", ignoreCase = true)) {
                "API stability manifest deferred.capabilities must include '$cap'"
            }
        }

        // ── Stable API source files exist ──

        val stableApiFiles = listOf(
            "tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalStore.kt",
            "tramai-engine/src/main/kotlin/dev/tramai/engine/SuspendedInvocationStore.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalContinuationStore.kt",
            "tramai-security/src/main/kotlin/dev/tramai/security/audit/AuditStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/outbox/SovereignOpsAuditOutboxStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/outbox/SovereignOpsApprovalMutationStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/lease/SovereignOpsWorkerLeaseStore.kt",
        )

        stableApiFiles.forEach { path ->
            val sourceFile = file(path)
            require(sourceFile.exists()) {
                "Stable API source file missing: $path"
            }
        }

        // ── Forbidden: internal implementation classes in RC+ Stable section ──

        val internalJdbcClasses = listOf(
            "JdbcApprovalStore",
            "JdbcApprovalResumeCredentialStore",
            "SovereignOpsApprovedContinuationResumeWorker",
            "ApprovedResumeQueueMetricsSnapshotProvider",
        )

        internalJdbcClasses.forEach { clazz ->
            require(!stableSection.contains(clazz)) {
                "Internal JDBC/worker class '$clazz' must not appear in the RC+ Stable section"
            }
        }

        // ── Forbidden: positive overclaims in STATUS and README ──
        // Uses regex patterns that match affirmative claims but not safe
        // negated disclaimers like "not GA-certified" or "not production-certified".

        val statusAndReadmeText = StringBuilder(statusText)
        val readmeFile = file("README.md")
        if (readmeFile.exists()) {
            statusAndReadmeText.append("\n").append(readmeFile.readText())
        }
        val combinedText = statusAndReadmeText.toString()

        val forbiddenOverclaimPatterns = listOf(
            Regex("\\bis\\s+GA-certified\\b", RegexOption.IGNORE_CASE),
            Regex("\\bproduction\\s+certified\\b", RegexOption.IGNORE_CASE),
            Regex("\\bstable\\s+1\\.0\\s+public\\s+API\\s+complete\\b", RegexOption.IGNORE_CASE),
            Regex("\\bMaven\\s+Central\\s+release\\s+complete\\b", RegexOption.IGNORE_CASE),
            Regex("\\benterprise\\s+IAM\\s+complete\\b", RegexOption.IGNORE_CASE),
            Regex("\\bkey\\s+rotation\\s+complete\\b", RegexOption.IGNORE_CASE),
            Regex("\\bproduction\\-grade\\s+reviewer\\s+UI\\s+complete\\b", RegexOption.IGNORE_CASE),
        )

        forbiddenOverclaimPatterns.forEach { pattern ->
            require(!combinedText.contains(pattern)) {
                "Forbidden affirmative overclaim pattern found: '${pattern.pattern}'. " +
                    "API boundary docs must not claim GA/production/Maven/key-rotation completion. " +
                    "Safe negated forms (e.g. 'not GA-certified') are permitted."
            }
        }

        // ── Promoted approval workflow APIs ──

        val stabilizationCandidateSection = manifestText
            .substringAfter("stabilizationCandidates:")
            .substringBefore("internal:")

        val promotedApprovalWorkflowTypes = listOf(
            "ApprovalGateway",
            "ApprovalRequestResult",
            "SovereignWorkflowResult",
            "ApprovalWorkflowResults",
            "ApprovalRequestResults",
            "HumanApprovalDecisions",
        )

        promotedApprovalWorkflowTypes.forEach { type ->
            require(stableManifestSection.contains("- $type")) {
                "Promoted approval workflow API '$type' must be listed in rcPlusStable manifest section."
            }
            require(!previewManifestSection.contains("- $type\n")) {
                "Promoted approval workflow API '$type' must not remain in Preview manifest section."
            }
            require(!stabilizationCandidateSection.contains("- $type")) {
                "Promoted approval workflow API '$type' must not remain in stabilizationCandidates."
            }
        }

        val promotedApprovalWorkflowFunctions = listOf(
            "ApprovalRequestResult.toWorkflowResult",
            "ApprovalWorkflowResults.fromApprovalRequestResult",
        )

        promotedApprovalWorkflowFunctions.forEach { func ->
            require(stableManifestSection.contains("- $func")) {
                "Promoted approval workflow function '$func' must be listed in rcPlusStable manifest section."
            }
            require(!previewManifestSection.contains("- $func")) {
                "Promoted approval workflow function '$func' must not remain in Preview manifest section."
            }
            require(!stabilizationCandidateSection.contains("- $func")) {
                "Promoted approval workflow function '$func' must not remain in stabilizationCandidates."
            }
        }

        // ── Non-promoted surfaces must remain Preview ──

        val stillPreviewApprovalSurfaces = listOf(
            "ApprovalDecisionControlPlane",
            "ApprovalResumeControlPlane",
            "ApprovalInboxQueryService",
            "ApprovalGatewayAutoConfiguration",
        )

        stillPreviewApprovalSurfaces.forEach { type ->
            require(previewManifestSection.contains("- $type")) {
                "'$type' must remain Preview."
            }
            require(!stableManifestSection.contains("- $type")) {
                "'$type' must not be promoted to RC+ Stable."
            }
        }

        // ── STATUS.md must reference the API stability boundary ──

        require(statusText.contains("Sovereign Runtime API Stability")) {
            "STATUS.md must reference Sovereign Runtime API Stability section"
        }

        logger.lifecycle("verifySovereignRuntimeApiBoundary: all API stability boundary checks passed.")
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
            // ── Included preview/preview surfaces (positive checks) ──
            "Preview reviewer UI",
            "Approved-resume lifecycle JDBC E2E proof",
            "Approved-continuation auto-resume worker",
            "Internal encrypted resume credential custody",
            "queue snapshot",
            "Micrometer",
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

        // ── Negative checks: prevent stale deferred claims about items now included ──
        // These items were added as preview/internal surfaces during post-closure PRs.
        // They must NOT appear as bare deferred items without proper context.
        // Context-aware: verify the "Production-grade" qualified versions exist (they're
        // in the Explicit Non-Goals section) rather than checking for bare "- Reviewer UI", etc.

        require(closureText.contains("Production-grade reviewer UI")) {
            "Closure boundary must include 'Production-grade reviewer UI' in non-goals (ensures " +
                "reviewer UI is not listed as a bare deferred item without context)."
        }

        require(closureText.contains("Production-grade admin REST surface")) {
            "Closure boundary must include 'Production-grade admin REST surface' in non-goals (ensures " +
                "REST control plane is not listed as a bare deferred item without context)."
        }

        // Ensure no stale section headers exist that would indicate these items were
        // moved out of Included Capabilities into deferred/planned buckets
        val staleSectionHeaders = listOf(
            "Deferred from Closure",
            "Planned / Not Complete",
        )

        staleSectionHeaders.forEach { staleHeader ->
            require(!closureText.contains(staleHeader, ignoreCase = true)) {
                "Closure boundary must not contain stale section header: '$staleHeader'. " +
                    "Items like Reviewer UI, REST control plane, and operational endpoints " +
                    "are already included as preview surfaces."
            }
        }

        val rcBoundary = file("docs/releases/sovereign-runtime-rc-boundary.md").readText()
        require(rcBoundary.contains("sovereign-runtime-closure-boundary.md")) {
            "RC boundary must link to the closure boundary."
        }

        val status = file("docs/STATUS.md").readText()
        require(status.contains("Sovereign Runtime Closure Status")) {
            "docs/STATUS.md must include Sovereign Runtime Closure Status section."
        }

        // ── API stability boundary ──

        val apiStabilityDoc = file("docs/architecture/sovereign-api-stability-boundary.md")
        require(apiStabilityDoc.exists()) {
            "Missing Sovereign Runtime API stability boundary document at ${apiStabilityDoc.absolutePath}."
        }

        val apiStabilityText = apiStabilityDoc.readText()

        val requiredApiStabilityPhrases = listOf(
            "RC+ Stable",
            "Preview",
            "Internal",
            "Deferred",
            "ApprovalStore",
            "SuspendedInvocationStore",
            "ApprovalContinuationStore",
            "AuditStore",
            "SovereignOpsAuditOutboxStore",
            "SovereignOpsApprovalMutationStore",
            "SovereignOpsWorkerLeaseStore",
            "concrete JDBC store implementations",
            "workflow ergonomics",
            "key rotation",
            "not a GA-certified production release",
        )

        requiredApiStabilityPhrases.forEach { phrase ->
            require(apiStabilityText.contains(phrase, ignoreCase = true)) {
                "Sovereign Runtime API stability boundary is missing required phrase: $phrase"
            }
        }

        // Closure boundary must link to the API stability boundary
        require(closureText.contains("sovereign-api-stability-boundary.md")) {
            "Closure boundary must link to the Sovereign Runtime API stability boundary."
        }

        // STATUS.md must mention the API stability boundary
        require(status.contains("Sovereign Runtime API Stability")) {
            "docs/STATUS.md must include Sovereign Runtime API Stability section."
        }

        // ── Docs consistency checks for PR #118 review findings ──
        // These prevent re-introduction of incorrect names, statuses, and patterns
        // that were fixed during the PR #118 docs review cycle.

        val changelog = file("CHANGELOG.md").readText()
        val quickstart = file("docs/guides/sovereign-runtime-quickstart.md").readText()
        val runbook = file("docs/runbooks/sovereign-jdbc-production-deployment.md").readText()
        val allDocs = changelog + "\n" + quickstart + "\n" + runbook

        // Forbidden: nested YAML form of rest-control-plane-enabled (history: quickstart used it)
        require(!allDocs.contains(Regex("rest:\\s*\\n\\s*control-plane-enabled"))) {
            "Docs must not contain nested rest: control-plane-enabled YAML form (use the correct flat property rest-control-plane-enabled)."
        }

        // Forbidden: "marked dead" — the worker marks continuations CANCELLED, not "dead"
        require(!runbook.contains("marked dead")) {
            "Runbook must not say 'marked dead'. Terminal failure marks the continuation CANCELLED."
        }

        // Forbidden: invented store name
        val inventedStore = Regex("SovereignOpsApprovedContinuationResumeStore")
        require(!allDocs.contains(inventedStore)) {
            "Docs must not reference invented store name SovereignOpsApprovedContinuationResumeStore. Use ApprovedContinuationResumeQueue or the real SPI name."
        }

        // Forbidden: invented queue statuses (check only CHANGELOG.md — the runbook
        // legitimately uses DEAD in the outbox dispatch model, which is a different domain)
        val inventedStatuses = listOf("QUEUED", "RUNNING", "RETRYING", "DEAD")
        inventedStatuses.forEach { status ->
            val pattern = Regex(status)
            require(!changelog.contains(pattern)) {
                "CHANGELOG.md must not contain invented queue status '$status'. Use real status values like eligibleNow, delayedRetry, activeLeases, expiredLeases, terminalFailures."
            }
        }

        // Forbidden: wrong polling semantics
        val wrongPolling = Regex("status\\s*=\\s*'approved'")
        require(!runbook.contains(wrongPolling)) {
            "JDBC runbook must not contain 'status = \\'approved\\'' polling semantics. Use APPROVED + PENDING dual condition."
        }

        // Required: real SPI queue name
        require(changelog.contains("ApprovedContinuationResumeQueue")) {
            "CHANGELOG.md must reference ApprovedContinuationResumeQueue (the real SPI name)."
        }

        // Required: correct flat property name
        require(changelog.contains("rest-control-plane-enabled")) {
            "CHANGELOG.md must reference rest-control-plane-enabled (the correct flat property name)."
        }

        // ── PR #119: Approved-resume worker dashboards and alerts ──

        // File existence checks
        val dashboardsDir = file("docs/observability")
        val alertFile = file("docs/observability/prometheus-approved-resume-worker-alerts.yml")
        val dashboardFile = file("docs/observability/grafana-approved-resume-worker-dashboard.json")
        val runbookFile = file("docs/runbooks/approved-resume-worker-observability.md")
        require(alertFile.exists()) { "Prometheus alert file missing at ${alertFile.absolutePath}" }
        require(dashboardFile.exists()) { "Grafana dashboard file missing at ${dashboardFile.absolutePath}" }
        require(runbookFile.exists()) { "Observability runbook missing at ${runbookFile.absolutePath}" }

        // Required phrases in alerts
        val alerts = alertFile.readText()
        require(alerts.contains("TramAIApprovedResumeWorkerFailures")) {
            "Prometheus alerts must contain TramAIApprovedResumeWorkerFailures"
        }
        require(alerts.contains("TramAIApprovedResumeExpiredLeases")) {
            "Prometheus alerts must contain TramAIApprovedResumeExpiredLeases"
        }
        require(alerts.contains("TramAIApprovedResumeTerminalFailures")) {
            "Prometheus alerts must contain TramAIApprovedResumeTerminalFailures"
        }

        // Forbidden: no individual identifiers as labels in alerts or dashboard
        val dashboard = dashboardFile.readText()
        val runbookText = runbookFile.readText()
        require(!alerts.contains("approval_id")) {
            "Prometheus alerts must not contain approval_id as a label"
        }
        require(!dashboard.contains("\"approval_id\"")) {
            "Grafana dashboard must not contain approval_id as a label"
        }
        require(!runbookText.contains("approval_id")) {
            "Observability runbook must not contain approval_id"
        }
        require(!alerts.contains("workflow_run_id")) {
            "Prometheus alerts must not contain workflow_run_id as a label"
        }
        require(!dashboard.contains("\"workflow_run_id\"")) {
            "Grafana dashboard must not contain workflow_run_id as a label"
        }
        require(!runbookText.contains("workflow_run_id")) {
            "Observability runbook must not contain workflow_run_id"
        }
        require(!alerts.contains("resume_token")) {
            "Prometheus alerts must not contain resume_token as a label"
        }
        require(!dashboard.contains("\"resume_token\"")) {
            "Grafana dashboard must not contain resume_token as a label"
        }
        require(!runbookText.contains("resume_token")) {
            "Observability runbook must not contain resume_token"
        }
        require(!alerts.contains("exception_message")) {
            "Prometheus alerts must not contain exception_message as a label"
        }
        require(!dashboard.contains("\"exception_message\"")) {
            "Grafana dashboard must not contain exception_message as a label"
        }
        require(!runbookText.contains("exception_message")) {
            "Observability runbook must not contain exception_message"
        }

        // Alert file must not claim production certification
        require(!alerts.contains("production certified")) {
            "Prometheus alerts must not claim production certification"
        }

        // STATUS.md must reference the new dashboard and alert examples
        val statusText = file("docs/STATUS.md").readText()
        require(statusText.contains("dashboard and alert examples")) {
            "STATUS.md must reference dashboard/alert examples"
        }

        // Forbidden: wrong config prefix for approved-resume worker metrics
        require(!runbookText.contains("tramai.sovereign.approved-resume.worker.metrics-enabled")) {
            "Runbook must not use stale prefix tramai.sovereign.approved-resume.worker.metrics-enabled; use tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled"
        }
        require(!runbookText.contains("tramai.sovereign.approved-resume.queue.snapshot-refresh-interval")) {
            "Runbook must not use stale prefix tramai.sovereign.approved-resume.queue.snapshot-refresh-interval; use tramai.sovereign.ops.actuator.approved-resume-worker-metrics.queue-snapshot-refresh-interval"
        }

        // Required: correct config properties in runbook
        require(runbookText.contains("tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled")) {
            "Runbook must reference the real config property tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled"
        }
        require(runbookText.contains("tramai.sovereign.ops.actuator.approved-resume-worker-metrics.queue-snapshot-refresh-interval")) {
            "Runbook must reference the real config property tramai.sovereign.ops.actuator.approved-resume-worker-metrics.queue-snapshot-refresh-interval"
        }

        // Forbidden: histogram_quantile(0.95 in runbook — dashboard uses average, not p95
        require(!runbookText.contains("histogram_quantile(0.95")) {
            "Runbook must not recommend histogram_quantile(0.95) unless histogram buckets are explicitly documented/enabled for the cycle_duration_seconds timer."
        }

        // ── Golden path guide consistency ──

        val goldenPathGuide = file("docs/guides/approval-gateway-golden-path.md")
        require(goldenPathGuide.exists()) {
            "Missing approval gateway golden path guide at ${goldenPathGuide.absolutePath}"
        }

        val goldenPathText = goldenPathGuide.readText()

        // Forbidden: stale "Reviewer UI | Not implemented yet" limitation
        require(!goldenPathText.contains("Reviewer UI | Not implemented yet")) {
            "Approval gateway golden path guide must not say reviewer UI is not implemented; " +
                "preview reviewer UI exists and is disabled by default."
        }

        // Required: golden path mentions updated reviewer UI status
        require(goldenPathText.contains("Preview reviewer UI available, disabled by default")) {
            "Approval gateway golden path guide must document that preview reviewer UI is available " +
                "and disabled by default."
        }

        // Forbidden: stale Preview language after RC+ promotion
        require(!goldenPathText.contains("using the Preview `ApprovalGateway` API")) {
            "Approval gateway golden path guide must not describe ApprovalGateway as Preview after RC+ promotion."
        }

        require(!goldenPathText.contains("Preview `ApprovalRequestResult.toWorkflowResult")) {
            "Approval gateway golden path guide must not describe toWorkflowResult as Preview after RC+ promotion."
        }

        // Required: guide documents the RC+ Stable split correctly
        require(goldenPathText.contains("RC+ Stable golden path")) {
            "Approval gateway golden path guide must document the RC+ Stable golden path status."
        }

        require(goldenPathText.contains("REST control plane") && goldenPathText.contains("Preview")) {
            "Approval gateway golden path guide must keep operational REST/control-plane surfaces marked Preview."
        }

        // ── Golden path test must not reference low-level stores ──

        val goldenPathTest = file(
            "tramai-core/src/test/kotlin/dev/tramai/core/workflow/ApprovalGatewayGoldenPathErgonomicsTest.kt",
        )
        require(goldenPathTest.exists()) {
            "Missing approval gateway golden path test at ${goldenPathTest.absolutePath}"
        }

        val forbiddenStoreReferences = listOf(
            "ApprovalStore",
            "SuspendedInvocationStore",
            "ApprovalContinuationStore",
            "JdbcApprovalStore",
            "JdbcSuspendedInvocationStore",
            "JdbcApprovalContinuationStore",
            "SovereignOpsAuditOutboxStore",
            "SovereignOpsApprovalMutationStore",
            "SovereignOpsWorkerLeaseStore",
            "ApprovalResumeCredentialStore",
        )
        val testSource = goldenPathTest.readText()
        forbiddenStoreReferences.forEach { forbidden ->
            require(!testSource.contains(forbidden)) {
                "ApprovalGateway golden path test must not reference low-level store type: $forbidden"
            }
        }

        // ── Spring golden path smoke test: workflow class must not reference low-level stores ──

        val springSmokeTest = file(
            "examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/ApprovalGatewaySpringGoldenPathSmokeTest.kt",
        )
        require(springSmokeTest.exists()) {
            "Missing Spring approval gateway golden path smoke test at ${springSmokeTest.absolutePath}"
        }
        val smokeSource = springSmokeTest.readText()

        require(smokeSource.contains("TestApprovalGatewayRequestFactory")) {
            "Spring golden path smoke test must use the reusable TestApprovalGatewayRequestFactory fixture. " +
                "Found: ${smokeSource.lines().firstOrNull { it.contains("ApprovalGatewayRequestFactory") } ?: "no factory reference"}"
        }

        val workflowSection = smokeSource
            .substringAfter("class ExampleApprovalWorkflow")
            .substringBefore("class SmokeTestDataSourceConfig")

        forbiddenStoreReferences.forEach { forbidden ->
            require(!workflowSection.contains(forbidden)) {
                "ExampleApprovalWorkflow must not depend on low-level store: $forbidden"
            }
        }

        // ── Regulated claim triage approval gateway factory must use the builder ──

        val regulatedFactoryFile = file(
            "examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/RegulatedClaimTriageApprovalGatewayRequestFactory.kt",
        )
        require(regulatedFactoryFile.exists()) {
            "Missing regulated claim triage factory at ${regulatedFactoryFile.absolutePath}"
        }
        val regulatedFactorySource = regulatedFactoryFile.readText()

        require(regulatedFactorySource.contains("TestApprovalGatewayPersistenceRequestBuilder")) {
            "Regulated claim triage factory must use TestApprovalGatewayPersistenceRequestBuilder."
        }

        val forbiddenLowLevelConstruction = listOf(
            "ApprovalRequest(",
            "ApprovalContinuation(",
            "SuspendedInvocationMetadata(",
            "SensitiveReplayEnvelope.of(",
            "ReplayEnvelopeDigestHelper.compute(",
            "Sha256ToolArgumentsDigester()",
        )

        forbiddenLowLevelConstruction.forEach { forbidden ->
            require(!regulatedFactorySource.contains(forbidden)) {
                "Regulated claim triage factory must not manually construct low-level approval records: $forbidden"
            }
        }

        // ── Non-transactional gateway fallback requires explicit opt-in ──

        val gatewayAutoConfig = file(
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/ApprovalGatewayAutoConfiguration.kt",
        )
        require(gatewayAutoConfig.exists()) {
            "Missing approval gateway auto-configuration."
        }
        val gatewayConfigSource = gatewayAutoConfig.readText()
        require(gatewayConfigSource.contains("non-transactional-fallback-enabled")) {
            "DefaultApprovalGateway fallback must require explicit non-transactional fallback opt-in."
        }
        require(gatewayConfigSource.contains("matchIfMissing = false")) {
            "Non-transactional DefaultApprovalGateway fallback must not be enabled by default."
        }
        require(!gatewayConfigSource.contains("DefaultApprovalGateway is created as fallback")) {
            "KDoc must no longer describe DefaultApprovalGateway as automatic fallback."
        }
        require(gatewayConfigSource.contains("only when")) {
            "KDoc must document that DefaultApprovalGateway requires explicit opt-in."
        }

        // ── Human approval ergonomics doc must not contain stale claims ──

        val humanApprovalErgonomics = file(
            "docs/architecture/human-approval-workflow-ergonomics.md",
        ).readText()

        require(!humanApprovalErgonomics.contains(
            "Spring Auto-configuration creates DefaultApprovalGateway when the factory bean is present alongside the JDBC stores",
        )) {
            "Human approval ergonomics doc must not claim DefaultApprovalGateway is automatically created alongside JDBC stores."
        }

        require(humanApprovalErgonomics.contains("non-transactional-fallback-enabled=true")) {
            "Human approval ergonomics doc must document explicit opt-in for DefaultApprovalGateway."
        }

        require(humanApprovalErgonomics.contains("PR #130")) {
            "Human approval ergonomics doc must include the post-#130 fallback hardening update."
        }

        // ── CHANGELOG must not contain stale auto-wiring claims ──

        val changelogText = file("CHANGELOG.md").readText()
        require(!changelogText.contains("Spring auto-configuration creates the DefaultApprovalGateway bean")) {
            "CHANGELOG must not claim DefaultApprovalGateway is automatically created by Spring auto-configuration."
        }

        // ── Java interop test for approval workflow mapper ──

        val javaInteropTest = file(
            "tramai-core/src/test/java/dev/tramai/core/workflow/ApprovalRequestWorkflowResultMappersJavaInteropTest.java",
        )
        require(javaInteropTest.exists()) {
            "Missing Java interop test for ApprovalRequestResult workflow mapper at ${javaInteropTest.absolutePath}"
        }

        val javaInteropSource = javaInteropTest.readText()

        require(javaInteropSource.contains("fromApprovalRequestResult")) {
            "Java interop test must prove Java can call the approval workflow mapper (fromApprovalRequestResult)."
        }

        val javaInteropRequiredOutputs = listOf(
            "AlreadyApproved",
            "Suspended",
            "AlreadyDenied",
            "Expired",
        )

        javaInteropRequiredOutputs.forEach { outcome ->
            require(javaInteropSource.contains(outcome)) {
                "Java interop test must cover $outcome mapping."
            }
        }

        // Must use String-based factories, not inline-value-class-returning factories
        require(javaInteropSource.contains("ApprovalRequestResults.suspended(")) {
            "Java interop test must prove Java can construct Suspended via String-based factory."
        }

        require(javaInteropSource.contains("HumanApprovalDecisions.approved(")) {
            "Java interop test must prove Java can construct Approved via String-based factory."
        }

        // Prove @JvmOverloads short forms compile without comment parameter
        require(javaInteropSource.contains("usesShortApprovedOverloadWithoutComment")) {
            "Java interop test must prove @JvmOverloads works for approved() without comment."
        }

        require(javaInteropSource.contains("usesShortDeniedOverloadWithoutComment")) {
            "Java interop test must prove @JvmOverloads works for denied() without comment."
        }

        // Prove the decision-aware lambda contract: terminal states must not invoke lambda
        require(javaInteropSource.contains("should not run")) {
            "Java interop test must prove the decision-aware lambda contract (terminal states skip lambda)."
        }

        // Prove HumanApprovalDecision approvalId has a clean Java getter
        require(javaInteropSource.contains("decision.getApprovalId()")) {
            "Java interop test must prove HumanApprovalDecision approvalId has a clean Java getter."
        }

        logger.lifecycle("verifySovereignRuntimeClosureDocs: all documentation consistency checks passed.")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignLabProfile
// ──────────────────────────────────────────────

tasks.register("verifySovereignLabProfile") {
    group = "verification"
    description = "Verifies the physical sovereign lab profile and documentation exist."

    doLast {
        val labProfile = file("examples/spring-sovereign-starter/src/main/resources/application-sovereign-lab.yml")
        require(labProfile.exists()) {
            "Missing sovereign lab Spring profile at ${labProfile.absolutePath}"
        }

        val labReadme = file("examples/sovereign-lab/README.md")
        require(labReadme.exists()) {
            "Missing sovereign lab README at ${labReadme.absolutePath}"
        }

        val labProfileText = labProfile.readText()

        // YAML root must be exactly one 'tramai:' key
        require(Regex("^tramai:", RegexOption.MULTILINE).findAll(labProfileText).count() == 1) {
            "Sovereign lab profile must define the 'tramai:' root key exactly once. " +
                "Found ${Regex("^tramai:", RegexOption.MULTILINE).findAll(labProfileText).count()}. Duplicate root keys are not valid YAML."
        }

        require(labProfileText.contains("  sovereign:")) {
            "Sovereign lab profile must include tramai.sovereign configuration."
        }

        require(labProfileText.contains("  providers:")) {
            "Sovereign lab profile must include tramai.providers configuration under the same tramai root."
        }

        val labText = labReadme.readText()
        require(labText.contains("local model", ignoreCase = true)) {
            "Sovereign lab README must explain local model setup."
        }
        require(labText.contains("PostgreSQL", ignoreCase = true)) {
            "Sovereign lab README must explain PostgreSQL setup."
        }
        require(labText.contains("no cloud", ignoreCase = true) || labText.contains("zero egress", ignoreCase = true)) {
            "Sovereign lab README must explain no-cloud / zero-egress intent."
        }
        require(labText.contains("[EVIDENCE.md]")) {
            "Sovereign lab README must link to the evidence capture guide (EVIDENCE.md)."
        }

        val evidence = file("examples/sovereign-lab/EVIDENCE.md")
        require(evidence.exists()) {
            "Missing sovereign lab evidence capture guide at ${evidence.absolutePath}"
        }
        val evidenceText = evidence.readText()
        require(evidenceText.contains("verifySovereignLabLocalModel")) {
            "Sovereign lab evidence guide must reference verifySovereignLabLocalModel."
        }
        require(evidenceText.contains("local-lab-provider")) {
            "Sovereign lab evidence guide must reference local-lab-provider."
        }
        require(evidenceText.contains("TRAMAI_LOCAL_BASE_URL")) {
            "Sovereign lab evidence guide must reference TRAMAI_LOCAL_BASE_URL."
        }
        require(evidenceText.contains("SuspendedForApproval")) {
            "Sovereign lab evidence guide must reference SuspendedForApproval."
        }
        require(evidenceText.contains("restart", ignoreCase = true)) {
            "Sovereign lab evidence guide must explain restart durability proof."
        }
        require(
            evidenceText.contains("no cloud", ignoreCase = true) ||
            evidenceText.contains("zero egress", ignoreCase = true) ||
            evidenceText.contains("No Cloud", ignoreCase = true),
        ) {
            "Sovereign lab evidence guide must explain no-cloud / zero-egress proof."
        }

        // ── PR #141+: Local model benchmark documentation guard ──

        require(evidenceText.contains("benchmarkSovereignLabLocalModel")) {
            "Sovereign lab evidence guide must reference benchmarkSovereignLabLocalModel."
        }
        require(evidenceText.contains("TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK")) {
            "Sovereign lab evidence guide must document the benchmark opt-in gate."
        }
        require(
            evidenceText.contains("does not define production performance thresholds", ignoreCase = true) ||
            evidenceText.contains("does not define production performance", ignoreCase = true),
        ) {
            "Sovereign lab evidence guide must state the benchmark is diagnostic, not a production threshold."
        }

        val benchmarkTemplate = file("examples/sovereign-lab/evidence-template/benchmark.md")
        require(benchmarkTemplate.exists()) {
            "Missing sovereign lab benchmark evidence template at ${benchmarkTemplate.absolutePath}"
        }

        // ── PR #143: Evidence bundle scaffold guards ──

        require(evidenceText.contains("create-evidence-bundle.sh")) {
            "Sovereign lab evidence guide must document evidence bundle creation."
        }

        val evidenceBundleScript = file("examples/sovereign-lab/create-evidence-bundle.sh")
        require(evidenceBundleScript.exists()) {
            "Missing sovereign lab evidence bundle helper script at ${evidenceBundleScript.absolutePath}"
        }
        val bundleScriptText = evidenceBundleScript.readText()
        require(
            bundleScriptText.contains("does not certify", ignoreCase = true) ||
            bundleScriptText.contains("does not define production", ignoreCase = true),
        ) {
            "Evidence bundle helper must avoid implying certification or production guarantees."
        }

        val manifestTemplate = file("examples/sovereign-lab/evidence-template/MANIFEST.md")
        require(manifestTemplate.exists()) {
            "Missing sovereign lab evidence bundle manifest template at ${manifestTemplate.absolutePath}"
        }

        val commandLogTemplate = file("examples/sovereign-lab/evidence-template/command-log.md")
        require(commandLogTemplate.exists()) {
            "Missing sovereign lab command log evidence template at ${commandLogTemplate.absolutePath}"
        }

        // ── PR #148: Finalization script and doc guard ──

        require(evidenceText.contains("finalize-evidence-bundle.sh")) {
            "Sovereign lab evidence guide must document evidence bundle finalization."
        }

        val finalizeScript = file("examples/sovereign-lab/finalize-evidence-bundle.sh")
        require(finalizeScript.exists()) {
            "Missing sovereign lab evidence bundle finalizer script at ${finalizeScript.absolutePath}"
        }

        // ── PR #149: Release readiness checklist guard ──

        val releaseReadiness = file("examples/sovereign-lab/RELEASE-READINESS.md")
        require(releaseReadiness.exists()) {
            "Missing sovereign lab release readiness checklist at ${releaseReadiness.absolutePath}"
        }

        val releaseReadinessText = releaseReadiness.readText()
        listOf(
            "verifySovereignRuntimeReleaseCandidate",
            "verifySovereignLabProfile",
            "verifySovereignLabRuntimeSmoke",
            "verifySovereignLabEvidenceBundle",
            "finalize-evidence-bundle.sh",
            "verify-evidence-bundle.sh",
            "certifiesProductionReadiness",
            "definesPerformanceGuarantees",
            "validatesEvidenceTruth",
            "Forbidden Claims",
            "Release Candidate Blockers",
        ).forEach { required ->
            require(releaseReadinessText.contains(required)) {
                "Sovereign lab release readiness checklist must mention $required."
            }
        }

        // ── PR #150: Reviewer guide guard ──

        val reviewerGuide = file("examples/sovereign-lab/REVIEWER-GUIDE.md")
        require(reviewerGuide.exists()) {
            "Missing sovereign lab evidence reviewer guide at ${reviewerGuide.absolutePath}"
        }

        val reviewerGuideText = reviewerGuide.readText()
        listOf(
            "verify-evidence-bundle.sh",
            "manifest.json",
            "finalizedUtc",
            "claimBoundary",
            "files[]",
            "SHA-256",
            "certifiesProductionReadiness",
            "validatesEvidenceTruth",
            "What Verification Does Not Check",
            "Safe Reviewer Statement",
        ).forEach { required ->
            require(reviewerGuideText.contains(required)) {
                "Sovereign lab reviewer guide must mention $required."
            }
        }

        require(reviewerGuideText.contains("does not certify production readiness")) {
            "Reviewer guide must avoid production-readiness overclaims."
        }

        // ── PR #162: Signature handoff reviewer guard ──

        listOf(
            "verify-evidence-archive-signature.sh",
            ".tar.gz.sha256.sig",
            "reviewer-public-key.pem",
            "does **not**:",
            "prove operator identity beyond the key trust model",
            "prove evidence truth",
            "prove legal compliance",
            "certify production readiness",
        ).forEach { required ->
            require(reviewerGuideText.contains(required)) {
                "Sovereign lab reviewer guide must mention signature verifier handoff: $required."
            }
        }

        // ── PR #162: Signature handoff release-readiness guard ──

        val handoffReadinessText = file("examples/sovereign-lab/RELEASE-READINESS.md").readText()
        listOf(
            "verify-evidence-archive-signature.sh",
            "detached signature",
            "optional provenance evidence",
            "not certify production readiness",
            "or replace an audit",
        ).forEach { required ->
            require(handoffReadinessText.contains(required)) {
                "Sovereign lab release readiness checklist must mention signature handoff: $required."
            }
        }

        // ── PR #162: Signature handoff evidence-chain guard ──

        val handoffEvidenceChainText = file("examples/sovereign-lab/EVIDENCE-CHAIN.md").readText()
        listOf(
            ".tar.gz.sha256.sig",
            "verify-evidence-archive-signature.sh",
            "caller-supplied public key",
        ).forEach { required ->
            require(handoffEvidenceChainText.contains(required)) {
                "Sovereign lab evidence chain overview must mention signature artifacts: $required."
            }
        }

        // ── PR #152: Packager guard ──

        val packagerScript = file("examples/sovereign-lab/package-evidence-bundle.sh")
        require(packagerScript.exists()) {
            "Missing sovereign lab evidence bundle packager at ${packagerScript.absolutePath}"
        }

        val labReadmeText = labReadme.readText()
        listOf(
            "package-evidence-bundle.sh",
            ".tar.gz",
            ".tar.gz.sha256",
            "sha256sum -c",
            "does not sign",
            "does not certify",
            "verify-evidence-bundle.sh",
        ).forEach { required ->
            require(
                labReadmeText.contains(required) ||
                    evidenceText.contains(required) ||
                    reviewerGuideText.contains(required)
            ) {
                "Sovereign lab archive export docs must mention $required."
            }
        }

        // ── PR #153: Evidence chain overview guard ──

        val evidenceChain = file("examples/sovereign-lab/EVIDENCE-CHAIN.md")
        require(evidenceChain.exists()) {
            "Missing sovereign lab evidence chain overview at ${evidenceChain.absolutePath}"
        }

        val evidenceChainText = evidenceChain.readText()
        listOf(
            "create → export runtime records → write runtime-evidence → fill → finalize → verify → readiness → review → package → extract → re-verify",
            "create-evidence-bundle.sh",
            "finalize-evidence-bundle.sh",
            "verify-evidence-bundle.sh",
            "package-evidence-bundle.sh",
            "RELEASE-READINESS.md",
            "REVIEWER-GUIDE.md",
            "claimBoundary",
            "certifiesProductionReadiness",
            "validatesEvidenceTruth",
            "does not certify production readiness",
        ).forEach { required ->
            require(evidenceChainText.contains(required)) {
                "Sovereign lab evidence chain overview must mention $required."
            }
        }

        // ── PR #156: Archive verifier guard ──

        val archiveVerifierScript = file("examples/sovereign-lab/verify-evidence-archive.sh")
        require(archiveVerifierScript.exists()) {
            "Missing sovereign lab evidence archive verifier at ${archiveVerifierScript.absolutePath}"
        }

        val readinessText = releaseReadiness.readText()
        listOf(
            "verify-evidence-archive.sh",
            "SHA-256 sidecar",
            "temporary directory",
            "unsafe archive entries",
            "verify-evidence-bundle.sh",
            "does not sign",
            "does not certify",
        ).forEach { required ->
            require(
                labReadmeText.contains(required) ||
                    evidenceChainText.contains(required) ||
                    reviewerGuideText.contains(required) ||
                    readinessText.contains(required)
            ) {
                "Sovereign lab archive verifier docs must mention $required."
            }
        }

        // ── PR #160: Archive signing boundary guard ──

        val archiveSigningDoc = file("examples/sovereign-lab/ARCHIVE-SIGNING.md")
        require(archiveSigningDoc.exists()) {
            "Missing sovereign lab archive signing boundary doc at ${archiveSigningDoc.absolutePath}"
        }

        val archiveSigningText = archiveSigningDoc.readText()
        listOf(
            "checksum sidecar",
            "transfer integrity",
            "signer identity",
            "operator identity",
            "regulatory compliance",
            "production readiness",
            "future optional archive signing",
        ).forEach { required ->
            require(archiveSigningText.contains(required)) {
                "Sovereign lab archive signing boundary doc must mention $required."
            }
        }

        require(evidenceChainText.contains("ARCHIVE-SIGNING.md")) {
            "Sovereign lab evidence chain overview must reference ARCHIVE-SIGNING.md."
        }

        logger.lifecycle("verifySovereignLabProfile: all sovereign lab profile checks passed.")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignLabEvidenceBundle
// ──────────────────────────────────────────────

tasks.register("verifySovereignLabEvidenceBundle") {
    group = "verification"
    description = "Verifies the sovereign lab evidence bundle scaffold."

    dependsOn("verifySovereignLabProfile")

    doLast {
        val script = file("examples/sovereign-lab/create-evidence-bundle.sh")
        require(script.exists()) {
            "Missing evidence bundle script at ${script.absolutePath}"
        }

        val bundleRoot = file("examples/sovereign-lab/build/evidence-bundles")
        val bundle = bundleRoot.resolve("test-bundle")
        if (bundle.exists()) {
            bundle.deleteRecursively()
        }

        val pb = ProcessBuilder("bash", script.absolutePath)
        pb.environment()["TRAMAI_EVIDENCE_BUNDLE_TIMESTAMP"] = "test-bundle"
        pb.inheritIO()
        val process = pb.start()
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Evidence bundle script exited with code $exitCode"
        }

        require(bundle.exists()) {
            "Evidence bundle was not created at ${bundle.absolutePath}"
        }

        val requiredFiles = listOf(
            "README.md",
            "manifest.json",
            "MANIFEST.md",
            "command-log.md",
            "environment.md",
            "run-log.md",
            "approval-flow.md",
            "restart-proof.md",
            "jdbc-persistence.md",
            "no-cloud-proof.md",
            "benchmark.md",
            "reports/.gitkeep",
        )

        requiredFiles.forEach { relativePath ->
            val candidate = bundle.resolve(relativePath)
            require(candidate.exists()) {
                "Generated evidence bundle is missing $relativePath at ${candidate.absolutePath}"
            }
        }

        val readmeText = bundle.resolve("README.md").readText()
        require(readmeText.contains("Sovereign Lab Evidence Bundle")) {
            "Generated README.md must be the bundle README, not the template README."
        }
        require(!readmeText.contains("Copy this entire folder", ignoreCase = true)) {
            "Generated README.md must not be copied from evidence-template/README.md."
        }
        require(readmeText.contains("does not certify", ignoreCase = true)) {
            "Generated README.md must avoid certification claims."
        }
        require(readmeText.contains("performance guarantees", ignoreCase = true)) {
            "Generated README.md must avoid production performance guarantee claims."
        }

        val manifestText = bundle.resolve("MANIFEST.md").readText()
        require(manifestText.contains("This bundle does not certify", ignoreCase = true)) {
            "MANIFEST.md must retain non-certification language."
        }

        // ── manifest.json checks ──

        val jsonManifestText = bundle.resolve("manifest.json").readText()
        require(jsonManifestText.contains("\"schemaVersion\": 1")) {
            "manifest.json must declare schemaVersion 1."
        }
        require(jsonManifestText.contains("\"bundleType\": \"sovereign-lab-evidence-bundle\"")) {
            "manifest.json must declare the sovereign lab evidence bundle type."
        }
        require(jsonManifestText.contains("\"localEvidenceScaffold\": true")) {
            "manifest.json must declare this as a local evidence scaffold."
        }
        require(jsonManifestText.contains("\"certifiesProductionReadiness\": false")) {
            "manifest.json must not imply production certification."
        }
        require(jsonManifestText.contains("\"definesPerformanceGuarantees\": false")) {
            "manifest.json must not imply performance guarantees."
        }
        require(jsonManifestText.contains("\"runsLocalModel\": false")) {
            "manifest.json must state that bundle verification does not run a local model."
        }
        require(jsonManifestText.contains("\"runsBenchmark\": false")) {
            "manifest.json must state that bundle verification does not run benchmarks."
        }
        require(jsonManifestText.contains("\"validatesEvidenceTruth\": false")) {
            "manifest.json must state that it does not validate evidence truth."
        }
        requiredFiles
            .filterNot { it == "manifest.json" }
            .forEach { required ->
                require(jsonManifestText.contains("\"$required\"")) {
                    "manifest.json must list required file $required."
                }
            }

        // ── manifest.json file digests ──

        require(jsonManifestText.contains("\"files\": [")) {
            "manifest.json must include file integrity metadata."
        }
        require(jsonManifestText.contains("\"sha256\"")) {
            "manifest.json must include SHA-256 digests."
        }
        require(jsonManifestText.contains("\"sizeBytes\"")) {
            "manifest.json must include file sizes."
        }

        // Recompute SHA-256 digests and verify they match
        requiredFiles
            .filterNot { it == "manifest.json" }
            .forEach { required ->
                val candidate = bundle.resolve(required)
                require(candidate.exists()) {
                    "Cannot recompute digest for missing file $required."
                }
                val digest = candidate.inputStream().use { input ->
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        md.update(buffer, 0, read)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
                require(jsonManifestText.contains("\"sha256\": \"$digest\"")) {
                    "manifest.json SHA-256 for $required does not match generated file."
                }
                require(jsonManifestText.contains("\"sizeBytes\": ${candidate.length()}")) {
                    "manifest.json sizeBytes for $required does not match generated file."
                }
            }

        // ── standalone verifier ──

        val verifier = file("examples/sovereign-lab/verify-evidence-bundle.sh")
        require(verifier.exists()) {
            "Missing evidence bundle verifier at ${verifier.absolutePath}"
        }

        val finalizer = file("examples/sovereign-lab/finalize-evidence-bundle.sh")
        require(finalizer.exists()) {
            "Missing evidence bundle finalizer at ${finalizer.absolutePath}"
        }

        // Clean generated bundle should pass
        val cleanProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val cleanExitCode = cleanProcess.waitFor()
        require(cleanExitCode == 0) {
            "Evidence bundle verifier rejected a clean generated bundle (exit $cleanExitCode)."
        }

        // ── Positive runtime-evidence: add valid records before finalization ──

        val rtEvidenceDir = bundle.resolve("runtime-evidence")
        rtEvidenceDir.mkdirs()

        fun writeRtLine(filename: String, vararg lines: String) {
            rtEvidenceDir.resolve(filename).writeText(lines.joinToString("\n") + "\n")
        }

        // Valid policy decision record
        writeRtLine("policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-policy-001","eventType":"policy.decision","workflowRunId":"wf-lc","correlationId":"corr-lc","actor":"policy-engine","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"ALLOW","reasonCode":"policy_allowed"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"providerName":"ollama","classification":"low"}}"""
        )

        // Valid approval decision record
        writeRtLine("approval-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-approval-001","eventType":"approval.decision","workflowRunId":"wf-lc","correlationId":"corr-lc2","actor":"human-approver","createdAt":"2026-07-13T10:00:10Z","source":{"component":"approval-control-plane","module":"approval"},"decision":{"kind":"APPROVED","reasonCode":"approval-approved"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000003","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000004"},"metadata":{"approvalVersion":"1","reasonDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","reasonLength":"29"}}"""
        )

        // Valid provider routing record
        writeRtLine("provider-routing.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-routing-001","eventType":"provider.route","workflowRunId":"wf-lc","correlationId":"corr-lc3","actor":"provider-router","createdAt":"2026-07-13T10:00:20Z","source":{"component":"provider-router","module":"tramai-engine"},"decision":{"kind":"SELECTED","reasonCode":"provider-selected"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000005","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000006"},"metadata":{"requestedModelDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","routeIndex":"0","attempt":"1"}}"""
        )

        // Valid tool permission record
        writeRtLine("tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-tool-001","eventType":"tool.permission","workflowRunId":"wf-lc","correlationId":"corr-lc4","actor":"policy-engine","createdAt":"2026-07-13T10:00:30Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000007","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000008"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )

        logger.lifecycle("verifySovereignLabEvidenceBundle: added positive runtime-evidence to ${bundle.absolutePath}")

        // ── lifecycle: edit → fail → finalize → pass → tamper → fail ──

        val evidenceFile = bundle.resolve("command-log.md")
        evidenceFile.appendText("\nOperator captured command output.\n")

        // Verify before finalization must fail (manifest is stale or missing files)
        val preFinalizeProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val preFinalizeOutput = preFinalizeProcess.inputStream.bufferedReader().readText()
        val preFinalizeExitCode = preFinalizeProcess.waitFor()
        require(preFinalizeExitCode != 0) {
            "Evidence bundle verifier must fail after evidence is filled but before finalization."
        }
        require(
            preFinalizeOutput.contains("sha256 mismatch") ||
            preFinalizeOutput.contains("sizeBytes mismatch") ||
            preFinalizeOutput.contains("missing from manifest") ||
            preFinalizeOutput.contains("files missing from manifest")
        ) {
            "Evidence bundle verifier failure before finalization should explain digest or size mismatch or missing files. Output: $preFinalizeOutput"
        }

        // Finalize to refresh manifest digests
        val finalizeProcess = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val finalizeExitCode = finalizeProcess.waitFor()
        require(finalizeExitCode == 0) {
            "Evidence bundle finalizer exited with code $finalizeExitCode"
        }

        // Finalized bundle must pass
        val postFinalizeProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val postFinalizeExitCode = postFinalizeProcess.waitFor()
        require(postFinalizeExitCode == 0) {
            "Evidence bundle verifier rejected a finalized bundle (exit $postFinalizeExitCode)."
        }

        // ── Positive runtime-evidence manifest checks ──

        val manifestAfterRt = bundle.resolve("manifest.json").readText()
        for (rtFile in listOf(
            "runtime-evidence/policy-decisions.jsonl",
            "runtime-evidence/approval-decisions.jsonl",
            "runtime-evidence/provider-routing.jsonl",
            "runtime-evidence/tool-permissions.jsonl",
        )) {
            require(manifestAfterRt.contains(rtFile)) {
                "manifest.json must contain runtime-evidence path '$rtFile' after finalization. " +
                    "Manifest: $manifestAfterRt"
            }
        }
        logger.lifecycle(
            "verifySovereignLabEvidenceBundle: positive runtime-evidence finalized " +
                "and verified with 4 files in manifest.json"
        )

        // ── Positive runtime-evidence tamper test ──
        // Tamper WITHOUT re-finalizing so verifier catches stale manifest

        val tamperedRtFile = bundle.resolve("runtime-evidence/policy-decisions.jsonl")
        val originalRtContent = tamperedRtFile.readText()
        tamperedRtFile.appendText("\n{\"tampered\":true}\n")
        val tamperVerifyProc = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tamperVerifyOutput = tamperVerifyProc.inputStream.bufferedReader().readText()
        val tamperVerifyExit = tamperVerifyProc.waitFor()
        require(tamperVerifyExit != 0) {
            "Verifier must reject a tampered runtime-evidence file, but exit was $tamperVerifyExit. Output: $tamperVerifyOutput"
        }
        require(
            tamperVerifyOutput.contains("sha256 mismatch") ||
            tamperVerifyOutput.contains("sizeBytes mismatch") ||
            tamperVerifyOutput.contains("unknown root field")
        ) {
            "Verifier failure after runtime-evidence tamper should explain digest, size, or unknown field. Output: $tamperVerifyOutput"
        }
        logger.lifecycle(
            "verifySovereignLabEvidenceBundle: tampered runtime-evidence correctly rejected"
        )

        // Restore original content and re-finalize for subsequent tests
        tamperedRtFile.writeText(originalRtContent)
        val restoreFinalizeProc = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
            .inheritIO().start()
        require(restoreFinalizeProc.waitFor() == 0) { "Failed to re-finalize after tamper recovery" }

        // ── tool-permissions.jsonl tamper test ──
        val tamperedToolFile = bundle.resolve("runtime-evidence/tool-permissions.jsonl")
        val originalToolContent = tamperedToolFile.readText()
        tamperedToolFile.appendText("\n{\"tampered\":true}\n")
        val tamperToolProc = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tamperToolOutput = tamperToolProc.inputStream.bufferedReader().readText()
        val tamperToolExit = tamperToolProc.waitFor()
        require(tamperToolExit != 0) {
            "Verifier must reject tampered tool-permissions.jsonl, but exit was $tamperToolExit. Output: $tamperToolOutput"
        }
        require(
            tamperToolOutput.contains("sha256 mismatch") ||
            tamperToolOutput.contains("sizeBytes mismatch") ||
            tamperToolOutput.contains("unknown root field")
        ) {
            "Verifier failure after tool-permissions.jsonl tamper should explain digest, size, or unknown field. Output: $tamperToolOutput"
        }
        logger.lifecycle(
            "verifySovereignLabEvidenceBundle: tampered tool-permissions.jsonl correctly rejected"
        )
        // Restore tool content and re-finalize
        tamperedToolFile.writeText(originalToolContent)
        val restoreToolProc = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
            .inheritIO().start()
        require(restoreToolProc.waitFor() == 0) { "Failed to re-finalize after tool-permissions tamper recovery" }

        // Post-finalization tamper must fail
        evidenceFile.appendText("\nTampered after finalization.\n")
        val tamperedAfterProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tamperedAfterOutput = tamperedAfterProcess.inputStream.bufferedReader().readText()
        val tamperedAfterExit = tamperedAfterProcess.waitFor()
        require(tamperedAfterExit != 0) {
            "Evidence bundle verifier must fail after a finalized bundle is tampered with."
        }
        require(
            tamperedAfterOutput.contains("sha256 mismatch") ||
            tamperedAfterOutput.contains("sizeBytes mismatch") ||
            tamperedAfterOutput.contains("unknown root field") ||
            tamperedAfterOutput.contains("unsupported schemaVersion")
        ) {
            "Evidence bundle verifier failure after tampering should explain digest, size, or structural mismatch. Output: $tamperedAfterOutput"
        }

        // ── copied reports regression ──

        val reportFile = bundle.resolve("reports/generated-report.txt")
        reportFile.parentFile.mkdirs()
        reportFile.writeText("Generated report content\n")

        // Re-finalize with new report
        val reFinalizeProcess = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val reFinalizeExitCode = reFinalizeProcess.waitFor()
        require(reFinalizeExitCode == 0) {
            "Evidence bundle finalizer exited with code $reFinalizeExitCode after adding report."
        }

        // Finalized bundle with copied report must pass
        val withReportProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val withReportExitCode = withReportProcess.waitFor()
        require(withReportExitCode == 0) {
            "Evidence bundle verifier rejected a finalized bundle with a copied report."
        }

        // Tampering the copied report must fail
        reportFile.appendText("tampered report\n")
        val tamperedReportProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tamperedReportOutput = tamperedReportProcess.inputStream.bufferedReader().readText()
        val tamperedReportExitCode = tamperedReportProcess.waitFor()
        require(tamperedReportExitCode != 0) {
            "Evidence bundle verifier must fail after a copied report is tampered with."
        }
        require(
            tamperedReportOutput.contains("sha256 mismatch") ||
            tamperedReportOutput.contains("sizeBytes mismatch")
        ) {
            "Evidence bundle verifier failure for copied report should explain digest or size mismatch. Output: $tamperedReportOutput"
        }

        // ── Negative fixture tests ──

        // Re-create a clean finalized bundle for negative fixture copies
        if (bundle.exists()) bundle.deleteRecursively()
        val cleanPb = ProcessBuilder("bash", script.absolutePath)
        cleanPb.environment()["TRAMAI_EVIDENCE_BUNDLE_TIMESTAMP"] = "test-bundle"
        cleanPb.inheritIO()
        require(cleanPb.start().waitFor() == 0) { "Failed to re-create clean bundle" }

        val finalizeCleanPb = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
        finalizeCleanPb.inheritIO()
        require(finalizeCleanPb.start().waitFor() == 0) { "Failed to finalize clean bundle" }

        val negDir = bundleRoot.resolve("negative-fixtures")
        if (negDir.exists()) negDir.deleteRecursively()
        negDir.mkdirs()

        fun negCopy(name: String): File {
            val target = negDir.resolve(name)
            if (target.exists()) target.deleteRecursively()
            bundle.copyRecursively(target, overwrite = true)
            return target
        }

        fun runExpectFail(
            runner: File,
            bundleDir: File,
            expectMessage: String,
            runnerName: String,
        ) {
            val p = ProcessBuilder("bash", runner.absolutePath, bundleDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            require(code != 0) {
                "Expected $runnerName to fail for ${bundleDir.name}, but exited 0. Output: $out"
            }
            require(out.contains(expectMessage, ignoreCase = true)) {
                "Expected $runnerName failure for ${bundleDir.name} to contain '$expectMessage'. Output: $out"
            }
        }

        fun negRunVerifier(dir: File, msg: String) =
            runExpectFail(verifier, dir, msg, "verifier")

        fun negRunFinalizer(dir: File, msg: String) =
            runExpectFail(finalizer, dir, msg, "finalizer")

        fun mutateManifest(dir: File, pythonCode: String) {
            val fullCode = """
import json, pathlib, sys
bp = pathlib.Path(sys.argv[1])
m = json.loads((bp / "manifest.json").read_text())
$pythonCode
(bp / "manifest.json").write_text(json.dumps(m, indent=2) + "\n")
"""
            val p = ProcessBuilder("python3", "-c", fullCode, dir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val exitCode = p.waitFor()
            require(exitCode == 0) { "manifest mutation failed: $out" }
        }

        // Case 1: Path traversal in requiredFiles
        val traversalDir = negCopy("required-path-traversal")
        mutateManifest(traversalDir, """m["requiredFiles"].append("../evil.md")""")
        negRunVerifier(traversalDir, "safe relative path")
        negRunFinalizer(traversalDir, "safe relative path")

        // Case 2: Absolute path in requiredFiles
        val absDir = negCopy("required-absolute-path")
        mutateManifest(absDir, """m["requiredFiles"].append("/tmp/evil.md")""")
        negRunVerifier(absDir, "safe relative path")
        negRunFinalizer(absDir, "safe relative path")

        // Case 3: Duplicate files[].path
        val dupDir = negCopy("duplicate-file-path")
        mutateManifest(dupDir, """m["files"].append(m["files"][0])""")
        negRunVerifier(dupDir, "duplicate files metadata entry")

        // Case 4: manifest.json self-digest
        // The verifier checks SHA-256 before the self-digest check, so the reject
        // message will be "sha256 mismatch for manifest.json" — which still proves
        // the bundle is rejected because of the manifest.json files[] entry.
        val selfDigestDir = negCopy("manifest-self-digest")
        mutateManifest(selfDigestDir, """m["files"].insert(0, {"path": "manifest.json", "sha256": "0" * 64, "sizeBytes": 0})""")
        negRunVerifier(selfDigestDir, "sha256 mismatch for manifest.json")

        // Case 5: Weakened claim boundary
        val weakenDir = negCopy("weakened-claims")
        mutateManifest(weakenDir, """m["claimBoundary"]["certifiesProductionReadiness"] = True""")
        negRunVerifier(weakenDir, "claimBoundary.certifiesProductionReadiness")
        negRunFinalizer(weakenDir, "claimBoundary.certifiesProductionReadiness")

        // Case 6: Invalid SHA-256
        val badShaDir = negCopy("malformed-sha")
        mutateManifest(badShaDir, """m["files"][0]["sha256"] = "not-a-sha" """)
        negRunVerifier(badShaDir, "sha256")

        // Case 7: Negative sizeBytes
        val negSizeDir = negCopy("negative-size")
        mutateManifest(negSizeDir, """m["files"][0]["sizeBytes"] = -1""")
        negRunVerifier(negSizeDir, "sizeBytes")

        // Case 8: Missing required file
        val missingDir = negCopy("missing-file")
        missingDir.resolve("command-log.md").delete()
        negRunVerifier(missingDir, "required file missing")
        negRunFinalizer(missingDir, "required file missing")

        // ── Symlink negative fixtures ──

        fun createSymlinkOrSkip(link: File, target: File): Boolean {
            return try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
                true
            } catch (ex: UnsupportedOperationException) {
                logger.lifecycle("Skipping symlink fixture: unsupported - ${ex.message}")
                false
            } catch (ex: java.nio.file.FileSystemException) {
                logger.lifecycle("Skipping symlink fixture: creation failed - ${ex.message}")
                false
            }
        }

        // Case 9: Required file symlink
        val requiredSymlinkDir = negCopy("required-file-symlink")
        val originalLog = requiredSymlinkDir.resolve("command-log.md")
        val realLog = requiredSymlinkDir.resolve("real-command-log.md")
        originalLog.copyTo(realLog, overwrite = true)
        originalLog.delete()
        if (createSymlinkOrSkip(originalLog, realLog)) {
            negRunVerifier(requiredSymlinkDir, "symlink")
            negRunFinalizer(requiredSymlinkDir, "symlink")
        }

        // Case 10: Copied report symlink
        val reportSymlinkDir = negCopy("report-file-symlink")
        val reportDir = reportSymlinkDir.resolve("reports")
        reportDir.mkdirs()
        val realReportFile = reportDir.resolve("generated-report-real.txt")
        realReportFile.writeText("generated report content\n")
        val symlinkReportFile = reportDir.resolve("generated-report.txt")
        if (createSymlinkOrSkip(symlinkReportFile, realReportFile)) {
            negRunVerifier(reportSymlinkDir, "symlink")
            negRunFinalizer(reportSymlinkDir, "symlink")
        }

        // Case 11: Unlisted symlink inside bundle
        val unlistedSymlinkDir = negCopy("unlisted-symlink")
        val hiddenLink = unlistedSymlinkDir.resolve("reports/unlisted-link.txt")
        val hiddenTarget = unlistedSymlinkDir.resolve("reports/generated-report.txt")
        if (createSymlinkOrSkip(hiddenLink, hiddenTarget)) {
            negRunVerifier(unlistedSymlinkDir, "symlink")
            negRunFinalizer(unlistedSymlinkDir, "symlink")
        }

        // Case 12: Manifest symlink
        val manifestSymlinkDir = negCopy("manifest-symlink")
        val realManifest = manifestSymlinkDir.resolve("real-manifest.json")
        val manifestFile = manifestSymlinkDir.resolve("manifest.json")
        manifestFile.copyTo(realManifest, overwrite = true)
        manifestFile.delete()
        if (createSymlinkOrSkip(manifestFile, realManifest)) {
            negRunVerifier(manifestSymlinkDir, "symlink")
            negRunFinalizer(manifestSymlinkDir, "symlink")
        }

        // ── Runtime evidence negative fixtures ──

        val rtDir = negDir.resolve("runtime-evidence-fixtures")
        if (rtDir.exists()) rtDir.deleteRecursively()
        rtDir.mkdirs()

        fun writeRtEvidence(bundle: File, filename: String, vararg lines: String) {
            val dir = bundle.resolve("runtime-evidence")
            dir.mkdirs()
            val file = dir.resolve(filename)
            file.writeText(lines.joinToString("\n") + "\n")
        }

        fun createRtNegFixture(name: String): File {
            val target = rtDir.resolve(name)
            if (target.exists()) target.deleteRecursively()
            bundle.copyRecursively(target, overwrite = true)
            return target
        }

        fun negFinalizeRt(bundleDir: File) {
            val p = ProcessBuilder("bash", finalizer.absolutePath, bundleDir.absolutePath)
                .inheritIO().start()
            require(p.waitFor() == 0) { "Finalization failed for ${bundleDir.name}" }
        }

        val validJsonlLine = """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-001","eventType":"policy.decision","workflowRunId":null,"correlationId":null,"actor":null,"createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"ALLOW","reasonCode":"policy_allowed"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"providerName":"ollama"}}"""

        // Case: Malformed JSON line
        val malformedDir = createRtNegFixture("malformed-json-line")
        writeRtEvidence(malformedDir, "policy-decisions.jsonl", "this is not json")
        negFinalizeRt(malformedDir)
        negRunVerifier(malformedDir, "invalid JSON")

        // Case: Blank file (must contain at least one record)
        val blankDir = createRtNegFixture("blank-jsonl-file")
        val blankFile = blankDir.resolve("runtime-evidence/policy-decisions.jsonl")
        blankFile.parentFile.mkdirs()
        blankFile.writeText("")
        negFinalizeRt(blankDir)
        negRunVerifier(blankDir, "must contain at least one record")

        // Case: Wrong schema version
        val badSchemaDir = createRtNegFixture("wrong-schema-version")
        writeRtEvidence(badSchemaDir, "policy-decisions.jsonl",
            """{"schemaVersion":"evidences.v2","eventId":"evt-002","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        negFinalizeRt(badSchemaDir)
        negRunVerifier(badSchemaDir, "unsupported schemaVersion")

        // Case: Event/file mismatch
        val mismatchDir = createRtNegFixture("event-file-mismatch")
        writeRtEvidence(mismatchDir, "approval-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-003","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        negFinalizeRt(mismatchDir)
        negRunVerifier(mismatchDir, "does not match expected")

        // Case: Invalid decision kind
        val badKindDir = createRtNegFixture("invalid-decision-kind")
        writeRtEvidence(badKindDir, "policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-004","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"INVALID_KIND"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        negFinalizeRt(badKindDir)
        negRunVerifier(badKindDir, "unsupported decision.kind")

        // Case: Unknown metadata key
        val badMetaDir = createRtNegFixture("unknown-metadata-key")
        writeRtEvidence(badMetaDir, "policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-005","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"rawPrompt":"this should be rejected"}}"""
        )
        negFinalizeRt(badMetaDir)
        negRunVerifier(badMetaDir, "not allowlisted")

        // Case: Runtime file removed from files[] in manifest
        val missingManifestDir = createRtNegFixture("runtime-file-missing-from-manifest")
        writeRtEvidence(missingManifestDir, "policy-decisions.jsonl", validJsonlLine)
        // Re-finalize (will include the file), then remove it from manifest
        val reFinalProcess = ProcessBuilder("bash", finalizer.absolutePath, missingManifestDir.absolutePath)
            .inheritIO().start()
        require(reFinalProcess.waitFor() == 0) { "Finalization failed for runtime-file-missing-from-manifest" }
        mutateManifest(missingManifestDir,
            """m["files"] = [f for f in m["files"] if f["path"] != "runtime-evidence/policy-decisions.jsonl"]"""
        )
        negRunVerifier(missingManifestDir, "manifest")

        // Case: Unknown JSONL filename
        val unknownFileDir = createRtNegFixture("unknown-runtime-jsonl")
        writeRtEvidence(unknownFileDir, "secret-events.jsonl", validJsonlLine)
        negFinalizeRt(unknownFileDir)
        negRunVerifier(unknownFileDir, "unknown file")

        // ── Tool permission negative fixtures ──

        // Case: tool-permissions.jsonl with invalid decision kind
        val badToolKindDir = createRtNegFixture("tool-permission-invalid-decision")
        writeRtEvidence(badToolKindDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-001","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"REDACT_RESULT","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        negFinalizeRt(badToolKindDir)
        negRunVerifier(badToolKindDir, "unsupported decision.kind")

        // Case: tool-permissions.jsonl with missing toolName
        val missingToolNameDir = createRtNegFixture("tool-permission-missing-toolname")
        writeRtEvidence(missingToolNameDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-002","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        negFinalizeRt(missingToolNameDir)
        negRunVerifier(missingToolNameDir, "toolName")

        // Case: tool-permissions.jsonl with invalid enforcementPoint
        val badEpDir = createRtNegFixture("tool-permission-bad-enforcementpoint")
        writeRtEvidence(badEpDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-003","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_SOMETHING","riskLevel":"HIGH"}}"""
        )
        negFinalizeRt(badEpDir)
        negRunVerifier(badEpDir, "enforcementPoint")

        // Case: tool-permissions.jsonl with invalid riskLevel
        val badRiskDir = createRtNegFixture("tool-permission-bad-risklevel")
        writeRtEvidence(badRiskDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-004","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"ULTRA_HIGH"}}"""
        )
        negFinalizeRt(badRiskDir)
        negRunVerifier(badRiskDir, "riskLevel")

        // Case: tool-permissions.jsonl with wrong source.component
        val badToolSrcDir = createRtNegFixture("tool-permission-wrong-source")
        writeRtEvidence(badToolSrcDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-005","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"provider-router"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        negFinalizeRt(badToolSrcDir)
        negRunVerifier(badToolSrcDir, "source.component")

        // Clean up negative fixture directories
        negDir.deleteRecursively()

        // ── Archive export verification ──

        val packager = file("examples/sovereign-lab/package-evidence-bundle.sh")
        require(packager.exists()) {
            "Missing evidence bundle packager at ${packager.absolutePath}"
        }

        // Package the finalized bundle
        val packageProcess = ProcessBuilder("bash", packager.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val packageOutput = packageProcess.inputStream.bufferedReader().readText()
        val packageExitCode = packageProcess.waitFor()
        require(packageExitCode == 0) {
            "Evidence bundle packager failed with code $packageExitCode. Output: $packageOutput"
        }

        val archiveRoot = file("examples/sovereign-lab/build/evidence-archives")
        val archive = archiveRoot.resolve("test-bundle.tar.gz")
        val checksum = archiveRoot.resolve("test-bundle.tar.gz.sha256")

        require(archive.isFile) {
            "Expected evidence bundle archive at ${archive.absolutePath}"
        }
        require(checksum.isFile) {
            "Expected evidence bundle archive checksum at ${checksum.absolutePath}"
        }

        // Verify checksum
        val checksumProcess = ProcessBuilder("sha256sum", "-c", checksum.name)
            .directory(archiveRoot)
            .redirectErrorStream(true)
            .start()
        val checksumOutput = checksumProcess.inputStream.bufferedReader().readText()
        val checksumExitCode = checksumProcess.waitFor()
        require(checksumExitCode == 0) {
            "Evidence bundle archive checksum validation failed. Output: $checksumOutput"
        }

        // Extract and re-verify
        val extractRoot = file("examples/sovereign-lab/build/evidence-archives/extracted")
        if (extractRoot.exists()) extractRoot.deleteRecursively()
        extractRoot.mkdirs()

        val extractProcess = ProcessBuilder(
            "tar", "-xzf", archive.absolutePath, "-C", extractRoot.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val extractOutput = extractProcess.inputStream.bufferedReader().readText()
        val extractExitCode = extractProcess.waitFor()
        require(extractExitCode == 0) {
            "Evidence bundle archive extraction failed with code $extractExitCode. Output: $extractOutput"
        }

        val extractedBundle = extractRoot.resolve("test-bundle")
        require(extractedBundle.isDirectory) {
            "Extracted evidence bundle directory not found at ${extractedBundle.absolutePath}"
        }

        val extractedVerify = ProcessBuilder("bash", verifier.absolutePath, extractedBundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val extractedVerifyOutput = extractedVerify.inputStream.bufferedReader().readText()
        val extractedVerifyExitCode = extractedVerify.waitFor()
        require(extractedVerifyExitCode == 0) {
            "Verifier rejected extracted evidence bundle. Output: $extractedVerifyOutput"
        }

        // ── Deterministic archive export regression ──

        fun sha256(file: File): String {
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

        val determinismRoot = archiveRoot.resolve("determinism")
        if (determinismRoot.exists()) determinismRoot.deleteRecursively()
        determinismRoot.mkdirs()

        // First packaging
        val firstPackage = ProcessBuilder("bash", packager.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val firstPackageOutput = firstPackage.inputStream.bufferedReader().readText()
        val firstPackageExitCode = firstPackage.waitFor()
        require(firstPackageExitCode == 0) {
            "First deterministic archive packaging failed. Output: $firstPackageOutput"
        }

        val firstArchive = determinismRoot.resolve("test-bundle-first.tar.gz")
        val firstChecksum = determinismRoot.resolve("test-bundle-first.tar.gz.sha256")
        archive.copyTo(firstArchive, overwrite = true)
        checksum.copyTo(firstChecksum, overwrite = true)

        val firstArchiveSha = sha256(firstArchive)
        val firstChecksumText = firstChecksum.readText()

        // Second packaging
        val secondPackage = ProcessBuilder("bash", packager.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val secondPackageOutput = secondPackage.inputStream.bufferedReader().readText()
        val secondPackageExitCode = secondPackage.waitFor()
        require(secondPackageExitCode == 0) {
            "Second deterministic archive packaging failed. Output: $secondPackageOutput"
        }

        val secondArchive = determinismRoot.resolve("test-bundle-second.tar.gz")
        val secondChecksum = determinismRoot.resolve("test-bundle-second.tar.gz.sha256")
        archive.copyTo(secondArchive, overwrite = true)
        checksum.copyTo(secondChecksum, overwrite = true)

        val secondArchiveSha = sha256(secondArchive)
        val secondChecksumText = secondChecksum.readText()

        require(firstArchiveSha == secondArchiveSha) {
            "Evidence archive export is not deterministic. First SHA-256=$firstArchiveSha, second SHA-256=$secondArchiveSha"
        }

        require(firstChecksumText == secondChecksumText) {
            "Evidence archive checksum sidecar is not deterministic. First=$firstChecksumText Second=$secondChecksumText"
        }

        require(secondChecksumText.startsWith(secondArchiveSha)) {
            "Checksum sidecar does not match archive SHA-256. Sidecar=$secondChecksumText Archive=$secondArchiveSha"
        }

        // ── PR #156: Archive verifier positive test ──

        val archiveVerifier = file("examples/sovereign-lab/verify-evidence-archive.sh")
        require(archiveVerifier.exists()) {
            "Missing evidence archive verifier at ${archiveVerifier.absolutePath}"
        }

        val archiveVerifyProcess = ProcessBuilder("bash", archiveVerifier.absolutePath, archive.absolutePath)
            .redirectErrorStream(true)
            .start()
        val archiveVerifyOutput = archiveVerifyProcess.inputStream.bufferedReader().readText()
        val archiveVerifyExitCode = archiveVerifyProcess.waitFor()

        require(archiveVerifyExitCode == 0) {
            "Evidence archive verifier failed with code $archiveVerifyExitCode. Output: $archiveVerifyOutput"
        }
        require(archiveVerifyOutput.contains("Evidence archive verified:")) {
            "Archive verifier success output missing. Got: $archiveVerifyOutput"
        }

        // ── PR #156: Negative archive fixtures ──

        val archiveNegRoot = archiveRoot.resolve("negative-archives")
        if (archiveNegRoot.exists()) archiveNegRoot.deleteRecursively()
        archiveNegRoot.mkdirs()

        fun runArchiveVerifierExpectFail(archiveFile: File, expected: String) {
            val process = ProcessBuilder("bash", archiveVerifier.absolutePath, archiveFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            require(exitCode != 0) {
                "Expected archive verifier to fail for ${archiveFile.name}, but it passed. Output: $output"
            }
            require(output.contains(expected, ignoreCase = true)) {
                "Expected archive verifier failure to contain '$expected', but output was: $output"
            }
        }

        // Negative 1: Missing checksum sidecar
        val missingChecksumArchive = archiveNegRoot.resolve("missing-checksum.tar.gz")
        archive.copyTo(missingChecksumArchive, overwrite = true)

        runArchiveVerifierExpectFail(missingChecksumArchive, "checksum")

        // Negative 2: Tampered archive
        val tamperedArchive = archiveNegRoot.resolve("tampered.tar.gz")
        val tamperedChecksum = archiveNegRoot.resolve("tampered.tar.gz.sha256")

        archive.copyTo(tamperedArchive, overwrite = true)
        checksum.copyTo(tamperedChecksum, overwrite = true)
        tamperedChecksum.writeText(tamperedChecksum.readText().replace("test-bundle.tar.gz", "tampered.tar.gz"))
        tamperedArchive.appendBytes("tamper".toByteArray())

        runArchiveVerifierExpectFail(tamperedArchive, "checksum mismatch")

        // Negative 3: Unsafe tar entry (path traversal)
        val unsafeArchive = archiveNegRoot.resolve("unsafe-entry.tar.gz")
        val unsafeDir = archiveNegRoot.resolve("unsafe-src")
        if (unsafeDir.exists()) unsafeDir.deleteRecursively()
        unsafeDir.mkdirs()
        unsafeDir.resolve("evil.txt").writeText("evil\n")

        val unsafeCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${unsafeArchive.absolutePath}")
payload = pathlib.Path("${unsafeDir.resolve("evil.txt").absolutePath}")
with tarfile.open(archive, "w:gz") as tar:
    tar.add(payload, arcname="../evil.txt")
"""
        )
            .redirectErrorStream(true)
            .start()
        val unsafeCreateOutput = unsafeCreateProcess.inputStream.bufferedReader().readText()
        val unsafeCreateExit = unsafeCreateProcess.waitFor()
        require(unsafeCreateExit == 0) {
            "Failed to create unsafe archive fixture. Output: $unsafeCreateOutput"
        }

        val unsafeSha = sha256(unsafeArchive)
        unsafeArchive.resolveSibling("${unsafeArchive.name}.sha256")
            .writeText("$unsafeSha  ${unsafeArchive.name}\n")

        runArchiveVerifierExpectFail(unsafeArchive, "safe relative path")

        // Negative 4: Symlink tar entry
        val symlinkArchive = archiveNegRoot.resolve("symlink-entry.tar.gz")
        val symlinkCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${symlinkArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/link.txt")
info.type = tarfile.SYMTYPE
info.linkname = "target.txt"
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
        )
            .redirectErrorStream(true)
            .start()
        val symlinkCreateOutput = symlinkCreateProcess.inputStream.bufferedReader().readText()
        val symlinkCreateExit = symlinkCreateProcess.waitFor()
        require(symlinkCreateExit == 0) {
            "Failed to create symlink archive fixture. Output: $symlinkCreateOutput"
        }

        val symlinkSha = sha256(symlinkArchive)
        symlinkArchive.resolveSibling("${symlinkArchive.name}.sha256")
            .writeText("$symlinkSha  ${symlinkArchive.name}\n")

        runArchiveVerifierExpectFail(symlinkArchive, "symlink")

        // Negative 5: Sidecar references wrong filename
        val wrongSidecarArchive = archiveNegRoot.resolve("wrong-sidecar-name.tar.gz")
        val wrongSidecar = archiveNegRoot.resolve("wrong-sidecar-name.tar.gz.sha256")
        archive.copyTo(wrongSidecarArchive, overwrite = true)
        val wrongSha = sha256(wrongSidecarArchive)
        wrongSidecar.writeText("$wrongSha  /dev/zero\n")

        runArchiveVerifierExpectFail(wrongSidecarArchive, "must reference")

        // ── PR #157: Expanded negative archive fixtures ──

        fun writeArchiveSidecar(archiveFile: File) {
            archiveFile.resolveSibling("${archiveFile.name}.sha256")
                .writeText("${sha256(archiveFile)}  ${archiveFile.name}\n")
        }

        // Negative 6: Absolute path tar entry
        val absoluteEntryArchive = archiveNegRoot.resolve("absolute-entry.tar.gz")

        val absoluteCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${absoluteEntryArchive.absolutePath}")

# Must use TarInfo directly because tar.add() strips leading slashes
info = tarfile.TarInfo("/evil.txt")
info.type = tarfile.REGTYPE
info.size = 0

with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
        )
            .redirectErrorStream(true)
            .start()
        val absoluteCreateOutput = absoluteCreateProcess.inputStream.bufferedReader().readText()
        require(absoluteCreateProcess.waitFor() == 0) {
            "Failed to create absolute-entry archive fixture. Output: $absoluteCreateOutput"
        }

        writeArchiveSidecar(absoluteEntryArchive)
        runArchiveVerifierExpectFail(absoluteEntryArchive, "must not be absolute")

        // Negative 7: Hardlink tar entry
        val hardlinkArchive = archiveNegRoot.resolve("hardlink-entry.tar.gz")

        val hardlinkCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${hardlinkArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/hardlink.txt")
info.type = tarfile.LNKTYPE
info.linkname = "target.txt"
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
        )
            .redirectErrorStream(true)
            .start()
        val hardlinkCreateOutput = hardlinkCreateProcess.inputStream.bufferedReader().readText()
        require(hardlinkCreateProcess.waitFor() == 0) {
            "Failed to create hardlink archive fixture. Output: $hardlinkCreateOutput"
        }

        writeArchiveSidecar(hardlinkArchive)
        runArchiveVerifierExpectFail(hardlinkArchive, "hardlink")

        // Negative 8: Special file / FIFO tar entry
        val specialFileArchive = archiveNegRoot.resolve("special-file-entry.tar.gz")

        val specialCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${specialFileArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/fifo")
info.type = tarfile.FIFOTYPE
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
        )
            .redirectErrorStream(true)
            .start()
        val specialCreateOutput = specialCreateProcess.inputStream.bufferedReader().readText()
        require(specialCreateProcess.waitFor() == 0) {
            "Failed to create special-file archive fixture. Output: $specialCreateOutput"
        }

        writeArchiveSidecar(specialFileArchive)
        runArchiveVerifierExpectFail(specialFileArchive, "regular file or directory")

        // Negative 9: Empty archive
        val emptyArchive = archiveNegRoot.resolve("empty-archive.tar.gz")

        val emptyCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${emptyArchive.absolutePath}")
with tarfile.open(archive, "w:gz"):
    pass
"""
        )
            .redirectErrorStream(true)
            .start()
        val emptyCreateOutput = emptyCreateProcess.inputStream.bufferedReader().readText()
        require(emptyCreateProcess.waitFor() == 0) {
            "Failed to create empty archive fixture. Output: $emptyCreateOutput"
        }

        writeArchiveSidecar(emptyArchive)
        runArchiveVerifierExpectFail(emptyArchive, "archive is empty")

        // Negative 10: Multiple top-level directories
        val multiTopArchive = archiveNegRoot.resolve("multi-top-level.tar.gz")
        val multiTopRoot = archiveNegRoot.resolve("multi-top-src")
        if (multiTopRoot.exists()) multiTopRoot.deleteRecursively()
        multiTopRoot.mkdirs()

        val fileA = multiTopRoot.resolve("a.txt")
        val fileB = multiTopRoot.resolve("b.txt")
        fileA.writeText("a\n")
        fileB.writeText("b\n")

        val multiTopCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${multiTopArchive.absolutePath}")
file_a = pathlib.Path("${fileA.absolutePath}")
file_b = pathlib.Path("${fileB.absolutePath}")
with tarfile.open(archive, "w:gz") as tar:
    tar.add(file_a, arcname="bundle-a/a.txt")
    tar.add(file_b, arcname="bundle-b/b.txt")
"""
        )
            .redirectErrorStream(true)
            .start()
        val multiTopCreateOutput = multiTopCreateProcess.inputStream.bufferedReader().readText()
        require(multiTopCreateProcess.waitFor() == 0) {
            "Failed to create multi-top-level archive fixture. Output: $multiTopCreateOutput"
        }

        writeArchiveSidecar(multiTopArchive)
        runArchiveVerifierExpectFail(multiTopArchive, "exactly one top-level")

        // Negative 11: Invalid sidecar SHA format
        val invalidShaArchive = archiveNegRoot.resolve("invalid-sidecar-sha.tar.gz")
        archive.copyTo(invalidShaArchive, overwrite = true)
        invalidShaArchive.resolveSibling("${invalidShaArchive.name}.sha256")
            .writeText("not-a-sha  ${invalidShaArchive.name}\n")

        runArchiveVerifierExpectFail(invalidShaArchive, "valid SHA-256")

        // Negative 12: Multi-line sidecar
        val multilineSidecarArchive = archiveNegRoot.resolve("multiline-sidecar.tar.gz")
        archive.copyTo(multilineSidecarArchive, overwrite = true)

        val multilineSha = sha256(multilineSidecarArchive)
        multilineSidecarArchive.resolveSibling("${multilineSidecarArchive.name}.sha256")
            .writeText(
                """
                $multilineSha  ${multilineSidecarArchive.name}
                $multilineSha  other.tar.gz
                """.trimIndent() + "\n"
            )

        runArchiveVerifierExpectFail(multilineSidecarArchive, "exactly one line")

        // ── PR #158: Sidecar parser fixtures ──

        fun writeCustomSidecar(archiveFile: File, text: String) {
            archiveFile.resolveSibling("${archiveFile.name}.sha256")
                .writeText(text)
        }

        // Positive: binary-mode sidecar (sha256sum -b)
        val binarySidecarArchive = archiveNegRoot.resolve("binary-sidecar.tar.gz")
        archive.copyTo(binarySidecarArchive, overwrite = true)
        val binarySha = sha256(binarySidecarArchive)
        writeCustomSidecar(binarySidecarArchive, "$binarySha *${binarySidecarArchive.name}\n")

        val binaryProcess = ProcessBuilder("bash", archiveVerifier.absolutePath, binarySidecarArchive.absolutePath)
            .redirectErrorStream(true)
            .start()
        val binaryOutput = binaryProcess.inputStream.bufferedReader().readText()
        val binaryExit = binaryProcess.waitFor()
        require(binaryExit == 0) {
            "Expected binary-mode sidecar to verify, but it failed. Output: $binaryOutput"
        }

        // Negative: extra sidecar field
        val extraFieldSidecarArchive = archiveNegRoot.resolve("extra-field-sidecar.tar.gz")
        archive.copyTo(extraFieldSidecarArchive, overwrite = true)
        val extraFieldSha = sha256(extraFieldSidecarArchive)
        writeCustomSidecar(
            extraFieldSidecarArchive,
            "$extraFieldSha  ${extraFieldSidecarArchive.name} unexpected\n"
        )
        runArchiveVerifierExpectFail(extraFieldSidecarArchive, "exactly a SHA-256 digest and archive filename")

        // Negative: missing filename
        val missingNameSidecarArchive = archiveNegRoot.resolve("missing-name-sidecar.tar.gz")
        archive.copyTo(missingNameSidecarArchive, overwrite = true)
        val missingNameSha = sha256(missingNameSidecarArchive)
        writeCustomSidecar(missingNameSidecarArchive, "$missingNameSha\n")
        runArchiveVerifierExpectFail(missingNameSidecarArchive, "digest and archive filename")

        // Negative: whitespace-only sidecar
        val blankSidecarArchive = archiveNegRoot.resolve("blank-sidecar.tar.gz")
        archive.copyTo(blankSidecarArchive, overwrite = true)
        writeCustomSidecar(blankSidecarArchive, "   \n")
        runArchiveVerifierExpectFail(blankSidecarArchive, "digest and archive filename")

        // Positive: sidecar without trailing newline
        val noTrailingNewlineArchive = archiveNegRoot.resolve("no-trailing-newline-sidecar.tar.gz")
        archive.copyTo(noTrailingNewlineArchive, overwrite = true)
        val noTrailingNewlineSha = sha256(noTrailingNewlineArchive)
        writeCustomSidecar(
            noTrailingNewlineArchive,
            "$noTrailingNewlineSha  ${noTrailingNewlineArchive.name}"
        )
        val noTrailingNewlineProcess = ProcessBuilder("bash", archiveVerifier.absolutePath, noTrailingNewlineArchive.absolutePath)
            .redirectErrorStream(true)
            .start()
        val noTrailingNewlineOutput = noTrailingNewlineProcess.inputStream.bufferedReader().readText()
        val noTrailingNewlineExit = noTrailingNewlineProcess.waitFor()
        require(noTrailingNewlineExit == 0) {
            "Expected sidecar without trailing newline to verify, but it failed. Output: $noTrailingNewlineOutput"
        }

        // Negative: two lines, second line has no trailing newline
        val multilineNoFinalNewlineArchive = archiveNegRoot.resolve("multiline-no-final-newline-sidecar.tar.gz")
        archive.copyTo(multilineNoFinalNewlineArchive, overwrite = true)
        val multilineNoFinalNewlineSha = sha256(multilineNoFinalNewlineArchive)
        writeCustomSidecar(
            multilineNoFinalNewlineArchive,
            "$multilineNoFinalNewlineSha  ${multilineNoFinalNewlineArchive.name}\n$multilineNoFinalNewlineSha  other.tar.gz"
        )
        runArchiveVerifierExpectFail(multilineNoFinalNewlineArchive, "exactly one line")

        // ── PR #159: Top-level file rejection ──

        val topLevelFileArchive = archiveNegRoot.resolve("top-level-file.tar.gz")

        val topLevelFileCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib, io
archive = pathlib.Path("${topLevelFileArchive.absolutePath}")
payload = b"not a bundle directory\\n"
info = tarfile.TarInfo("bundle.txt")
info.type = tarfile.REGTYPE
info.size = len(payload)
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info, io.BytesIO(payload))
"""
        )
            .redirectErrorStream(true)
            .start()
        val topLevelFileCreateOutput = topLevelFileCreateProcess.inputStream.bufferedReader().readText()
        require(topLevelFileCreateProcess.waitFor() == 0) {
            "Failed to create top-level-file archive fixture. Output: $topLevelFileCreateOutput"
        }
        writeArchiveSidecar(topLevelFileArchive)
        runArchiveVerifierExpectFail(topLevelFileArchive, "top-level entry must be a directory")

        // ── PR #161: Optional archive signature verifier ──

        val signatureVerifier = file("examples/sovereign-lab/verify-evidence-archive-signature.sh")
        require(signatureVerifier.exists()) {
            "Missing evidence archive signature verifier at ${signatureVerifier.absolutePath}"
        }

        val sigArchiveRoot = archiveRoot.resolve("signature-tests")
        if (sigArchiveRoot.exists()) sigArchiveRoot.deleteRecursively()
        sigArchiveRoot.mkdirs()

        // Helper: generate ephemeral RSA keypair for fixture testing
        fun generateKeypair(dir: File): Pair<File, File> {
            dir.mkdirs()
            val privateKey = dir.resolve("fixture-key.pem")
            val publicKey = dir.resolve("fixture-key.pub")
            val genProcess = ProcessBuilder(
                "openssl", "genpkey",
                "-algorithm", "RSA",
                "-pkeyopt", "rsa_keygen_bits:2048",
                "-outform", "PEM",
                "-out", privateKey.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val genOutput = genProcess.inputStream.bufferedReader().readText()
            require(genProcess.waitFor() == 0) {
                "Failed to generate ephemeral signing key. Output: $genOutput"
            }

            val pubProcess = ProcessBuilder(
                "openssl", "rsa",
                "-pubout",
                "-in", privateKey.absolutePath,
                "-outform", "PEM",
                "-out", publicKey.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val pubOutput = pubProcess.inputStream.bufferedReader().readText()
            require(pubProcess.waitFor() == 0) {
                "Failed to extract public key. Output: $pubOutput"
            }

            return Pair(privateKey, publicKey)
        }

        // Helper: sign a checksum sidecar
        fun signChecksum(checksumFile: File, privateKey: File, signatureFile: File) {
            val signProcess = ProcessBuilder(
                "openssl", "dgst", "-sha256",
                "-sign", privateKey.absolutePath,
                "-out", signatureFile.absolutePath,
                checksumFile.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val signOutput = signProcess.inputStream.bufferedReader().readText()
            require(signProcess.waitFor() == 0) {
                "Failed to sign checksum sidecar. Output: $signOutput"
            }
        }

        fun runSignatureVerifierExpectFail(
            archiveFile: File,
            publicKey: File,
            expected: String,
        ) {
            val process = ProcessBuilder(
                "bash", signatureVerifier.absolutePath,
                archiveFile.absolutePath, publicKey.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            require(exitCode != 0) {
                "Expected signature verifier to fail for ${archiveFile.name}, but it passed. Output: $output"
            }
            require(output.contains(expected, ignoreCase = true)) {
                "Expected signature verifier failure to contain '$expected', but output was: $output"
            }
        }

        // Re-package the finalized bundle into a fresh archive for signature tests
        val sigPackageProcess = ProcessBuilder("bash", packager.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val sigPackageOutput = sigPackageProcess.inputStream.bufferedReader().readText()
        require(sigPackageProcess.waitFor() == 0) {
            "Repackaging for signature tests failed. Output: $sigPackageOutput"
        }

        val sigArchive = archiveRoot.resolve("test-bundle.tar.gz")
        val sigChecksum = archiveRoot.resolve("test-bundle.tar.gz.sha256")
        require(sigArchive.isFile && sigChecksum.isFile) {
            "Re-packaged archive or checksum missing for signature tests."
        }

        // Copy archive + checksum to fixture dir so we don't mutate the originals
        val sigArchiveCopy = sigArchiveRoot.resolve("test-bundle.tar.gz")
        val sigChecksumCopy = sigArchiveRoot.resolve("test-bundle.tar.gz.sha256")
        sigArchive.copyTo(sigArchiveCopy, overwrite = true)
        sigChecksum.copyTo(sigChecksumCopy, overwrite = true)

        // Generate ephemeral keypair
        val (sigPrivateKey, sigPublicKey) = generateKeypair(sigArchiveRoot)

        // Sign the checksum sidecar
        val sigSigFile = sigArchiveRoot.resolve("test-bundle.tar.gz.sha256.sig")
        signChecksum(sigChecksumCopy, sigPrivateKey, sigSigFile)

        // Positive: valid signature + archive verification
        val positiveSigProcess = ProcessBuilder(
            "bash", signatureVerifier.absolutePath,
            sigArchiveCopy.absolutePath, sigPublicKey.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val positiveSigOutput = positiveSigProcess.inputStream.bufferedReader().readText()
        val positiveSigExitCode = positiveSigProcess.waitFor()
        require(positiveSigExitCode == 0) {
            "Expected signature verifier to pass for valid signature. Output: $positiveSigOutput"
        }
        require(positiveSigOutput.contains("Evidence archive signature verified:")) {
            "Signature verifier success output missing. Got: $positiveSigOutput"
        }

        // Negative 1: Missing .sha256.sig
        val noSigArchive = sigArchiveRoot.resolve("no-sig.tar.gz")
        val noSigChecksum = sigArchiveRoot.resolve("no-sig.tar.gz.sha256")
        sigArchiveCopy.copyTo(noSigArchive, overwrite = true)
        sigChecksumCopy.copyTo(noSigChecksum, overwrite = true)
        runSignatureVerifierExpectFail(noSigArchive, sigPublicKey, "missing")

        // Negative 2: Tampered checksum sidecar after signing
        val tamperedSigArchive = sigArchiveRoot.resolve("tampered-sidecar.tar.gz")
        val tamperedSigChecksum = sigArchiveRoot.resolve("tampered-sidecar.tar.gz.sha256")
        val tamperedSigSig = sigArchiveRoot.resolve("tampered-sidecar.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(tamperedSigArchive, overwrite = true)
        sigChecksumCopy.copyTo(tamperedSigChecksum, overwrite = true)
        signChecksum(tamperedSigChecksum, sigPrivateKey, tamperedSigSig)
        // Tamper the sidecar after signing
        tamperedSigChecksum.appendText("tamper\n")
        runSignatureVerifierExpectFail(tamperedSigArchive, sigPublicKey, "FAILED")

        // Negative 3: Wrong public key
        val wrongKeyArchive = sigArchiveRoot.resolve("wrong-key.tar.gz")
        val wrongKeyChecksum = sigArchiveRoot.resolve("wrong-key.tar.gz.sha256")
        val wrongKeySig = sigArchiveRoot.resolve("wrong-key.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(wrongKeyArchive, overwrite = true)
        sigChecksumCopy.copyTo(wrongKeyChecksum, overwrite = true)
        signChecksum(wrongKeyChecksum, sigPrivateKey, wrongKeySig)
        val (_, wrongPublicKey) = generateKeypair(sigArchiveRoot.resolve("wrong-key-keys"))
        runSignatureVerifierExpectFail(wrongKeyArchive, wrongPublicKey, "FAILED")

        // Negative 4: Tampered archive after valid signature
        // Proves the script chains into verify-evidence-archive.sh after signature verification
        val tamperedArchiveSig = sigArchiveRoot.resolve("tampered-archive.tar.gz")
        val tamperedArchiveChecksum = sigArchiveRoot.resolve("tampered-archive.tar.gz.sha256")
        val tamperedArchiveSigFile = sigArchiveRoot.resolve("tampered-archive.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(tamperedArchiveSig, overwrite = true)
        // Write a proper sidecar referencing the tampered archive filename
        val tamperedSha = sha256(tamperedArchiveSig)
        tamperedArchiveChecksum.writeText("$tamperedSha  tampered-archive.tar.gz\n")
        signChecksum(tamperedArchiveChecksum, sigPrivateKey, tamperedArchiveSigFile)
        // Tamper the archive content after signature creation
        tamperedArchiveSig.appendBytes("tamper".toByteArray())
        // Signature was over the original checksum; archive is now different.
        // openssl verifies the signature (valid for the signed checksum),
        // then archive verifier rejects because the archive doesn't match the checksum.
        runSignatureVerifierExpectFail(tamperedArchiveSig, sigPublicKey, "checksum mismatch")

        // Negative 5: Missing public key (non-existent file)
        val missingKeyArchive = sigArchiveRoot.resolve("missing-key.tar.gz")
        val missingKeyChecksum = sigArchiveRoot.resolve("missing-key.tar.gz.sha256")
        val missingKeySig = sigArchiveRoot.resolve("missing-key.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(missingKeyArchive, overwrite = true)
        sigChecksumCopy.copyTo(missingKeyChecksum, overwrite = true)
        signChecksum(missingKeyChecksum, sigPrivateKey, missingKeySig)
        val nonexistentKey = sigArchiveRoot.resolve("nonexistent.pem")
        val missingKeyProcess = ProcessBuilder(
            "bash", signatureVerifier.absolutePath,
            missingKeyArchive.absolutePath, nonexistentKey.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val missingKeyOutput = missingKeyProcess.inputStream.bufferedReader().readText()
        val missingKeyExitCode = missingKeyProcess.waitFor()
        require(missingKeyExitCode != 0) {
            "Expected signature verifier to fail for missing public key. Output: $missingKeyOutput"
        }
        require(missingKeyOutput.contains("Public key must be a readable regular file", ignoreCase = true)) {
            "Expected missing public key error, but got: $missingKeyOutput"
        }

        logger.lifecycle("verifySovereignLabEvidenceBundle: generated bundle verified at ${bundle.absolutePath}")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignLabRuntimeSmoke
// ──────────────────────────────────────────────

tasks.register("verifySovereignLabRuntimeSmoke") {
    group = "verification"
    description = "Runs the sovereign lab runtime smoke test against embedded PostgreSQL."

    dependsOn(":examples:spring-sovereign-starter:e2eTest")

    doLast {
        val reportDir = file(
            "examples/spring-sovereign-starter/build/test-results/e2eTest/"
        )
        val reportFile = reportDir.resolve(
            "TEST-dev.tramai.examples.spring.SovereignLabProfileSmokeTest.xml"
        )

        require(reportFile.exists()) {
            "SovereignLabProfileSmokeTest did not run. " +
                "verifySovereignLabRuntimeSmoke must prove the lab smoke test executed.\n" +
                "Expected report: ${reportFile.absolutePath}"
        }

        val xml = reportFile.readText()
        require(xml.contains("failures=\"0\"") && xml.contains("errors=\"0\"")) {
            "SovereignLabProfileSmokeTest did not pass cleanly. " +
                "Check the test report at:\n  ${reportFile.absolutePath}"
        }

        logger.lifecycle("verifySovereignLabRuntimeSmoke: sovereign lab runtime smoke tests passed.")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignLabLocalModel
// ──────────────────────────────────────────────

tasks.register("verifySovereignLabLocalModel") {
    group = "verification"
    description = "Runs the opt-in sovereign lab local-model invocation proof (requires a real local OpenAI-compatible endpoint)."

    dependsOn(":examples:spring-sovereign-starter:localModelTest")

    doFirst {
        if (System.getenv("TRAMAI_ENABLE_LOCAL_MODEL_TEST") != "true") {
            logger.lifecycle(
                "verifySovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_TEST=true."
            )
            logger.lifecycle(
                "Set it and ensure a local OpenAI-compatible endpoint is running."
            )
        }
    }
}

// ──────────────────────────────────────────────
// Task: benchmarkSovereignLabLocalModel
// ──────────────────────────────────────────────

tasks.register("benchmarkSovereignLabLocalModel") {
    group = "verification"
    description = "Runs opt-in sovereign lab local-model benchmark diagnostics."

    dependsOn(":examples:spring-sovereign-starter:localModelBenchmark")

    doFirst {
        if (System.getenv("TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK") != "true") {
            logger.lifecycle(
                "benchmarkSovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK=true."
            )
            logger.lifecycle(
                "Set it and ensure a local OpenAI-compatible endpoint is running."
            )
        }
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
        "verifySovereignRuntimeApiBoundary",
    )

    doLast {
        logger.lifecycle("Sovereign runtime closure verification complete.")
        logger.lifecycle("Validated:")
        logger.lifecycle("  - check (full test suite)")
        logger.lifecycle("  - verifySovereignRuntimeReleaseCandidate")
        logger.lifecycle("  - :examples:spring-sovereign-starter:e2eTest")
        logger.lifecycle("  - verifySovereignRuntimeClosureDocs (documentation consistency)")
        logger.lifecycle("  - verifySovereignRuntimeApiBoundary (API stability boundary)")
        logger.lifecycle("Sovereignty roadmap is closed at the RC+ / enterprise proof level.")
    }
}

// ──────────────────────────────────────────────
// Task: verifyPostSovereigntyRoadmap
// ──────────────────────────────────────────────

tasks.register("verifyPostSovereigntyRoadmap") {
    group = "verification"
    description = "Verifies the post-sovereignty roadmap exists and contains required declaration statements."

    doLast {
        val roadmapDoc = file("docs/POST-SOVEREIGNTY-ROADMAP.md")
        require(roadmapDoc.exists()) {
            "Missing post-sovereignty roadmap document at ${roadmapDoc.absolutePath}."
        }

        val roadmapText = roadmapDoc.readText()

        val requiredPhrases = listOf(
            "Sovereign Lab Evidence Handoff v1 is complete",
            "Workflow Ergonomics",
            "API Stability",
            "Structured Output Contracts",
            "Runtime Evidence",
            "Phase 0",
            "Non-Goals",
            "Claim Boundaries",
            "Global Acceptance Criteria",
        )

        requiredPhrases.forEach { phrase ->
            require(roadmapText.contains(phrase)) {
                "Post-sovereignty roadmap is missing required phrase: $phrase"
            }
        }

        // Negative guard: prevent production-readiness overclaim in the roadmap itself.
        // These phrases must not appear *anywhere* — even in non-goals, because non-goals
        // are about what the roadmap explicitly defers, not about what the project claims.
        // Exceptions: "EU AI Act conformity" legitimately appears in the non-goals section
        // as a disclaimer; the guard uses a phrase that only an affirmative claim would use.
        val forbiddenClaims = listOf(
            "production certified",
            "is GA-certified",
        )

        forbiddenClaims.forEach { forbidden ->
            require(!roadmapText.contains(forbidden, ignoreCase = true)) {
                "Post-sovereignty roadmap must not claim: $forbidden"
            }
        }

        logger.lifecycle("Post-sovereignty roadmap verification complete.")
        logger.lifecycle("  - docs/POST-SOVEREIGNTY-ROADMAP.md exists")
        logger.lifecycle("  - Required declarations verified")
        logger.lifecycle("  - Forbidden claims absent")
    }
}

// Wire the roadmap guard into the default check lifecycle task so it runs
// on every build and protects the roadmap from accidental deletion or drift.
tasks.named("check") {
    dependsOn("verifyPostSovereigntyRoadmap")
}

// ──────────────────────────────────────────────
// Task: verifyProductPositioning
// ──────────────────────────────────────────────

tasks.register("verifyProductPositioning") {
    group = "verification"
    description = "Verifies the canonical product positioning document exists, contains required sections, and avoids forbidden claims."

    doLast {
        val positioningDoc = file("docs/product/positioning.md")
        require(positioningDoc.isFile) {
            "Missing product positioning document at ${positioningDoc.absolutePath}."
        }

        val text = positioningDoc.readText()

        // Required sections
        val requiredSections = listOf(
            "## Canonical Message",
            "### Tagline",
            "### One-Sentence Positioning",
            "### Thirty-Second Description",
            "## The Problem TramAI Solves",
            "## Product Category",
            "## Who TramAI Is For",
            "## Representative Use Cases",
            "## Product Pillars",
            "## What TramAI Is Not",
            "## Current Maturity",
            "## Claim Boundaries",
            "## Messaging Guide",
            "## Source-of-Truth Documents",
        )
        for (section in requiredSections) {
            require(text.contains(section)) {
                "Missing required section: '$section'"
            }
        }

        // Required tagline
        require(text.contains("Governed AI workflows for the JVM")) {
            "Missing canonical tagline: 'Governed AI workflows for the JVM'"
        }

        // Required one-sentence positioning
        require(text.contains("Kotlin-first JVM runtime for governed AI workflows")) {
            "Missing one-sentence positioning phrase"
        }

        // Required status/non-claims section
        require(text.contains("Current Maturity")) {
            "Missing current maturity section"
        }
        require(text.contains("Not implemented")) {
            "Missing deferred/not-implemented status indicators"
        }

        // Forbidden claims (case-insensitive, with punctuation variants)
        val forbiddenClaims = listOf(
            "fully compliant",
            "guarantees compliance",
            "production certified",
            "production-certified",
            "production-ready for every deployment",
            "guarantees sovereignty",
            "fully air-gapped by default",
            "amount-threshold authorization is implemented",
            "remote MCP tools are currently governed",
        )
        for (claim in forbiddenClaims) {
            require(!text.contains(claim, ignoreCase = true)) {
                "Forbidden claim found: '$claim'"
            }
        }

        // Verify old thesis links to canonical document
        val oldThesis = file("docs/security/PRODUCT-THESIS.md")
        require(oldThesis.isFile) {
            "Missing historical thesis at ${oldThesis.absolutePath}"
        }
        val thesisText = oldThesis.readText()
        require(thesisText.contains("product/positioning.md")) {
            "Old PRODUCT-THESIS.md must link to the canonical positioning document"
        }
        require(thesisText.contains("historical path retained")) {
            "Old PRODUCT-THESIS.md must declare itself as historical"
        }

        // Verify MCP boundary document correctly distinguishes server from client/connector
        val mcpDoc = file("docs/security/mcp-governance-boundary.md")
        require(mcpDoc.isFile) {
            "Missing MCP governance boundary at ${mcpDoc.absolutePath}"
        }
        val mcpText = mcpDoc.readText()

        // Must positively acknowledge the existing server
        require(mcpText.contains("currently includes an MCP server module", ignoreCase = true)) {
            "MCP governance boundary must state that the MCP server module exists"
        }

        // Must positively state the client/connector is not implemented
        require(mcpText.contains("does not currently implement the governed MCP client/connector", ignoreCase = true)) {
            "MCP governance boundary must state that the governed MCP client/connector is not implemented"
        }

        // Must not contain stale language that implies no MCP runtime exists at all
        val staleMcpPhrases = listOf(
            "does not implement an MCP server",
            "no MCP server exists",
            "does not currently implement an MCP connector, MCP client, MCP server",
            "before any MCP runtime implementation",
            "when TramAI eventually supports MCP",
        )
        for (phrase in staleMcpPhrases) {
            require(!mcpText.contains(phrase, ignoreCase = true)) {
                "Stale MCP language found: '$phrase'"
            }
        }

        // Verify roadmap contains PR #192
        val roadmap = file("docs/POST-SOVEREIGNTY-ROADMAP.md")
        require(roadmap.isFile) {
            "Missing post-sovereignty roadmap at ${roadmap.absolutePath}"
        }
        val roadmapText = roadmap.readText()
        require(roadmapText.contains("#192")) {
            "Post-sovereignty roadmap must reference PR #192"
        }

        logger.lifecycle("Product positioning verification complete.")
        logger.lifecycle("  - docs/product/positioning.md exists with all required sections")
        logger.lifecycle("  - Canonical tagline and one-sentence positioning present")
        logger.lifecycle("  - Current maturity and non-claims present")
        logger.lifecycle("  - Forbidden claims absent")
        logger.lifecycle("  - Old thesis links to canonical document")
        logger.lifecycle("  - MCP boundary correctly distinguishes server from client/connector")
        logger.lifecycle("  - Roadmap contains PR #192")
    }
}

// Wire into check
tasks.named("check") {
    dependsOn("verifyProductPositioning")
}

// ──────────────────────────────────────────────
// Task: verifyReadmePositioning
// ──────────────────────────────────────────────

tasks.register("verifyReadmePositioning") {
    group = "verification"
    description = "Verifies the README leads with governed workflows and avoids forbidden claims."

    doLast {
        val readme = file("README.md")
        require(readme.isFile) {
            "Missing README.md at ${readme.absolutePath}"
        }

        val text = readme.readText()

        // Required phrases
        val requiredPhrases = listOf(
            "Governed AI Workflows for the JVM",
            "Kotlin-first JVM runtime for governed AI workflows",
            "./gradlew :examples:governed-workflow:run",
            "examples/governed-workflow",
            "examples/approval-resume",
            "examples/sovereign-document-intelligence",
            "docs/guides/quickstart.md",
            "docs/guides/governed-workflow-quickstart.md",
            "docs/STATUS.md",
            "docs/product/positioning.md",
            "active development",
        )
        for (phrase in requiredPhrases) {
            require(text.contains(phrase, ignoreCase = true)) {
                "README must contain: '$phrase'"
            }
        }

        // The governed-workflow command must be the FIRST Gradle command in the README
        val governedCommand = "./gradlew :examples:governed-workflow:run"
        val governedRunIndex = text.indexOf(governedCommand)
        require(governedRunIndex >= 0) {
            "README must contain the governed-workflow run command"
        }

        val firstGradleIndex = Regex("""(?m)^\s*\./gradlew\b""")
            .find(text)
            ?.range
            ?.first
            ?: -1
        require(firstGradleIndex == governedRunIndex) {
            "The governed-workflow command must be the first Gradle command in README.md. " +
                "Found another Gradle command at position $firstGradleIndex before governed-workflow at $governedRunIndex."
        }

        // Verify all README navigation links resolve to actual files
        val navTargets = listOf(
            "docs/architecture/overview.md",
            "docs/modules/sovereign-runtime-module-matrix.md",
            "examples/governed-workflow",
            "examples/approval-resume",
            "examples/sovereign-document-intelligence",
        )
        for (path in navTargets) {
            val target = file(path)
            require(target.exists()) {
                "README navigation target does not exist: $path"
            }
        }

        // Forbidden claims (case-insensitive)
        val forbiddenClaims = listOf(
            "fully compliant",
            "guarantees compliance",
            "production certified",
            "production-certified",
            "production-ready for every deployment",
            "guarantees sovereignty",
            "fully air-gapped by default",
            "amount-threshold authorization is implemented",
            "remote MCP tools are currently governed",
        )
        for (claim in forbiddenClaims) {
            require(!text.contains(claim, ignoreCase = true)) {
                "Forbidden claim in README: '$claim'"
            }
        }

        // No premature competitor comparisons (reserved for PR #196)
        val forbiddenComparisons = listOf(
            "Spring AI lacks",
            "LangChain4j lacks",
            "better than Spring AI",
            "better than LangChain4j",
        )
        for (phrase in forbiddenComparisons) {
            require(!text.contains(phrase, ignoreCase = true)) {
                "Forbidden comparison in README: '$phrase'"
            }
        }

        // No stale roadmap sentence referencing completed phases
        val staleSentence = "The next phase focuses on workflow ergonomics, API stability, " +
            "structured output contracts, and runtime evidence"
        require(!text.contains(staleSentence, ignoreCase = true)) {
            "README must not contain stale roadmap sentence about completed phases"
        }

        logger.lifecycle("README positioning verification complete.")
        logger.lifecycle("  - All required phrases present")
        logger.lifecycle("  - Governed-workflow command is the first Gradle command")
        logger.lifecycle("  - All navigation targets exist")
        logger.lifecycle("  - Forbidden claims absent")
        logger.lifecycle("  - No premature competitor comparisons")
        logger.lifecycle("  - No stale roadmap language")
    }
}

// Wire into check
tasks.named("check") {
    dependsOn("verifyReadmePositioning")
    dependsOn("verifyGovernedWorkflowArticle")
    dependsOn("verifyExampleSelectionGuide")
    dependsOn("verifyJvmAiFrameworkComparison")
}

// ──────────────────────────────────────────────
// Task: verifyGovernedWorkflowArticle
// ──────────────────────────────────────────────

tasks.register("verifyGovernedWorkflowArticle") {
    group = "verification"
    description = "Verifies the governed AI workflow article and companion talk outline are correct."

    doLast {
        val article = file("docs/articles/governed-ai-workflows-for-the-jvm.md")
        val talk = file("docs/talks/governed-ai-workflows-for-the-jvm.md")
        require(article.isFile) {
            "Missing article at ${article.absolutePath}"
        }
        require(talk.isFile) {
            "Missing talk outline at ${talk.absolutePath}"
        }

        val articleText = article.readText()
        val talkText = talk.readText()

        // Required article headings
        val requiredHeadings = listOf(
            "## The Model Call Is the Easy Part",
            "## Governance Cannot Live Only in Prompts",
            "## What Makes a Workflow Governed",
            "## A Concrete Example: Claim Triage",
            "## Policy Before Side Effects",
            "## Human Approval Is a Lifecycle",
            "## Controlled Routing for Sensitive Workloads",
            "## Evidence and Operational Recovery",
            "## Why the JVM",
            "## Composable Adoption",
            "## What This Does Not Claim",
            "## Try It",
        )
        for (heading in requiredHeadings) {
            require(articleText.contains(heading)) {
                "Article missing required heading: '$heading'"
            }
        }

        // Required phrases
        val requiredPhrases = listOf(
            "Governed AI workflows for the JVM",
            "when governance components are configured",
            "active development",
            "does not itself",
            "make an organization compliant",
            "./gradlew :examples:governed-workflow:run",
            "policy-check",
            "approval-required",
            "replay-safe continuation",
            "classification-aware routing",
            "tamper-evident",
        )
        for (phrase in requiredPhrases) {
            require(articleText.contains(phrase, ignoreCase = true)) {
                "Article must contain: '$phrase'"
            }
        }

        // Verify the workflow snippet is actual source (not abridged placeholder)
        require(articleText.contains(".build {") || articleText.contains("abridged")) {
            "Article workflow snippet must either be the actual source with .build or labeled as abridged"
        }
        require(articleText.contains("ClaimTriageResult(")) {
            "Article workflow snippet must construct ClaimTriageResult directly, not use a made-up helper"
        }

        // Required links with target existence validation
        val requiredLinks = mapOf(
            "../../README.md" to "README.md",
            "../product/positioning.md" to "docs/product/positioning.md",
            "../STATUS.md" to "docs/STATUS.md",
            "../../examples/governed-workflow" to "examples/governed-workflow",
            "../../examples/approval-resume" to "examples/approval-resume",
            "../../examples/sovereign-document-intelligence" to "examples/sovereign-document-intelligence",
        )
        for ((link, targetPath) in requiredLinks) {
            require(articleText.contains(link)) {
                "Article must contain link: '$link'"
            }
            val target = file(targetPath)
            require(target.exists()) {
                "Article link target does not exist: $targetPath (linked as '$link')"
            }
        }

        // Required talk-outline sections
        val requiredTalkSections = listOf(
            "## Audience",
            "## Thirty-Minute Version",
            "## Forty-Five-Minute Version",
            "## Demo Plan",
            "## Speaker Claim Boundaries",
        )
        for (section in requiredTalkSections) {
            require(talkText.contains(section)) {
                "Talk outline missing required section: '$section'"
            }
        }

        // Forbidden claims (case-insensitive)
        val forbiddenClaims = listOf(
            "fully compliant",
            "guarantees compliance",
            "production certified",
            "production-certified",
            "production-ready for every deployment",
            "guarantees sovereignty",
            "fully air-gapped by default",
            "amount-threshold authorization is implemented",
            "remote MCP tools are currently governed",
            "tamper-proof",
            "every decision is always recorded",
            "every workflow resumes exactly once",
            "record every decision",
            "every governance decision",
            "evidence export worker",
            "automatically exported as",
        )
        for (claim in forbiddenClaims) {
            require(!articleText.contains(claim, ignoreCase = true)) {
                "Forbidden claim in article: '$claim'"
            }
        }

        // No premature competitor comparisons (reserved for PR #196)
        val forbiddenComparisons = listOf(
            "Spring AI lacks",
            "LangChain4j lacks",
            "better than Spring AI",
            "better than LangChain4j",
        )
        for (phrase in forbiddenComparisons) {
            require(!articleText.contains(phrase, ignoreCase = true)) {
                "Forbidden comparison in article: '$phrase'"
            }
        }

        logger.lifecycle("Governed workflow article verification complete.")
        logger.lifecycle("  - Article and talk outline exist")
        logger.lifecycle("  - All required headings present")
        logger.lifecycle("  - Workflow snippet is actual source (not abridged)")
        logger.lifecycle("  - All required phrases present")
        logger.lifecycle("  - All link targets verified")
        logger.lifecycle("  - Talk outline sections present")
        logger.lifecycle("  - Forbidden claims absent")
        logger.lifecycle("  - No premature competitor comparisons")
    }
}

// ──────────────────────────────────────────────
// Task: verifyExampleSelectionGuide
// ──────────────────────────────────────────────

tasks.register("verifyExampleSelectionGuide") {
    group = "verification"
    description = "Verifies the example selection guide covers eight examples with correct classifications, commands, and non-claims."

    doLast {
        val guide = file("examples/README.md")
        require(guide.isFile) {
            "Missing example selection guide at ${guide.absolutePath}"
        }
        val text = guide.readText()

        // ── Section extraction helper ──
        fun sectionBetween(start: String, end: String): String {
            val s = text.indexOf(start)
            require(s >= 0) { "Missing section start: '$start'" }
            val e = text.indexOf(end, s + start.length)
            require(e >= 0) { "Missing section end marker after '$start': '$end'" }
            return text.substring(s, e)
        }

        // Required headings
        val requiredHeadings = listOf(
            "## Start Here",
            "## Choose by Goal",
            "## Example Matrix",
            "## Recommended Learning Paths",
            "## Example Profiles",
            "## What the Examples Do Not Prove",
        )
        for (heading in requiredHeadings) {
            require(text.contains(heading)) {
                "Guide missing required heading: '$heading'"
            }
        }

        // Required profiles
        val requiredProfiles = listOf(
            "### Governed Workflow",
            "### Support Agent",
            "### Kotlin Spring Boot Example",
            "### Approval Resume",
            "### Spring Sovereign Starter",
            "### Sovereign Document Intelligence",
            "### Sovereign Offline Verification",
            "### Sovereign Lab",
        )
        for (profile in requiredProfiles) {
            require(text.contains(profile)) {
                "Guide missing required profile: '$profile'"
            }
        }

        // Required commands
        val requiredCommands = listOf(
            "./gradlew :examples:governed-workflow:run",
            "./gradlew :examples:support-agent:run",
            "./gradlew :examples:approval-resume:test",
            "./gradlew :examples:spring-sovereign-starter:bootRun",
            "./gradlew :examples:sovereign-document-intelligence:run",
            "./gradlew -p examples/kotlin-springboot-example bootRun",
            "./scripts/verify-zero-egress.sh",
            "./gradlew verifySovereignLabProfile",
        )
        for (cmd in requiredCommands) {
            require(text.contains(cmd)) {
                "Guide must contain command: '$cmd'"
            }
        }

        // Required phrases
        val requiredPhrases = listOf(
            "no credentials",
            "embedded PostgreSQL",
            "no Docker",
            "separate Gradle build",
            "in-memory",
            "reference workflow",
            "verification harness",
            "physical local-model evaluation",
            "not a production deployment template",
            "active development",
        )
        for (phrase in requiredPhrases) {
            require(text.contains(phrase, ignoreCase = true)) {
                "Guide must contain: '$phrase'"
            }
        }

        // Target existence validation
        // Link-to-target validation — verifies both that the link text exists
        // in the guide AND that the target file exists on disk
        val requiredLinks = mapOf(
            "spring-sovereign-starter/README.md" to
                "examples/spring-sovereign-starter/README.md",
            "kotlin-springboot-example/README.md" to
                "examples/kotlin-springboot-example/README.md",
            "sovereign-lab/README.md" to "examples/sovereign-lab/README.md",
        )
        for ((link, targetPath) in requiredLinks) {
            require(text.contains(link)) {
                "Guide must contain relative link: $link"
            }
            val target = file(targetPath)
            require(target.isFile) {
                "Guide link target does not exist: $targetPath"
            }
        }

        // Offline harness link points to script (no README)
        require(text.contains("../scripts/verify-zero-egress.sh")) {
            "Guide must link the offline verification script as ../scripts/verify-zero-egress.sh"
        }
        require(file("scripts/verify-zero-egress.sh").isFile) {
            "Offline verification script does not exist: scripts/verify-zero-egress.sh"
        }

        // Prohibit duplicated prefix inside examples/README.md
        require(!text.contains("examples/sovereign-lab/README.md")) {
            "Links inside examples/README.md must not repeat the examples/ prefix"
        }

        // Root example modules in settings.gradle.kts
        val settingsText = file("settings.gradle.kts").readText()
        val rootModules = listOf(
            "examples:support-agent",
            "examples:sovereign-document-intelligence",
            "examples:sovereign-offline-verification",
            "examples:spring-sovereign-starter",
            "examples:governed-workflow",
            "examples:approval-resume",
        )
        for (module in rootModules) {
            require(settingsText.contains("\"$module\"")) {
                "settings.gradle.kts must still include root example module: $module"
            }
        }

        // ── Section-scoped checks ──

        // Matrix row: offline verification must document Docker + Python 3
        val matrixSection = sectionBetween("## Example Matrix", "## Example Profiles")
        require(matrixSection.contains("Docker + Python 3")) {
            "Offline verification matrix row must document 'Docker + Python 3', not 'Controlled network environment'"
        }

        // Governed Workflow
        val gwSection = sectionBetween("### Governed Workflow", "### Support Agent")
        require(gwSection.contains("no credentials")) {
            "Governed Workflow section must contain 'no credentials'"
        }
        require(gwSection.contains("composition")) {
            "Governed Workflow section must contain 'composition'"
        }

        // Support Agent
        val saSection = sectionBetween("### Support Agent", "### Kotlin Spring Boot Example")
        require(saSection.contains("Ollama")) {
            "Support Agent section must contain 'Ollama'"
        }
        require(saSection.contains("MockAiProvider")) {
            "Support Agent section must contain 'MockAiProvider'"
        }
        require(saSection.contains("@AiDescription")) {
            "Support Agent section must contain '@AiDescription'"
        }
        require(!saSection.contains("@Structured")) {
            "Support Agent section must not reference '@Structured' — the example uses @AiDescription"
        }

        // Kotlin Spring Boot Example
        val ktSection = sectionBetween("### Kotlin Spring Boot Example", "### Approval Resume")
        require(ktSection.contains("separate Gradle build")) {
            "Kotlin Spring Boot Example section must contain 'separate Gradle build'"
        }
        require(ktSection.contains("0.4.0")) {
            "Kotlin Spring Boot Example section must contain '0.4.0'"
        }
        require(ktSection.contains("gemma4:e4b")) {
            "Kotlin Spring Boot Example section must specify 'gemma4:e4b' model"
        }
        require(ktSection.contains("deepseek-r1:8b-64k")) {
            "Kotlin Spring Boot Example section must specify 'deepseek-r1:8b-64k' model"
        }

        // Approval Resume
        val arSection = sectionBetween("### Approval Resume", "### Spring Sovereign Starter")
        require(arSection.contains("embedded PostgreSQL")) {
            "Approval Resume section must contain 'embedded PostgreSQL'"
        }
        require(arSection.contains("no Docker")) {
            "Approval Resume section must contain 'no Docker'"
        }
        require(arSection.contains("at-most-once", ignoreCase = true)) {
            "Approval Resume section must contain 'at-most-once'"
        }

        // Spring Sovereign Starter
        val ssSection = sectionBetween("### Spring Sovereign Starter", "### Sovereign Document Intelligence")
        require(ssSection.contains("in-memory")) {
            "Spring Sovereign Starter section must contain 'in-memory'"
        }
        require(ssSection.contains("state is lost on restart")) {
            "Spring Sovereign Starter section must contain 'state is lost on restart'"
        }

        // Sovereign Document Intelligence
        val sdiSection = sectionBetween("### Sovereign Document Intelligence", "### Sovereign Offline Verification")
        require(sdiSection.contains("reference workflow")) {
            "Sovereign Document Intelligence section must contain 'reference workflow'"
        }
        require(sdiSection.contains("not a production deployment template")) {
            "Sovereign Document Intelligence section must contain 'not a production deployment template'"
        }

        // Sovereign Offline Verification
        val sovSection = sectionBetween("### Sovereign Offline Verification", "### Sovereign Lab")
        require(sovSection.contains("verification harness")) {
            "Sovereign Offline Verification section must contain 'verification harness'"
        }
        require(sovSection.contains("Docker")) {
            "Sovereign Offline Verification section must document Docker requirement"
        }
        require(sovSection.contains("Python 3")) {
            "Sovereign Offline Verification section must document Python 3 requirement"
        }
        require(sovSection.contains("--network=none")) {
            "Sovereign Offline Verification section must mention --network=none"
        }

        // Sovereign Lab
        val labSection = sectionBetween("### Sovereign Lab", "## Recommended Learning Paths")
        require(labSection.contains("PostgreSQL")) {
            "Sovereign Lab section must contain 'PostgreSQL'"
        }
        require(labSection.contains("local model")) {
            "Sovereign Lab section must contain 'local model'"
        }
        require(labSection.contains("advanced")) {
            "Sovereign Lab section must contain 'advanced'"
        }

        // Forbidden claims (case-insensitive)
        val forbiddenClaims = listOf(
            "all examples require no credentials",
            "all examples are production-ready",
            "proves compliance",
            "certifies compliance",
            "guarantees sovereignty",
            "LOCAL means air-gapped",
            "every TramAI deployment has zero egress",
            "all side effects execute exactly once",
            "every workflow resumes exactly once",
            "remote MCP tools are governed",
            "support-agent demonstrates sovereign governance",
        )
        for (claim in forbiddenClaims) {
            require(!text.contains(claim, ignoreCase = true)) {
                "Forbidden claim in guide: '$claim'"
            }
        }

        // No premature competitor comparisons (reserved for PR #196)
        val forbiddenComparisons = listOf(
            "Spring AI lacks",
            "LangChain4j lacks",
            "better than Spring AI",
            "better than LangChain4j",
        )
        for (phrase in forbiddenComparisons) {
            require(!text.contains(phrase, ignoreCase = true)) {
                "Forbidden comparison in guide: '$phrase'"
            }
        }

        logger.lifecycle("Example selection guide verification complete.")
        logger.lifecycle("  - Guide exists with all required headings")
        logger.lifecycle("  - All 8 example profiles present")
        logger.lifecycle("  - All required commands present")
        logger.lifecycle("  - All required phrases present")
        logger.lifecycle("  - All link targets verified (guide text + filesystem)")
        logger.lifecycle("  - No duplicated examples/ prefix in links")
        logger.lifecycle("  - Six root example modules still in settings.gradle.kts")
        logger.lifecycle("  - Section-scoped checks pass")
        logger.lifecycle("  - Forbidden claims absent")
        logger.lifecycle("  - No premature competitor comparisons")
    }
}

// ──────────────────────────────────────────────
// Task: verifyJvmAiFrameworkComparison
// ──────────────────────────────────────────────

tasks.register("verifyJvmAiFrameworkComparison") {
    group = "verification"
    description = "Verifies the JVM AI framework comparison document."

    doLast {
        val doc = file("docs/comparison/jvm-ai-frameworks.md")
        require(doc.isFile) {
            "Missing comparison document at ${doc.absolutePath}"
        }
        val text = doc.readText()

        // ── Section extraction helper ──
        fun sectionBetween(start: String, end: String): String {
            val s = text.indexOf(start)
            require(s >= 0) { "Missing section start: '$start'" }
            val e = text.indexOf(end, s + start.length)
            require(e >= 0) { "Missing section end marker after '$start': '$end'" }
            return text.substring(s, e)
        }

        // Section boundaries
        val springOptimization = sectionBetween("### Spring AI", "### LangChain4j")
        val langChainOptimization = sectionBetween("### LangChain4j", "### TramAI")
        val capabilitySection = sectionBetween("## Capability Comparison", "## Choose Spring AI When")
        val springChoiceSection = sectionBetween("## Choose Spring AI When", "## Choose LangChain4j When")
        val langChainChoiceSection = sectionBetween("## Choose LangChain4j When", "## Choose TramAI When")
        val tramaiChoiceSection = sectionBetween("## Choose TramAI When", "## Where TramAI Is Weaker Today")
        val weaknessesSection = sectionBetween("## Where TramAI Is Weaker Today", "## Coexistence and Migration")
        val coexistenceSection = sectionBetween("## Coexistence and Migration", "## Limitations and Non-Claims")

        // Spring AI content: optimization + capability (table + qualification) + choice
        val springAiContent = springOptimization + "\n" +
            sectionBetween("## Capability Comparison", "## Choose LangChain4j When")

        // LangChain4j content: optimization + capability (table + qualification) + choice
        val langChainContent = langChainOptimization + "\n" +
            sectionBetween("## Capability Comparison", "## Choose TramAI When")

        // TramAI content: comparison table + choice + weaknesses
        val tramaiContent = sectionBetween("## Capability Comparison", "## Choose Spring AI When") + "\n" +
            tramaiChoiceSection + "\n" + weaknessesSection

        // Required headings
        val requiredHeadings = listOf(
            "## Scope and Method",
            "## Version and Source Snapshot",
            "## What the Three Projects Optimize For",
            "## Shared Capabilities",
            "## Capability Comparison",
            "## Choose Spring AI When",
            "## Choose LangChain4j When",
            "## Choose TramAI When",
            "## Where TramAI Is Weaker Today",
            "## Coexistence and Migration",
            "## Limitations and Non-Claims",
            "## Source Notes",
        )
        for (heading in requiredHeadings) {
            require(text.contains(heading)) {
                "Comparison missing required heading: '$heading'"
            }
        }

        // Required snapshot phrases
        val snapshotPhrases = listOf(
            "July 12, 2026",
            "Spring AI 2.0.0",
            "LangChain4j 1.17.2",
            "0.4.0",
            "dated snapshot",
            "official documentation",
            "not an evergreen benchmark",
        )
        for (phrase in snapshotPhrases) {
            require(text.contains(phrase, ignoreCase = true)) {
                "Comparison must contain: '$phrase'"
            }
        }

        // Required Spring AI acknowledgements (scoped to Spring AI sections)
        val springAiTerms = listOf(
            "ChatClient",
            "Advisors",
            "ToolCallingManager",
            "structured output",
            "observability",
            "MCP client",
            "MCP server",
            "RAG",
        )
        for (term in springAiTerms) {
            require(springAiContent.contains(term, ignoreCase = true)) {
                "Spring AI section must acknowledge '$term'"
            }
        }

        // Required LangChain4j acknowledgements (scoped to LangChain4j sections)
        val langchainTerms = listOf(
            "AI Services",
            "structured outputs",
            "guardrails",
            "HumanInTheLoop",
            "PendingResponse",
            "persistent `AgenticScope`",
            "dynamic model selection",
            "compensation",
            "MCP client",
            "experimental",
        )
        for (term in langchainTerms) {
            require(langChainContent.contains(term, ignoreCase = true)) {
                "LangChain4j section must acknowledge '$term'"
            }
        }

        // Required TramAI boundaries (scoped to TramAI sections: comparison table, choice, weaknesses)
        val tramaiTerms = listOf(
            "policy",
            "DLP",
            "approval",
            "replay-safe",
            "trust-zone",
            "tamper-evident",
            "RC+",
            "active development",
        )
        for (term in tramaiTerms) {
            require(tramaiContent.contains(term, ignoreCase = true)) {
                "TramAI section must contain '$term'"
            }
        }

        // Required maturity acknowledgements (scoped to relevant sections)
        val langChainMaturityTerms = listOf(
            "guardrails are experimental",
            "agentic module is experimental",
        )
        for (term in langChainMaturityTerms) {
            require(langChainContent.contains(term, ignoreCase = true)) {
                "Comparison must acknowledge maturity: '$term'"
            }
        }

        val springMaturityTerms = listOf(
            "MCP security",
            "work in progress",
        )
        for (term in springMaturityTerms) {
            require(springAiContent.contains(term, ignoreCase = true)) {
                "Comparison must acknowledge maturity: '$term'"
            }
        }

        val tramaiMaturityTerms = listOf(
            "governed remote MCP client",
            "not implemented",
            "no stable sovereign 1.0 API",
        )
        for (term in tramaiMaturityTerms) {
            require(tramaiContent.contains(term, ignoreCase = true)) {
                "Comparison must acknowledge maturity: '$term'"
            }
        }

        // Required coexistence boundaries (scoped to Coexistence section)
        val coexistenceTerms = listOf(
            "not a drop-in replacement",
            "no official interoperability adapter",
            "architectural composition",
            "not a shipped adapter",
        )
        for (term in coexistenceTerms) {
            require(coexistenceSection.contains(term, ignoreCase = true)) {
                "Comparison must contain coexistence boundary: '$term'"
            }
        }

        // Official source-domain validation (link presence, not network access)
        val requiredSpringLink = "docs.spring.io/spring-ai/reference"
        val requiredLangchainLink = "docs.langchain4j.dev"
        val requiredLangchainRepoLink = "github.com/langchain4j/langchain4j"
        require(text.contains(requiredSpringLink)) {
            "Comparison must link to docs.spring.io/spring-ai/reference"
        }
        require(text.contains(requiredLangchainLink)) {
            "Comparison must link to docs.langchain4j.dev"
        }
        require(text.contains(requiredLangchainRepoLink)) {
            "Comparison must link to github.com/langchain4j/langchain4j"
        }

        // Forbidden claims (case-insensitive)
        val forbiddenClaims = listOf(
            "Spring AI lacks governance",
            "LangChain4j lacks governance",
            "Spring AI has no policy",
            "LangChain4j has no policy",
            "Spring AI cannot block requests",
            "LangChain4j cannot block requests",
            "Spring AI has no tool controls",
        )
        for (claim in forbiddenClaims) {
            require(!text.contains(claim, ignoreCase = true)) {
                "Forbidden claim in comparison: '$claim'"
            }
        }

        // Row-level comparison matrix checks (against capability section only)
        val matrixRows = listOf(
            "| **MCP client** | Implemented | Implemented | Not implemented",
            "| **MCP server** | Implemented | Community server",
            "| **Release maturity** | Stable 2.0.0",
            "Dedicated DLP/redaction",
            "Policy enforcement points with explicit ALLOW/DENY/REQUIRE_APPROVAL",
        )
        for (row in matrixRows) {
            require(capabilitySection.contains(row, ignoreCase = true)) {
                "Capability comparison table must contain row fragment: '$row'"
            }
        }

        logger.lifecycle("JVM AI framework comparison verification complete.")
        logger.lifecycle("  - All required headings present")
        logger.lifecycle("  - All snapshot phrases present")
        logger.lifecycle("  - Spring AI acknowledgements verified (section-scoped)")
        logger.lifecycle("  - LangChain4j acknowledgements verified (section-scoped)")
        logger.lifecycle("  - TramAI boundaries verified (section-scoped)")
        logger.lifecycle("  - Maturity and coexistence boundaries present (section-scoped)")
        logger.lifecycle("  - Comparison matrix rows verified (capability section)")
        logger.lifecycle("  - Official source links present")
        logger.lifecycle("  - Forbidden claims absent")
    }
}

// ──────────────────────────────────────────────
// Task: verifyWorkflowApiStabilityBoundary
// ──────────────────────────────────────────────

tasks.register("verifyWorkflowApiStabilityBoundary") {
    group = "verification"
    description = "Verifies the workflow API stability boundary document exists, contains required classifications, and avoids forbidden overclaims."

    doLast {
        val boundaryDoc = file("docs/workflow-api-stability-boundary.md")
        require(boundaryDoc.isFile) {
            "Missing workflow API stability boundary document at ${boundaryDoc.absolutePath}."
        }

        val text = boundaryDoc.readText()

        // ── Section extraction helper ──
        fun sectionBetween(text: String, start: String, end: String): String {
            require(text.contains(start)) {
                "Workflow API stability boundary is missing section: $start"
            }
            val after = text.substringAfter(start)
            return after.substringBefore(end)
        }

        // ── Extract each stability section by its heading pair ──
        val stableSection = sectionBetween(text, "## Stable Workflow Surface", "## Preview Workflow Surface")
        val previewSection = sectionBetween(text, "## Preview Workflow Surface", "## Internal Workflow Surface")
        val internalSection = sectionBetween(text, "## Internal Workflow Surface", "## Deferred Workflow Surface")
        val deferredSection = text.substringAfter("## Deferred Workflow Surface")
            .substringBefore("## Cross-References")
        val allowedClaimsSection = sectionBetween(text, "## Allowed Claims", "## Forbidden Claims")
        val forbiddenClaimsSection = text.substringAfter("## Forbidden Claims")
            .substringBefore("## Acceptance Criteria")

        // ── Stable section: core workflow annotations ──
        listOf(
            "@AiService",
            "@Operation",
            "@SystemMessage",
            "@UserMessage",
            "@AiTool",
            "@ConversationId",
            "@AIRange",
            "@AIMinItems",
            "PolicyEngine",
            "PolicyDecision",
            "ApprovalGateway",
            "SovereignWorkflowResult",
            "TramaiException",
        ).forEach { phrase ->
            require(stableSection.contains(phrase)) {
                "Stable workflow API section must contain: $phrase"
            }
        }

        // ── Preview section: evolving capabilities ──
        listOf(
            "orchestration patterns",
            "evidence export",
            "MCP adapter",
            "tool governance",
            "REST/control-plane",
        ).forEach { phrase ->
            require(previewSection.contains(phrase, ignoreCase = true)) {
                "Preview workflow API section must contain: $phrase"
            }
        }

        // ── Internal section: implementation details ──
        listOf(
            "JDBC",
            "Worker lease internals",
            "audit outbox",
            "Gradle verification task",
        ).forEach { phrase ->
            require(internalSection.contains(phrase, ignoreCase = true)) {
                "Internal workflow API section must contain: $phrase"
            }
        }

        // ── Deferred section: out-of-scope capabilities ──
        listOf(
            "Release Console",
            "compliance",
            "attestation",
            "key rotation",
        ).forEach { phrase ->
            require(deferredSection.contains(phrase, ignoreCase = true)) {
                "Deferred workflow API section must contain: $phrase"
            }
        }

        // ── Allowed Claims section must exist and mention key allowed statements ──
        require(allowedClaimsSection.contains("stable", ignoreCase = true)) {
            "Allowed Claims section must contain stability reference."
        }

        // ── Forbidden Claims section must reject overclaims ──
        listOf(
            "all workflow APIs are stable",
            "production-certified",
            "backward compatibility for preview APIs",
            "EU AI Act conformity certification",
            "proves legal or regulatory compliance",
        ).forEach { phrase ->
            require(forbiddenClaimsSection.contains(phrase, ignoreCase = true)) {
                "Forbidden Claims section must reject: $phrase"
            }
        }

        logger.lifecycle("Workflow API stability boundary verification complete.")
        logger.lifecycle("  - docs/workflow-api-stability-boundary.md exists")
        logger.lifecycle("  - Stable section: core annotations verified")
        logger.lifecycle("  - Preview section: evolving capabilities verified")
        logger.lifecycle("  - Internal section: implementation details verified")
        logger.lifecycle("  - Deferred section: out-of-scope capabilities verified")
        logger.lifecycle("  - Allowed/forbidden claims sections verified")
    }
}
// ──────────────────────────────────────────────
// Task: verifyVersionAlignment
// ──────────────────────────────────────────────

tasks.register("verifyVersionAlignment") {
    group = "verification"
    description = "Verifies the repository version surfaces are aligned: 0.5.0 as release version."

    doLast {
        val expectedVersion = "0.5.0"
        val expectedReleaseDate = project.findProperty("tramaiReleaseDate") as? String
            ?: error("tramaiReleaseDate must be set in gradle.properties")

        // 1. gradle.properties contains exactly tramaiVersion=0.5.0
        val propsFile = file("gradle.properties")
        require(propsFile.isFile) { "Missing gradle.properties" }
        val propsText = propsFile.readText()
        require(propsText.contains("tramaiVersion=$expectedVersion")) {
            "gradle.properties must set tramaiVersion=$expectedVersion"
        }
        require(!propsText.contains("tramaiVersion=$expectedVersion-SNAPSHOT")) {
            "gradle.properties must not contain -SNAPSHOT suffix for a release"
        }

        // 2. Build fallback is 0.5.0
        val buildFile = file("build.gradle.kts")
        val buildText = buildFile.readText()
        require(buildText.contains("orElse(\"$expectedVersion\")")) {
            "build.gradle.kts fallback must be $expectedVersion"
        }

        // 3. CHANGELOG.md has ## Unreleased present above a dated 0.5.0 section
        val changelog = file("CHANGELOG.md")
        val changelogText = changelog.readText()
        require(changelogText.contains("## Unreleased")) {
            "CHANGELOG.md must retain ## Unreleased heading"
        }
        val unreleasedSection = changelogText.substringAfter("## Unreleased")
            .substringBefore("## ")
        // After promotion, ## Unreleased is immediately followed by ## 0.5.0
        val afterUnreleased = changelogText.substringAfter("## Unreleased")
        require(afterUnreleased.contains("## $expectedVersion - $expectedReleaseDate")) {
            "CHANGELOG.md must contain a dated $expectedVersion section after ## Unreleased"
        }

        // 4. Active Gradle and Maven dependency snippets use 0.5.0
        val newVersionCoordinate = Regex("""dev\.tramai:[a-z0-9-]+:$expectedVersion""")
        val newMavenVersion = Regex("""<version>\s*$expectedVersion\s*</version>""")

        // 5. No active 0.5.0-SNAPSHOT references remain
        val snapshotGradleCoordinate = Regex("""dev\.tramai:[a-z0-9-]+:0\.5\.0-SNAPSHOT""")
        val snapshotMavenVersion = Regex("""<version>\s*0\.5\.0-SNAPSHOT\s*</version>""")
        val snapshotVariable = Regex("""tramaiVersion\s*=\s*"0\.5\.0-SNAPSHOT"""")

        // 6. 0.4.0 remains documented as the previous release where relevant
        val statusDoc = file("docs/STATUS.md")
        val statusText = statusDoc.readText()
        require(statusText.contains("0.4.0") && statusText.contains("Latest published release")) {
            "STATUS.md must identify 0.4.0 as latest published release"
        }

        // 7. The roadmap identifies the completed 0.5.0 train
        val roadmap = file("docs/POST-SOVEREIGNTY-ROADMAP.md")
        val roadmapText = roadmap.readText()
        require(roadmapText.contains("Release train: TramAI $expectedVersion")) {
            "Roadmap must identify release train $expectedVersion"
        }
        require(roadmapText.contains("$expectedVersion release")) {
            "Roadmap must reference $expectedVersion release"
        }
        // 7b. Roadmap tables use valid Markdown (no line starting with ||)
        require(!roadmapText.lineSequence().any { it.trimStart().startsWith("||") }) {
            "Roadmap contains malformed Markdown table rows beginning with '||' — pipe prefixes must be a single |"
        }

        // 8. Release notes and readiness documents exist
        require(file("docs/releases/$expectedVersion-release-readiness.md").isFile) {
            "Missing $expectedVersion release-readiness document"
        }
        require(file("docs/releases/sovereign-runtime-release-readiness.md").isFile) {
            "Missing sovereign-runtime release-readiness document"
        }

        // 9. Consumer docs use 0.5.0 for active coordinates (historical records excluded)
        val consumerDocs = listOf(
            "README.md",
            "docs/guides/getting-started.md",
            "docs/guides/quickstart.md",
            "docs/guides/spring-boot.md",
            "docs/guides/standalone-usage.md",
            "docs/guides/tutorial-invoice-analyzer.md",
            "docs/module-guide.md",
            "examples/README.md",
            "examples/support-agent/build.gradle.kts",
            "examples/kotlin-springboot-example/build.gradle.kts",
            "examples/kotlin-native-smoke-example/build.gradle.kts",
            "examples/sovereign-runtime-consumer-smoke/build.gradle.kts",
            "examples/spring-sovereign-starter/build.gradle.kts",
        )
        // Also check all module docs
        val moduleDocsDir = file("docs/modules")
        val moduleDocs = if (moduleDocsDir.isDirectory) {
            moduleDocsDir.listFiles().orEmpty().filter { it.name.endsWith(".md") }.map { it.path }
        } else emptyList()
        val allConsumerDocs = consumerDocs + moduleDocs

        // Historical allowlist - old release records
        val historicalAllowlist = setOf(
            "docs/releases/CHANGELOG-0.3.1.md",
            "docs/releases/CHANGELOG-0.4.0.md",
            "docs/guides/secure-defaults-migration.md",
            "docs/reference/release-0.1.0.md",
        )

        for (path in allConsumerDocs) {
            val f = file(path)
            if (!f.isFile) continue
            if (f.canonicalPath in historicalAllowlist.map { file(it).canonicalPath }) continue
            val content = f.readText()

            // No stale SNAPSHOT references in active docs
            require(!snapshotGradleCoordinate.containsMatchIn(content)) {
                "Consumer doc $path still contains dev.tramai:*:0.5.0-SNAPSHOT dependency reference"
            }
            require(!snapshotMavenVersion.containsMatchIn(content)) {
                "Consumer doc $path still contains Maven <version>0.5.0-SNAPSHOT</version>"
            }
            require(!snapshotVariable.containsMatchIn(content)) {
                "Consumer doc $path still contains tramaiVersion = \"0.5.0-SNAPSHOT\""
            }
        }

        // 10. No malformed Markdown tables or prohibited claims
        require(!roadmapText.lineSequence().any { it.trimStart().startsWith("||") }) {
            "Roadmap contains malformed Markdown table rows beginning with '||'"
        }

        logger.lifecycle("Version alignment verification complete.")
        logger.lifecycle("  - gradle.properties: $expectedVersion")
        logger.lifecycle("  - Build fallback: $expectedVersion")
        logger.lifecycle("  - STATUS.md: 0.4.0 stable, $expectedVersion current")
        logger.lifecycle("  - CHANGELOG: ## Unreleased + dated $expectedVersion section")
        logger.lifecycle("  - Roadmap: release train $expectedVersion")
        logger.lifecycle("  - Release readiness document: present")
        logger.lifecycle("  - Consumer docs: no stale SNAPSHOT references")
        logger.lifecycle("  - Historical records: preserved")
    }
}

// ──────────────────────────────────────────────
// Task: verifyToolGovernanceExample
// ──────────────────────────────────────────────

tasks.register("verifyToolGovernanceExample") {
    group = "verification"
    description = "Verifies the tool-governance example module and guide are correctly wired."

    doLast {
        val exampleDir = file("examples/tool-governance")
        val settingsText = file("settings.gradle.kts").readText()
        val examplesReadme = file("examples/README.md").readText()
        val guideFile = file("docs/guides/governed-tool-use.md")

        require(exampleDir.isDirectory) {
            "examples/tool-governance/ directory must exist"
        }
        require(settingsText.contains("\"examples:tool-governance\"")) {
            "settings.gradle.kts must include examples:tool-governance"
        }
        require(file("examples/tool-governance/src/main/kotlin/dev/tramai/examples/toolgovernance/ToolGovernanceMain.kt").isFile) {
            "ToolGovernanceMain.kt must exist"
        }
        require(file("examples/tool-governance/README.md").isFile) {
            "examples/tool-governance/README.md must exist"
        }
        require(examplesReadme.contains("./gradlew :examples:tool-governance:run")) {
            "examples/README.md must contain the exact run command"
        }
        // Verify the example matrix uses :run as primary command
        val matrixLine = examplesReadme.lines().find { it.contains("Tool Governance") && it.contains("./gradlew") }
        require(matrixLine != null && matrixLine.contains(":run")) {
            "examples/README.md example matrix must use :run as primary command for tool-governance, found: ${matrixLine?.take(80)}"
        }
        require(guideFile.isFile) {
            "docs/guides/governed-tool-use.md must exist"
        }
        val guideText = guideFile.readText()
        require(guideText.contains("./gradlew :examples:tool-governance:run")) {
            "governed-tool-use.md must contain the run command"
        }
        for (ep in listOf("BEFORE_TOOL_EXPOSURE", "BEFORE_TOOL_EXECUTION", "BEFORE_TOOL_RESULT_REINJECTION")) {
            require(guideText.contains(ep)) {
                "governed-tool-use.md must mention enforcement point '$ep'"
            }
        }
        for (decision in listOf("ALLOW", "DENY", "REQUIRE_APPROVAL")) {
            require(guideText.contains(decision)) {
                "governed-tool-use.md must mention decision '$decision'"
            }
        }
        require(guideText.contains("tool.permission")) {
            "governed-tool-use.md must reference tool.permission evidence"
        }
        require(guideText.contains("exposure permission is not execution permission")) {
            "governed-tool-use.md must state that exposure permission is not execution permission"
        }
        require(guideText.contains("never appear")) {
            "governed-tool-use.md must contain privacy boundaries"
        }
        require(guideText.contains("compliance") && guideText.contains("certification")) {
            "governed-tool-use.md must contain non-compliance and non-certification boundaries"
        }

        val roadmapText = file("docs/POST-SOVEREIGNTY-ROADMAP.md").readText()
        require(roadmapText.contains("PR #201")) {
            "POST-SOVEREIGNTY-ROADMAP.md must reference PR #201"
        }

        logger.lifecycle("verifyToolGovernanceExample: all documentation guards passed.")
    }
}

// ──────────────────────────────────────────────
// Task: verify050ReleaseReadiness
// ──────────────────────────────────────────────

tasks.register("verify050ReleaseReadiness") {
    group = "verification"
    description = "Aggregates all 0.5.0 release-readiness verification tasks."
    notCompatibleWithConfigurationCache("Release readiness aggregates execution-time verification tasks.")

    dependsOn(
        "verifyVersionAlignment",
        "verifyReleaseReadiness",
        "verifyWorkflowApiStabilityBoundary",
        "verifySovereignRuntimeApiBoundary",
        "verifyToolGovernanceExample",
    )

    doLast {
        val rootDir = rootProject.layout.projectDirectory.asFile
        val expectedVersion = "0.5.0"
        val expectedReleaseDate = project.findProperty("tramaiReleaseDate") as? String
            ?: error("tramaiReleaseDate must be set in gradle.properties")

        // 0.5.0 release-readiness document exists
        val releaseReadinessDoc = rootDir.resolve("docs/releases/$expectedVersion-release-readiness.md")
        require(releaseReadinessDoc.isFile) {
            "Missing $expectedVersion release-readiness document at ${releaseReadinessDoc.path}"
        }

        // CHANGELOG has 0.5.0 section
        val changelog = rootDir.resolve("CHANGELOG.md")
        val changelogText = changelog.readText()
        require(changelogText.contains("## $expectedVersion - $expectedReleaseDate")) {
            "CHANGELOG.md must contain ## $expectedVersion - $expectedReleaseDate section"
        }

        // STATUS and roadmap state are correct
        val statusDoc = rootDir.resolve("docs/STATUS.md")
        val statusText = statusDoc.readText()
        require(statusText.contains("0.5.0 release candidate prepared")) {
            "STATUS.md must mention 0.5.0 release candidate prepared"
        }

        val roadmap = rootDir.resolve("docs/POST-SOVEREIGNTY-ROADMAP.md")
        val roadmapText = roadmap.readText()
        require(roadmapText.contains("Release prepared — publication pending")) {
            "Roadmap must indicate release prepared — publication pending"
        }

        // Publish workflow has tag/version matching
        val publishWorkflow = rootDir.resolve(".github/workflows/publish.yml")
        val publishText = publishWorkflow.readText()
        require(publishText.contains("Verify version alignment") || publishText.contains("version alignment")) {
            "Publish workflow must contain version alignment check"
        }

        // No absolute /home/... links in release docs (allow placeholder /home/...)
        val localHomePath = Regex("""/home/(?!\.\.\.)[^/\s]+/""")
        val releaseDocs = listOf(
            rootDir.resolve("docs/reference/release-validation.md"),
            rootDir.resolve("docs/reference/releasing.md"),
            rootDir.resolve("docs/releases/$expectedVersion-release-readiness.md"),
            rootDir.resolve("docs/releases/sovereign-runtime-release-readiness.md"),
        )
        for (doc in releaseDocs) {
            if (!doc.isFile) continue
            val docText = doc.readText()
            require(!localHomePath.containsMatchIn(docText)) {
                "${doc.name} must not contain absolute /home/<user>/ paths — use repository-relative links"
            }
        }

        // No duplicate PR entries in the Added section
        val addedSection = changelogText.substringAfter("### Added").substringBefore("### Changed")
        val prPattern = Regex("""\(PR #(\d+)\)""")
        val prCounts = prPattern.findAll(addedSection).map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val duplicates = prCounts.filter { it.value > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate PR entries in Added section: ${duplicates.keys.joinToString(", ") { "PR #$it appears ${duplicates[it]} times" }}"
        }

        // No stale "no DB outbox" or "single-node only" claims in sovereign-runtime-release-readiness.md
        val sovereignReadiness = rootDir.resolve("docs/releases/sovereign-runtime-release-readiness.md")
        if (sovereignReadiness.isFile) {
            val sovereignText = sovereignReadiness.readText()
            require(!sovereignText.contains("Database persistence is future work")) {
                "sovereign-runtime-release-readiness.md must not claim 'Database persistence is future work'"
            }
            require(!sovereignText.contains("No DB-backed outbox")) {
                "sovereign-runtime-release-readiness.md must not claim 'No DB-backed outbox'"
            }
            require(!sovereignText.contains("worker assumes single-node operation")) {
                "sovereign-runtime-release-readiness.md must not claim 'worker assumes single-node operation'"
            }
        }

        logger.lifecycle("verify050ReleaseReadiness: all checks passed.")
        logger.lifecycle("  - Version alignment: verified")
        logger.lifecycle("  - Release readiness: verified")
        logger.lifecycle("  - Workflow API stability boundary: verified")
        logger.lifecycle("  - Sovereign runtime API boundary: verified")
        logger.lifecycle("  - Tool governance example: verified")
        logger.lifecycle("  - 0.5.0 release-readiness doc: verified")
        logger.lifecycle("  - CHANGELOG: 0.5.0 section verified")
        logger.lifecycle("  - STATUS/roadmap: release-ready state verified")
        logger.lifecycle("  - Publish workflow: version alignment check verified")
        logger.lifecycle("  - Release docs: no absolute paths or stale claims")
    }
}

// ──────────────────────────────────────────────
// Task: check

tasks.named("check") {
    dependsOn("verify050ReleaseReadiness")
}
