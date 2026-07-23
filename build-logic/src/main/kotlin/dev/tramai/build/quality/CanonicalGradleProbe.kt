package dev.tramai.build.quality

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class ApiProbeResult(
    val records: List<ApiDumpRecord>,
    val reportFile: File,
    val diagnostic: String
)

data class DependencyProbeResult(
    val records: List<ResolvedDependency>,
    val reportFile: File,
    val diagnostic: String
)

/**
 * Runs Gradle-backed measurements in an isolated source checkout.
 *
 * Probe reports and generated API dumps are written outside [sourceRoot]. The
 * measured checkout's own wrapper is always used, and cleanliness is checked
 * before and after every nested build.
 */
class CanonicalGradleProbe(
    private val sourceRoot: File,
    outputDir: File? = null,
    private val analyzerRoot: File? = null
) {
    private val outputDir = outputDir ?: Files.createTempDirectory("tramai-canonical-probe-").toFile()

    init {
        require(sourceRoot.isDirectory) { "Canonical probe source root is not a directory: $sourceRoot" }
        this.outputDir.mkdirs()
        require(!this.outputDir.canonicalFile.toPath().startsWith(sourceRoot.canonicalFile.toPath())) {
            "Canonical probe output directory must be outside the measured checkout"
        }
    }

    fun verifyWorktreeClean(): Boolean = gitStatus().isBlank()

    fun probeApiBaseline(): ApiProbeResult {
        requireCleanWorktree("before API probing")

        val catalogRoot = analyzerRoot ?: sourceRoot
        val ctx = MeasurementContext.fromDirectory(sourceRoot, catalogRoot)
        if (ctx.modules.isEmpty()) {
            throw GradleException("Canonical API probe discovered no Gradle modules in <SOURCE_ROOT>")
        }

        // Run apiDump using default per-project locations, then collect and clean up
        val apiDumpDir = File(outputDir, "api-dumps-generated")
        apiDumpDir.mkdirs()
        val initScript = File(outputDir, "canonical-api-probe.init.gradle")
        initScript.writeText(apiInitScript(), Charsets.UTF_8)
        runGradle(listOf("--init-script", initScript.absolutePath, "apiDump"))

        // All apiDump files are generated inside the worktree at per-project api/ dirs.
        // Collect them, copy to output dir for hashing, then clean up.
        val generatedDumps = mutableMapOf<String, File>()
        for (mod in ctx.modules) {
            val defaultDump = File(mod.projectDir, "api/${mod.name}.api")
            if (defaultDump.isFile) {
                val outputDump = File(apiDumpDir, "${mod.name}.api")
                defaultDump.copyTo(outputDump, overwrite = true)
                generatedDumps[mod.name] = outputDump
            }
        }
        // Clean up generated api/ dirs from the worktree
        val cleanProcess = ProcessBuilder(
            "bash", "-c",
            "find ${sourceRoot.absolutePath} -name '*.api' -path '*/api/*' -delete && " +
                "find ${sourceRoot.absolutePath} -depth -type d -name 'api' -empty -delete"
        )
            .redirectErrorStream(true)
            .start()
        cleanProcess.waitFor()

        requireCleanWorktree("after API probing (apiDump cleanup)")

        val records = ctx.modules.sortedBy { it.path }.map { module ->
            val hasSources = module.sourceDirs.any { sourceDir ->
                sourceDir.isDirectory && sourceDir.walkTopDown().any {
                    it.isFile && (it.extension == "kt" || it.extension == "java")
                }
            }
            val excluded = module.apiStability == "excluded" || !hasSources
            val generatedDump = generatedDumps[module.name]
            val sha256 = if (!excluded && generatedDump != null) sha256(generatedDump.readBytes()) else ""
            val relativeModuleDir = ReportNormalizer.repoRelativePath(module.projectDir, sourceRoot)
            ApiDumpRecord(
                module = module.path,
                stability = module.apiStability,
                applicable = !excluded,
                dumpPath = "$relativeModuleDir/api/${module.name}.api",
                sha256 = sha256,
                exclusionReason = when {
                    module.apiStability == "excluded" -> "module has apiStability 'excluded'"
                    !hasSources -> "module has no Kotlin or Java production sources"
                    else -> null
                }
            )
        }

        requireCleanWorktree("after API probing")

        val report = File(outputDir, "public-api-dumps.json")
        ReportNormalizer.writeJson(ApiBaselineVerifier.sortRecords(records), report)
        val diagnostic = "${records.size} module(s) scanned, " +
            "${records.count { it.sha256.isNotBlank() }} API dump(s) found"
        return ApiProbeResult(ApiBaselineVerifier.sortRecords(records), report, diagnostic)
    }

    fun probeDependencyBaseline(): DependencyProbeResult {
        requireCleanWorktree("before dependency probing")
        val report = File(outputDir, "resolved-dependencies.json")
        val probeOutputRoot = report.parentFile
        probeOutputRoot.mkdirs()
        val initScript = File(probeOutputRoot, "canonical-dependency-probe.init.gradle")
        initScript.writeText(dependencyInitScript(report), Charsets.UTF_8)

        val diagnostic = runGradle(
            listOf("--init-script", initScript.absolutePath, "canonicalDependencyProbe")
        )
        requireCleanWorktree("after dependency probing")

        // Walk per-project per-configuration output files and merge
        val allRecords = mutableListOf<ResolvedDependency>()
        val probeFiles = probeOutputRoot.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.nameWithoutExtension != "resolved-dependencies" }
            .sortedBy { it.absolutePath }
            .toList()

        if (probeFiles.isEmpty()) {
            throw GradleException(
                "Canonical dependency probe produced no output files. Nested Gradle output:\n$diagnostic"
            )
        }

        for (probeFile in probeFiles) {
            try {
                val records = ReportNormalizer.readJson(probeFile, Array<ResolvedDependency>::class.java).toList()
                allRecords.addAll(records)
            } catch (e: Exception) {
                throw GradleException(
                    "Failed to parse probe output ${probeFile.name}: ${e.message}", e
                )
            }
        }

        if (allRecords.isEmpty()) {
            throw GradleException(
                "Canonical dependency probe produced no external dependencies. Nested Gradle output:\n$diagnostic"
            )
        }

        val normalized = DependencyEdgeNormalizer.normalize(allRecords)
        ReportNormalizer.writeJson(normalized, report)
        return DependencyProbeResult(normalized, report, diagnostic)
    }

    private fun runGradle(arguments: List<String>): String {
        val wrapper = File(sourceRoot, if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
        if (!wrapper.isFile) {
            throw GradleException("Measured checkout has no Gradle wrapper at <SOURCE_ROOT>/${wrapper.name}")
        }
        val command = mutableListOf<String>()
        if (!wrapper.canExecute() && !wrapper.name.endsWith(".bat")) command.add("bash")
        command.add(wrapper.absolutePath)
        command.addAll(
            listOf(
                "--no-build-cache",
                "--no-configuration-cache",
                "--no-parallel",
                "--max-workers=2",
                "--console=plain",
                "--stacktrace"
            )
        )
        command.addAll(arguments)

        val process = try {
            ProcessBuilder(command)
                .directory(sourceRoot)
                .redirectErrorStream(true)
                .apply { environment()["GRADLE_OPTS"] = "-Dorg.gradle.jvmargs=-Xmx16g" }
                .start()
        } catch (e: Exception) {
            throw GradleException("Unable to start measured Gradle wrapper: ${e.message}", e)
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        val sanitized = sanitize(output)
        if (exitCode != 0) {
            throw GradleException(
                "Canonical Gradle probe failed with exit code $exitCode. Sanitized output:\n$sanitized"
            )
        }
        return sanitized
    }

    private fun requireCleanWorktree(stage: String) {
        val status = gitStatus()
        if (status.isNotBlank()) {
            throw GradleException(
                "Measured checkout must be clean $stage. git status --porcelain:\n${sanitize(status)}"
            )
        }
    }

    private fun gitStatus(): String {
        val process = try {
            ProcessBuilder("git", "status", "--porcelain")
                .directory(sourceRoot)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return "git status could not be started"
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return if (process.waitFor() == 0) output.trim() else output.ifBlank { "git status failed" }.trim()
    }

    private fun sanitize(value: String): String {
        var sanitized = value.replace("\r\n", "\n")
        val replacements = linkedMapOf(
            sourceRoot.absolutePath to "<SOURCE_ROOT>",
            outputDir.absolutePath to "<OUTPUT_DIR>",
            System.getProperty("user.home").orEmpty() to "<USER_HOME>",
            System.getenv("GRADLE_USER_HOME").orEmpty() to "<GRADLE_USER_HOME>"
        )
        replacements.filterKeys { it.isNotBlank() }
            .toList()
            .sortedByDescending { it.first.length }
            .forEach { (path, replacement) ->
                sanitized = sanitized.replace(path, replacement)
            }
        sanitized = sanitized
            .replace(Regex("""/tmp/[^\s:'\"]+"""), "<TEMP_PATH>")
            .replace(Regex("""[A-Za-z]:\\[^\s:'\"]+"""), "<ABSOLUTE_PATH>")
        return sanitized.trimEnd()
    }

    private fun apiInitScript(): String =
        """
        initscript {
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
            dependencies {
                classpath 'org.jetbrains.kotlinx:binary-compatibility-validator:0.16.3'
            }
        }

        gradle.beforeProject { project ->
            if (project.parent != null) return
            def pluginClass = initscript.classLoader.loadClass('kotlinx.validation.BinaryCompatibilityValidatorPlugin')
            project.pluginManager.apply(pluginClass)
        }
        """.trimIndent() + "\n"

    private fun dependencyInitScript(report: File): String =
        """
        import groovy.json.JsonOutput
        import org.gradle.api.GradleException
        import org.gradle.api.artifacts.component.ModuleComponentIdentifier
        import org.gradle.api.artifacts.component.ModuleComponentSelector
        import org.gradle.api.artifacts.component.ProjectComponentIdentifier
        import org.gradle.api.artifacts.result.ResolvedDependencyResult
        import org.gradle.api.artifacts.result.UnresolvedDependencyResult

        // Track all probe tasks for root task coordination
        def probeTasks = []
        def probeOutputRoot = new File('${groovyString(probeOutputRoot().absolutePath)}')

        gradle.beforeProject { project ->
            project.afterEvaluate {
                ['compileClasspath', 'runtimeClasspath'].each { configName ->
                    def config = project.configurations.findByName(configName)
                    if (config == null || !config.canBeResolved) return

                    def taskName = "canonical${'$'}{configName.capitalize()}Probe"
                    def probeTask = project.tasks.register(taskName) { task ->
                        task.doLast {
                            def records = []
                            def visit
                            visit = { String consumer, String configuration, component, List path, int depth, Set activePath, Set expandedComponents ->
                                component.dependencies.toList().sort { a, b ->
                                    a.requested.displayName <=> b.requested.displayName
                                }.each { dependency ->
                                    if (dependency instanceof UnresolvedDependencyResult) {
                                        throw new GradleException(
                                            'Failed to resolve ' + consumer + ':' + configuration + ' dependency ' +
                                                dependency.requested.displayName + ': ' + dependency.failure.message,
                                            dependency.failure
                                        )
                                    }
                                    if (dependency instanceof ResolvedDependencyResult) {
                                        def selected = dependency.selected
                                        def selectedId = selected.id
                                        def pathElement
                                        if (selectedId instanceof ModuleComponentIdentifier) {
                                            pathElement = selectedId.group + ':' + selectedId.module + ':' + selectedId.version
                                        } else if (selectedId instanceof ProjectComponentIdentifier) {
                                            pathElement = selectedId.projectPath
                                        } else {
                                            pathElement = selectedId.displayName
                                        }
                                        def nextPath = path + pathElement
                                        if (selectedId instanceof ModuleComponentIdentifier) {
                                            def requested = dependency.requested instanceof ModuleComponentSelector ?
                                                dependency.requested.version : null
                                            records << [
                                                group: selectedId.group,
                                                artifact: selectedId.module,
                                                selectedVersion: selectedId.version,
                                                requestedVersion: requested,
                                                direct: depth == 0,
                                                configuration: configuration,
                                                selectionReason: selected.selectionReason.descriptions.collect {
                                                    it.description
                                                }.sort().join('; '),
                                                dependencyPath: nextPath,
                                                consumers: [consumer]
                                            ]
                                        }
                                        if (selectedId.displayName !in activePath && expandedComponents.add(selectedId.displayName)) {
                                            visit(consumer, configuration, selected, nextPath, depth + 1,
                                                activePath + selectedId.displayName, expandedComponents)
                                        }
                                    }
                                }
                            }

                            def root = config.incoming.resolutionResult.rootComponent.get()
                            def expandedComponents = new HashSet()
                            visit(project.path, configName, root, [project.path], 0,
                                [root.id.displayName] as Set, expandedComponents)

                            // Write per-configuration output
                            def outputFile = new File(probeOutputRoot, "${'$'}{project.path.replace(':', '_')}/${'$'}{configName}.json")
                            outputFile.parentFile.mkdirs()
                            // Deduplication handled by Kotlin-side DependencyEdgeNormalizer
                            records.sort { a, b ->
                                def left = [a.consumers.join(','), a.configuration, a.group, a.artifact,
                                    a.selectedVersion, a.dependencyPath.join(' -> ')].join('|')
                                def right = [b.consumers.join(','), b.configuration, b.group, b.artifact,
                                    b.selectedVersion, b.dependencyPath.join(' -> ')].join('|')
                                left <=> right
                            }
                            outputFile.setText(JsonOutput.prettyPrint(JsonOutput.toJson(records)) + '\n', 'UTF-8')
                        }
                    }
                    probeTasks.add(probeTask)
                }
            }
        }

        gradle.projectsEvaluated {
            rootProject.tasks.register('canonicalDependencyProbe') {
                dependsOn probeTasks.collect { it.get() }
                doLast {
                    println "Dependency probe complete. Output in ${'$'}{probeOutputRoot.absolutePath}"
                }
            }
        }
        """.trimIndent() + "\n"

    private fun probeOutputRoot(): File = outputDir

    private fun groovyString(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
