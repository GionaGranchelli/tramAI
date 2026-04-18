package io.aurora.testing

import io.aurora.core.model.ModelResponse
import io.aurora.core.observation.OperationCallContext
import io.aurora.core.observation.OperationObservation
import io.aurora.core.observation.OperationObserver

/**
 * Test observer that records every engine attempt and outcome.
 */
class RecordingOperationObserver : OperationObserver {
    /** Recorded attempt lifecycle events in call order. */
    val callRecords: MutableList<CallRecord> = mutableListOf()

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        val record = CallRecord(context = context)
        callRecords += record
        return RecordingObservation(record)
    }

    /**
     * Mutable record for a single provider attempt.
     */
    data class CallRecord(
        val context: OperationCallContext,
        var response: ModelResponse? = null,
        var providerFailure: Throwable? = null,
        var parseFailureSummary: String? = null,
        var parseSuccess: Boolean? = null,
    )

    private class RecordingObservation(
        private val record: CallRecord,
    ) : OperationObservation {
        override fun onProviderResponse(response: ModelResponse) {
            record.response = response
        }

        override fun onProviderFailure(error: Throwable) {
            record.providerFailure = error
        }

        override fun onStructuredParseFailure(
            rawResponse: String,
            errorSummary: String,
        ) {
            record.parseFailureSummary = errorSummary
        }

        override fun onCallCompleted(parseSuccess: Boolean?) {
            record.parseSuccess = parseSuccess
        }
    }
}
