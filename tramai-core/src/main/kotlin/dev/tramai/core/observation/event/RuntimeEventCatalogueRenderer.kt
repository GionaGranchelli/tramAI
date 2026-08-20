package dev.tramai.core.observation.event

/**
 * Deterministic renderer for the runtime event catalogue reference document.
 * The committed docs/reference/runtime-event-catalogue.md is generated from
 * this renderer and verified by RuntimeEventCatalogueDocumentationTest, so the
 * documentation cannot drift from the runtime definitions.
 */
object RuntimeEventCatalogueRenderer {
    fun render(): String = buildString {
        appendLine("# Runtime Event Catalogue")
        appendLine()
        appendLine("Generated from `RuntimeEventCatalogue`; do not edit by hand. Deterministic and CI-checked.")
        appendLine()
        appendLine("## Events")
        appendLine()
        appendLine("| Event | Domain | Required attributes | Sensitivity | Audit | Evidence | Metric | Span | Failure policy |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        for (event in RuntimeEventCatalogue.allEvents) {
            append('|').append('`').append(event.name).append('`')
            append('|').append(event.domain.name)
            append('|').append(event.requiredAttributes.sortedBy { it.name }.joinToString(", ") { "`${it.name}`" })
            append('|').append(event.sensitivity.name)
            append('|').append(if (event.auditEligible) "yes" else "no")
            append('|').append(if (event.evidenceEligible) "yes" else "no")
            append('|').append(event.metricMapping?.let { "`${it.name}`" } ?: "—")
            append('|').append(if (event.spanEligible) "yes" else "no")
            append('|').append(event.failurePolicy.name)
            appendLine('|')
        }
        appendLine()
        appendLine("## Metrics")
        appendLine()
        appendLine("| Metric | Description | Unit | Instrument | Value type |")
        appendLine("|---|---|---|---|---|")
        for (metric in RuntimeMetrics.all) {
            append('|').append('`').append(metric.name).append('`')
            append('|').append(metric.description.replace('|', '/'))
            append('|').append('`').append(metric.unit).append('`')
            append('|').append(metric.instrumentType.name)
            append('|').append(metric.valueType.name)
            appendLine('|')
        }
        appendLine()
        appendLine("## Dynamic attribute namespaces")
        appendLine()
        appendLine("| Namespace | Prefix | Sensitivity |")
        appendLine("|---|---|---|")
        append('|').append("workflow context").append('|').append('`').append(DynamicAttributeNamespaces.WORKFLOW_CONTEXT.prefix).append('`')
        append('|').append(DynamicAttributeNamespaces.WORKFLOW_CONTEXT.sensitivity.name)
        appendLine('|')
    }
}
