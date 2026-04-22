package dev.tramai.examples.springboot.api

import dev.tramai.examples.springboot.domain.ActionType
import dev.tramai.examples.springboot.domain.ExtractedInvoiceFacts
import dev.tramai.examples.springboot.domain.InvoiceStatus
import dev.tramai.examples.springboot.domain.TriagePriority

data class InvoiceTriageResponse(
    val summary: String,
    val status: InvoiceStatus,
    val priority: TriagePriority,
    val needsImmediateAttention: Boolean,
    val riskScore: Int,
    val facts: ExtractedInvoiceFacts,
    val nextStep: ActionType,
)
