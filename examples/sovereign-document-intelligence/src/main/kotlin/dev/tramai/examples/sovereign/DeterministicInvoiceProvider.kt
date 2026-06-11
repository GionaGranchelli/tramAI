package dev.tramai.examples.sovereign

import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic fake provider that simulates an approved local model.
 *
 * Call #1: Returns a tool call for schedule-payment.
 *   This triggers approval suspension in the engine.
 *
 * Call #2 (after tool-result reinjection): Returns a JSON response
 *   representing the final InvoiceAssessment.
 *
 * Thread-safe and deterministic.
 */
class DeterministicInvoiceProvider(
    private val invoiceAssessmentJson: String = """
        {
            "invoiceId": "INV-001",
            "supplierName": "Acme Corp",
            "amountCents": 15000000,
            "currency": "EUR",
            "risk": "HIGH",
            "recommendedAction": "SCHEDULE_PAYMENT",
            "rationale": "Enterprise license renewal Q3 exceeds threshold and requires payment scheduling"
        }
    """.trimIndent(),
) : ModelProvider {

    private val callCount = AtomicInteger(0)

    /** Captured requests for test assertions. */
    val capturedRequests = mutableListOf<ModelRequest>()

    override fun providerId(): String = "local-provider"

    override fun supportsCapability(capability: ProviderCapability): Boolean =
        capability == ProviderCapability.TOOL_CALLING ||
            capability == ProviderCapability.STRUCTURED_OUTPUT

    override suspend fun complete(request: ModelRequest): ModelResponse {
        capturedRequests.add(request)

        val attempt = callCount.incrementAndGet()

        // Call #1: Request a tool call for schedule-payment
        if (attempt == 1) {
            return ModelResponse(
                content = "I need to schedule the payment for this invoice.",
                toolCalls = listOf(
                    ToolCall(
                        id = "call-schedule-payment-001",
                        name = "schedule-payment",
                        argumentsJson = """{
                            "invoiceId": "INV-001",
                            "iban": "DE89370400440532013000",
                            "amountCents": 15000000,
                            "currency": "EUR"
                        }""".trimIndent(),
                    ),
                ),
                finishReason = FinishReason.OTHER,
            )
        }

        // Call #2 (after tool result reinjection): Return structured assessment
        return ModelResponse(
            content = invoiceAssessmentJson,
            finishReason = FinishReason.STOP,
        )
    }
}
