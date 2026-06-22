package dev.tramai.security.audit

object AuditChainVerifier {
    fun verify(events: List<AuditEvent>): VerificationResult {
        if (events.isEmpty()) {
            return VerificationResult(isValid = true, errors = emptyList())
        }

        val errors = mutableListOf<VerificationError>()

        val streamId = events.first().auditStreamId
        val schemaVersion = events.first().schemaVersion
        val hashAlgo = events.first().hashAlgorithm
        verifyStreamIds(events, streamId, errors)
        verifySchemaVersions(events, schemaVersion, errors)
        verifyHashAlgorithms(events, hashAlgo, errors)
        verifyUniqueEventIds(events, errors)

        for ((index, event) in events.withIndex()) {
            verifyChainPosition(index, event, events.getOrNull(index - 1), errors)
            verifyEventHash(event, errors)
        }

        return VerificationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }

    private fun verifyStreamIds(
        events: List<AuditEvent>,
        streamId: String,
        errors: MutableList<VerificationError>,
    ) {
        for (event in events) {
            if (event.auditStreamId != streamId) {
                errors.addError(event, "Event has auditStreamId '${event.auditStreamId}' but expected '$streamId'")
            }
        }
    }

    private fun verifySchemaVersions(
        events: List<AuditEvent>,
        schemaVersion: Int,
        errors: MutableList<VerificationError>,
    ) {
        for (event in events) {
            if (event.schemaVersion != schemaVersion) {
                errors.addError(event, "Event has schemaVersion ${event.schemaVersion} but expected $schemaVersion")
            }
            if (event.schemaVersion != CURRENT_AUDIT_SCHEMA_VERSION) {
                errors.addError(event, "Unsupported schemaVersion ${event.schemaVersion}")
            }
        }
    }

    private fun verifyHashAlgorithms(
        events: List<AuditEvent>,
        hashAlgo: AuditHashAlgorithm,
        errors: MutableList<VerificationError>,
    ) {
        for (event in events) {
            if (event.hashAlgorithm != hashAlgo) {
                errors.addError(event, "Event has hashAlgorithm ${event.hashAlgorithm} but expected $hashAlgo")
            }
        }
    }

    private fun verifyUniqueEventIds(
        events: List<AuditEvent>,
        errors: MutableList<VerificationError>,
    ) {
        val eventIds = mutableSetOf<String>()
        for (event in events) {
            if (!eventIds.add(event.eventId)) {
                errors.addError(event, "Duplicate eventId '${event.eventId}'")
            }
        }
    }

    private fun verifyChainPosition(
        index: Int,
        event: AuditEvent,
        previousEvent: AuditEvent?,
        errors: MutableList<VerificationError>,
    ) {
        if (index == 0) {
            verifyFirstEvent(event, errors)
            return
        }
        if (previousEvent != null) {
            verifyLinkedEvent(event, previousEvent, errors)
        }
    }

    private fun verifyFirstEvent(
        event: AuditEvent,
        errors: MutableList<VerificationError>,
    ) {
        if (event.sequenceNumber != 1L) {
            errors.addError(event, "First event must have sequenceNumber 1")
        }
        if (event.previousEventHash != null) {
            errors.addError(event, "First event must have null previousEventHash")
        }
    }

    private fun verifyLinkedEvent(
        event: AuditEvent,
        previousEvent: AuditEvent,
        errors: MutableList<VerificationError>,
    ) {
        val expectedSequenceNumber = previousEvent.sequenceNumber + 1L
        if (event.sequenceNumber != expectedSequenceNumber) {
            errors.addError(event, "Expected sequenceNumber $expectedSequenceNumber but got ${event.sequenceNumber}")
        }
        if (event.previousEventHash != previousEvent.eventHash) {
            errors.addError(event, "previousEventHash does not match prior eventHash")
        }
    }

    private fun verifyEventHash(
        event: AuditEvent,
        errors: MutableList<VerificationError>,
    ) {
        if (event.eventHash != event.calculateHash()) {
            errors.addError(event, "eventHash does not match recalculated hash")
        }
    }

    private fun MutableList<VerificationError>.addError(event: AuditEvent, message: String) {
        add(
            VerificationError(
                eventId = event.eventId,
                sequenceNumber = event.sequenceNumber,
                message = message,
            ),
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
