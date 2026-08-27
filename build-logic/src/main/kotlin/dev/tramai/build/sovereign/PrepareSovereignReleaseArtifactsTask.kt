package dev.tramai.build.sovereign

import dev.tramai.build.sovereign.evidence.Hashing
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Collects JARs from sovereign release modules, computes SHA-256 digests, and
 * generates release-artifacts-v1.json (9.2b extraction).
 *
 * Deterministic: source files, group/version, Gradle version, and Java version
 * are all declared inputs; entries are globally sorted by filename. Marked
 * [CacheableTask] — safe because every environment input is declared.
 *
 * Enforces the expected repository module set: for every module in
 * [moduleNames] the binary JAR, sources JAR, and javadoc JAR must exist and be
 * non-empty. An expected module that disappears makes the task FAIL instead of
 * silently omitting it from the manifest.
 */
@CacheableTask
abstract class PrepareSovereignReleaseArtifactsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceJars: ConfigurableFileCollection

    @get:Input
    abstract val moduleNames: ListProperty<String>

    @get:Input
    abstract val groupId: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Input
    abstract val javaVersion: Property<String>

    @get:OutputDirectory
    abstract val artifactsDirectory: DirectoryProperty

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val outputDir = artifactsDirectory.get().asFile.parentFile
        val artifactsDir = artifactsDirectory.get().asFile
        val manifestOut = manifestFile.get().asFile

        // ── 0. Invalidate previous evidence first ─────────────────────────
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        if (manifestOut.exists()) {
            manifestOut.delete()
        }
        artifactsDir.mkdirs()

        val groupId = groupId.get()
        val version = version.get()
        val jarsByFileName = sourceJars.files.filter { it.isFile }.associateBy { it.name }

        // ── 1. Enforce the expected module set ────────────────────────────
        // Every expected module must contribute a binary JAR, a sources JAR,
        // and a javadoc JAR — all non-empty. This is the repository-set ↔
        // generated-manifest contract; ReleaseManifestVerifier separately
        // checks manifest ↔ files-on-disk.
        val expectedJars = mutableListOf<Pair<String, java.io.File>>()
        moduleNames.get().forEach { moduleName ->
            val baseName = "$moduleName-$version"
            val requiredSuffixes = listOf(
                "$baseName.jar" to "binary jar",
                "$baseName-sources.jar" to "sources jar",
                "$baseName-javadoc.jar" to "javadoc jar",
            )
            requiredSuffixes.forEach { (fileName, description) ->
                val jarFile = jarsByFileName[fileName]
                require(jarFile != null) {
                    "Sovereign release module $moduleName is missing its $description " +
                        "($fileName). Expected modules: ${moduleNames.get().joinToString(", ")}."
                }
                require(jarFile.length() > 0) {
                    "Sovereign release module $moduleName has an empty $description ($fileName)."
                }
                expectedJars.add(moduleName to jarFile)
            }
        }

        // ── 2. Build artifact entries (globally sorted by filename) ────────
        val artifactEntries = expectedJars
            .sortedBy { it.second.name }
            .map { (moduleName, jarFile) ->
                val copied = jarFile.copyTo(artifactsDir.resolve(jarFile.name), overwrite = true)
                val sha256 = "sha256:${Hashing.sha256Hex(copied)}"

                val classifier = when {
                    jarFile.name.contains("-sources.jar") -> "sources"
                    jarFile.name.contains("-javadoc.jar") -> "javadoc"
                    else -> null
                }

                buildString {
                    append("        {")
                    append("\"groupId\": \"${Hashing.jsonEscape(groupId)}\", ")
                    append("\"artifactId\": \"${Hashing.jsonEscape(moduleName)}\", ")
                    append("\"version\": \"${Hashing.jsonEscape(version)}\", ")
                    append("\"classifier\": ${if (classifier != null) "\"${Hashing.jsonEscape(classifier)}\"" else "null"}, ")
                    append("\"extension\": \"jar\", ")
                    append("\"fileName\": \"${Hashing.jsonEscape(jarFile.name)}\", ")
                    append("\"sha256\": \"$sha256\", ")
                    append("\"sizeBytes\": ${copied.length()}")
                    append("}")
                }
            }

        val javaVersion = javaVersion.get()
        val gradleVersion = gradleVersion.get()

        val json = buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"buildTool\": \"Gradle\",")
            appendLine("  \"javaVersion\": \"${Hashing.jsonEscape(javaVersion)}\",")
            appendLine("  \"gradleVersion\": \"${Hashing.jsonEscape(gradleVersion)}\",")
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

        manifestOut.parentFile.mkdirs()
        manifestOut.writeText(json)
        logger.lifecycle("Sovereign release artifact manifest generated: ${manifestOut.absolutePath}")
        logger.lifecycle("  Modules expected: ${moduleNames.get().size} (all present with jar/sources/javadoc)")
        logger.lifecycle("  Artifacts collected: ${artifactEntries.size}")
    }
}
