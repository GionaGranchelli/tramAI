package dev.tramai.build.quality

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

/**
 * Fails when production code grows a direct, authority-free approval mutation that no reviewer admitted.
 *
 * Registered on its own and attached to `verifyStaticSafetyGuards`, so the existing CI gate runs it without a workflow
 * change. See [DirectApprovalMutationGuard] for what the scan does and does not prove.
 *
 * The Kotlin parser is deliberately **not** on the build's plugin classpath: it is a `compileOnly` dependency, resolved
 * into a private configuration and loaded only inside the worker's isolated classloader. The Kotlin Gradle plugin warns
 * when `kotlin-compiler-embeddable` is visible from the build classpath, and that warning is not a false alarm - a
 * second compiler stack next to the plugin's own invites unpredictable behaviour.
 */
class DirectApprovalMutationGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val configFile =
            project.layout.projectDirectory.file("config/quality/direct-approval-mutation-inventory.yml")
        // scanRoots are authoritative: the declared task inputs are derived from the same configuration the task
        // reads, so a configured root cannot diverge from what CI scans, and a cached run cannot hide a new file.
        val inventory =
            DirectApprovalMutationInventoryParser.parse(
                project.providers
                    .fileContents(configFile)
                    .asText
                    .get(),
            )
        val sources =
            project.fileTree(project.rootDir) {
                include(inventory.scanRoots.flatMap { listOf("$it/**/*.kt", "$it/**/*.java") })
                exclude(inventory.exclusions.map { "${it.path.trimEnd('/')}/**" })
            }
        val parserClasspath =
            project.configurations.create(PARSER_CONFIGURATION) {
                isCanBeConsumed = false
                isCanBeResolved = true
                isVisible = false
                description = "Kotlin parser used only by the direct approval mutation guard worker."
            }
        project.dependencies.add(parserClasspath.name, KOTLIN_COMPILER_COORDINATES)
        val guard =
            project.tasks.register<VerifyDirectApprovalMutationsTask>("verifyDirectApprovalMutations") {
                group = "verification"
                description = "Fails when production code introduces an unreviewed direct approval mutation."
                this.configFile.set(configFile)
                sourceFiles.from(sources)
                repositoryRoot.set(project.rootDir.absolutePath)
                this.parserClasspath.from(parserClasspath)
            }
        project.tasks.matching { it.name == "verifyStaticSafetyGuards" }.configureEach {
            dependsOn(guard)
        }
    }

    private companion object {
        const val PARSER_CONFIGURATION = "directApprovalMutationParser"
        const val KOTLIN_COMPILER_COORDINATES = "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.0"
    }
}

@CacheableTask
abstract class VerifyDirectApprovalMutationsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: Property<String>

    /** The isolated parser classpath. Not part of the build's plugin classpath. */
    @get:Classpath
    abstract val parserClasspath: ConfigurableFileCollection

    @get:Inject
    abstract val workers: WorkerExecutor

    @TaskAction
    fun verify() {
        val queue = workers.classLoaderIsolation { classpath.from(parserClasspath) }
        val inventoryFile = configFile
        val productionSources = sourceFiles
        val root = repositoryRoot
        queue.submit(DirectApprovalMutationGuardWork::class.java) {
            configFile.set(inventoryFile)
            sourceFiles.from(productionSources)
            repositoryRoot.set(root)
        }
    }
}

interface DirectApprovalMutationGuardParameters : WorkParameters {
    val configFile: RegularFileProperty
    val sourceFiles: ConfigurableFileCollection
    val repositoryRoot: Property<String>
}

abstract class DirectApprovalMutationGuardWork : WorkAction<DirectApprovalMutationGuardParameters> {
    override fun execute() {
        val root = File(parameters.repositoryRoot.get())
        val inventory =
            DirectApprovalMutationInventoryParser.parse(
                parameters.configFile
                    .get()
                    .asFile
                    .readText(),
            )
        val files =
            parameters.sourceFiles.files.filter { it.isFile }.map { file ->
                val relative =
                    root
                        .toPath()
                        .relativize(file.toPath())
                        .toString()
                        .replace(File.separatorChar, '/')
                relative to file.readText()
            }
        // Exclusions are applied by the guard itself, so the non-vacuity check judges the population actually scanned.
        val violations = DirectApprovalMutationGuard.verify(inventory, files)
        if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
        Logging.getLogger(DirectApprovalMutationGuardWork::class.java).lifecycle(
            "direct-approval-mutation-guard: ${DirectApprovalMutationGuard.included(inventory, files).size} " +
                "file(s) scanned from ${files.size} selected, " +
                "${inventory.entries.sumOf { it.callSites }} reviewed call site(s).",
        )
    }
}

object DirectApprovalMutationInventoryParser {
    private val mapper = ObjectMapper(YAMLFactory())

    fun parse(text: String): DirectApprovalMutationInventory {
        val node =
            try {
                mapper.readTree(text)
            } catch (e: JsonProcessingException) {
                throw IllegalArgumentException("direct approval mutation inventory YAML is malformed: ${e.message}", e)
            }
        require(node.isObject) { "direct approval mutation inventory must be a mapping" }
        return DirectApprovalMutationInventory(
            scanRoots = node.path("scanRoots").map { it.asText() },
            exclusions = node.path("exclusions").map { exclusion(it) },
            entries = node.path("inventory").map { entry(it) },
        )
    }

    private fun exclusion(node: JsonNode) =
        DirectApprovalMutationExclusion(path = required(node, "path"), rationale = required(node, "rationale"))

    private fun entry(node: JsonNode) =
        DirectApprovalMutationEntry(
            path = required(node, "path"),
            callSites = node.path("callSites").asInt(-1),
            rationale = required(node, "rationale"),
        )

    private fun required(
        node: JsonNode,
        field: String,
    ): String {
        require(node.hasNonNull(field)) { "direct approval mutation inventory entry is missing '$field'" }
        return node.get(field).asText()
    }
}
