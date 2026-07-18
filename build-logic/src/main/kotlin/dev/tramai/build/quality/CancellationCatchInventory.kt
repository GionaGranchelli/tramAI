package dev.tramai.build.quality

import org.gradle.api.Project
import java.io.File

/**
 * Scans Kotlin source files for broad exception catches in suspend-capable code.
 * Delegates core scanning logic to KotlinCancellationCatchScanner.
 */
class CancellationCatchInventory(private val rootProject: Project) {

    fun inventory(): List<CancellationCatchFinding> {
        val findings = mutableListOf<CancellationCatchFinding>()
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }

        for (proj in projects) {
            listOf("src/main/kotlin", "src/test/kotlin").forEach { sourceSet ->
                val srcDir = File(proj.projectDir, sourceSet)
                if (!srcDir.exists()) return@forEach

                srcDir.walkTopDown().forEach { file ->
                    if (!file.isFile || file.extension != "kt") return@forEach
                    val content = file.readText()
                    val relativePath = ReportNormalizer.repoRelativePath(file, rootProject.rootDir)
                    findings.addAll(KotlinCancellationCatchScanner.scan(content, proj.name, relativePath))
                }
            }
        }

        return findings
    }
}
