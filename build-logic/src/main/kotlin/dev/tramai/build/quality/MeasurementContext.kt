package dev.tramai.build.quality

import org.gradle.api.Project
import java.io.File

/**
 * Abstracts over the measurement source: a Gradle project (normal mode) or a
 * detached worktree directory (canonical generation mode).
 *
 * Directory mode discovers the EXACT same module set as Gradle mode by parsing
 * the multi-argument `include(...)` block in settings.gradle.kts. No filesystem
 * fallback — this guarantees canonical and CI measurements compare the same population.
 *
 * All discovered module paths are normalized to Gradle format (leading `:`)
 * so that canonical and project modes produce identical module identities.
 */
class MeasurementContext(
    /** Root directory to scan for source files, build files, and git metadata. */
    val rootDir: File,
    /** Modules discovered in this tree. */
    val modules: List<DiscoveredModule>,
    /** The Gradle project (null when scanning a detached worktree). */
    val gradleProject: Project? = null
) {
    fun runGit(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        check(process.waitFor() == 0 && output.isNotBlank()) {
            "git ${arguments.joinToString(" ")} failed in ${rootDir.absolutePath}: ${output.ifBlank { "no output" }}"
        }
        return output.lineSequence().first()
    }

    fun runGitMulti(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        check(process.waitFor() == 0) {
            "git ${arguments.joinToString(" ")} failed in ${rootDir.absolutePath}: ${output.ifBlank { "no output" }}"
        }
        return output
    }

    fun isWorkingTreeClean(): Boolean {
        return try {
            val process = ProcessBuilder(listOf("git", "status", "--porcelain"))
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor() == 0 && output.isBlank()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        // ── Module metadata (provisional — will move to a machine-readable manifest) ──
        // NOTE: This hardcoded set is acknowledged as temporary. PR #204 will introduce
        // a shared module-metadata file consumed by both Gradle publication and analysis.

        private val publishableNames = setOf(
            "tramai-anthropic", "tramai-azure-openai", "tramai-bedrock", "tramai-bom",
            "tramai-core", "tramai-deepseek", "tramai-embedding", "tramai-engine",
            "tramai-gemini", "tramai-memory", "tramai-observability", "tramai-ollama",
            "tramai-openai", "tramai-orchestration", "tramai-platform", "tramai-spring",
            "tramai-standalone", "tramai-sovereign", "tramai-persistence-file",
            "tramai-structured", "tramai-testing", "tramai-vectorstore-spi",
            "tramai-vectorstore-chroma", "tramai-vectorstore-pgvector", "tramai-rag",
            "tramai-security", "tramai-spring-boot-starter-sovereign",
            "tramai-spring-boot-starter-sovereign-persistence-file",
            "tramai-spring-boot-starter-sovereign-persistence-jdbc",
            "tramai-spring-boot-starter-sovereign-ops",
            "tramai-spring-boot-starter-sovereign-ops-rest",
            "tramai-spring-boot-starter-sovereign-ops-actuator",
            "tramai-spring-boot-starter-sovereign-ops-micrometer",
            "tramai-spring-boot-starter-sovereign-ops-observability",
            "tramai-spring-boot-starter-local-provider-openai",
            "tramai-scheduler"
        )

        private fun classifyLayerByName(name: String, path: String): String {
            val normalizedPath = if (path.startsWith(":")) path else ":$path"
            return when {
            name == "tramai-core" -> "core-contracts"
            name == "tramai-bom" -> "core-contracts"
            name in listOf("tramai-engine", "tramai-structured", "tramai-orchestration", "tramai-standalone") -> "runtime-execution"
            name in listOf("tramai-security", "tramai-sovereign") -> "governance-security"
            name.startsWith("tramai-persistence") -> "persistence"
            name in listOf("tramai-anthropic", "tramai-azure-openai", "tramai-bedrock", "tramai-deepseek",
                "tramai-gemini", "tramai-ollama", "tramai-openai") -> "provider-adapters"
            name == "tramai-spring" || name.startsWith("tramai-spring-boot-starter") -> "framework-integrations"
            name in listOf("tramai-observability", "tramai-platform", "tramai-server", "tramai-dashboard",
                "tramai-mcp") -> "operations-observability"
            name in listOf("tramai-rag", "tramai-memory", "tramai-memory-store", "tramai-scheduler",
                "tramai-embedding") -> "higher-capabilities"
            name.startsWith("tramai-vectorstore") -> "higher-capabilities"
            normalizedPath.startsWith(":examples:") -> "applications-examples"
            name == "tramai-testing" -> "testing-support"
            else -> "unknown"
        }
    }

        // ── Factory methods ──

        fun fromProject(project: Project): MeasurementContext {
            val gradleModules = project.allprojects
                .filter { it != project && it.buildFile.exists() }
                .map { proj ->
                    DiscoveredModule(
                        name = proj.name,
                        path = proj.path,
                        projectDir = proj.projectDir,
                        buildFile = proj.buildFile,
                        sourceDirs = listOf(
                            File(proj.projectDir, "src/main/kotlin"),
                            File(proj.projectDir, "src/main/java"),
                        ),
                        testSourceDirs = listOf(
                            File(proj.projectDir, "src/test/kotlin"),
                        ),
                        testFixtureDirs = listOf(
                            File(proj.projectDir, "src/testFixtures/kotlin"),
                        ),
                        publishable = proj.name in publishableNames,
                        layer = classifyLayerByName(proj.name, proj.path),
                    )
                }

            return MeasurementContext(
                rootDir = project.rootDir,
                modules = gradleModules,
                gradleProject = project,
            )
        }

        fun fromDirectory(rootDir: File): MeasurementContext {
            val modules = discoverModulesFromSettings(rootDir)
            return MeasurementContext(
                rootDir = rootDir,
                modules = modules,
                gradleProject = null,
            )
        }

        // ── Settings parser (handles multi-line include(...) blocks) ──
        // All paths are normalized to Gradle format (leading ":") so that
        // canonical and project modes produce identical module identities.

        private fun discoverModulesFromSettings(rootDir: File): List<DiscoveredModule> {
            val settingsFile = File(rootDir, "settings.gradle.kts")
            val includedPaths = if (settingsFile.isFile) {
                parseSettingsIncludes(settingsFile)
            } else {
                emptyList()
            }

            return includedPaths.mapNotNull { includePath ->
                val moduleDir = File(rootDir, includePath.replace(":", "/"))
                val buildFile = File(moduleDir, "build.gradle.kts")
                if (!buildFile.isFile) return@mapNotNull null
                val name = includePath.substringAfterLast(":")

                DiscoveredModule(
                    name = name,
                    path = includePath,
                    projectDir = moduleDir,
                    buildFile = buildFile,
                    sourceDirs = listOf(
                        File(moduleDir, "src/main/kotlin"),
                        File(moduleDir, "src/main/java"),
                    ),
                    testSourceDirs = listOf(
                        File(moduleDir, "src/test/kotlin"),
                    ),
                    testFixtureDirs = listOf(
                        File(moduleDir, "src/testFixtures/kotlin"),
                    ),
                    publishable = name in publishableNames,
                    layer = classifyLayerByName(name, includePath),
                )
            }
        }

        /**
         * Parses a settings.gradle.kts file for all module paths inside `include(...)`.
         * Handles both single-argument `include("foo")` and multi-argument:
         *   include(
         *       "foo",
         *       "bar",
         *       "examples:baz"
         *   )
         *
         * Normalizes paths to Gradle format (leading `:`) so that canonical and
         * project modes produce identical module identities.
         */
        private fun parseSettingsIncludes(settingsFile: File): List<String> {
            val content = settingsFile.readText()

            // Extract the body of include(...) — everything between "include(" and the matching ")"
            val includeBlockRegex = Regex("""include\s*\(([^)]*(?:\([^)]*\)[^)]*)*)\)""", RegexOption.DOT_MATCHES_ALL)
            val blockMatch = includeBlockRegex.find(content) ?: return emptyList()

            // Extract all quoted strings from the block
            val quotedRegex = Regex(""""([^"]+)"""")
            return quotedRegex.findAll(blockMatch.groupValues[1]).map { it.groupValues[1] }.toList()
                .map { normalizeGradlePath(it) }
        }

        /** Normalize a module path to Gradle format (leading colon). */
        private fun normalizeGradlePath(includePath: String): String =
            if (includePath.startsWith(":")) includePath else ":$includePath"
    }
}

/**
 * A module discovered in the source tree, independent of Gradle's project model.
 * Paths are normalized to Gradle format (leading `:`) regardless of discovery mode.
 */
data class DiscoveredModule(
    val name: String,
    val path: String,
    val projectDir: File,
    val buildFile: File,
    val sourceDirs: List<File>,
    val testSourceDirs: List<File>,
    val testFixtureDirs: List<File>,
    val publishable: Boolean,
    val layer: String,
)
