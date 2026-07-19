package dev.tramai.build.quality

import org.gradle.api.Project
import java.io.File

/**
 * Abstracts over the measurement source: a Gradle project (normal mode) or a
 * detached worktree directory (canonical generation mode).
 *
 * Every analyzer receives this instead of a raw [Project] so that
 * canonical baseline generation can scan a v0.5.0 worktree independently
 * of the active Gradle project.
 */
class MeasurementContext(
    /** Root directory to scan for source files, build files, and git metadata. */
    val rootDir: File,
    /** Modules discovered in this tree. */
    val modules: List<DiscoveredModule>,
    /** The Gradle project (null when scanning a detached worktree). */
    val gradleProject: Project? = null
) {
    /** Shortcut: run git in [rootDir]. */
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

    /** Shortcut: run git returning full output (multi-line ok). */
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

    /** Shortcut: run git returning exit code. */
    fun runGitSilent(vararg arguments: String): Int {
        return try {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor()
        } catch (_: Exception) {
            1
        }
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
        /**
         * Create from a Gradle project (normal/CI mode).
         * Uses Gradle's project model for module discovery.
         */
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
                        publishable = isPublishableFromProject(proj),
                        layer = classifyLayerFromProject(proj),
                    )
                }

            return MeasurementContext(
                rootDir = project.rootDir,
                modules = gradleModules,
                gradleProject = project,
            )
        }

        /**
         * Create from a detached worktree directory (canonical generation mode).
         * Discovers modules by walking the filesystem for build.gradle.kts files
         * and reading settings.gradle.kts for include() statements.
         */
        fun fromDirectory(rootDir: File): MeasurementContext {
            val modules = discoverModulesFromFilesystem(rootDir)
            return MeasurementContext(
                rootDir = rootDir,
                modules = modules,
                gradleProject = null,
            )
        }

        private fun discoverModulesFromFilesystem(rootDir: File): List<DiscoveredModule> {
            val settingsFile = File(rootDir, "settings.gradle.kts")
            val includedPaths = if (settingsFile.isFile) {
                parseSettingsIncludes(settingsFile)
            } else {
                emptyList()
            }

            val modules = mutableListOf<DiscoveredModule>()

            // First, process explicit includes from settings
            for (includePath in includedPaths) {
                val moduleDir = File(rootDir, includePath.replace(":", "/"))
                val buildFile = File(moduleDir, "build.gradle.kts")
                if (buildFile.isFile) {
                    val name = includePath.substringAfterLast(":")
                    modules.add(
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
                            publishable = isPublishableFromDir(moduleDir),
                            layer = classifyLayerFromPath(includePath),
                        )
                    )
                }
            }

            // Also discover any build.gradle.kts in subdirs (including nested like examples/*)
            rootDir.walkTopDown()
                .maxDepth(3)
                .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "src" }
                .forEach { dir ->
                    val buildFile = File(dir, "build.gradle.kts")
                    if (buildFile.isFile && dir != rootDir) {
                        val relativePath = dir.relativeTo(rootDir).path.replace("/", ":")
                        val modulePath = ":$relativePath"
                        if (modules.none { it.path == modulePath }) {
                            modules.add(
                                DiscoveredModule(
                                    name = dir.name,
                                    path = modulePath,
                                    projectDir = dir,
                                    buildFile = buildFile,
                                    sourceDirs = listOf(
                                        File(dir, "src/main/kotlin"),
                                        File(dir, "src/main/java"),
                                    ),
                                    testSourceDirs = listOf(
                                        File(dir, "src/test/kotlin"),
                                    ),
                                    testFixtureDirs = listOf(
                                        File(dir, "src/testFixtures/kotlin"),
                                    ),
                                    publishable = isPublishableFromDir(dir),
                                    layer = classifyLayerFromPath(modulePath),
                                )
                            )
                        }
                    }
                }

            return modules
        }

        private fun parseSettingsIncludes(settingsFile: File): List<String> {
            val content = settingsFile.readText()
            val includeRegex = Regex("""include\s*\(\s*"([^"]+)"\s*\)""")
            return includeRegex.findAll(content).map { it.groupValues[1] }.toList()
        }

        private fun isPublishableFromProject(proj: Project): Boolean {
            // Check if the project has publishing plugin applied or is in the published set
            return proj.plugins.hasPlugin("maven-publish") ||
                proj.name !in setOf("tramai-dashboard") // examples/apps are not publishable
        }

        private fun isPublishableFromDir(moduleDir: File): Boolean {
            val buildFile = File(moduleDir, "build.gradle.kts")
            if (!buildFile.isFile) return false
            val content = buildFile.readText()
            return content.contains("maven-publish") || content.contains("publishing {")
        }

        private fun classifyLayerFromProject(proj: Project): String {
            return classifyLayerFromPath(proj.path)
        }

        private fun classifyLayerFromPath(path: String): String {
            val name = path.substringAfterLast(":").lowercase()
            return when {
                name in setOf("tramai-core", "tramai-structured") -> "core-contracts"
                name in setOf("tramai-engine", "tramai-orchestration", "tramai-scheduler") -> "runtime-execution"
                name in setOf("tramai-security", "tramai-sovereign") -> "governance"
                name.startsWith("tramai-") && !name.startsWith("tramai-spring") -> "provider-adapters"
                name.startsWith("tramai-spring") -> "framework-integrations"
                name.startsWith("tramai-") && name.contains("starter") -> "composition"
                path.startsWith(":examples") -> "applications-examples"
                else -> "unknown"
            }
        }
    }
}

/**
 * A module discovered in the source tree, independent of Gradle's project model.
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
