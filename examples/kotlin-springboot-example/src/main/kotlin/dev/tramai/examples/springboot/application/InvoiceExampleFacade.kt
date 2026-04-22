package dev.tramai.examples.springboot.application

import dev.tramai.core.model.StreamChunk
import dev.tramai.examples.springboot.ai.InvoiceAnalyzer
import dev.tramai.examples.springboot.api.InvoiceSummaryResponse
import dev.tramai.examples.springboot.api.InvoiceTriageResponse
import dev.tramai.examples.springboot.domain.toApiResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import org.springframework.stereotype.Service

/**
 * Application-facing facade that keeps HTTP transport logic out of the Tramai contract.
 */
@Service
class InvoiceExampleFacade(
    private val analyzer: InvoiceAnalyzer,
) {
    suspend fun summarize(invoiceText: String): InvoiceSummaryResponse = InvoiceSummaryResponse(
        summary = analyzer.summarize(invoiceText),
    )

    fun streamSummary(invoiceText: String): Flow<String> = analyzer.streamSummarize(invoiceText)
        .mapNotNull { chunk ->
            when (chunk) {
                is StreamChunk.Token -> chunk.text
                else -> null
            }
        }

    suspend fun enrich(invoiceText: String): Map<String, String> = mapOf(
        "enrichment" to analyzer.enrich(invoiceText),
    )

    suspend fun triage(invoiceText: String): InvoiceTriageResponse = analyzer.triage(invoiceText).toApiResponse()
}
