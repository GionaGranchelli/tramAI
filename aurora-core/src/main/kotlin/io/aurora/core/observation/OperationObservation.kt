package io.aurora.core.observation

import io.aurora.core.model.ModelResponse

data class OperationCallContext(
    val serviceInterface: String,
    val methodName: String,
    val providerId: String,
    val requestedModel: String,
    val attempt: Int,
)

interface OperationObserver {
    fun onCallStarted(context: OperationCallContext): OperationObservation
}

interface OperationObservation {
    fun onProviderResponse(response: ModelResponse)

    fun onProviderFailure(error: Throwable)

    fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    )

    fun onCallCompleted(parseSuccess: Boolean?)
}

object NoOpOperationObserver : OperationObserver {
    override fun onCallStarted(context: OperationCallContext): OperationObservation = NoOpOperationObservation
}

object NoOpOperationObservation : OperationObservation {
    override fun onProviderResponse(response: ModelResponse) = Unit

    override fun onProviderFailure(error: Throwable) = Unit

    override fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    ) = Unit

    override fun onCallCompleted(parseSuccess: Boolean?) = Unit
}
