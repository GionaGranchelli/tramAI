package dev.tramai.build.quality

import javax.xml.parsers.DocumentBuilderFactory

/**
 * Detekt baseline document model for the Epic 10.1b static-analysis gate.
 *
 * Baseline identity is the raw `<ID>` element text as produced by Detekt
 * (`Rule:FileName$<entity-signature>`), NOT XML line position. An unchanged
 * repository produces an identical ID set (verified in the permanent suite).
 */
data class DetektBaselineDocument(
    val currentIssueIds: Set<String>,
    val manuallySuppressedIds: Set<String>,
)

sealed class BaselineParseResult {
    data class Success(val document: DetektBaselineDocument) : BaselineParseResult()
    data object NotFound : BaselineParseResult()
    data class Invalid(val reason: String) : BaselineParseResult()
}

object DetektBaselineParser {

    /** Parses baseline XML content. `null`/blank content means "file absent". */
    fun parse(xml: String?): BaselineParseResult {
        if (xml == null || xml.isBlank()) return BaselineParseResult.NotFound
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            // Fail closed on any external-entity/DTD vector; baselines are trusted but strict.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val root = document.documentElement
            if (root.tagName != "SmellBaseline") {
                return BaselineParseResult.Invalid("root element must be <SmellBaseline>, found <${root.tagName}>")
            }
            val currentSections = root.getElementsByTagName("CurrentIssues")
            if (currentSections.length != 1) {
                return BaselineParseResult.Invalid(
                    "expected exactly one <CurrentIssues> section, found ${currentSections.length}"
                )
            }
            val currentIds = mutableListOf<String>()
            for (i in 0 until currentSections.item(0).childNodes.length) {
                val node = currentSections.item(0).childNodes.item(i)
                if (node.nodeName == "ID") {
                    val text = node.textContent
                    if (text.isBlank()) return BaselineParseResult.Invalid("empty <ID> element")
                    currentIds.add(text)
                }
            }
            val duplicates = currentIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) {
                return BaselineParseResult.Invalid(
                    "duplicate baseline ID(s): ${duplicates.joinToString(", ").take(400)}"
                )
            }
            val suppressedIds = mutableListOf<String>()
            val suppressedSections = root.getElementsByTagName("ManuallySuppressedIssues")
            if (suppressedSections.length == 1) {
                for (i in 0 until suppressedSections.item(0).childNodes.length) {
                    val node = suppressedSections.item(0).childNodes.item(i)
                    if (node.nodeName == "ID") suppressedIds.add(node.textContent)
                }
            }
            BaselineParseResult.Success(
                DetektBaselineDocument(
                    currentIssueIds = currentIds.toSet(),
                    manuallySuppressedIds = suppressedIds.toSet(),
                )
            )
        } catch (e: Exception) {
            BaselineParseResult.Invalid(e.message ?: "baseline XML parse failure: ${e::class.simpleName}")
        }
    }
}
