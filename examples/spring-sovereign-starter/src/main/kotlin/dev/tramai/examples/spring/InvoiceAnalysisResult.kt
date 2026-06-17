package dev.tramai.examples.spring

/**
 * Deterministic structured result from the sovereign invoice analysis.
 *
 * Returned by [InvoiceAiService.analyzeInvoice] as a typed data class.
 * The structured output module deserialises the provider's JSON response
 * into this type automatically.
 */
data class InvoiceAnalysisResult(
    val summary: String,
    val riskLevel: String,
    val detectedRisks: List<String>,
    val recommendedAction: String,
)
