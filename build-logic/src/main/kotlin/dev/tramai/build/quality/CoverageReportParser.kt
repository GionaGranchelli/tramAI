package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CoverageReportParser {
    fun parse(module: String, report: File): ModuleCoverage {
        if (!report.isFile) throw GradleException("Coverage report is missing for $module: ${report.absolutePath}")
        val document = try {
            secureFactory().newDocumentBuilder().parse(report)
        } catch (e: Exception) {
            throw GradleException("Malformed JaCoCo XML for $module: ${e.message}", e)
        }
        val root = document.documentElement
        if (root.tagName != "report") throw GradleException("Malformed JaCoCo XML for $module: root element must be report")
        rejectAbsolutePaths(root)

        val counters = (0 until root.childNodes.length)
            .mapNotNull { root.childNodes.item(it) as? Element }
            .filter { it.tagName == "counter" }
            .associateBy { it.getAttribute("type") }
        val line = counters["LINE"] ?: throw GradleException("JaCoCo report for $module has no LINE counter")
        val branch = counters["BRANCH"] ?: throw GradleException("JaCoCo report for $module has no BRANCH counter")

        val lineCovered = counter(line, "covered", module)
        val lineMissed = counter(line, "missed", module)
        val branchCovered = counter(branch, "covered", module)
        val branchMissed = counter(branch, "missed", module)
        val lineTotal = lineCovered + lineMissed
        val branchTotal = branchCovered + branchMissed
        return ModuleCoverage(
            module = module,
            lineCoverage = percentage(lineCovered, lineTotal),
            branchCoverage = percentage(branchCovered, branchTotal),
            linesCovered = lineCovered,
            linesMissed = lineMissed,
            linesTotal = lineTotal,
            branchesCovered = branchCovered,
            branchesMissed = branchMissed,
            branchesTotal = branchTotal
        )
    }

    private fun counter(element: Element, name: String, module: String): Int =
        element.getAttribute(name).toIntOrNull()
            ?: throw GradleException("JaCoCo $name counter for $module is missing or invalid")

    private fun percentage(covered: Int, total: Int): Double =
        if (total == 0) 0.0 else covered * 100.0 / total

    private fun rejectAbsolutePaths(root: Element) {
        val elements = root.getElementsByTagName("*")
        val candidates = buildList {
            add(root)
            for (index in 0 until elements.length) {
                (elements.item(index) as? Element)?.let(::add)
            }
        }
        for (element in candidates) {
            for (attribute in listOf("name", "sourcefilename")) {
                val value = element.getAttribute(attribute)
                if (value.isNotBlank() && (
                        File(value).isAbsolute ||
                            Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(value) ||
                            value.contains("/.gradle/") ||
                            value.contains("\\.gradle\\")
                        )
                ) {
                    throw GradleException("JaCoCo report leaks an absolute or cache path: $value")
                }
            }
        }
    }

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
}
