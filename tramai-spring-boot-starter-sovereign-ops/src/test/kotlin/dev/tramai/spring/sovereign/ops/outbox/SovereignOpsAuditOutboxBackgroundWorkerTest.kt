package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsAuditEmitter
import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import dev.tramai.spring.sovereign.ops.SovereignOpsProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SovereignOpsAuditOutboxBackgroundWorkerTest {

    @Test
    fun `runOnce recovers prepared before dispatching pending`() {
        val operations = TrackingOutboxOperations()
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = operations,
            properties = SovereignOpsOutboxWorkerProperties(batchSize = 25),
            clock = fixedClock(),
        )

        val summary = runBlocking { worker.runOnce() }

        assertThat(operations.calls).containsExactly("recover:25", "dispatch:25")
        assertThat(summary.recovered).isNotNull
        assertThat(summary.dispatched).isNotNull
        assertThat(summary.startedAt).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        assertThat(summary.completedAt).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
    }

    @Test
    fun `runOnce can recover prepared only`() {
        val operations = TrackingOutboxOperations()
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = operations,
            properties = SovereignOpsOutboxWorkerProperties(dispatchPending = false),
        )

        val summary = runBlocking { worker.runOnce() }

        assertThat(operations.calls).containsExactly("recover:100")
        assertThat(summary.recovered).isNotNull
        assertThat(summary.dispatched).isNull()
    }

    @Test
    fun `runOnce can dispatch pending only`() {
        val operations = TrackingOutboxOperations()
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = operations,
            properties = SovereignOpsOutboxWorkerProperties(recoverPrepared = false),
        )

        val summary = runBlocking { worker.runOnce() }

        assertThat(operations.calls).containsExactly("dispatch:100")
        assertThat(summary.recovered).isNull()
        assertThat(summary.dispatched).isNotNull
    }

    @Test
    fun `runOnce rethrows CancellationException`() {
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = TrackingOutboxOperations(
                recoverFailure = CancellationException("cancelled"),
            ),
            properties = SovereignOpsOutboxWorkerProperties(),
        )

        val ex = runCatching {
            runBlocking { worker.runOnce() }
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `runOnce catches RuntimeException and returns failure summary`() {
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = TrackingOutboxOperations(
                recoverFailure = IllegalStateException("sensitive@example.com"),
            ),
            properties = SovereignOpsOutboxWorkerProperties(),
        )

        val summary = runBlocking { worker.runOnce() }

        assertThat(summary.recovered).isNull()
        assertThat(summary.dispatched).isNull()
        val failure = summary.failure ?: error("expected failure summary")
        assertThat(failure.action).isEqualTo("recoverPrepared")
        assertThat(failure.errorCode).isEqualTo("IllegalStateException")
        assertThat(failure.errorCode).doesNotContain("sensitive@example.com")
    }

    @Test
    fun `runOnce catches non-runtime Exception and returns failure summary`() {
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = TrackingOutboxOperations(
                recoverFailure = java.io.IOException("file error /secret/path"),
            ),
            properties = SovereignOpsOutboxWorkerProperties(),
        )

        val summary = runBlocking { worker.runOnce() }

        assertThat(summary.recovered).isNull()
        assertThat(summary.dispatched).isNull()
        val failure = summary.failure ?: error("expected failure summary")
        assertThat(failure.action).isEqualTo("recoverPrepared")
        assertThat(failure.errorCode).isEqualTo("IOException")
        assertThat(failure.errorCode).doesNotContain("secret")
    }

    @Test
    fun `runOnce catches non-runtime Exception from dispatch and returns failure summary`() {
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = TrackingOutboxOperations(
                dispatchFailure = java.io.IOException("dispatch I/O error /tmp/secret"),
            ),
            properties = SovereignOpsOutboxWorkerProperties(),
        )

        val summary = runBlocking { worker.runOnce() }

        assertThat(summary.recovered).isNotNull
        assertThat(summary.dispatched).isNull()
        val failure = summary.failure ?: error("expected failure summary")
        assertThat(failure.action).isEqualTo("dispatchPending")
        assertThat(failure.errorCode).isEqualTo("IOException")
        assertThat(failure.errorCode).doesNotContain("secret")
    }

    @Test
    fun `lifecycle validation rejects zero batch size`() {
        val ex = runCatching {
            validateSovereignOpsAuditOutboxWorkerProperties(
                SovereignOpsOutboxWorkerProperties(batchSize = 0),
            )
        }.exceptionOrNull()

        assertThat(ex).hasMessageContaining("tramai-sovereign-ops-outbox-worker-invalid-batch-size")
    }

    @Test
    fun `lifecycle validation rejects zero interval`() {
        val ex = runCatching {
            validateSovereignOpsAuditOutboxWorkerProperties(
                SovereignOpsOutboxWorkerProperties(interval = Duration.ZERO),
            )
        }.exceptionOrNull()

        assertThat(ex).hasMessageContaining("tramai-sovereign-ops-outbox-worker-invalid-interval")
    }

    @Test
    fun `lifecycle validation rejects enabled worker with both actions disabled`() {
        val ex = runCatching {
            validateSovereignOpsAuditOutboxWorkerProperties(
                SovereignOpsOutboxWorkerProperties(
                    recoverPrepared = false,
                    dispatchPending = false,
                ),
            )
        }.exceptionOrNull()

        assertThat(ex).hasMessageContaining("tramai-sovereign-ops-outbox-worker-invalid-actions")
    }

    @Test
    fun `prepared record recovered as committed denied is emitted by worker runOnce`() {
        val store = DurableTestInMemoryOutboxStore()
        val operations = DefaultSovereignOpsAuditOutboxOperations(
            outboxStore = store,
            outboxDispatcher = SovereignOpsAuditOutboxDispatcher(
                outboxStore = store,
                auditEmitter = NoopReplayAuditEmitter,
            ),
            properties = SovereignOpsProperties(mutationsEnabled = true),
            recoveryResolver = SovereignOpsApprovalRecoveryResolver {
                SovereignOpsPreparedRecoveryDecision.COMMITTED_DENIED
            },
        )
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = operations,
            properties = SovereignOpsOutboxWorkerProperties(batchSize = 10),
        )

        runBlocking {
            store.append(record("prepared"))
            worker.runOnce()
        }

        val stored = runBlocking { store.get("prepared") }
        assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
    }

    @Test
    fun `pending record with failing emitter becomes failed retryable by worker runOnce`() {
        val store = DurableTestInMemoryOutboxStore()
        val operations = DefaultSovereignOpsAuditOutboxOperations(
            outboxStore = store,
            outboxDispatcher = SovereignOpsAuditOutboxDispatcher(
                outboxStore = store,
                auditEmitter = FailingReplayAuditEmitter,
            ),
            properties = SovereignOpsProperties(mutationsEnabled = true),
            recoveryResolver = UnknownSovereignOpsApprovalRecoveryResolver,
        )
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = operations,
            properties = SovereignOpsOutboxWorkerProperties(
                recoverPrepared = false,
                batchSize = 10,
            ),
        )

        runBlocking {
            store.append(record("pending"))
            store.markReadyForDispatch("pending", SovereignOpsAuditOutboxStatus.PREPARED)
            worker.runOnce()
        }

        val stored = runBlocking { store.get("pending") }
        assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE)
        assertThat(stored.lastErrorCode).isEqualTo("IllegalStateException")
    }

    // ── Observer lifecycle tests ──────────────────────────────────────

    @Test
    fun `observer receives onCycleCompleted after successful runOnce`() {
        val operations = TrackingOutboxOperations()
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = operations,
            properties = SovereignOpsOutboxWorkerProperties(recoverPrepared = false, dispatchPending = false),
        )
        val observer = RecordingSovereignOpsAuditOutboxWorkerObserver()
        val lifecycle = SovereignOpsAuditOutboxWorkerLifecycle(
            worker = worker,
            properties = SovereignOpsOutboxWorkerProperties(
                enabled = true,
                initialDelay = Duration.ofMillis(1),
            ),
            observer = observer,
        )

        lifecycle.start()
        // Let one cycle run
        Thread.sleep(50)
        lifecycle.stop()

        assertThat(observer.completed).hasSize(1)
        assertThat(observer.failures).isEmpty()
    }

    @Test
    fun `observer receives onCycleFailed when summary has failure`() {
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = TrackingOutboxOperations(
                recoverFailure = IllegalStateException("sensitive@example.com"),
            ),
            properties = SovereignOpsOutboxWorkerProperties(batchSize = 1),
        )
        val observer = RecordingSovereignOpsAuditOutboxWorkerObserver()
        val lifecycle = SovereignOpsAuditOutboxWorkerLifecycle(
            worker = worker,
            properties = SovereignOpsOutboxWorkerProperties(
                enabled = true,
                initialDelay = Duration.ofMillis(1),
            ),
            observer = observer,
        )

        lifecycle.start()
        Thread.sleep(50)
        lifecycle.stop()

        assertThat(observer.completed).hasSize(1)
        assertThat(observer.failures).hasSize(1)
        val failure = observer.failures.first()
        assertThat(failure.first).isEqualTo("recoverPrepared")
        assertThat(failure.second).isEqualTo("IllegalStateException")
        assertThat(failure.second).doesNotContain("sensitive@example.com")
    }

    @Test
    fun `observer receives onCycleFailed when unexpected exception escapes runOnce`() {
        // Worker that throws CancellationException won't be caught by lifecycle
        // Use a RuntimeException that escapes runOnce (unexpected path)
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = TrackingOutboxOperations(
                recoverFailure = RuntimeException("unexpected /secret/path"),
            ),
            properties = SovereignOpsOutboxWorkerProperties(batchSize = 1),
        )
        val observer = RecordingSovereignOpsAuditOutboxWorkerObserver()
        val lifecycle = SovereignOpsAuditOutboxWorkerLifecycle(
            worker = worker,
            properties = SovereignOpsOutboxWorkerProperties(
                enabled = true,
                initialDelay = Duration.ofMillis(1),
            ),
            observer = observer,
        )

        lifecycle.start()
        Thread.sleep(50)
        lifecycle.stop()

        assertThat(observer.completed).hasSize(1)
        assertThat(observer.failures).hasSize(1)
        val failure = observer.failures.first()
        assertThat(failure.first).isEqualTo("recoverPrepared")
        assertThat(failure.second).doesNotContain("secret")
        assertThat(failure.second).doesNotContain("/secret/path")
    }

    @Test
    fun `CancellationException is not sent to observer`() {
        val worker = SovereignOpsAuditOutboxBackgroundWorker(
            operations = TrackingOutboxOperations(
                recoverFailure = CancellationException("cancelled"),
            ),
            properties = SovereignOpsOutboxWorkerProperties(batchSize = 1),
        )
        val observer = RecordingSovereignOpsAuditOutboxWorkerObserver()
        val lifecycle = SovereignOpsAuditOutboxWorkerLifecycle(
            worker = worker,
            properties = SovereignOpsOutboxWorkerProperties(
                enabled = true,
                initialDelay = Duration.ofMillis(1),
            ),
            observer = observer,
        )

        lifecycle.start()
        Thread.sleep(50)
        lifecycle.stop()

        // CancellationException is rethrown by runOnce, but lifecycle catches generic
        // Exception around it — this is the unexpected path
        assertThat(observer.failures.any { it.second == "CancellationException" }).isFalse()
    }

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    private fun record(outboxId: String): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateIdDigest = "digest-$outboxId",
            eventKey = "event-$outboxId",
            actor = "admin",
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            approvalStatus = "DENIED",
            approvalVersion = 1L,
            reasonDigest = "reason-$outboxId",
            reasonLength = 12,
            createdAt = Instant.parse("2026-06-01T00:00:00Z"),
        )
}

private class TrackingOutboxOperations(
    private val recoverFailure: Throwable? = null,
    private val dispatchFailure: Throwable? = null,
) : SovereignOpsAuditOutboxOperations {
    val calls = mutableListOf<String>()

    override suspend fun listOutboxRecords(
        status: SovereignOpsAuditOutboxStatus?,
        limit: Int?,
    ): List<SovereignOpsAuditOutboxSummary> = emptyList()

    override suspend fun retryPending(limit: Int?): SovereignOpsAuditOutboxDispatchResult {
        dispatchFailure?.let { throw it }
        calls += "dispatch:$limit"
        return SovereignOpsAuditOutboxDispatchResult(
            claimed = 1,
            emitted = 1,
            failedRetryable = 0,
            failedPermanent = 0,
        )
    }

    override suspend fun markPreparedFailed(
        outboxId: String,
        reason: String,
    ): SovereignOpsAuditOutboxSummary {
        throw UnsupportedOperationException("test stub")
    }

    override suspend fun recoverPrepared(limit: Int?): SovereignOpsAuditOutboxRecoverySummary {
        recoverFailure?.let { throw it }
        calls += "recover:$limit"
        return SovereignOpsAuditOutboxRecoverySummary(
            inspected = 1,
            movedToPending = 1,
        )
    }
}

private object NoopReplayAuditEmitter : SovereignOpsAuditEmitter {
    override suspend fun approvalDenied(
        approvalId: String,
        actor: String,
        reason: String,
        approvalStatus: String,
        approvalVersion: Long?,
        workflowRunId: String?,
        correlationId: String?,
    ) = Unit

    override suspend fun approvalDeniedFromOutbox(record: SovereignOpsAuditOutboxRecord) = Unit
}

private object FailingReplayAuditEmitter : SovereignOpsAuditEmitter {
    override suspend fun approvalDenied(
        approvalId: String,
        actor: String,
        reason: String,
        approvalStatus: String,
        approvalVersion: Long?,
        workflowRunId: String?,
        correlationId: String?,
    ) = Unit

    override suspend fun approvalDeniedFromOutbox(record: SovereignOpsAuditOutboxRecord) {
        throw IllegalStateException("emission failure contains sensitive@example.com")
    }
}

private class RecordingSovereignOpsAuditOutboxWorkerObserver : SovereignOpsAuditOutboxWorkerObserver {
    val completed = mutableListOf<SovereignOpsAuditOutboxWorkerRunSummary>()
    val failures = mutableListOf<Pair<String, String>>()

    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        completed += summary
    }

    override fun onCycleFailed(action: String, errorCode: String) {
        failures += action to errorCode
    }
}
