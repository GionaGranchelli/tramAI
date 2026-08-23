package dev.tramai.testing.persistence.outbox

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1e: the shared, cross-implementation
 * [SovereignOpsAuditOutboxStore] compatibility contract. Every concrete
 * store — in-memory, encrypted file, and JDBC — must expose the same
 * delivery state machine, claiming/retry/lease semantics, failure matrix,
 * lookup, listing, and concurrency contract regardless of storage
 * technology.
 *
 * The intended lifecycle (the JDBC guards from PR #85's review are the
 * authoritative semantics):
 * ```
 * PREPARED ──markReadyForDispatch──→ PENDING ──claimPending──→ EMITTING
 * PREPARED ──markFailed(false)──→ FAILED_PERMANENT
 * EMITTING ──markEmitted──→ EMITTED
 * EMITTING ──markFailed(true)──→ FAILED_RETRYABLE ──claimPending──→ EMITTING
 * EMITTING ──markFailed(false)──→ FAILED_PERMANENT
 * EMITTING (expired claim) ──claimPending──→ EMITTING (attempt + 1)
 * ```
 *
 * Out of scope (implementation-specific): durability/restart guarantees,
 * encryption, permissions, corruption, SQL indexes/schema, FOR UPDATE SKIP
 * LOCKED, file record versions, JDBC maxClaimLimit, and `isDurable()` (the
 * SPI documents it as implementation-dependent).
 */
abstract class SovereignOpsAuditOutboxStoreTck {

    /** Fresh isolated storage per case; the runner owns setup/teardown. */
    protected abstract fun createStore(): SovereignOpsAuditOutboxStore

    private val t0: Instant = SovereignOpsAuditOutboxFixtures.T0

    private fun record(
        outboxId: String,
        eventKey: String = "event-$outboxId",
        status: SovereignOpsAuditOutboxStatus = SovereignOpsAuditOutboxStatus.PREPARED,
        attemptCount: Int = 0,
        lastErrorCode: String? = null,
        claimedBy: String? = null,
        claimedAt: Instant? = null,
        claimExpiresAt: Instant? = null,
        emittedAt: Instant? = null,
    ): SovereignOpsAuditOutboxRecord = SovereignOpsAuditOutboxFixtures.record(
        outboxId = outboxId,
        eventKey = eventKey,
        status = status,
        createdAt = t0,
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        claimedBy = claimedBy,
        claimedAt = claimedAt,
        claimExpiresAt = claimExpiresAt,
        emittedAt = emittedAt,
    )

    // ── A. Append / creation / lookup ────────────────────────────────

    @Test
    fun `valid PREPARED record round-trips exactly`() = runBlocking<Unit> {
        val store = createStore()
        val rec = record("roundtrip-1")
        val returned = store.append(rec)
        assertThat(returned).isEqualTo(rec)
        assertThat(store.get("roundtrip-1")).isEqualTo(rec)
    }

    @Test
    fun `append returns the stored record`() = runBlocking<Unit> {
        val store = createStore()
        val rec = record("returns-1")
        assertThat(store.append(rec)).isEqualTo(rec)
    }

    @Test
    fun `get on missing outboxId returns null`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.get("no-such-id")).isNull()
    }

    @Test
    fun `findByEventKey on missing key returns null`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.findByEventKey("no-such-key")).isNull()
    }

    @Test
    fun `findByEventKey returns the exact record`() = runBlocking<Unit> {
        val store = createStore()
        val rec = record("find-1", eventKey = "key-find-1")
        store.append(rec)
        assertThat(store.findByEventKey("key-find-1")).isEqualTo(rec)
    }

    @Test
    fun `duplicate outboxId rejected with fixed code`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("dup-id"))
        val thrown = runCatching { store.append(record("dup-id")) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-duplicate-id")
    }

    @Test
    fun `duplicate eventKey rejected with fixed code`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("first", eventKey = "shared-key"))
        val thrown = runCatching { store.append(record("second", eventKey = "shared-key")) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-duplicate-event-key")
    }

    @Test
    fun `duplicate eventKey rejection leaves no orphan second record`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("first", eventKey = "shared-key"))
        runCatching { store.append(record("second", eventKey = "shared-key")) }
        assertThat(store.get("second")).isNull()
        assertThat(store.get("first")).isNotNull()
    }

    @Test
    fun `original event-key mapping remains intact after rejected duplicate`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("first", eventKey = "shared-key"))
        runCatching { store.append(record("second", eventKey = "shared-key")) }
        assertThat(store.findByEventKey("shared-key")?.outboxId).isEqualTo("first")
    }

    @Test
    fun `blank outboxId rejected`() = runBlocking<Unit> {
        val store = createStore()
        for (blank in listOf("", "   ")) {
            val thrown = runCatching { store.append(record(blank)) }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-invalid-id")
        }
    }

    @Test
    fun `blank eventKey rejected`() = runBlocking<Unit> {
        val store = createStore()
        for (blank in listOf("", "   ")) {
            val thrown = runCatching { store.append(record("id-1", eventKey = blank)) }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-invalid-event-key")
        }
    }

    @Test
    fun `every non-PREPARED initial status rejected`() = runBlocking<Unit> {
        val store = createStore()
        for (status in SovereignOpsAuditOutboxStatus.entries.filter { it != SovereignOpsAuditOutboxStatus.PREPARED }) {
            val thrown = runCatching { store.append(record("status-$status", status = status)) }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-invalid-status")
        }
    }

    // ── B. markReadyForDispatch ─────────────────────────────────────

    @Test
    fun `markReady legal PREPARED to PENDING preserves non-status fields`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("ready-1"))
        val updated = store.markReadyForDispatch("ready-1", SovereignOpsAuditOutboxStatus.PREPARED)
        assertThat(updated.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        assertThat(updated).isEqualTo(record("ready-1").copy(status = SovereignOpsAuditOutboxStatus.PENDING))
    }

    @Test
    fun `markReady persisted state is PENDING`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("ready-2"))
        store.markReadyForDispatch("ready-2", SovereignOpsAuditOutboxStatus.PREPARED)
        assertThat(store.get("ready-2")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
    }

    @Test
    fun `markReady on missing record throws not-found`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching {
            store.markReadyForDispatch("missing", SovereignOpsAuditOutboxStatus.PREPARED)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-not-found")
    }

    @Test
    fun `markReady with wrong current status rejects`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("ready-3"))
        store.markReadyForDispatch("ready-3", SovereignOpsAuditOutboxStatus.PREPARED)
        val thrown = runCatching {
            store.markReadyForDispatch("ready-3", SovereignOpsAuditOutboxStatus.PENDING)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markReady requires expectedStatus to be PREPARED`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("ready-4"))
        // The record IS PREPARED; supplying a non-PREPARED expected status must
        // still reject — this catches the InMemory divergence.
        for (expected in SovereignOpsAuditOutboxStatus.entries.filter { it != SovereignOpsAuditOutboxStatus.PREPARED }) {
            val thrown = runCatching {
                store.markReadyForDispatch("ready-4", expected)
            }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
        }
        assertThat(store.get("ready-4")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PREPARED)
    }

    // ── C. markEmitted ──────────────────────────────────────────────

    private suspend fun emittingRecord(store: SovereignOpsAuditOutboxStore, outboxId: String): SovereignOpsAuditOutboxRecord {
        store.append(record(outboxId))
        store.markReadyForDispatch(outboxId, SovereignOpsAuditOutboxStatus.PREPARED)
        return store.claimPending("worker-1", 10, t0).single { it.outboxId == outboxId }
    }

    @Test
    fun `markEmitted legal EMITTING to EMITTED with exact emittedAt`() = runBlocking<Unit> {
        val store = createStore()
        val claimed = emittingRecord(store, "emit-1")
        val emittedAt = t0.plusSeconds(30)
        val updated = store.markEmitted("emit-1", SovereignOpsAuditOutboxStatus.EMITTING, emittedAt)
        assertThat(updated.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
        assertThat(updated.emittedAt).isEqualTo(emittedAt)
    }

    @Test
    fun `markEmitted preserves attempt and claim fields`() = runBlocking<Unit> {
        val store = createStore()
        val claimed = emittingRecord(store, "emit-2")
        val updated = store.markEmitted("emit-2", SovereignOpsAuditOutboxStatus.EMITTING, t0.plusSeconds(30))
        assertThat(updated.attemptCount).isEqualTo(claimed.attemptCount)
        assertThat(updated.claimedBy).isEqualTo("worker-1")
        assertThat(updated.claimedAt).isEqualTo(t0)
        assertThat(updated.claimExpiresAt).isEqualTo(t0.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY))
    }

    @Test
    fun `markEmitted on missing record throws not-found`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching {
            store.markEmitted("missing", SovereignOpsAuditOutboxStatus.EMITTING, t0)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-not-found")
    }

    @Test
    fun `markEmitted rejects PREPARED with expected PREPARED`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("emit-3"))
        val thrown = runCatching {
            store.markEmitted("emit-3", SovereignOpsAuditOutboxStatus.PREPARED, t0)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markEmitted rejects PENDING with expected PENDING`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("emit-4"))
        store.markReadyForDispatch("emit-4", SovereignOpsAuditOutboxStatus.PREPARED)
        val thrown = runCatching {
            store.markEmitted("emit-4", SovereignOpsAuditOutboxStatus.PENDING, t0)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markEmitted rejects EMITTED with expected EMITTED`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "emit-5")
        store.markEmitted("emit-5", SovereignOpsAuditOutboxStatus.EMITTING, t0)
        val thrown = runCatching {
            store.markEmitted("emit-5", SovereignOpsAuditOutboxStatus.EMITTED, t0)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markEmitted rejects FAILED_RETRYABLE and FAILED_PERMANENT`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "emit-6")
        store.markFailed("emit-6", SovereignOpsAuditOutboxStatus.EMITTING, "boom", retryable = true)
        val retryThrown = runCatching {
            store.markEmitted("emit-6", SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE, t0)
        }.exceptionOrNull()
        assertThat(retryThrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(retryThrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")

        emittingRecord(store, "emit-7")
        store.markFailed("emit-7", SovereignOpsAuditOutboxStatus.EMITTING, "boom", retryable = false)
        val permThrown = runCatching {
            store.markEmitted("emit-7", SovereignOpsAuditOutboxStatus.FAILED_PERMANENT, t0)
        }.exceptionOrNull()
        assertThat(permThrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(permThrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    // ── D. markFailed ───────────────────────────────────────────────

    @Test
    fun `markFailed PREPARED permanent is legal with lastErrorCode`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("fail-1"))
        val updated = store.markFailed("fail-1", SovereignOpsAuditOutboxStatus.PREPARED, "orphaned", retryable = false)
        assertThat(updated.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT)
        assertThat(updated.lastErrorCode).isEqualTo("orphaned")
    }

    @Test
    fun `markFailed EMITTING retryable is legal with lastErrorCode`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "fail-2")
        val updated = store.markFailed("fail-2", SovereignOpsAuditOutboxStatus.EMITTING, "timeout", retryable = true)
        assertThat(updated.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE)
        assertThat(updated.lastErrorCode).isEqualTo("timeout")
    }

    @Test
    fun `markFailed EMITTING permanent is legal with lastErrorCode`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "fail-3")
        val updated = store.markFailed("fail-3", SovereignOpsAuditOutboxStatus.EMITTING, "fatal", retryable = false)
        assertThat(updated.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT)
        assertThat(updated.lastErrorCode).isEqualTo("fatal")
    }

    @Test
    fun `markFailed rejects PREPARED with retryable true`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("fail-4"))
        val thrown = runCatching {
            store.markFailed("fail-4", SovereignOpsAuditOutboxStatus.PREPARED, "x", retryable = true)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markFailed rejects PENDING permanent`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("fail-5"))
        store.markReadyForDispatch("fail-5", SovereignOpsAuditOutboxStatus.PREPARED)
        val thrown = runCatching {
            store.markFailed("fail-5", SovereignOpsAuditOutboxStatus.PENDING, "x", retryable = false)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markFailed rejects PENDING retryable`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("fail-6"))
        store.markReadyForDispatch("fail-6", SovereignOpsAuditOutboxStatus.PREPARED)
        val thrown = runCatching {
            store.markFailed("fail-6", SovereignOpsAuditOutboxStatus.PENDING, "x", retryable = true)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markFailed rejects FAILED_RETRYABLE`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "fail-7")
        store.markFailed("fail-7", SovereignOpsAuditOutboxStatus.EMITTING, "x", retryable = true)
        val thrown = runCatching {
            store.markFailed("fail-7", SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE, "x", retryable = true)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markFailed rejects EMITTED`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "fail-8")
        store.markEmitted("fail-8", SovereignOpsAuditOutboxStatus.EMITTING, t0)
        val thrown = runCatching {
            store.markFailed("fail-8", SovereignOpsAuditOutboxStatus.EMITTED, "x", retryable = true)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markFailed rejects FAILED_PERMANENT`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("fail-9"))
        store.markFailed("fail-9", SovereignOpsAuditOutboxStatus.PREPARED, "x", retryable = false)
        val thrown = runCatching {
            store.markFailed("fail-9", SovereignOpsAuditOutboxStatus.FAILED_PERMANENT, "x", retryable = false)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-status-mismatch")
    }

    // ── E. Claim / retry / lease semantics ──────────────────────────

    @Test
    fun `claim PENDING moves to EMITTING with attempt and lease`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("claim-1"))
        store.markReadyForDispatch("claim-1", SovereignOpsAuditOutboxStatus.PREPARED)
        val claimed = store.claimPending("worker-1", 10, t0)
        assertThat(claimed).hasSize(1)
        val c = claimed.single()
        assertThat(c.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
        assertThat(c.attemptCount).isEqualTo(1)
        assertThat(c.claimedBy).isEqualTo("worker-1")
        assertThat(c.claimedAt).isEqualTo(t0)
        assertThat(c.claimExpiresAt).isEqualTo(t0.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY))
    }

    @Test
    fun `claim FAILED_RETRYABLE moves to EMITTING with new claimant`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "claim-2")
        store.markFailed("claim-2", SovereignOpsAuditOutboxStatus.EMITTING, "timeout", retryable = true)
        val claimed = store.claimPending("worker-2", 10, t0.plusSeconds(60))
        val c = claimed.single()
        assertThat(c.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
        assertThat(c.attemptCount).isEqualTo(2)
        assertThat(c.claimedBy).isEqualTo("worker-2")
        assertThat(c.claimedAt).isEqualTo(t0.plusSeconds(60))
        assertThat(c.claimExpiresAt).isEqualTo(t0.plusSeconds(60).plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY))
    }

    @Test
    fun `retry claim clears lastErrorCode`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "claim-3")
        store.markFailed("claim-3", SovereignOpsAuditOutboxStatus.EMITTING, "timeout", retryable = true)
        assertThat(store.get("claim-3")?.lastErrorCode).isEqualTo("timeout")
        val claimed = store.claimPending("worker-3", 10, t0.plusSeconds(60))
        assertThat(claimed.single().lastErrorCode).isNull()
        assertThat(store.get("claim-3")?.lastErrorCode).isNull()
    }

    @Test
    fun `expired EMITTING reclaim replaces claimant and increments attempt`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "claim-4")
        val expiry = t0.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY)
        val reclaimAt = expiry.plusSeconds(1)
        val claimed = store.claimPending("worker-2", 10, reclaimAt)
        val c = claimed.single()
        assertThat(c.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
        assertThat(c.attemptCount).isEqualTo(2)
        assertThat(c.claimedBy).isEqualTo("worker-2")
        assertThat(c.claimedAt).isEqualTo(reclaimAt)
        assertThat(c.claimExpiresAt).isEqualTo(reclaimAt.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY))
    }

    @Test
    fun `lease boundary at exact expiry is not reclaimable`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "lease-1")
        val expiry = t0.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY)
        val claimed = store.claimPending("worker-2", 10, expiry)
        assertThat(claimed).isEmpty()
    }

    @Test
    fun `lease boundary one second past expiry is reclaimable`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "lease-2")
        val expiry = t0.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY)
        val claimed = store.claimPending("worker-2", 10, expiry.plusSeconds(1))
        assertThat(claimed).hasSize(1)
    }

    @Test
    fun `PREPARED is never claimable`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("never-1"))
        assertThat(store.claimPending("worker-1", 10, t0)).isEmpty()
    }

    @Test
    fun `EMITTED is never claimable`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "never-2")
        store.markEmitted("never-2", SovereignOpsAuditOutboxStatus.EMITTING, t0)
        assertThat(store.claimPending("worker-1", 10, t0.plusSeconds(3600))).isEmpty()
    }

    @Test
    fun `FAILED_PERMANENT is never claimable`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("never-3"))
        store.markFailed("never-3", SovereignOpsAuditOutboxStatus.PREPARED, "fatal", retryable = false)
        assertThat(store.claimPending("worker-1", 10, t0.plusSeconds(3600))).isEmpty()
    }

    @Test
    fun `fresh EMITTING is never claimable`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "never-4")
        assertThat(store.claimPending("worker-1", 10, t0.plusSeconds(60))).isEmpty()
    }

    // ── F. Diagnostic listing ───────────────────────────────────────

    @Test
    fun `listPending returns PENDING records only`() = runBlocking<Unit> {
        val store = createStore()
        // A FAILED_RETRYABLE record must never appear in listPending.
        store.append(record("list-retry"))
        store.markReadyForDispatch("list-retry", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("worker-1", 10, t0)
        store.markFailed("list-retry", SovereignOpsAuditOutboxStatus.EMITTING, "boom", retryable = true)
        store.append(record("list-1"))
        store.markReadyForDispatch("list-1", SovereignOpsAuditOutboxStatus.PREPARED)
        store.append(record("list-2"))
        store.append(record("list-3"))
        store.append(record("list-4"))
        store.markReadyForDispatch("list-4", SovereignOpsAuditOutboxStatus.PREPARED)
        val pending = store.listPending(10)
        assertThat(pending.map { it.outboxId }).containsExactlyInAnyOrder("list-1", "list-4")
        assertThat(pending.all { it.status == SovereignOpsAuditOutboxStatus.PENDING }).isTrue()
    }

    @Test
    fun `listByStatus returns the exact status only`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("list-5"))
        store.markReadyForDispatch("list-5", SovereignOpsAuditOutboxStatus.PREPARED)
        store.append(record("list-6"))
        store.append(record("list-7"))
        val prepared = store.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10)
        assertThat(prepared.map { it.outboxId }).containsExactlyInAnyOrder("list-6", "list-7")
        val pending = store.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)
        assertThat(pending.map { it.outboxId }).containsExactly("list-5")
        assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.EMITTING, 10)).isEmpty()
        assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE, 10)).isEmpty()
    }

    @Test
    fun `listExpiredEmitting returns only expired EMITTING records`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "list-8")
        store.append(record("list-9"))
        store.markReadyForDispatch("list-9", SovereignOpsAuditOutboxStatus.PREPARED)
        val now = t0.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY).plusSeconds(1)
        val expired = store.listExpiredEmitting(now, 10)
        assertThat(expired.map { it.outboxId }).containsExactly("list-8")
    }

    @Test
    fun `list limits cap the result size`() = runBlocking<Unit> {
        val store = createStore()
        repeat(5) { i ->
            store.append(record("cap-$i"))
            store.markReadyForDispatch("cap-$i", SovereignOpsAuditOutboxStatus.PREPARED)
        }
        assertThat(store.listPending(2).size).isLessThanOrEqualTo(2)
        assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 3).size).isLessThanOrEqualTo(3)
        assertThat(store.claimPending("worker-1", 4, t0).size).isLessThanOrEqualTo(4)
        assertThat(store.listExpiredEmitting(t0.plusSeconds(3600), 1).size).isLessThanOrEqualTo(1)
    }

    @Test
    fun `non-positive limits return empty on every diagnostic path`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("limit-1"))
        store.markReadyForDispatch("limit-1", SovereignOpsAuditOutboxStatus.PREPARED)
        for (limit in listOf(0, -1)) {
            assertThat(store.listPending(limit)).isEmpty()
            assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, limit)).isEmpty()
            assertThat(store.claimPending("worker-1", limit, t0)).isEmpty()
            assertThat(store.listExpiredEmitting(t0, limit)).isEmpty()
        }
    }

    @Test
    fun `listExpiredEmitting excludes the exact expiry boundary`() = runBlocking<Unit> {
        val store = createStore()
        emittingRecord(store, "boundary-1")
        val expiry = t0.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY)
        assertThat(store.listExpiredEmitting(expiry, 10)).isEmpty()
        assertThat(store.listExpiredEmitting(expiry.plusSeconds(1), 10)).hasSize(1)
    }

    // ── G. Lookup reflects transitions ──────────────────────────────

    @Test
    fun `findByEventKey reflects the current transitioned version`() = runBlocking<Unit> {
        val store = createStore()
        store.append(record("trans-1", eventKey = "key-trans-1"))
        store.markReadyForDispatch("trans-1", SovereignOpsAuditOutboxStatus.PREPARED)
        assertThat(store.findByEventKey("key-trans-1")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
    }

    // ── H. Concurrency ──────────────────────────────────────────────

    @Test
    fun `concurrent duplicate outboxId - exactly one winner`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val outcomes = runInParallel(0 until 8) {
                runCatching { store.append(record("race-id-$round")) }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one append winner, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull()?.message }.toSet())
                .containsExactly("tramai-sovereign-ops-outbox-duplicate-id")
            assertThat(store.get("race-id-$round")).isNotNull()
        }
    }

    @Test
    fun `concurrent duplicate eventKey - loser records are rolled back`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val ids = (0 until 8).map { "race-key-$round-$it" }
            val outcomes = runInParallel(ids) { id ->
                runCatching { store.append(record(id, eventKey = "race-shared-key-$round")) }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one event-key winner, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            val winnerId = ids[outcomes.indexOfFirst { it.isSuccess }]
            assertThat(store.findByEventKey("race-shared-key-$round")?.outboxId).isEqualTo(winnerId)
            for (id in ids) {
                if (id != winnerId) {
                    assertThat(store.get(id)).withFailMessage { "round $round: loser $id still present" }.isNull()
                }
            }
        }
    }

    @Test
    fun `concurrent claims of one PENDING record - exactly one winner`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            store.append(record("race-claim-$round"))
            store.markReadyForDispatch("race-claim-$round", SovereignOpsAuditOutboxStatus.PREPARED)
            val outcomes = runInParallel(0 until 8) { index ->
                store.claimPending("worker-$index", 1, t0)
            }
            val claimed = outcomes.flatten()
            assertThat(claimed).withFailMessage {
                "round $round: expected exactly one claimed record total, got ${claimed.size}"
            }.hasSize(1)
            val final = store.get("race-claim-$round")!!
            assertThat(final.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
            assertThat(final.attemptCount).isEqualTo(1)
            assertThat(claimed.single().claimedBy).isEqualTo(final.claimedBy)
        }
    }

    @Test
    fun `concurrent pool claim - every record claimed exactly once`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val ids = (0 until 8).map { "race-pool-$round-$it" }
            for (id in ids) {
                store.append(record(id))
                store.markReadyForDispatch(id, SovereignOpsAuditOutboxStatus.PREPARED)
            }
            val outcomes = runInParallel(0 until 8) { index ->
                store.claimPending("worker-$index", 10, t0)
            }
            val claimedIds = outcomes.flatten().map { it.outboxId }
            assertThat(claimedIds.toSet()).withFailMessage {
                "round $round: duplicate claim of the same record: $claimedIds"
            }.hasSize(claimedIds.size)
            assertThat(claimedIds.toSet()).containsExactlyInAnyOrder(*ids.toTypedArray())
            for (id in ids) {
                assertThat(store.get(id)?.attemptCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `concurrent completion - exactly one winner, final EMITTED`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val id = "race-emit-$round"
            emittingRecord(store, id)
            val outcomes = runInParallel(0 until 2) { index ->
                runCatching {
                    store.markEmitted(id, SovereignOpsAuditOutboxStatus.EMITTING, t0.plusSeconds(index.toLong()))
                }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one markEmitted winner, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            assertThat(store.get(id)?.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
        }
    }

    // ── Parallel-race helper (shared pattern from #269/#270/#271) ───

    /**
     * Runs [block] for every element of [items] on real parallel workers with
     * a start barrier, returning the outcomes in input order. The barrier
     * gives every contender a scheduling opportunity before any of them is
     * allowed to proceed.
     */
    private suspend fun <T, R> runInParallel(
        items: List<T>,
        block: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val ready = Channel<Unit>(items.size)
        val release = CompletableDeferred<Unit>()
        val workers = items.map { item ->
            async(Dispatchers.Default) {
                ready.send(Unit)
                release.await()
                block(item)
            }
        }
        repeat(items.size) { ready.receive() }
        release.complete(Unit)
        workers.map { it.await() }
    }

    /** Range convenience overload for the race loops. */
    private suspend fun <R> runInParallel(
        range: IntRange,
        block: suspend (Int) -> R,
    ): List<R> = runInParallel(range.toList(), block)
}
