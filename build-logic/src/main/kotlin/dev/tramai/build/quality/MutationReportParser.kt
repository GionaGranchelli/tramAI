package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class MutationRecord(
    val module: String,
    val family: String,
    val status: String,
    val sourceFile: String,
    val className: String,
    val method: String,
    val methodDescription: String,
    val line: Int,
    val mutator: String,
    val description: String,
    val identity: String,
    val block: Int = 0,
    val index: Int = 0,
)

data class ParsedMutationReport(
    val module: String,
    val family: String,
    val mutants: List<MutationRecord>,
)

class MutationReportParser {
    fun parse(
        module: String,
        family: String,
        report: File,
    ): ParsedMutationReport {
        if (!report.isFile) throw GradleException("Mutation report is missing for $family/$module: ${report.absolutePath}")
        return when (report.extension.lowercase()) {
            "xml" -> parseXml(module, family, report)
            "html", "htm" -> parseHtml(module, family, report)
            else -> throw GradleException("Unsupported mutation report format: ${report.name}")
        }
    }

    private fun parseXml(
        module: String,
        family: String,
        report: File,
    ): ParsedMutationReport {
        val document =
            try {
                secureFactory().newDocumentBuilder().parse(report)
            } catch (e: Exception) {
                throw GradleException("Malformed PITest XML for $family/$module: ${e.message}", e)
            }
        val elements = document.getElementsByTagName("mutation")
        val records =
            (0 until elements.length)
                .map { index -> parseMutation(module, family, elements.item(index)) }
                .sortedWith(
                    compareBy<MutationRecord> { it.module }
                        .thenBy { it.family }
                        .thenBy { it.identity }
                        .thenBy { it.status },
                )
        return ParsedMutationReport(module, family, records)
    }

    private fun parseMutation(
        module: String,
        family: String,
        node: Node,
    ): MutationRecord {
        val element =
            node as? Element
                ?: throw GradleException("Malformed PITest XML for $family/$module: mutation is not an element")
        val status =
            normalizeStatus(
                element.getAttribute("status").ifBlank {
                    childText(element, "status")
                },
                family,
                module,
            )
        val sourceFile = childText(element, "sourceFile")
        val className = childText(element, "mutatedClass")
        val method = childText(element, "mutatedMethod")
        val methodDescription = childText(element, "methodDescription")
        val mutator = childText(element, "mutator")
        val description = childText(element, "description")
        val line = childText(element, "lineNumber").toIntOrNull() ?: 0
        val block = childText(element, "block").toIntOrNull() ?: 0
        val mutationIndex = childText(element, "index").toIntOrNull() ?: 0
        if (status.isBlank() || className.isBlank() || method.isBlank() || mutator.isBlank()) {
            throw GradleException("Malformed PITest XML for $family/$module: mutation lacks required identity fields")
        }
        rejectPath(sourceFile)
        val identity =
            MutationIdentity(
                module,
                className,
                method,
                methodDescription,
                mutator,
                description,
                block,
                mutationIndex,
            ).stableKey()
        return MutationRecord(
            module,
            family,
            status,
            sourceFile,
            className,
            method,
            methodDescription,
            line,
            mutator,
            description,
            identity,
            block,
            mutationIndex,
        )
    }

    private fun parseHtml(
        module: String,
        family: String,
        report: File,
    ): ParsedMutationReport {
        val html =
            try {
                report.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                throw GradleException("Malformed PITest HTML for $family/$module: ${e.message}", e)
            }
        val row =
            Regex(
                """data-status=["']([^"']+)["'][^>]*data-class=["']([^"']+)["'][^>]*data-method=["']([^"']+)["'][^>]*data-mutator=["']([^"']+)["'][^>]*data-description=["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            )
        val records =
            row
                .findAll(html)
                .map { match ->
                    val status = normalizeStatus(match.groupValues[1], family, module)
                    val className = match.groupValues[2]
                    val method = match.groupValues[3]
                    val mutator = match.groupValues[4]
                    val description = match.groupValues[5]
                    MutationRecord(
                        module = module,
                        family = family,
                        status = status,
                        sourceFile = "",
                        className = className,
                        method = method,
                        methodDescription = "",
                        line = 0,
                        mutator = mutator,
                        description = description,
                        identity = MutationIdentity(module, className, method, "", mutator, description).stableKey(),
                    )
                }.toList()
        if (records.isEmpty()) {
            throw GradleException(
                "PITest HTML for $family/$module has no machine-readable mutation rows; XML output is required",
            )
        }
        return ParsedMutationReport(module, family, records.sortedBy { it.identity })
    }

    private fun childText(
        element: Element,
        name: String,
    ): String =
        element
            .getElementsByTagName(name)
            .item(0)
            ?.textContent
            ?.trim()
            .orEmpty()

    private fun rejectPath(value: String) {
        if (value.isNotBlank() && (
                File(value).isAbsolute ||
                    Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(value) ||
                    value.contains("/.gradle/") ||
                    value.contains("\\.gradle\\")
            )
        ) {
            throw GradleException("PITest report leaks an absolute or cache path: $value")
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

    companion object {
        /** Terminal PIT statuses we understand. Anything else is a hard failure (M11). */
        val KNOWN_STATUSES =
            setOf(
                "KILLED",
                "SURVIVED",
                "NO_COVERAGE",
                "TIMED_OUT",
                "MEMORY_ERROR",
                "RUN_ERROR",
                "NON_VIABLE",
                "REMOVED",
                "NOT_STARTED",
            )

        private fun normalizeStatus(
            raw: String,
            family: String,
            module: String,
        ): String {
            val status = raw.uppercase()
            if (status !in KNOWN_STATUSES) {
                throw GradleException(
                    "Unknown PITest status '$raw' for $family/$module; known: " +
                        KNOWN_STATUSES.sorted().joinToString(),
                )
            }
            return status
        }
    }
}
