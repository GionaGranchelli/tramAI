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
        // LINE and BRANCH counters may be absent when JaCoCo excludes all
        // classes or a module has no branches. The caller (CoverageCollector)
        // handles the zero-coverage case with a clear "zero executable lines"
        // message.
        val line = counters["LINE"]
        val branch = counters["BRANCH"]

        val lineCovered = line?.let { counter(it, "covered", module) } ?: 0
        val lineMissed = line?.let { counter(it, "missed", module) } ?: 0
        val branchCovered = branch?.let { counter(it, "covered", module) } ?: 0
        val branchMissed = branch?.let { counter(it, "missed", module) } ?: 0
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
            // JaCoCo XML reports use DOCTYPE declarations — allow them but block
            // external entities to prevent XXE attacks.
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
}
