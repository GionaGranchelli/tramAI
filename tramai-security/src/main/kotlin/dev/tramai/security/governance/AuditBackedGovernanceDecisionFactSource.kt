package dev.tramai.security.governance

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.VerificationResult

/**
 * How a workflow run is associated with the audit stream that carries its decisions.
 *
 * The store has no run index: `AuditStore` can read a stream, not find one by run. A run-scoped
 * read therefore needs the association supplied, and it must be supplied honestly — inventing one,
 * or assuming the default, is exactly how a foreign run's records end up in a projection.
 */
internal interface RunStreamAssociation {
    fun streamIdFor(workflowRunId: String): String?

    /**
     * The default association, and the limit that goes with it: this is what
     * [dev.tramai.security.audit.DefaultAuditStreamIdResolver] produces **when the context carries
     * a run id**. It is a resolution rule, not a property of the store. A deployment that
     * customises the resolver, or writes several runs into one correlated stream, must supply its
     * own association rather than rely on this one.
     */
    object DirectlyNamedByRunId : RunStreamAssociation {
        override fun streamIdFor(workflowRunId: String): String? = workflowRunId
    }
}

/**
 * 0.7.4c2 — the production decision-fact source, reading persisted audit decisions.
 *
 * The 0.7.4c1 projection may report only what its source can establish. This source therefore does
 * three things and refuses the rest: it reads the stream through the existing [AuditStore] SPI, it
 * attests the range it actually read, and it maps only events it can recognise as decisions.
 *
 * What it establishes:
 * - **source order** — `sourceSequence` is the store's own `sequenceNumber`, never a line number,
 *   an ordering index, a timestamp or an event-id sort.
 * - **attested range** — [DecisionFactRead.assertedRange] is populated only when the read captured
 *   positions `1..head` contiguously, verified the hash chain over exactly those events, and
 *   re-read the head afterwards to show no append raced the read. Any other shape reports `null`,
 *   which the projection reads as unknown completeness, not as complete.
 * - **attribution** — only events whose own `workflowRunId` is the requested run become facts.
 * - **integrity** — a broken hash chain throws rather than producing a projection: unverified
 *   records are not a partial answer, they are an unanswerable question.
 *
 * What it deliberately does not do: infer execution, completion or success from an `ALLOW`; carry
 * telemetry as a decision; read a record's metadata into the projection; or claim a registered
 * enforcement strength for a boundary an event merely names. A recorded decision stays an
 * authorization record — [GovernanceFactClassification] assigns its authority, not this source.
 */
internal class AuditBackedGovernanceDecisionFactSource(
    private val auditStore: AuditStore,
    private val streamAssociation: RunStreamAssociation = RunStreamAssociation.DirectlyNamedByRunId,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxEventsPerRead: Int = DEFAULT_MAX_EVENTS,
) : GovernanceDecisionFactSource {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
        require(maxEventsPerRead >= pageSize) { "maxEventsPerRead must be at least pageSize" }
    }

    override suspend fun decisionsFor(workflowRunId: String): DecisionFactRead {
        require(workflowRunId.isNotBlank()) { "a decision read must name the run it is about" }
        // Head before: the event this read is about, not merely its position. An absent association
        // or an absent head is an empty read, not a complete one — nothing was observed, and nothing
        // is asserted.
        val streamId = streamAssociation.streamIdFor(workflowRunId)
        val headBefore = streamId?.let { auditStore.latestEvent(it) }
        if (streamId == null || headBefore == null) {
            return DecisionFactRead(records = emptyList(), coverage = StreamCoverage())
        }

        val events = readFromStart(streamId, headBefore.sequenceNumber)
        val verification = AuditChainVerifier.verify(events)
        if (!verification.isValid) {
            throw AuditDecisionIntegrityException(streamId, verification)
        }
        // Attribution fails closed rather than filtering. A run-named stream carrying another run's
        // event is not this run's stream, so dropping the event would leave a shorter, confident read
        // — possibly over an attested range — where the honest answer is that the stream cannot be
        // read as this run's evidence at all. Multi-run streams are therefore not supported by this
        // source; supporting them would be a separate mode with its own attribution and completeness
        // semantics, not a silent filter here.
        events.firstOrNull { it.workflowRunId != workflowRunId }?.let { foreign ->
            throw AuditDecisionAttributionException(streamId, workflowRunId, foreign)
        }

        // A source answering from a different stream is an integrity failure, not an
        // unknown-completeness condition: those events belong to somebody else's run, and range
        // attestation cannot police it because a foreign chain can be perfectly coherent on its own.
        // The read is refused rather than narrowed.
        events.firstOrNull { it.auditStreamId != streamId }?.let { elsewhere ->
            throw AuditDecisionStreamMismatchException(streamId, elsewhere)
        }

        val verified = verifyEvents(events, workflowRunId)
        val range = verifiedRange(headBefore, auditStore.latestEvent(streamId), events)
        return DecisionFactRead(
            records = verified.records,
            // A read that verified no range has no stream claim to qualify, so it reports no
            // classification against one: the buckets describe positions inside a range that was
            // anchored, and outside one they would be claims about a stream nobody verified.
            coverage =
                if (range == null) {
                    StreamCoverage()
                } else {
                    StreamCoverage(
                        verifiedRange = range,
                        accountedPositions = verified.accountedPositions,
                        unrecordedDecisionPositions = verified.unrecordedDecisionPositions,
                        unclassifiedPositions = verified.unclassifiedPositions,
                    )
                },
        )
    }

    /**
     * What one verified read turned out to contain.
     *
     * The records are one half of it; the other half is the positions that produced no record, split
     * into the ones that were supposed to produce one and the ones this source could not classify at
     * all. Collapsing those two into "everything else" is what lets a run of legitimate non-decision
     * events look like a hole, or lets an unrecognised event pass as irrelevant.
     */
    private data class VerifiedRead(
        val records: List<RecordedDecision>,
        val unrecordedDecisionPositions: Set<Long>,
        val unclassifiedPositions: Set<Long>,
        val accountedPositions: Long,
    )

    /**
     * What this read made of one verified event.
     *
     * Exhaustive by construction: the classification below assigns exactly one outcome to every
     * verified event, and the read's records, its gaps, its unknowns and its account are all derived
     * from those outcomes. That is the point of the type — an account reported beside the
     * classification would restate the size of the input list, which the attested range already
     * implies, and would prove nothing about what the classification actually did with it.
     */
    private enum class EventOutcome {
        DECISION_RECORD,
        RECOGNISED_NON_DECISION,
        MISSING_RECORD,
        UNCLASSIFIED,
    }

    /** A verified event, the outcome this read assigned it, and the record when it has one. */
    private class ClassifiedEvent(
        val position: Long,
        val outcome: EventOutcome,
        val record: RecordedDecision?,
    )

    private fun verifyEvents(
        events: List<AuditEvent>,
        workflowRunId: String,
    ): VerifiedRead {
        // One linear pass producing one entry per verified event, then linear projections of it, so a
        // bounded read costs O(n) rather than rebuilding the record list and both position sets once
        // per event.
        val classified = events.map { classify(it, workflowRunId) }
        return VerifiedRead(
            records = classified.mapNotNull { it.record },
            unrecordedDecisionPositions =
                classified.filter { it.outcome == EventOutcome.MISSING_RECORD }.map { it.position }.toSet(),
            unclassifiedPositions =
                classified.filter { it.outcome == EventOutcome.UNCLASSIFIED }.map { it.position }.toSet(),
            // Counted from the outcomes rather than from the input: an event the classification never
            // reached leaves its position unaccounted for, and this account is what the completeness
            // contract checks against the verified range.
            accountedPositions = classified.count { it.outcome != EventOutcome.UNCLASSIFIED }.toLong(),
        )
    }

    private fun classify(
        event: AuditEvent,
        workflowRunId: String,
    ): ClassifiedEvent {
        recordFor(event, workflowRunId)?.let {
            return ClassifiedEvent(event.sequenceNumber, EventOutcome.DECISION_RECORD, it)
        }
        val outcome =
            when {
                // A producer in this repository defines it as another family's event: not a gap and
                // not an unknown, because the source knows exactly what it is.
                isRecognisedNonDecision(event) -> EventOutcome.RECOGNISED_NON_DECISION

                // A boundary one of this repository's decision families owns recorded an outcome and
                // produced no record: a real hole in the record set.
                isMissingRecord(event) -> EventOutcome.MISSING_RECORD

                // Nobody can say what it is, so decision coverage is not established. An unrecognised
                // boundary carrying a decision token lands here: that an event looks decision-shaped is
                // not evidence that a record was required of it.
                else -> EventOutcome.UNCLASSIFIED
            }
        return ClassifiedEvent(event.sequenceNumber, outcome, null)
    }

    /**
     * The raw-stream range this read can attest, or `null` when it can attest none.
     *
     * This is the stream claim and nothing else: which positions were read, verified against the
     * chain, and anchored to the identity of the head that was observed. Positions alone are not
     * enough — [AuditChainVerifier] proves only that the returned list is internally consistent, and a
     * foreign chain is refused before reaching here — so the range additionally requires the last
     * event's position **and** hash to be the observed head's, the head's identity to be unchanged
     * either side of the read, and the positions to begin at 1 and be contiguous up to that head.
     *
     * Whether the events in that range became decision records is a different question, answered by
     * the classification the caller receives alongside it.
     */
    private fun verifiedRange(
        headBefore: AuditEvent,
        headAfter: AuditEvent?,
        events: List<AuditEvent>,
    ): LongRange? {
        val last = events.lastOrNull()
        val headUnchanged =
            headAfter != null &&
                headAfter.sequenceNumber == headBefore.sequenceNumber &&
                headAfter.eventHash == headBefore.eventHash
        val anchored =
            last != null &&
                events.first().sequenceNumber == FIRST_SOURCE_POSITION &&
                last.sequenceNumber == headBefore.sequenceNumber &&
                last.eventHash == headBefore.eventHash &&
                events.size.toLong() == headBefore.sequenceNumber &&
                headUnchanged
        return if (anchored) FIRST_SOURCE_POSITION..headBefore.sequenceNumber else null
    }

    /**
     * Whether a producer in this repository records this event as belonging to another family.
     *
     * Recognition is a value match against the values the producers record, and it fails safe: an
     * emitter that changes its vocabulary stops being recognised, the event becomes unclassified, and
     * the decision claim is withheld rather than granted.
     *
     * The limitation is stated rather than implied: the emitters are not driven through this
     * classification by a test, so a vocabulary change has to update both the emitter and this table.
     * Until it does, the effect is a withheld claim, never a false one, and the record in
     * docs/roadmap/0.7.4/0.7.4c3-VERIFIED-PROJECTION-COVERAGE.md carries it as a limitation.
     */
    private fun isRecognisedNonDecision(event: AuditEvent): Boolean =
        event.enforcementPoint to event.decision in NON_DECISION_PRODUCER_EVENTS

    /**
     * Whether the event recorded a decision outcome at a boundary one of this repository's decision
     * families owns, which should therefore have produced a record.
     *
     * Both parts are required. A decision token alone is not enough: an enforcement point this source
     * does not recognise carrying `ALLOW` is not evidence that a policy or tool-permission record was
     * required of it, and reporting it as a hole would turn an unsupported event into a proven missing
     * record. Reserving `GAPS_PRESENT` for recognised decision-producing boundaries is what keeps it
     * meaning "a record that should exist does not" rather than "something decision-shaped went past".
     */
    private fun isMissingRecord(event: AuditEvent): Boolean =
        event.decision in DECISION_TOKENS &&
            (
                event.enforcementPoint in POLICY_ENFORCEMENT_POINTS ||
                    event.enforcementPoint in TOOL_ENFORCEMENT_POINTS
            )

    /** An audit stream is evidence, not telemetry. Nothing read here becomes an observation. */
    override suspend fun telemetryFor(workflowRunId: String): List<TelemetryObservation> = emptyList()

    /**
     * Reads positions 1..[head] contiguously, without ever reading more than [maxEventsPerRead]
     * events, or as much of that prefix as the bound allows.
     *
     * A bounded read returns the prefix it did verify, with no attested range; it never returns a
     * range it did not read.
     */
    private suspend fun readFromStart(
        streamId: String,
        head: Long,
    ): List<AuditEvent> {
        // A new list per page rather than a mutable accumulator: the loop is bounded by
        // maxEventsPerRead, so the copies are bounded too, and nothing here is process-global state.
        var events: List<AuditEvent> = emptyList()
        var cursor: Long? = null
        while (events.size.toLong() < head && events.size < maxEventsPerRead) {
            // The remaining capacity is the limit, not the page size: requesting a full page when
            // fewer slots remain reads past the cap, and the range could then attest more events
            // than the configured bound allows.
            val remaining = maxEventsPerRead - events.size
            val page =
                auditStore.readStreamPage(
                    auditStreamId = streamId,
                    afterSequenceNumber = cursor,
                    limit = minOf(pageSize, remaining),
                )
            if (page.isEmpty()) break
            events = events + page
            cursor = page.last().sequenceNumber
        }
        return events
    }

    /**
     * The decision record an audit event carries, or `null` when it carries none this source can
     * recognise.
     *
     * Recognition is an allowlist in both directions, which is stricter than the existing evidence
     * exporters on purpose: their policy family is defined by exclusion, so an event with an
     * unrecognised enforcement point and an `ALLOW` token passes those filters. Here an
     * unrecognised enforcement point is not a decision record at all, because classifying it as a
     * policy decision would be manufacturing a fact the boundary vocabulary does not contain.
     */
    private fun recordFor(
        event: AuditEvent,
        requestedRun: String,
    ): RecordedDecision? =
        decisionKindOf(event)?.let { kind ->
            RecordedDecision(
                workflowRunId = requestedRun,
                kind = kind,
                decision = event.decision,
                // The store guarantees no reason-code format, so the value is treated as untrusted input
                // and dropped unless it is a bounded safe token. Dropping it never invents one.
                reasonCode = event.reasonCode?.takeIf(SAFE_REASON_CODE::matches),
                // The boundary an event names, at the strength registration can prove for it here:
                // none. Audit data carries a declared label, not a registered strength.
                boundary = DeclaredBoundary(id = event.enforcementPoint, registeredStrength = null),
                sourceSequence = event.sequenceNumber,
            )
        }

    /**
     * The family an event belongs to, or `null` when this source cannot recognise a decision.
     *
     * Run attribution is not checked here: it is enforced over the whole read, failing closed, so a
     * record reaching this point is already known to belong to the requested run.
     */
    private fun decisionKindOf(event: AuditEvent): DecisionKind? =
        when {
            event.decision !in DECISION_TOKENS -> {
                null
            }

            // A tool enforcement event without the tool it concerns is not a tool permission, and
            // must not fall back to a policy decision instead.
            event.enforcementPoint in TOOL_ENFORCEMENT_POINTS -> {
                DecisionKind.TOOL_PERMISSION.takeIf { !event.metadata["toolName"].isNullOrBlank() }
            }

            event.enforcementPoint in POLICY_ENFORCEMENT_POINTS -> {
                DecisionKind.POLICY_DECISION
            }

            else -> {
                null
            }
        }
}

private const val DEFAULT_PAGE_SIZE = 256

private const val DEFAULT_MAX_EVENTS = 8192

private const val FIRST_SOURCE_POSITION = 1L

/** The decision tokens the emitters produce, as the exporters already define them. */
private val DECISION_TOKENS = setOf("ALLOW", "DENY", "REQUIRE_APPROVAL")

/**
 * The (enforcement point, decision) pairs this repository's non-decision audit producers record.
 *
 * Taken from the emitters themselves: AuditEngineDlpRedactionAuditEmitter records redactions at
 * DLP_MODEL_OUTPUT and DLP_TOOL_RESULT with decision REDACTED, and
 * AuditEngineApprovalLifecycleAuditEmitter records its eight lifecycle points and their outcomes.
 * Those are real audit events and not governance decisions, which is the whole reason a verified
 * stream may contain positions that produce no record without being incomplete.
 */
private val NON_DECISION_PRODUCER_EVENTS: Set<Pair<String, String>> =
    setOf(
        "DLP_MODEL_OUTPUT" to "REDACTED",
        "DLP_TOOL_RESULT" to "REDACTED",
        "APPROVAL_SUSPENDED" to "PENDING",
        "APPROVAL_RESUMED" to "AUTHORIZED",
        "APPROVAL_COMPLETED" to "SUCCESS",
        "APPROVAL_UNCERTAIN_OUTCOME" to "CANCELLED_UNCERTAIN",
        "APPROVAL_SUSPENSION_CANCELLED" to "CANCELLED",
        "APPROVAL_STALE_CLAIM_DETECTED" to "STALE",
        "APPROVAL_FORCE_CANCELLATION_REQUESTED" to "FORCE_CANCEL",
        "APPROVAL_FORCE_CANCELLED" to "FORCE_CANCELLED",
    )

/** The enforcement points whose decisions belong to the tool permission family. */
private val TOOL_ENFORCEMENT_POINTS =
    setOf(
        "BEFORE_TOOL_EXPOSURE",
        "BEFORE_TOOL_EXECUTION",
        "BEFORE_TOOL_RESULT_REINJECTION",
    )

/**
 * The remaining enforcement points are the recognised non-tool vocabulary, taken from the engine's
 * own enum rather than from a string convention.
 */
private val POLICY_ENFORCEMENT_POINTS =
    EnforcementPoint.entries
        .map { it.name }
        .toSet()
        .minus(TOOL_ENFORCEMENT_POINTS)

/**
 * A bounded safe token for a persisted reason code. The audit store does not enforce this shape, so
 * a value that does not match is untrusted and is dropped rather than projected.
 */
private val SAFE_REASON_CODE = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

/** A stream whose chain does not verify cannot produce a trustworthy projection. */
internal class AuditDecisionIntegrityException(
    val auditStreamId: String,
    result: VerificationResult,
) : IllegalStateException(
        "audit stream '$auditStreamId' failed hash-chain verification: " +
            result.errors
                .firstOrNull()
                ?.message
                .orEmpty(),
    )

/**
 * The store answered a read for one stream with another stream's event. A foreign chain can be
 * internally coherent, so this cannot be left to range attestation: the read is refused.
 */
internal class AuditDecisionStreamMismatchException(
    val requestedAuditStreamId: String,
    offending: AuditEvent,
) : IllegalStateException(
        "audit store answered a read for stream '$requestedAuditStreamId' with an event from " +
            "stream '${offending.auditStreamId}' at position ${offending.sequenceNumber}",
    )

/**
 * A stream carrying another run's event cannot be read as this run's evidence, so the read is
 * refused rather than silently narrowed to the events this run happens to own.
 */
internal class AuditDecisionAttributionException(
    val auditStreamId: String,
    val requestedWorkflowRunId: String,
    offending: AuditEvent,
) : IllegalStateException(
        "audit stream '$auditStreamId' cannot be read as run '$requestedWorkflowRunId': " +
            "event at position ${offending.sequenceNumber} belongs to run '${offending.workflowRunId}'",
    )
