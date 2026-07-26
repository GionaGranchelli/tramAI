package dev.tramai.build.quality

import java.io.File

/**
 * Scans Kotlin source files for broad exception catches in suspend-capable code.
 * Delegates core scanning logic to KotlinCancellationCatchScanner.
 */
class CancellationCatchInventory(private val ctx: MeasurementContext) {

    fun inventory(): List<CancellationCatchFinding> {
        val findings = mutableListOf<CancellationCatchFinding>()

        for (mod in ctx.modules) {
            mod.sourceDirs.forEach { srcDir ->
                if (!srcDir.exists()) return@forEach

                srcDir.walkTopDown().forEach { file ->
                    if (!file.isFile || file.extension != "kt") return@forEach
                    val content = file.readText()
                    val relativePath = ReportNormalizer.repoRelativePath(file, ctx.rootDir)
                    findings.addAll(KotlinCancellationCatchScanner.scan(content, mod.path, relativePath))
                }
            }
        }

        return findings
    }
}
