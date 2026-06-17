package dev.tramai.examples.spring

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation

/**
 * Typed AI service for sovereign invoice analysis.
 *
 * The `model` references the logical model name defined in `application.yml`
 * under `tramai.sovereign.models`, which routes to the deterministic local provider.
 */
@AiService
interface InvoiceAiService {
    @Operation(model = "local-invoice-model")
    suspend fun analyzeInvoice(invoiceText: String): InvoiceAnalysisResult
}
