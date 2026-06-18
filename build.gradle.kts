import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension
import org.gradle.util.GradleVersion
import org.w3c.dom.Element
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
    "tramai-spring-boot-starter-sovereign-ops-observability",
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

tasks.register("verifyReleaseReadiness") {
    group = "verification"
    description = "Runs the repo-local release verification checks for publication metadata and published artifacts."
    notCompatibleWithConfigurationCache("Release readiness aggregates execution-time verification tasks.")
    dependsOn(
        jarPublishingProjectNames.map { ":${it}:test" },
        "verifyPublicationMetadata",
        "verifyPublishedLocalArtifacts",
    )
}

val sovereignRuntimePublishableModules = listOf(
    "tramai-security",
    "tramai-sovereign",
    "tramai-persistence-file",
    "tramai-spring-boot-starter-sovereign",
    "tramai-spring-boot-starter-sovereign-persistence-file",
    "tramai-spring-boot-starter-sovereign-ops",
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
