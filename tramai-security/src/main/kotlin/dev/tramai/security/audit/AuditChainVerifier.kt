package dev.tramai.security.audit

object AuditChainVerifier {
    fun verify(events: List<AuditEvent>): VerificationResult {
        val errors = mutableListOf<VerificationError>()

        for ((index, event) in events.withIndex()) {
            val previousEvent = events.getOrNull(index - 1)

            if (index == 0) {
                if (event.sequenceNumber != 1L) {
                    errors.add(
                        VerificationError(
                            eventId = event.eventId,
                            sequenceNumber = event.sequenceNumber,
                            message = "First event must have sequenceNumber 1",
                        ),
                    )
                }
                if (event.previousEventHash != null) {
                    errors.add(
                        VerificationError(
                            eventId = event.eventId,
                            sequenceNumber = event.sequenceNumber,
                            message = "First event must have null previousEventHash",
                        ),
                    )
                }
            } else if (previousEvent != null) {
                val expectedSequenceNumber = previousEvent.sequenceNumber + 1L
                if (event.sequenceNumber != expectedSequenceNumber) {
                    errors.add(
                        VerificationError(
                            eventId = event.eventId,
                            sequenceNumber = event.sequenceNumber,
                            message = "Expected sequenceNumber $expectedSequenceNumber but got ${event.sequenceNumber}",
                        ),
                    )
                }
                if (event.previousEventHash != previousEvent.eventHash) {
                    errors.add(
                        VerificationError(
                            eventId = event.eventId,
                            sequenceNumber = event.sequenceNumber,
                            message = "previousEventHash does not match prior eventHash",
                        ),
                    )
                }
            }

            val recalculatedHash = event.calculateHash()
            if (event.eventHash != recalculatedHash) {
                errors.add(
                    VerificationError(
                        eventId = event.eventId,
                        sequenceNumber = event.sequenceNumber,
                        message = "eventHash does not match recalculated hash",
                    ),
                )
            }
        }

        return VerificationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }
}

data class VerificationResult(
    val isValid: Boolean,
    val errors: List<VerificationError>,
)

data class VerificationError(
    val eventId: String,
    val sequenceNumber: Long,
    val message: String,
)
