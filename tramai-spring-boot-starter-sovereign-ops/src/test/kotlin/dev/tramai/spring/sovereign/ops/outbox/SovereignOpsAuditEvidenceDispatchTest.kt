package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.spring.sovereign.ops.AuditEngineSovereignOpsAuditEmitter
import dev.tramai.spring.sovereign.ops.SovereignOpsAuditEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 0.7.6f §6 / §8 discriminators 10-13: dispatch says what the evidence is.
 *
 * Three failure modes are excluded here, all of them live defects before this slice: an approval emitted as
 * a denial, a refusal emitted as a decision, and evidence the deployment cannot represent retried forever
 * or silently mapped to something it is not.
 */
class SovereignOpsAuditEvidenceDispatchTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC)
    private val stream = "sovereign-ops-approval:$AGGREGATE_DIGEST"

    // --- discriminator 10 -----------------------------------------------------------------------

    @Test
    fun `each kind produces its own enforcement point, decision and reason code`() =
        runBlocking<Unit> {
            val stores = stores()
            val approved = stores.seed(decision("approved-1", APPROVED, "APPROVED"))
            val denied = stores.seed(decision("denied-1", DENIED, "DENIED"))
            val refused =
                stores.seed(
                    refusal("refused-1", reason = "missing-capability"),
                )

            val result = stores.dispatcher.dispatchPending(limit = 10)

            assertThat(result).isEqualTo(SovereignOpsAuditOutboxDispatchResult(3, 3, 0, 0))
            val events = stores.audit.readStream(stream)
            assertThat(events).hasSize(3)
            assertThat(events).allSatisfy { assertThat(it.actor).isEqualTo("operator-1") }

            val byOutboxId = events.associateBy { it.metadata.getValue(META_OUTBOX_ID) }
            assertThat(byOutboxId.keys).containsExactlyInAnyOrder(approved, denied, refused)

            assertThat(byOutboxId.getValue(approved))
                .matches({ it.enforcementPoint == "sovereign-ops.approval.decide" }, "approval enforcement point")
                .matches({ it.decision == "APPROVED" }, "approval decision")
                .matches({ it.reasonCode == "sovereign-ops-approval-granted" }, "approval reason code")

            assertThat(byOutboxId.getValue(denied))
                .matches({ it.enforcementPoint == "sovereign-ops.approval.decide" }, "denial enforcement point")
                .matches({ it.decision == "DENIED" }, "denial decision")
                .matches({ it.reasonCode == "sovereign-ops-admin-denial" }, "denial reason code")

            // The refusal must not be emitted as a decision: it is a different enforcement point.
            val refusalEvent = byOutboxId.getValue(refused)
            assertThat(refusalEvent.enforcementPoint).isEqualTo("sovereign-ops.approval.authorization")
            assertThat(refusalEvent.decision).isEqualTo("DENIED")
            assertThat(refusalEvent.reasonCode).isEqualTo("sovereign-ops-authorization-refused")
            assertThat(refusalEvent.metadata).containsEntry("refusalReason", "missing-capability")
            // A refusal carries no approval status at all, and the event must not invent one.
            assertThat(refusalEvent.metadata).doesNotContainKey("approvalStatus")
            assertThat(refusalEvent.metadata).doesNotContainKey("approvalVersion")
            // Correlation back to exactly one outbox record.
            assertThat(refusalEvent.metadata).containsEntry(META_EVENT_KEY, "event-$refused")
            assertThat(refusalEvent.metadata).doesNotContainKey(META_LEGACY)
        }

    // --- discriminator 11 -----------------------------------------------------------------------

    @Test
    fun `a legacy-style emitter emits denials and parks the kinds it cannot represent`() =
        runBlocking<Unit> {
            val stores = stores(emitter = null)
            val emitted = mutableListOf<String>()
            val legacyEmitter =
                object : SovereignOpsAuditEmitter {
                    override suspend fun approvalDenied(
                        approvalId: String,
                        actor: String,
                        reason: String,
                        approvalStatus: String,
                        approvalVersion: Long?,
                        workflowRunId: String?,
                        correlationId: String?,
                    ) {
                        emitted.add(approvalId)
                    }

                    override suspend fun approvalDeniedFromOutbox(record: SovereignOpsAuditOutboxRecord) {
                        emitted.add(record.outboxId)
                    }
                }
            val approved = stores.seed(decision("approved-2", APPROVED, "APPROVED"))
            val denied = stores.seed(decision("denied-2", DENIED, "DENIED"))
            val refused = stores.seed(refusal("refused-2", reason = "role-not-held"))

            val result = stores.dispatch(legacyEmitter, limit = 10)

            // Only the kind this emitter can represent is emitted -- an approval is never relabelled a denial.
            assertThat(emitted).containsExactly(denied)
            assertThat(result.emitted).isEqualTo(1)
            assertThat(result.failedPermanent).isEqualTo(2)
            assertThat(result.failedRetryable).isZero()
            stores.assertParked(approved)
            stores.assertParked(refused)
            assertThat(stores.store.get(denied)?.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
        }

    // --- discriminator 12 -----------------------------------------------------------------------

    @Test
    fun `legacy records are classified from persisted status and fail closed when it names no kind`() =
        runBlocking<Unit> {
            val stores = stores()
            // No evidenceKind at all: these are records written before 0.7.6f.
            val legacyApproved = stores.seed(decision("legacy-approved", null, "APPROVED"))
            val legacyDenied = stores.seed(decision("legacy-denied", null, "DENIED"))
            val legacyUnknown = stores.seed(decision("legacy-unknown", null, "SOMETHING_ELSE"))
            val legacyMissing = stores.seed(decision("legacy-missing", null, null))

            val result = stores.dispatcher.dispatchPending(limit = 10)

            assertThat(result.emitted).isEqualTo(2)
            assertThat(result.failedPermanent).isEqualTo(2)
            val byOutboxId = stores.audit.readStream(stream).associateBy { it.metadata.getValue(META_OUTBOX_ID) }
            assertThat(byOutboxId.getValue(legacyApproved).decision).isEqualTo("APPROVED")
            assertThat(byOutboxId.getValue(legacyDenied).decision).isEqualTo("DENIED")
            // A reconstructed kind is flagged: it is a weaker claim than a kind the decision plane wrote.
            assertThat(byOutboxId.getValue(legacyApproved).metadata).containsEntry(META_LEGACY, "true")
            assertThat(byOutboxId.getValue(legacyDenied).metadata).containsEntry(META_LEGACY, "true")
            // Never guessed into a denial -- the false attribution 0.7.6f exists to remove.
            assertThat(byOutboxId).doesNotContainKeys(legacyUnknown, legacyMissing)
            stores.assertParked(legacyUnknown, ERROR_UNDECODABLE_LEGACY_EVIDENCE)
            stores.assertParked(legacyMissing, ERROR_UNDECODABLE_LEGACY_EVIDENCE)
        }

    @Test
    fun `legacyEvidence marks a reconstructed kind, not a compatibility code path`() =
        runBlocking<Unit> {
            val stores = stores()
            val persistedDenied = stores.seed(decision("kind-denied", DENIED, "DENIED"))
            val legacyDenied = stores.seed(decision("legacy-denied-2", null, "DENIED"))

            stores.dispatcher.dispatchPending(limit = 10)

            val byOutboxId = stores.audit.readStream(stream).associateBy { it.metadata.getValue(META_OUTBOX_ID) }
            // Same kind, same emitter, same code path -- the difference is the source record, not the route.
            assertThat(byOutboxId.getValue(persistedDenied).decision).isEqualTo("DENIED")
            assertThat(byOutboxId.getValue(persistedDenied).metadata).doesNotContainKey(META_LEGACY)
            assertThat(byOutboxId.getValue(legacyDenied).decision).isEqualTo("DENIED")
            assertThat(byOutboxId.getValue(legacyDenied).metadata).containsEntry(META_LEGACY, "true")
        }

    // --- discriminator 13 -----------------------------------------------------------------------

    @Test
    fun `an emitter that cannot represent a kind parks the record instead of retrying it`() =
        runBlocking<Unit> {
            val stores = stores(emitter = null)
            val unrepresentable =
                object : SovereignOpsAuditEmitter {
                    override suspend fun approvalDenied(
                        approvalId: String,
                        actor: String,
                        reason: String,
                        approvalStatus: String,
                        approvalVersion: Long?,
                        workflowRunId: String?,
                        correlationId: String?,
                    ) = Unit

                    override suspend fun evidenceFromOutbox(record: SovereignOpsAuditOutboxRecord): Unit =
                        throw UnsupportedOperationException("tramai-sovereign-ops-refusal-evidence-unsupported")
                }
            val refused = stores.seed(refusal("refused-3", reason = "missing-capability"))

            val first = stores.dispatch(unrepresentable, limit = 10)
            val second = stores.dispatch(unrepresentable, limit = 10)

            assertThat(first.failedPermanent).isEqualTo(1)
            assertThat(first.failedRetryable).isZero()
            stores.assertParked(refused, ERROR_UNSUPPORTED_EVIDENCE_KIND)
            // Permanent really is permanent: nothing is re-claimed, so there is no retry loop.
            assertThat(second.claimed).isZero()
            assertThat(stores.audit.readStream(stream)).isEmpty()
        }

    @Test
    fun `an unrelated UnsupportedOperationException stays retryable`() =
        runBlocking<Unit> {
            val stores = stores(emitter = null)
            val brokenEmitter =
                object : SovereignOpsAuditEmitter {
                    override suspend fun approvalDenied(
                        approvalId: String,
                        actor: String,
                        reason: String,
                        approvalStatus: String,
                        approvalVersion: Long?,
                        workflowRunId: String?,
                        correlationId: String?,
                    ) = Unit

                    override suspend fun evidenceFromOutbox(record: SovereignOpsAuditOutboxRecord) {
                        // Someone else's bug, not the evidence contract saying "cannot represent this kind".
                        throw UnsupportedOperationException("unsupported operation: transport does not exist")
                    }
                }
            val refused = stores.seed(refusal("refused-4", reason = "missing-capability"))

            val result = stores.dispatch(brokenEmitter, limit = 10)

            assertThat(result.failedPermanent).isZero()
            assertThat(result.failedRetryable).isEqualTo(1)
            val stored = stores.store.get(refused)
            assertThat(stored?.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE)
            // And it really is retried, not parked for good.
            assertThat(stores.dispatch(brokenEmitter, limit = 10).claimed).isEqualTo(1)
        }

    // --- helpers --------------------------------------------------------------------------------

    private fun stores(emitter: SovereignOpsAuditEmitter? = null): Fixture {
        val audit = InMemoryAuditStore()
        val store = InMemorySovereignOpsAuditOutboxStore()
        val realEmitter = AuditEngineSovereignOpsAuditEmitter(AuditEngine(audit, clock = clock))
        return Fixture(audit, store, realEmitter, emitter)
    }

    private fun decision(
        outboxId: String,
        kind: SovereignOpsAuditEvidenceKind?,
        approvalStatus: String?,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = AGGREGATE_TYPE,
            aggregateIdDigest = AGGREGATE_DIGEST,
            operation = "decideApproval",
            eventKey = "event-$outboxId",
            actor = ACTOR,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            approvalStatus = approvalStatus,
            approvalVersion = 1L,
            reasonDigest = REASON_DIGEST,
            reasonLength = 11,
            createdAt = clock.instant(),
            status = SovereignOpsAuditOutboxStatus.PENDING,
            evidenceKind = kind,
        )

    private fun refusal(
        outboxId: String,
        reason: String,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = AGGREGATE_TYPE,
            aggregateIdDigest = AGGREGATE_DIGEST,
            operation = "authorizationRefusal",
            eventKey = "event-$outboxId",
            actor = ACTOR,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            approvalStatus = null,
            approvalVersion = null,
            reasonDigest = REASON_DIGEST,
            reasonLength = 11,
            createdAt = clock.instant(),
            status = SovereignOpsAuditOutboxStatus.PENDING,
            evidenceKind = SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL,
            refusalReason = reason,
        )

    private inner class Fixture(
        val audit: InMemoryAuditStore,
        val store: InMemorySovereignOpsAuditOutboxStore,
        private val realEmitter: SovereignOpsAuditEmitter,
        private val override: SovereignOpsAuditEmitter?,
    ) {
        val dispatcher = SovereignOpsAuditOutboxDispatcher(store, override ?: realEmitter, clock = clock)

        /**
         * Seeds through the real lifecycle: a refusal has no transition to wait for and is inserted
         * dispatchable, while decision evidence is appended PREPARED and released once its transition
         * commits -- which is also what makes a legacy row (no kind) seedable.
         */
        suspend fun seed(record: SovereignOpsAuditOutboxRecord): String {
            if (record.evidenceKind == SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL) {
                store.appendDispatchable(record)
            } else {
                store.append(record.copy(status = SovereignOpsAuditOutboxStatus.PREPARED))
                store.markReadyForDispatch(
                    outboxId = record.outboxId,
                    expectedStatus = SovereignOpsAuditOutboxStatus.PREPARED,
                )
            }
            return record.outboxId
        }

        suspend fun dispatch(
            emitter: SovereignOpsAuditEmitter,
            limit: Int,
        ): SovereignOpsAuditOutboxDispatchResult {
            val dispatcher = SovereignOpsAuditOutboxDispatcher(store, emitter, clock = clock)
            return dispatcher.dispatchPending(limit)
        }

        suspend fun assertParked(
            outboxId: String,
            errorCode: String = ERROR_UNSUPPORTED_EVIDENCE_KIND,
        ) {
            val stored = store.get(outboxId)
            assertThat(stored?.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT)
            assertThat(stored?.lastErrorCode).isEqualTo(errorCode)
        }
    }

    private companion object {
        private const val AGGREGATE_TYPE = "approval"
        private val APPROVED = SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED
        private val DENIED = SovereignOpsAuditEvidenceKind.APPROVAL_DENIED
        private val AGGREGATE_DIGEST = "sha256:" + "a".repeat(64)
        private val REASON_DIGEST = "sha256:" + "b".repeat(64)
        private const val ACTOR = "operator-1"
        private const val META_OUTBOX_ID = "outboxId"
        private const val META_EVENT_KEY = "eventKey"
        private const val META_LEGACY = "legacyEvidence"
    }
}
