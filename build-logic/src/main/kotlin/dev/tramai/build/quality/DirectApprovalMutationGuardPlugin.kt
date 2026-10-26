package dev.tramai.build.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Fails when production code grows a direct, authority-free approval mutation that no reviewer admitted.
 *
 * Registered as its own task and attached to `verifyStaticSafetyGuards`, so the existing CI gate runs it without a
 * workflow change. See [DirectApprovalMutationGuard] for what the scan does and does not prove.
 */
class DirectApprovalMutationGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val sources =
            project.fileTree(project.rootDir) {
                include("*/src/main/kotlin/**/*.kt", "*/src/main/java/**/*.java")
                exclude("build/**", "*/build/**", "build-logic/**", "examples/**", "**/.gradle/**")
            }
        val guard =
            project.tasks.register<VerifyDirectApprovalMutationsTask>("verifyDirectApprovalMutations") {
                group = "verification"
                description =
                    "Fails when production code introduces an unreviewed direct approval mutation."
                configFile.set(
                    project.layout.projectDirectory.file("config/quality/direct-approval-mutation-inventory.yml"),
                )
                sourceFiles.from(sources)
                repositoryRoot.set(project.rootDir.absolutePath)
            }
        // Attach to the existing static-safety gate: that task is already owned by `check`, `verifyPr` and CI.
        project.tasks.matching { it.name == "verifyStaticSafetyGuards" }.configureEach {
            dependsOn(guard)
        }
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

    @TaskAction
    fun verify() {
        val root = File(repositoryRoot.get())
        val inventory = DirectApprovalMutationInventoryParser.parse(configFile.get().asFile.readText())
        val scanned = sourceFiles.files.filter { it.isFile }
        val files =
            scanned
                .map { file ->
                    val relative =
                        root
                            .toPath()
                            .relativize(file.toPath())
                            .toString()
                            .replace(File.separatorChar, '/')
                    relative to file.readText()
                }.filterNot { (relative, _) -> inventory.excludes(relative) }
        val violations = DirectApprovalMutationGuard.verify(inventory, files, scannedFiles = scanned.size)
        if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
        logger.lifecycle(
            "direct-approval-mutation-guard: ${files.size} file(s) scanned, " +
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
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                throw IllegalArgumentException("direct approval mutation inventory YAML is malformed: ${e.message}", e)
            }
        return parse(node)
    }

    private fun parse(node: JsonNode): DirectApprovalMutationInventory {
        require(node.isObject) { "direct approval mutation inventory must be a mapping" }
        val scanRoots = node.path("scanRoots").map { it.asText() }
        val exclusions =
            node.path("exclusions").map {
                DirectApprovalMutationExclusion(
                    path = required(it, "path"),
                    rationale = required(it, "rationale"),
                )
            }
        val entries =
            node.path("inventory").map {
                DirectApprovalMutationEntry(
                    path = required(it, "path"),
                    callSites = it.path("callSites").asInt(-1),
                    rationale = required(it, "rationale"),
                )
            }
        return DirectApprovalMutationInventory(scanRoots, exclusions, entries)
    }

    private fun required(
        node: JsonNode,
        field: String,
    ): String {
        require(node.hasNonNull(field)) { "direct approval mutation inventory entry is missing '$field'" }
        return node.get(field).asText()
    }
}
