package dev.tramai.build.sovereign

import dev.tramai.build.publishing.TramaiPublishingRepositories
import dev.tramai.build.quality.ModuleManifest
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.gradle.api.tasks.Exec
import java.io.File

/** Execution args + human-readable display for the consumer smoke invocation. */
data class ConsumerSmokeInvocation(
    val gradleWrapper: String,
    val args: List<String>,
    val display: String,
)

/**
 * Builds the consumer smoke invocation from the resolved verification repo
 * path. Pure so it can be unit tested with paths containing spaces — the
 * executable arguments must NEVER be derived by splitting the display string.
 */
fun consumerSmokeInvocation(verificationRepoAbsolutePath: String, tramaiVersion: String): ConsumerSmokeInvocation {
    val gradleWrapper = if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "./gradlew"
    val args = listOf(
        "-p", "examples/sovereign-runtime-consumer-smoke",
        "test",
        "-PtramaiVersion=$tramaiVersion",
        "-PsovereignRuntimeVerificationRepo=$verificationRepoAbsolutePath",
        "--no-configuration-cache",
    )
    return ConsumerSmokeInvocation(
        gradleWrapper = gradleWrapper,
        args = args,
        display = "$gradleWrapper ${args.joinToString(" ")}",
    )
}

/**
 * Registers the sovereign release verification pipeline as typed DefaultTasks
 * (Epic 9.2b). Applied to the root project. Task names and observable
 * semantics are identical to the historical root build script.
 *
 * Security invariant: the sovereign dry-run publishes ONLY to the dedicated
 * file:// sovereignBundleLocal repository. The plugin rejects a non-file
 * tramaiPublishReleaseUrl at CONFIGURATION time so no publish task can ever
 * run against a remote server.
 */
class TramaiSovereignVerificationPlugin : Plugin<Project> {

    /** Sovereign runtime publishable modules (test + publishToMavenLocal scope). Preserved exactly. */
    val sovereignRuntimePublishableModules = listOf(
        "tramai-security",
        "tramai-sovereign",
        "tramai-persistence-file",
        "tramai-spring-sovereign",
        "tramai-spring-boot-starter",
        "tramai-spring-boot-starter-sovereign-persistence-file",
        "tramai-spring-boot-starter-sovereign-ops",
        "tramai-spring-boot-starter-sovereign-ops-actuator",
        "tramai-spring-boot-starter-sovereign-ops-micrometer",
        "tramai-spring-boot-starter-sovereign-ops-observability",
    )

    /** Sovereign release modules (JAR collection scope). Preserved exactly. */
    val sovereignReleaseModules = listOf(
        ":tramai-core",
        ":tramai-security",
        ":tramai-structured",
        ":tramai-engine",
        ":tramai-standalone",
        ":tramai-sovereign",
        ":tramai-persistence-file",
        ":tramai-spring-core",
        ":tramai-observability",
        ":tramai-spring-sovereign",
        ":tramai-spring-boot-starter",
        ":tramai-spring-boot-starter-sovereign-persistence-file",
        ":tramai-spring-boot-starter-sovereign-ops",
        ":tramai-spring-boot-starter-sovereign-ops-actuator",
        ":tramai-spring-boot-starter-sovereign-ops-micrometer",
        ":tramai-spring-boot-starter-sovereign-ops-observability",
    )

    override fun apply(project: Project) {
        val sovereignBundleRepoUrl = TramaiPublishingRepositories.sovereignBundleRepoUrl(project.rootProject).get()
        val consumerSmoke = consumerSmokeInvocation(project)

        registerVerifySovereignRuntimePublication(project)
        registerVerifySovereignRuntimeSignedBundle(project, sovereignBundleRepoUrl)
        registerPrepareSovereignReleaseArtifacts(project)
        registerVerifySovereignReleaseManifest(project)
        registerVerifySovereignRuntimeVerificationRepoClosure(project)
        registerVerifySovereignRuntimeConsumerSmoke(project, consumerSmoke)
        registerGenerateSovereignReleaseEvidenceIndex(project, consumerSmoke)
    }

    /**
     * Publishable module set for the sovereign bundle: the root build's
     * manifest-derived extra (`tramai.publishableModulePaths`) minus the
     * excluded set. Resolved lazily at task realization — the extra is set in
     * the root build body AFTER the plugins block, so it must not be read in
     * [apply]. Falls back to the module manifest directly.
     */
    private fun sovereignBundleModules(project: Project): Set<String> {
        val fromExtra = (project.rootProject.extensions.extraProperties.properties["tramai.publishableModulePaths"] as? Collection<*>)
            ?.map { it.toString().removePrefix(":") }
            .orEmpty()
        return if (fromExtra.isNotEmpty()) {
            fromExtra.toSet() - TramaiPublishingRepositories.sovereignBundleExcludedProjectNames
        } else {
            ModuleManifest.publishableModulePaths(project.rootDir)
                .map { it.removePrefix(":") }
                .toSet() - TramaiPublishingRepositories.sovereignBundleExcludedProjectNames
        }
    }

    private fun consumerSmokeInvocation(project: Project): ConsumerSmokeInvocation {
        val consumerSmokeVersion = project.providers.gradleProperty("tramaiVersion").orElse("0.5.0").get()
        val verificationRepo = project.layout.buildDirectory
            .dir("sovereign-runtime-release-verification-repo")
            .get()
            .asFile
            .absolutePath
        return dev.tramai.build.sovereign.consumerSmokeInvocation(verificationRepo, consumerSmokeVersion)
    }

    private fun registerVerifySovereignRuntimePublication(project: Project) {
        project.tasks.register("verifySovereignRuntimePublication") {
            group = "verification"
            description = "Validates local publishability of sovereign runtime modules — POM metadata, sources/javadoc JARs, and dependency graph. Does not publish remotely."
            // Missing projects (TestKit fixtures) are skipped; production has all modules.
            sovereignRuntimePublishableModules.forEach { moduleName ->
                if (project.rootProject.findProject(":$moduleName") != null) {
                    dependsOn(":$moduleName:test", ":$moduleName:publishToMavenLocal")
                }
            }
            if (project.rootProject.findProject(":tramai-spring-boot-starter-sovereign-persistence-jdbc") != null) {
                dependsOn(":tramai-spring-boot-starter-sovereign-persistence-jdbc:test")
            }
            doLast {
                logger.lifecycle("Sovereign runtime publication validation complete.")
                logger.lifecycle("  Validated modules: ${sovereignRuntimePublishableModules.joinToString(", ")}")
                logger.lifecycle("  POMs, sources JARs, and javadoc JARs have been published to mavenLocal().")
                logger.lifecycle("  No remote repository was touched.")
            }
        }
    }

    private fun registerVerifySovereignRuntimeSignedBundle(
        project: Project,
        sovereignBundleRepoUrl: String,
    ) {
        val wantsSigning = project.providers.gradleProperty("signingKey").orNull.isNullOrBlank().not() &&
            project.providers.gradleProperty("signingPassword").orNull.isNullOrBlank().not()

        project.tasks.register<VerifySovereignSignedBundleTask>("verifySovereignRuntimeSignedBundle") {
            group = "verification"
            description = "Validates local signed publication bundle for the sovereign runtime release boundary. " +
                "Publishes to a dedicated local-only file-based Maven repository (" +
                "build/sovereign-runtime-release-verification-repo by default), validates artifact " +
                "structure (POMs, JARs, .module metadata), and optionally verifies .asc signatures " +
                "when signing properties are provided. Generates bundle-manifest.json. " +
                "Does NOT publish remotely, tag, bump versions, or freeze APIs."

            // Lazy URL guard — runs when THIS task is realized (selected for
            // execution or pulled in as a dependency), before any of its
            // publication dependencies can execute. Unrelated invocations
            // (test, verifyPublicationMetadata, ...) never realize the task
            // and therefore never see this check.
            val userProvidedUrl = project.providers.gradleProperty("tramaiPublishReleaseUrl").orNull
            if (userProvidedUrl != null && userProvidedUrl.isNotEmpty() && !userProvidedUrl.startsWith("file:")) {
                throw GradleException(
                    "verifySovereignRuntimeSignedBundle only supports file:// repositories for local " +
                        "verification. Got: $userProvidedUrl. The sovereign bundle dry-run publishes " +
                        "to a dedicated local-only repository and must never contact a remote server."
                )
            }

            val bundleModules = sovereignBundleModules(project).sorted()
            expectedGroup.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            moduleNames.set(bundleModules)
            signingRequested.set(wantsSigning)
            bundleRepositoryRootPath.set(sovereignBundleRepoUrl)

            mavenLocalRepositoryDirectory.fileProvider(
                project.providers.systemProperty("user.home")
                    .map { home -> File(home, ".m2/repository/${expectedGroup.get().replace('.', '/')}") }
            )
            bundleRepositoryDirectory.set(
                project.layout.buildDirectory.dir("sovereign-runtime-release-verification-repo")
            )
            bundleManifestFile.set(
                project.layout.buildDirectory.file("sovereign-runtime-release/bundle-manifest.json")
            )

            dependsOn(bundleModules.map { ":${it}:publishToMavenLocal" })
            dependsOn(bundleModules.map { ":${it}:publishMavenPublicationToSovereignBundleLocalRepository" })

            doFirst {
                if (wantsSigning) {
                    logger.lifecycle("Signing key provided — will validate .asc signatures.")
                } else {
                    logger.lifecycle("No signing key provided — skipping .asc signature validation.")
                }
            }
        }
    }

    private fun registerPrepareSovereignReleaseArtifacts(project: Project) {
        project.tasks.register<PrepareSovereignReleaseArtifactsTask>("prepareSovereignReleaseArtifacts") {
            group = "verification"
            description = "Collects JARs from sovereign release modules, computes SHA-256 digests, and generates release-artifacts-v1.json."

            groupId.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            version.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            moduleNames.set(sovereignReleaseModules.map { it.removePrefix(":") })
            gradleVersion.set(org.gradle.util.GradleVersion.current().version)
            javaVersion.set(project.providers.systemProperty("java.version").orElse("unknown"))
            artifactsDirectory.set(project.layout.buildDirectory.dir("sovereign-release/artifacts"))
            manifestFile.set(project.layout.buildDirectory.file("sovereign-release/release-artifacts-v1.json"))

            // Source JARs: consume the actual output of each module's
            // jar/sourcesJar/javadocJar task as real Gradle producer edges.
            // `tasks.matching { ... }.configureEach { }` would only fire when a
            // matching task is realized through OTHER means — on a clean
            // workspace the lazily-registered jar tasks are never realized, so
            // the edge silently never registers and prepare fails (regression
            // found by the Sovereign Runtime Release Candidate workflow).
            // `.all { }` registers the action eagerly for tasks added later.
            // Declaring task outputs as inputs (rather than the whole libs dir)
            // keeps Gradle's implicit dependency validation happy — reading the
            // dir would pull in producers like :tramai-engine:testFixturesJar
            // that this task neither depends on nor wants.
            sovereignReleaseModules.forEach { modulePath ->
                val subproject = project.rootProject.findProject(modulePath) ?: return@forEach
                listOf("jar", "sourcesJar", "javadocJar").forEach { taskName ->
                    subproject.tasks.matching { it.name == taskName }.all(
                        object : org.gradle.api.Action<org.gradle.api.Task> {
                            override fun execute(task: org.gradle.api.Task) {
                                if (task is org.gradle.api.tasks.bundling.Jar) {
                                    sourceJars.from(task.archiveFile)
                                    dependsOn(task)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun registerVerifySovereignReleaseManifest(project: Project) {
        project.tasks.register<VerifySovereignReleaseManifestTask>("verifySovereignReleaseManifest") {
            group = "verification"
            description = "Verifies that build/sovereign-release/release-artifacts-v1.json is internally consistent with the JAR files in build/sovereign-release/artifacts/."

            manifestFile.set(project.layout.buildDirectory.file("sovereign-release/release-artifacts-v1.json"))
            artifactsDirectory.set(project.layout.buildDirectory.dir("sovereign-release/artifacts"))
            dependsOn("prepareSovereignReleaseArtifacts")
        }
    }

    private fun registerVerifySovereignRuntimeVerificationRepoClosure(project: Project) {
        project.tasks.register<VerifySovereignRuntimeVerificationRepoClosureTask>("verifySovereignRuntimeVerificationRepoClosure") {
            group = "verification"
            description = "Validates that the sovereign runtime verification repo contains all required dev.tramai artifacts " +
                "for the consumer smoke build. Fails if any required module, POM, metadata, or JAR is missing."

            repositoryDirectory.set(project.layout.buildDirectory.dir("sovereign-runtime-release-verification-repo"))
            expectedGroup.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            moduleNames.set(sovereignBundleModules(project).sorted())
            dependsOn("verifySovereignRuntimeSignedBundle")
        }
    }

    private fun registerVerifySovereignRuntimeConsumerSmoke(project: Project, invocation: ConsumerSmokeInvocation) {
        project.tasks.register<Exec>("verifySovereignRuntimeConsumerSmoke") {
            group = "verification"
            description = "Runs the standalone sovereign runtime consumer smoke test against the dedicated verification repo."
            dependsOn("verifySovereignRuntimeVerificationRepoClosure")
            workingDir = project.projectDir
            // Never derive executable args from a display string — a repo path
            // containing spaces would be split into multiple arguments.
            commandLine(listOf(invocation.gradleWrapper) + invocation.args)
        }
    }

    private fun registerGenerateSovereignReleaseEvidenceIndex(project: Project, invocation: ConsumerSmokeInvocation) {
        project.tasks.register<GenerateSovereignReleaseEvidenceIndexTask>("generateSovereignReleaseEvidenceIndex") {
            group = "verification"
            description = "Generates a release evidence index (JSON + Markdown) tying together commit metadata, validation gates, bundle manifest, release artifact manifest, and artifact hashes. Fails if required evidence artifacts are missing."

            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            this.consumerSmokeCommand.set(invocation.display)
            repositoryRoot.set(project.layout.projectDirectory)
            bundleManifestFile.from(project.layout.buildDirectory.file("sovereign-runtime-release/bundle-manifest.json"))
            releaseManifestFile.from(project.layout.buildDirectory.file("sovereign-release/release-artifacts-v1.json"))
            verificationRepositoryDirectory.from(project.layout.buildDirectory.dir("sovereign-runtime-release-verification-repo"))
            releaseArtifactsDirectory.from(project.layout.buildDirectory.dir("sovereign-release/artifacts"))
            evidenceIndexJson.set(project.layout.buildDirectory.file("sovereign-runtime-release/evidence-index.json"))
            evidenceIndexMarkdown.set(project.layout.buildDirectory.file("sovereign-runtime-release/evidence-index.md"))

            dependsOn(
                "verifyReleaseReadiness",
                "verifySovereignRuntimePublication",
                "verifySovereignRuntimeSignedBundle",
                "verifySovereignRuntimeConsumerSmoke",
                "prepareSovereignReleaseArtifacts",
                "verifySovereignReleaseManifest",
            )
        }
    }
}
