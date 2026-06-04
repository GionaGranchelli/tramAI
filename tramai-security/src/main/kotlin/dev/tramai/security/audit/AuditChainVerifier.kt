package dev.tramai.security.audit

object AuditChainVerifier {
    fun verify(events: List<AuditEvent>): VerificationResult {
        if (events.isEmpty()) {
            return VerificationResult(isValid = true, errors = emptyList())
        }

        val errors = mutableListOf<VerificationError>()

        // All events must have the same auditStreamId
        val streamId = events.first().auditStreamId
        for (event in events) {
            if (event.auditStreamId != streamId) {
                errors.add(
                    VerificationError(
                        eventId = event.eventId,
                        sequenceNumber = event.sequenceNumber,
                        message = "Event has auditStreamId '${event.auditStreamId}' but expected '$streamId'",
                    ),
                )
            }
        }

        // Consistent schemaVersion
        val schemaVersion = events.first().schemaVersion
        for (event in events) {
            if (event.schemaVersion != schemaVersion) {
                errors.add(
                    VerificationError(
                        eventId = event.eventId,
                        sequenceNumber = event.sequenceNumber,
                        message = "Event has schemaVersion ${event.schemaVersion} but expected $schemaVersion",
                    ),
                )
            }
            if (event.schemaVersion != CURRENT_AUDIT_SCHEMA_VERSION) {
                errors.add(
                    VerificationError(
                        eventId = event.eventId,
                        sequenceNumber = event.sequenceNumber,
                        message = "Unsupported schemaVersion ${event.schemaVersion}",
                    ),
                )
            }
        }

        // Consistent hashAlgorithm
        val hashAlgo = events.first().hashAlgorithm
        for (event in events) {
            if (event.hashAlgorithm != hashAlgo) {
                errors.add(
                    VerificationError(
                        eventId = event.eventId,
                        sequenceNumber = event.sequenceNumber,
                        message = "Event has hashAlgorithm ${event.hashAlgorithm} but expected $hashAlgo",
                    ),
                )
            }
        }

        // Unique eventIds
        val eventIds = mutableSetOf<String>()
        for (event in events) {
            if (!eventIds.add(event.eventId)) {
                errors.add(
                    VerificationError(
                        eventId = event.eventId,
                        sequenceNumber = event.sequenceNumber,
                        message = "Duplicate eventId '${event.eventId}'",
                    ),
                )
            }
        }

        // Sequence continuity, hash chain, and hash recalculation
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
