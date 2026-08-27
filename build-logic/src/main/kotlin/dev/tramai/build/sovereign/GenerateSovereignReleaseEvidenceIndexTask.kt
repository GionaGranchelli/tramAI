package dev.tramai.build.sovereign

import dev.tramai.build.sovereign.evidence.ConsumerSmokeEvidenceCheck
import dev.tramai.build.sovereign.evidence.DevTramaiResolutionPolicy
import dev.tramai.build.sovereign.evidence.EvidenceArtifact
import dev.tramai.build.sovereign.evidence.EvidenceCheck
import dev.tramai.build.sovereign.evidence.EvidenceChecks
import dev.tramai.build.sovereign.evidence.EvidenceIndexWriter
import dev.tramai.build.sovereign.evidence.Hashing
import dev.tramai.build.sovereign.evidence.SovereignReleaseEvidenceIndexV1
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Generates a release evidence index (JSON + Markdown) tying together commit
 * metadata, validation gates, bundle manifest, release artifact manifest, and
 * artifact hashes (9.2b extraction). Fails if required evidence artifacts are
 * missing.
 *
 * Git metadata is obtained at EXECUTION time — never during configuration —
 * via plain process execution, or supplied through optional task properties
 * (e.g. from CI) to avoid process execution entirely.
 *
 * Evidence is intentionally run-specific (timestamps, hashes): build caching
 * is disabled, configuration-cache compatibility is preserved.
 */
@DisableCachingByDefault(because = "Release evidence is intentionally run-specific (timestamps, git metadata)")
abstract class GenerateSovereignReleaseEvidenceIndexTask : DefaultTask() {

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Optional
    @get:Input
    abstract val consumerSmokeCommand: Property<String>

    @get:Optional
    @get:Input
    abstract val commitShaOverride: Property<String>

    @get:Optional
    @get:Input
    abstract val refNameOverride: Property<String>

    @get:Optional
    @get:Input
    abstract val repositoryOverride: Property<String>

    // Evidence inputs are declared as @InputFiles file collections so that
    // Gradle 9.0.0 (the repo wrapper version) accepts them when the referenced
    // files/directories do not exist yet. The task ACTION enforces the
    // fail-closed contract itself (missing evidence → FAIL after stale outputs
    // are invalidated). @InputFile/@InputDirectory + @Optional does NOT allow
    // missing files under Gradle 9.0.0 (validation problem input_file_does_not_exist).
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleManifestFile: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseManifestFile: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verificationRepositoryDirectory: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseArtifactsDirectory: ConfigurableFileCollection

    @get:OutputFile
    abstract val evidenceIndexJson: RegularFileProperty

    @get:OutputFile
    abstract val evidenceIndexMarkdown: RegularFileProperty

    /**
     * Repository root for git metadata commands. Configured by the plugin from
     * the project directory; tasks must NOT access `project` during execution
     * (configuration-cache violation), so the working directory arrives as an
     * injected task property instead.
     */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val buildDir = bundleManifestFile.files.single().parentFile.parentFile
        val outputDir = evidenceIndexJson.get().asFile.parentFile
        val jsonFile = evidenceIndexJson.get().asFile
        val mdFile = evidenceIndexMarkdown.get().asFile

        // ── 0. Invalidate previous evidence first ─────────────────────────
        // If this run fails, no stale index from a previous PASS may survive.
        if (jsonFile.exists()) {
            jsonFile.delete()
        }
        if (mdFile.exists()) {
            mdFile.delete()
        }
        outputDir.mkdirs()

        // ── Git metadata (fail-closed, execution time) ────────────────────
        val commitSha = commitShaOverride.orNull ?: runGit("rev-parse", "HEAD").also {
            require(it.matches(Regex("[a-f0-9]{40}"))) {
                "Cannot generate release evidence index without a valid git commit SHA."
            }
        }

        val refName = refNameOverride.orNull ?: runGit("rev-parse", "--abbrev-ref", "HEAD").also {
            require(it.isNotBlank() && it != "unknown") {
                "Cannot generate release evidence index without git ref metadata."
            }
        }

        val repository = repositoryOverride.orNull ?: run {
            val remoteUrl = runGit("config", "--get", "remote.origin.url")
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

        val version = expectedVersion.get()

        // ── Required artifact validation ──────────────────────────────────
        val bundleManifest = bundleManifestFile.files.single()
        val verificationRepo = verificationRepositoryDirectory.files.single()
        val releaseManifest = releaseManifestFile.files.single()
        val releaseArtifactsDir = releaseArtifactsDirectory.files.single()

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
        val repoFileCount = Hashing.fileCount(verificationRepo)
        val artifactsFileCount = Hashing.fileCount(releaseArtifactsDir)

        val artifactList = listOf(
            EvidenceArtifact(
                id = "sovereign-runtime-bundle-manifest",
                path = "build/sovereign-runtime-release/bundle-manifest.json",
                type = "json",
                required = true,
                sha256 = Hashing.sha256Hex(bundleManifest),
            ),
            EvidenceArtifact(
                id = "sovereign-release-artifact-manifest",
                path = "build/sovereign-release/release-artifacts-v1.json",
                type = "json",
                required = true,
                sha256 = Hashing.sha256Hex(releaseManifest),
            ),
            EvidenceArtifact(
                id = "sovereign-runtime-local-maven-repo",
                path = "build/sovereign-runtime-release-verification-repo",
                type = "directory",
                required = true,
                fileCount = repoFileCount,
                sha256Tree = Hashing.treeHash(verificationRepo),
            ),
            EvidenceArtifact(
                id = "sovereign-release-artifacts",
                path = "build/sovereign-release/artifacts/",
                type = "directory",
                required = true,
                fileCount = artifactsFileCount,
                sha256Tree = Hashing.treeHash(releaseArtifactsDir),
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
                    executes = consumerSmokeCommand.orNull ?: "",
                    devTramaiResolutionPolicy = DevTramaiResolutionPolicy(
                        allowedRepositories = listOf("build/sovereign-runtime-release-verification-repo"),
                        blockedRepositories = listOf("mavenLocal", "mavenCentral"),
                        coverage = "full-dev-tramai-dependency-closure",
                    ),
                ),
            ),
        )

        val jsonContent = EvidenceIndexWriter.writeJson(evidence)
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

        // Validate the devTramaiResolutionPolicy field
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
        mdFile.writeText(EvidenceIndexWriter.writeMarkdown(evidence))
        logger.lifecycle("Evidence index Markdown generated: ${mdFile.absolutePath}")
    }

    private fun runGit(vararg args: String): String {
        val output = ByteArrayOutputStream()
        execOperations.exec(
            object : org.gradle.api.Action<org.gradle.process.ExecSpec> {
                override fun execute(spec: org.gradle.process.ExecSpec) {
                    // Pin to the repository root: the daemon's CWD is not guaranteed to
                    // be the project directory, and git metadata in an evidence artifact
                    // must describe THIS repository. Configured at plugin time — task
                    // execution must never read `project`.
                    spec.workingDir(repositoryRoot.get().asFile)
                    spec.commandLine("git", *args)
                    spec.standardOutput = output
                    spec.errorOutput = output
                }
            },
        ).assertNormalExitValue()
        return output.toString(Charsets.UTF_8).trim()
    }
}
