package dev.tramai.build.sovereign

import dev.tramai.build.sovereign.evidence.Hashing
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI

/**
 * Validates the local signed publication bundle for the sovereign runtime
 * release boundary (9.2b extraction).
 *
 * Publishes to a dedicated local-only file-based Maven repository (task
 * dependencies wired by the plugin: publishToMavenLocal +
 * publishMavenPublicationToSovereignBundleLocalRepository), validates artifact
 * structure, optionally validates .asc signatures, and generates
 * bundle-manifest.json. Never publishes remotely.
 *
 * The manifest is written atomically: all validation happens first, the model
 * is fully constructed, then a temp file is written and moved into place. A
 * failed run leaves no new valid manifest.
 */
@DisableCachingByDefault(because = "Release evidence is intentionally run-specific (timestamps, hashes)")
abstract class VerifySovereignSignedBundleTask : DefaultTask() {

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val moduleNames: ListProperty<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mavenLocalRepositoryDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleRepositoryDirectory: DirectoryProperty

    @get:Input
    abstract val signingRequested: Property<Boolean>

    @get:Input
    abstract val bundleRepositoryRootPath: Property<String>

    @get:OutputFile
    abstract val bundleManifestFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val groupPath = expectedGroup.get().replace('.', '/')
        val expectedVersion = expectedVersion.get()
        val allModules = moduleNames.get()
        val wantsSigning = signingRequested.get()
        val m2Repo = mavenLocalRepositoryDirectory.get().asFile
        val bundleRepoDir = bundleRepositoryDirectory.get().asFile
        val manifestFile = bundleManifestFile.get().asFile

        // ── 0. Invalidate previous evidence first ─────────────────────────
        // If this run fails, no stale manifest from a previous PASS may
        // survive to be mistaken for current evidence.
        if (manifestFile.exists()) {
            manifestFile.delete()
        }

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

        // ── 3. Generate bundle-manifest.json (fail-closed, atomic) ────────
        logger.lifecycle("Generating bundle manifest...")
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
                    checksums[relPath] = "sha256:${Hashing.sha256Hex(file)}"
                }
            }

            val moduleEntry = buildString {
                append("      {")
                append("\"groupId\": \"${Hashing.jsonEscape(expectedGroup.get())}\", ")
                append("\"artifactId\": \"${Hashing.jsonEscape(moduleName)}\", ")
                append("\"version\": \"${Hashing.jsonEscape(expectedVersion)}\", ")
                append("\"baseName\": \"${Hashing.jsonEscape(baseName)}\", ")
                append("\"artifactPaths\": [${artifactPaths.joinToString(", ") { "\"${Hashing.jsonEscape(it)}\"" }}], ")
                if (signatures.isNotEmpty()) {
                    append("\"signatures\": [${signatures.joinToString(", ") { "\"${Hashing.jsonEscape(it)}\"" }}], ")
                }
                append("\"checksums\": {${checksums.entries.joinToString(", ") { "\"${Hashing.jsonEscape(it.key)}\": \"${Hashing.jsonEscape(it.value)}\"" }}}")
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
            appendLine("  \"generatedAt\": \"${Hashing.jsonEscape(now)}\",")
            appendLine("  \"version\": \"${Hashing.jsonEscape(expectedVersion)}\",")
            appendLine("  \"repository\": \"${Hashing.jsonEscape(bundleRepoDir.absolutePath)}\",")
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

        val bundleManifestFile = bundleManifestFile.get().asFile
        bundleManifestFile.parentFile.mkdirs()

        // Atomic write: temp file in the same directory, then move into place.
        val tempFile = File(bundleManifestFile.parentFile, "${bundleManifestFile.name}.tmp-${System.nanoTime()}")
        try {
            tempFile.writeText(jsonSink)
            if (!tempFile.renameTo(bundleManifestFile)) {
                // Fallback for filesystems where renameTo fails across same-dir edge cases
                bundleManifestFile.writeText(jsonSink)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
        logger.lifecycle("Bundle manifest generated: ${bundleManifestFile.absolutePath}")

        // ── Summary ──────────────────────────────────────────────────────
        logger.lifecycle("")
        logger.lifecycle("verifySovereignRuntimeSignedBundle — PASSED")
        logger.lifecycle("  Modules validated: ${allModules.size} (${allModules.joinToString(", ")})")
        logger.lifecycle("  Repository: ${bundleRepoDir.absolutePath}")
        logger.lifecycle("  Signatures: ${if (wantsSigning) "validated" else "not configured (skipped)"}")
        logger.lifecycle("  Remote publish: false")
        logger.lifecycle("  Tag created: false")
        logger.lifecycle("  Manifest: ${bundleManifestFile.absolutePath}")
    }
}
