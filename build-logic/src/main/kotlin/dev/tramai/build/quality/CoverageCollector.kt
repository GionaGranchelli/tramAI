package dev.tramai.build.quality

import org.gradle.api.GradleException
import java.io.File

class CoverageCollector(
    private val repositoryRoot: File,
    private val configuration: TestQualityConfiguration,
    private val parser: CoverageReportParser = CoverageReportParser()
) {
    fun collect(reportRoot: File? = null): CoverageData {
        val byModule = linkedMapOf<String, ModuleCoverage>()
        val expected = configuration.criticalModules.toSet()
        configuration.criticalModules.sorted().forEach { module ->
            val moduleDir = File(repositoryRoot, module.removePrefix(":").replace(":", "/"))
            val candidates = if (reportRoot != null) {
                listOf(
                    File(reportRoot, "${module.removePrefix(":").replace(":", "_")}.xml"),
                    File(reportRoot, "${module.removePrefix(":")}/jacoco.xml")
                )
            } else {
                listOf(
                    File(moduleDir, "build/reports/jacoco/test/jacocoTestReport.xml"),
                    File(moduleDir, "build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml"),
                    File(moduleDir, "build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
                )
            }
            val report = candidates.firstOrNull { it.isFile }
                ?: throw GradleException("Critical module $module produced no JaCoCo XML report; expected one of ${candidates.joinToString()}")
            val coverage = parser.parse(module, report)
            val nonEmptyModule = listOf(
                File(moduleDir, "src/main/kotlin"),
                File(moduleDir, "src/main/java")
            ).any { source ->
                source.isDirectory && source.walkTopDown().any { it.isFile && it.extension in setOf("kt", "java") }
            }
            if (nonEmptyModule && coverage.linesTotal == 0) {
                throw GradleException("Critical module $module has production sources but zero executable lines")
            }
            byModule[module] = coverage
        }
        val unknown = byModule.keys - expected
        if (unknown.isNotEmpty()) throw GradleException("Coverage contains unknown modules: ${unknown.sorted().joinToString()}")

        val linesCovered = byModule.values.sumOf { it.linesCovered }
        val linesTotal = byModule.values.sumOf { it.linesTotal }
        val branchesCovered = byModule.values.sumOf { it.branchesCovered }
        val branchesTotal = byModule.values.sumOf { it.branchesTotal }
        return CoverageData(
            status = "measured",
            byModule = byModule,
            criticalModules = byModule,
            exclusions = configuration.coverage.exclusions,
            overallLineCoverage = percentage(linesCovered, linesTotal),
            overallBranchCoverage = percentage(branchesCovered, branchesTotal)
        )
    }

    private fun percentage(covered: Int, total: Int): Double =
        if (total == 0) 0.0 else covered * 100.0 / total
}
