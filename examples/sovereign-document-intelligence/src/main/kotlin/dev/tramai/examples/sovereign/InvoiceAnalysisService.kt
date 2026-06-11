package dev.tramai.examples.sovereign

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.User
import dev.tramai.core.model.ClassifiedDocument

/**
 * AI service contract for sovereign document intelligence.
 *
 * Analyzes sensitive invoice documents through approved local models,
 * with HIGH-risk payment actions suspension for human approval.
 */
@AiService
interface InvoiceAnalysisService {

    /**
     * Analyzes a classified invoice document and returns a typed assessment.
     *
     * The model is routed to the approved local provider. The schedule-payment
     * tool carries HIGH risk, triggering approval suspension before execution.
     */
    @Operation(
        model = "local-invoice-model",
        tools = ["schedule-payment"],
    )
    @User(
        """
        Analyze this classified invoice document.
        Return a typed assessment.
        Schedule payment only when the invoice requires execution.

        Invoice:
        {document}
        """
    )
    suspend fun analyze(
        document: ClassifiedDocument<InvoiceDocument>,
    ): InvoiceAssessment
}
