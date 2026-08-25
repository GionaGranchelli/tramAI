package dev.tramai.testing.persistence.lease

import dev.tramai.orchestration.WorkflowLease
import dev.tramai.orchestration.WorkflowLeaseConflictException
import dev.tramai.orchestration.WorkflowLeaseStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1g: the shared, cross-implementation
 * [dev.tramai.orchestration.WorkflowLeaseStore] compatibility contract.
 *
 * The property the contract enforces: for one (workflowName, workflowId),
 * at any instant there is at most one active lease token capable of
 * authorizing mutations, and storage technology cannot change that answer.
 * Identity → claim ownership → time-bound validity → renewal/takeover.
 *
 * Expiry boundary (authoritative): `expiresAt > now` → active;
 * `expiresAt <= now` → expired. The durable stored lease is authoritative;
 * the caller's lease object is a capability/token snapshot, never writable
 * lease metadata.
 */
abstract class WorkflowLeaseStoreTck {

    /** Fresh isolated storage + deterministic clock per case; runner owns cleanup. */
    protected abstract fun createStore(clock: MutableMillisClock): WorkflowLeaseStore

    private fun assertConflict(thrown: Throwable?) {
        assertThat(thrown).isInstanceOf(WorkflowLeaseConflictException::class.java)
        assertThat(thrown?.message).isEqualTo("Workflow lease conflict")
        assertThat(thrown?.cause).isNull()
    }

    // ── A. Claim / identity ─────────────────────────────────────────

    @Test
    fun `missing lease loads null`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        assertThat(store.currentLease("no-such", "never")).isNull()
    }

    @Test
    fun `claim returns an active lease`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val lease = store.claim("invoice-review", "run-001", "worker-7", checkpointRevision = 3, leaseDurationMillis = 1_000)
        assertThat(lease).isNotNull
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(lease)
    }

    @Test
    fun `claim preserves exact workflow name and id`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val lease = store.claim("invoice-review", "run-001", "worker-7", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(lease.workflowName).isEqualTo("invoice-review")
        assertThat(lease.workflowId).isEqualTo("run-001")
    }

    @Test
    fun `claim preserves exact owner`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val lease = store.claim("invoice-review", "run-001", "worker-42", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(lease.ownerId).isEqualTo("worker-42")
    }

    @Test
    fun `claim preserves nullable checkpoint revision`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val lease = store.claim("invoice-review", "run-001", "worker-7", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(lease.checkpointRevision).isNull()
        assertThat(store.currentLease("invoice-review", "run-001")?.checkpointRevision).isNull()
    }

    @Test
    fun `claim sets acquiredAt to now`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val lease = store.claim("invoice-review", "run-001", "worker-7", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(lease.acquiredAtEpochMillis).isEqualTo(clock())
    }

    @Test
    fun `claim sets expiresAt to now plus duration`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val lease = store.claim("invoice-review", "run-001", "worker-7", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(lease.expiresAtEpochMillis).isEqualTo(clock() + 1_000)
    }

    @Test
    fun `lease id is nonblank`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val lease = store.claim("invoice-review", "run-001", "worker-7", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(lease.leaseId).isNotBlank()
    }

    @Test
    fun `same workflow name different ids stay independent`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val a = store.claim("shared", "id-a", "w-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        val b = store.claim("shared", "id-b", "w-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(store.currentLease("shared", "id-a")).isEqualTo(a)
        assertThat(store.currentLease("shared", "id-b")).isEqualTo(b)
    }

    @Test
    fun `same workflow id different names stay independent`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val a = store.claim("name-a", "shared-id", "w-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        val b = store.claim("name-b", "shared-id", "w-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(store.currentLease("name-a", "shared-id")).isEqualTo(a)
        assertThat(store.currentLease("name-b", "shared-id")).isEqualTo(b)
    }

    @Test
    fun `second active claim by another owner conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching {
            store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull())
    }

    @Test
    fun `second active claim by the same owner conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching {
            store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull())
    }

    @Test
    fun `rejected active claim leaves the original unchanged`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val original = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = 7, leaseDurationMillis = 1_000)
        runCatching {
            store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = 9, leaseDurationMillis = 1_000)
        }
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(original)
    }

    @Test
    fun `legacy-colliding identities can own active leases simultaneously`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        // DefaultWorkflowCheckpointPathStrategy maps both to "order_a".
        val a = store.claim("order", "a/b", "w-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        val b = store.claim("order", "a?b", "w-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(store.currentLease("order", "a/b")).isEqualTo(a)
        assertThat(store.currentLease("order", "a?b")).isEqualTo(b)
        assertThat(store.currentLease("order", "a/b")).isNotEqualTo(store.currentLease("order", "a?b"))
    }

    // ── B. Exact expiry semantics ───────────────────────────────────

    @Test
    fun `one millisecond before expiry the lease is active`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val lease = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(999)
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(lease)
    }

    @Test
    fun `at exact expiry the lease is gone`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(1_000)
        assertThat(store.currentLease("invoice-review", "run-001")).isNull()
    }

    @Test
    fun `claim one millisecond before expiry conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(999)
        assertConflict(runCatching {
            store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull())
    }

    @Test
    fun `claim at exact expiry succeeds`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(1_000)
        val takeover = store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(takeover).isNotNull
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(takeover)
    }

    @Test
    fun `takeover gets the new owner`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        val takeover = store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(takeover.ownerId).isEqualTo("worker-2")
    }

    @Test
    fun `takeover gets a new acquisition time`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        val takeover = store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(takeover.acquiredAtEpochMillis).isEqualTo(clock())
    }

    @Test
    fun `takeover gets a fresh expiry`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        val takeover = store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(takeover.expiresAtEpochMillis).isEqualTo(clock() + 1_000)
    }

    @Test
    fun `stale old lease cannot renew after takeover`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val old = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching {
            store.renew(old, checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull())
    }

    @Test
    fun `stale old lease cannot release the new owners lease`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val old = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        val takeover = store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching { store.release(old) }.exceptionOrNull())
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(takeover)
    }

    @Test
    fun `same owner taking over after expiry receives a new fencing token`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val first = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        val second = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(second.leaseId).isNotEqualTo(first.leaseId)
    }

    // ── C. Renewal contract ─────────────────────────────────────────

    @Test
    fun `successful renew preserves identity and updates revision and expiry`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = 1, leaseDurationMillis = 1_000)
        clock.advance(250)
        val renewed = store.renew(claimed, checkpointRevision = 7, leaseDurationMillis = 1_000)
        assertThat(renewed.workflowName).isEqualTo(claimed.workflowName)
        assertThat(renewed.workflowId).isEqualTo(claimed.workflowId)
        assertThat(renewed.leaseId).isEqualTo(claimed.leaseId)
        assertThat(renewed.ownerId).isEqualTo(claimed.ownerId)
        assertThat(renewed.acquiredAtEpochMillis).isEqualTo(claimed.acquiredAtEpochMillis)
        assertThat(renewed.checkpointRevision).isEqualTo(7)
        assertThat(renewed.expiresAtEpochMillis).isEqualTo(clock() + 1_000)
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(renewed)
    }

    @Test
    fun `renew expiry is based on renewal time not old expiry`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(500)
        val renewed = store.renew(claimed, checkpointRevision = null, leaseDurationMillis = 5_000)
        assertThat(renewed.expiresAtEpochMillis).isEqualTo(clock() + 5_000)
    }

    @Test
    fun `caller-tampered acquired and expiry times are not authoritative`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = 1, leaseDurationMillis = 1_000)
        val callerSnapshot = claimed.copy(acquiredAtEpochMillis = 42, expiresAtEpochMillis = 43)
        clock.advance(200)
        val renewed = store.renew(callerSnapshot, checkpointRevision = 7, leaseDurationMillis = 5_000)
        assertThat(renewed.acquiredAtEpochMillis).isEqualTo(claimed.acquiredAtEpochMillis)
        assertThat(renewed).isEqualTo(store.currentLease("invoice-review", "run-001"))
    }

    @Test
    fun `renewal can set checkpoint revision to null`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = 5, leaseDurationMillis = 1_000)
        val renewed = store.renew(claimed, checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(renewed.checkpointRevision).isNull()
    }

    @Test
    fun `renewal can set checkpoint revision from null to a value`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        val renewed = store.renew(claimed, checkpointRevision = 9, leaseDurationMillis = 1_000)
        assertThat(renewed.checkpointRevision).isEqualTo(9)
    }

    @Test
    fun `renew with wrong lease id conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching {
            store.renew(claimed.copy(leaseId = "other-token"), checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull())
    }

    @Test
    fun `renew with wrong owner conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching {
            store.renew(claimed.copy(ownerId = "intruder"), checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull())
    }

    @Test
    fun `renew of a missing lease conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        assertConflict(runCatching {
            store.renew(
                WorkflowLeaseFixtures.lease(),
                checkpointRevision = null,
                leaseDurationMillis = 1_000,
            )
        }.exceptionOrNull())
    }

    @Test
    fun `renew at exact expiry conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(1_000)
        assertConflict(runCatching {
            store.renew(claimed, checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull())
    }

    @Test
    fun `rejected renew is non-mutating`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = 3, leaseDurationMillis = 1_000)
        runCatching { store.renew(claimed.copy(leaseId = "wrong"), checkpointRevision = 99, leaseDurationMillis = 1_000) }
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(claimed)
    }

    // ── D. Release contract ─────────────────────────────────────────

    @Test
    fun `release with the active correct token removes the lease`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        store.release(claimed)
        assertThat(store.currentLease("invoice-review", "run-001")).isNull()
    }

    @Test
    fun `release of a missing lease is a no-op`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        store.release(WorkflowLeaseFixtures.lease())
        assertThat(store.currentLease("invoice-review", "run-001")).isNull()
    }

    @Test
    fun `release of an expired lease is a no-op`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        store.release(claimed)
        assertThat(store.currentLease("invoice-review", "run-001")).isNull()
    }

    @Test
    fun `release with the wrong lease token conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching { store.release(claimed.copy(leaseId = "other-token")) }.exceptionOrNull())
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(claimed)
    }

    @Test
    fun `release with the wrong owner conflicts`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching { store.release(claimed.copy(ownerId = "intruder")) }.exceptionOrNull())
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(claimed)
    }

    @Test
    fun `stale predecessor cannot release the successor`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val old = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        val takeover = store.claim("invoice-review", "run-001", "worker-2", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertConflict(runCatching { store.release(old) }.exceptionOrNull())
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(takeover)
    }

    @Test
    fun `same owner stale predecessor cannot release the successor`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val old = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        clock.advance(2_000)
        val takeover = store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        assertThat(takeover.leaseId).isNotEqualTo(old.leaseId)
        assertConflict(runCatching { store.release(old) }.exceptionOrNull())
        assertThat(store.currentLease("invoice-review", "run-001")).isEqualTo(takeover)
    }

    // ── E. Input-domain hardening ───────────────────────────────────

    @Test
    fun `blank workflow name is a caller error`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        assertThat(runCatching {
            store.claim("   ", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank workflow id is a caller error`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        assertThat(runCatching {
            store.claim("invoice-review", "", "worker-1", checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank owner id is a caller error`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        assertThat(runCatching {
            store.claim("invoice-review", "run-001", "\t", checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `zero lease duration is a caller error`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        assertThat(runCatching {
            store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = 0)
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative lease duration is a caller error`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        assertThat(runCatching {
            store.claim("invoice-review", "run-001", "worker-1", checkpointRevision = null, leaseDurationMillis = -5)
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank lease id on renew and release is a caller error`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val blank = WorkflowLeaseFixtures.lease(leaseId = " ")
        assertThat(runCatching {
            store.renew(blank, checkpointRevision = null, leaseDurationMillis = 1_000)
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { store.release(blank) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── F. Concurrency ──────────────────────────────────────────────

    @Test
    fun `initial claim race - exactly one winner`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            val store = createStore(clock)
            val name = "race-claim-$round"
            val outcomes = runInParallel(0 until 8) { index ->
                runCatching {
                    store.claim(name, "wf", "worker-$index", checkpointRevision = null, leaseDurationMillis = 1_000)
                }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one claim winner, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.name }.toSet())
                .containsExactly(WorkflowLeaseConflictException::class.java.name)
            assertThat(store.currentLease(name, "wf")).isNotNull
        }
    }

    @Test
    fun `expired takeover race - exactly one takeover`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            val store = createStore(clock)
            val name = "race-takeover-$round"
            store.claim(name, "wf", "worker-old", checkpointRevision = null, leaseDurationMillis = 1_000)
            clock.advance(2_000)
            val outcomes = runInParallel(0 until 8) { index ->
                runCatching {
                    store.claim(name, "wf", "worker-new-$index", checkpointRevision = null, leaseDurationMillis = 1_000)
                }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one takeover, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.name }.toSet())
                .containsExactly(WorkflowLeaseConflictException::class.java.name)
            val winnerOwner = "worker-new-${outcomes.indexOfFirst { it.isSuccess }}"
            assertThat(store.currentLease(name, "wf")?.ownerId).isEqualTo(winnerOwner)
        }
    }

    @Test
    fun `renewal versus takeover at exact expiry - takeover wins`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            val store = createStore(clock)
            val name = "race-boundary-$round"
            val old = store.claim(name, "wf", "worker-old", checkpointRevision = null, leaseDurationMillis = 1_000)
            clock.advance(1_000)
            val outcomes = runInParallel(0 until 2) { index ->
                if (index == 0) {
                    runCatching { store.renew(old, checkpointRevision = 5, leaseDurationMillis = 1_000) }
                } else {
                    runCatching {
                        store.claim(name, "wf", "worker-new", checkpointRevision = null, leaseDurationMillis = 1_000)
                    }
                }
            }
            assertThat(outcomes[0].isFailure).withFailMessage {
                "round $round: renew at exact expiry must conflict, got $outcomes"
            }.isTrue
            assertThat(outcomes[1].isSuccess).withFailMessage {
                "round $round: claim at exact expiry must succeed, got $outcomes"
            }.isTrue
            assertThat(store.currentLease(name, "wf")?.ownerId).isEqualTo("worker-new")
        }
    }

    @Test
    fun `colliding logical keys claim in parallel without interference`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            val store = createStore(clock)
            val name = "order"
            val outcomes = runInParallel(listOf("a/b" to "w-1", "a?b" to "w-2")) { (id, owner) ->
                runCatching { store.claim(name, id, owner, checkpointRevision = null, leaseDurationMillis = 1_000) }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: both colliding keys must claim independently, got $outcomes"
            }.isEqualTo(2)
            assertThat(store.currentLease(name, "a/b")?.ownerId).isEqualTo("w-1")
            assertThat(store.currentLease(name, "a?b")?.ownerId).isEqualTo("w-2")
        }
    }

    // ── G. Lease lifecycle state-machine properties ────────────────

    @Test
    fun `generated lease histories match the lifecycle model`() = runBlocking<Unit> {
        for (seed in 0L until WorkflowLeaseLifecycleActionGenerator.SEED_COUNT) {
            val clock = MutableMillisClock()
            val store = createStore(clock)
            val workflowName = "state-machine-$seed"
            val workflowId = "workflow-$seed"
            val bindings = linkedMapOf<String, MutableList<WorkflowLease>>()
            var currentLeaseObject: WorkflowLease? = null
            var model = WorkflowLeaseLifecycleModel.absent(clock())
            val actions = WorkflowLeaseLifecycleActionGenerator.generate(seed, initialNow = clock())

            actions.forEachIndexed { step, action ->
                val before = model
                val storedBefore = store.currentLease(workflowName, workflowId)
                val expected = model.apply(action, LIFECYCLE_DURATION_MILLIS)

                fun fallbackCapability(): WorkflowLease = currentLeaseObject
                    ?: bindings.values.lastOrNull()?.lastOrNull()
                    ?: WorkflowLeaseFixtures.lease(
                        workflowName = workflowName,
                        workflowId = workflowId,
                        leaseId = "missing-token",
                        ownerId = "worker-a",
                        acquiredAtEpochMillis = clock(),
                        expiresAtEpochMillis = clock() + LIFECYCLE_DURATION_MILLIS,
                    )

                fun currentCapability(): WorkflowLease {
                    val token = before.current?.symbolicToken
                    return token?.let { bindings[it]?.lastOrNull() } ?: fallbackCapability()
                }

                fun olderCurrentSnapshot(): WorkflowLease {
                    val token = before.current?.symbolicToken
                    return token?.let { bindings[it]?.firstOrNull() } ?: fallbackCapability()
                }

                fun predecessorCapability(generation: Long): WorkflowLease =
                    bindings["T$generation"]?.firstOrNull() ?: fallbackCapability()

                val actual: Result<WorkflowLease?> = runCatching {
                    when (action) {
                        is WorkflowLeaseLifecycleAction.Claim -> store.claim(
                            workflowName = workflowName,
                            workflowId = workflowId,
                            ownerId = action.ownerId,
                            checkpointRevision = action.checkpointRevision,
                            leaseDurationMillis = LIFECYCLE_DURATION_MILLIS,
                        )
                        is WorkflowLeaseLifecycleAction.RenewCurrent -> store.renew(
                            currentCapability(), action.checkpointRevision, LIFECYCLE_DURATION_MILLIS,
                        )
                        is WorkflowLeaseLifecycleAction.RenewCurrentOldSnapshot -> store.renew(
                            olderCurrentSnapshot(), action.checkpointRevision, LIFECYCLE_DURATION_MILLIS,
                        )
                        is WorkflowLeaseLifecycleAction.RenewStalePredecessor -> store.renew(
                            predecessorCapability(action.targetGeneration),
                            checkpointRevision = null,
                            leaseDurationMillis = LIFECYCLE_DURATION_MILLIS,
                        )
                        is WorkflowLeaseLifecycleAction.RenewWrongOwner -> store.renew(
                            currentCapability().copy(ownerId = "intruder"),
                            checkpointRevision = null,
                            leaseDurationMillis = LIFECYCLE_DURATION_MILLIS,
                        )
                        is WorkflowLeaseLifecycleAction.RenewForgedToken -> store.renew(
                            currentCapability().copy(leaseId = "forged-token"),
                            checkpointRevision = null,
                            leaseDurationMillis = LIFECYCLE_DURATION_MILLIS,
                        )
                        WorkflowLeaseLifecycleAction.ReleaseCurrent -> {
                            store.release(currentCapability())
                            null
                        }
                        WorkflowLeaseLifecycleAction.ReleaseCurrentOldSnapshot -> {
                            store.release(olderCurrentSnapshot())
                            null
                        }
                        is WorkflowLeaseLifecycleAction.ReleaseStalePredecessor -> {
                            store.release(predecessorCapability(action.targetGeneration))
                            null
                        }
                        is WorkflowLeaseLifecycleAction.ReleaseWrongOwner -> {
                            store.release(currentCapability().copy(ownerId = "intruder"))
                            null
                        }
                        is WorkflowLeaseLifecycleAction.ReleaseForgedToken -> {
                            store.release(currentCapability().copy(leaseId = "forged-token"))
                            null
                        }
                        WorkflowLeaseLifecycleAction.AdvanceBeforeExpiry,
                        WorkflowLeaseLifecycleAction.AdvanceToExactExpiry,
                        WorkflowLeaseLifecycleAction.AdvancePastExpiry,
                        -> {
                            val next = (expected as WorkflowLeaseLifecycleOutcome.Success).next
                            clock.set(next.now)
                            null
                        }
                        WorkflowLeaseLifecycleAction.ObserveCurrent -> store.currentLease(workflowName, workflowId)
                    }
                }

                val context = "seed=$seed step=$step action=${action.describe()} before=${before.describe()}"
                when (expected) {
                    is WorkflowLeaseLifecycleOutcome.Success -> {
                        assertThat(actual.exceptionOrNull()).withFailMessage(context).isNull()
                        when (action) {
                            is WorkflowLeaseLifecycleAction.Claim -> {
                                val returned = requireNotNull(actual.getOrNull())
                                val expectedLease = requireNotNull(expected.next.current)
                                val priorLeaseIds = bindings.values.flatten().map { it.leaseId }.toSet()
                                assertThat(returned.leaseId)
                                    .withFailMessage("$context must allocate a fresh generation token")
                                    .isNotIn(priorLeaseIds)
                                bindings.getOrPut(expectedLease.symbolicToken) { arrayListOf() }.add(returned)
                                currentLeaseObject = returned
                            }
                            is WorkflowLeaseLifecycleAction.RenewCurrent,
                            is WorkflowLeaseLifecycleAction.RenewCurrentOldSnapshot,
                            -> {
                                val returned = requireNotNull(actual.getOrNull())
                                val expectedLease = requireNotNull(expected.next.current)
                                bindings.getOrPut(expectedLease.symbolicToken) { arrayListOf() }.add(returned)
                                currentLeaseObject = returned
                            }
                            WorkflowLeaseLifecycleAction.ReleaseCurrent,
                            WorkflowLeaseLifecycleAction.ReleaseCurrentOldSnapshot,
                            -> {
                                assertThat(actual.getOrNull()).withFailMessage(context).isNull()
                                currentLeaseObject = null
                            }
                            WorkflowLeaseLifecycleAction.AdvanceBeforeExpiry,
                            WorkflowLeaseLifecycleAction.AdvanceToExactExpiry,
                            WorkflowLeaseLifecycleAction.AdvancePastExpiry,
                            -> if (expected.next.current == null) currentLeaseObject = null
                            WorkflowLeaseLifecycleAction.ObserveCurrent -> Unit
                            else -> error("$context unexpectedly succeeded")
                        }
                        model = expected.next
                    }
                    is WorkflowLeaseLifecycleOutcome.NoOp -> {
                        assertThat(actual.exceptionOrNull()).withFailMessage(context).isNull()
                        assertThat(actual.getOrNull()).withFailMessage(context).isNull()
                        model = expected.next
                        currentLeaseObject = null
                    }
                    is WorkflowLeaseLifecycleOutcome.Failure -> {
                        assertThat(expected.kind).isEqualTo(WorkflowLeaseLifecycleFailureKind.CONFLICT)
                        assertConflict(actual.exceptionOrNull())
                        assertThat(store.currentLease(workflowName, workflowId))
                            .withFailMessage("$context conflict must not mutate the current lease")
                            .isEqualTo(storedBefore)
                    }
                }

                val violations = model.invariants()
                assertThat(violations)
                    .withFailMessage("$context after=${model.describe()} violations=$violations")
                    .isEmpty()
                assertLifecycleStoreMatchesModel(
                    store = store,
                    workflowName = workflowName,
                    workflowId = workflowId,
                    model = model,
                    bindings = bindings,
                    currentLeaseObject = currentLeaseObject,
                    context = context,
                )
            }

            if (model.current == null) {
                assertThat(store.currentLease(workflowName, workflowId))
                    .withFailMessage("seed=$seed terminal absent model must have no durable lease")
                    .isNull()
            }
        }
    }

    @Test
    fun `every predecessor stays fenced after multiple generations`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val name = "multi-generation-fence"
        val id = "workflow"
        val t1 = store.claim(name, id, "worker-a", null, LIFECYCLE_DURATION_MILLIS)
        clock.advance(LIFECYCLE_DURATION_MILLIS + 1)
        val t2 = store.claim(name, id, "worker-b", null, LIFECYCLE_DURATION_MILLIS)
        clock.advance(LIFECYCLE_DURATION_MILLIS + 1)
        val t3 = store.claim(name, id, "worker-a", null, LIFECYCLE_DURATION_MILLIS)
        clock.advance(LIFECYCLE_DURATION_MILLIS + 1)
        val t4 = store.claim(name, id, "worker-b", null, LIFECYCLE_DURATION_MILLIS)

        assertThat(store.currentLease(name, id)).isEqualTo(t4)
        for (predecessor in listOf(t1, t2, t3)) {
            assertConflict(runCatching {
                store.renew(predecessor, checkpointRevision = 9, leaseDurationMillis = LIFECYCLE_DURATION_MILLIS)
            }.exceptionOrNull())
            assertConflict(runCatching { store.release(predecessor) }.exceptionOrNull())
            assertThat(store.currentLease(name, id)).isEqualTo(t4)
        }
    }

    @Test
    fun `same owner reincarnation is a new generation`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val first = store.claim("same-owner-generation", "workflow", "worker-a", null, LIFECYCLE_DURATION_MILLIS)
        clock.advance(LIFECYCLE_DURATION_MILLIS + 1)
        val second = store.claim("same-owner-generation", "workflow", "worker-a", null, LIFECYCLE_DURATION_MILLIS)

        assertThat(second.leaseId).isNotEqualTo(first.leaseId)
        assertConflict(runCatching {
            store.renew(first, checkpointRevision = 1, leaseDurationMillis = LIFECYCLE_DURATION_MILLIS)
        }.exceptionOrNull())
        assertConflict(runCatching { store.release(first) }.exceptionOrNull())
        assertThat(store.currentLease("same-owner-generation", "workflow")).isEqualTo(second)
    }

    @Test
    fun `renewal does not create a new generation`() = runBlocking<Unit> {
        val clock = MutableMillisClock()
        val store = createStore(clock)
        val claimed = store.claim("renew-generation", "workflow", "worker-a", null, LIFECYCLE_DURATION_MILLIS)
        clock.advance(100)
        val revisionOne = store.renew(claimed, 1, LIFECYCLE_DURATION_MILLIS)
        clock.advance(100)
        val revisionSeven = store.renew(revisionOne, 7, LIFECYCLE_DURATION_MILLIS)
        clock.advance(100)
        val revisionNull = store.renew(revisionSeven, null, LIFECYCLE_DURATION_MILLIS)
        val chain = listOf(claimed, revisionOne, revisionSeven, revisionNull)

        assertThat(chain.map { it.leaseId }.toSet()).containsExactly(claimed.leaseId)
        assertThat(chain.map { it.ownerId }.toSet()).containsExactly(claimed.ownerId)
        assertThat(chain.map { it.acquiredAtEpochMillis }.toSet()).containsExactly(claimed.acquiredAtEpochMillis)
        assertThat(chain.map { it.checkpointRevision }).containsExactly(null, 1L, 7L, null)
        assertThat(chain.map { it.expiresAtEpochMillis }).containsExactly(
            claimed.expiresAtEpochMillis,
            claimed.expiresAtEpochMillis + 100,
            claimed.expiresAtEpochMillis + 200,
            claimed.expiresAtEpochMillis + 300,
        )
        assertThat(store.currentLease("renew-generation", "workflow")).isEqualTo(revisionNull)
    }

    // ── H. Lifecycle concurrency properties ────────────────────────

    @Test
    fun `concurrent renew versus release has one legal serialization`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            val store = createStore(clock)
            val name = "renew-release-$round"
            val lease = store.claim(name, "workflow", "worker-a", null, LIFECYCLE_DURATION_MILLIS)
            val outcomes: List<Result<WorkflowLease?>> = runInParallel(0 until 2) { index ->
                if (index == 0) {
                    runCatching { store.renew(lease, checkpointRevision = 1, leaseDurationMillis = LIFECYCLE_DURATION_MILLIS) }
                } else {
                    runCatching {
                        store.release(lease)
                        null
                    }
                }
            }

            assertThat(outcomes[1].isSuccess)
                .withFailMessage("round $round: release must succeed, outcomes=$outcomes")
                .isTrue()
            if (outcomes[0].isFailure) assertConflict(outcomes[0].exceptionOrNull())
            assertThat(store.currentLease(name, "workflow"))
                .withFailMessage("round $round: renew must never resurrect after release")
                .isNull()
        }
    }

    @Test
    fun `exact expiry takeover versus old release cannot destroy successor`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            val store = createStore(clock)
            val name = "takeover-release-$round"
            val old = store.claim(name, "workflow", "worker-old", null, LIFECYCLE_DURATION_MILLIS)
            clock.advance(LIFECYCLE_DURATION_MILLIS)
            val outcomes: List<Result<WorkflowLease?>> = runInParallel(0 until 2) { index ->
                if (index == 0) {
                    runCatching {
                        store.claim(name, "workflow", "worker-new", null, LIFECYCLE_DURATION_MILLIS)
                    }
                } else {
                    runCatching {
                        store.release(old)
                        null
                    }
                }
            }

            assertThat(outcomes[0].isSuccess)
                .withFailMessage("round $round: exact-expiry takeover must succeed, outcomes=$outcomes")
                .isTrue()
            if (outcomes[1].isFailure) assertConflict(outcomes[1].exceptionOrNull())
            assertThat(store.currentLease(name, "workflow")?.ownerId)
                .withFailMessage("round $round: old release must not destroy successor")
                .isEqualTo("worker-new")
        }
    }

    // ── Parallel-race helper (shared pattern from #269-#273) ────────

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

    private suspend fun <R> runInParallel(
        range: IntRange,
        block: suspend (Int) -> R,
    ): List<R> = runInParallel(range.toList(), block)

    private suspend fun assertLifecycleStoreMatchesModel(
        store: WorkflowLeaseStore,
        workflowName: String,
        workflowId: String,
        model: WorkflowLeaseLifecycleModel,
        bindings: Map<String, List<WorkflowLease>>,
        currentLeaseObject: WorkflowLease?,
        context: String,
    ) {
        val actual = store.currentLease(workflowName, workflowId)
        val expected = model.current
        if (expected == null) {
            assertThat(actual).withFailMessage("$context model=${model.describe()}").isNull()
            assertThat(currentLeaseObject).withFailMessage("$context current tracker must be empty").isNull()
            return
        }

        val bound = bindings[expected.symbolicToken]?.lastOrNull()
        assertThat(bound).withFailMessage("$context missing binding for ${expected.symbolicToken}").isNotNull
        assertThat(actual).withFailMessage("$context model=${model.describe()}").isNotNull
        assertThat(actual?.workflowName).isEqualTo(workflowName)
        assertThat(actual?.workflowId).isEqualTo(workflowId)
        assertThat(actual?.leaseId).isEqualTo(bound?.leaseId)
        assertThat(actual?.ownerId).isEqualTo(expected.ownerId)
        assertThat(actual?.checkpointRevision).isEqualTo(expected.checkpointRevision)
        assertThat(actual?.acquiredAtEpochMillis).isEqualTo(expected.acquiredAtEpochMillis)
        assertThat(actual?.expiresAtEpochMillis).isEqualTo(expected.expiresAtEpochMillis)
        assertThat(currentLeaseObject).withFailMessage("$context current tracker differs from store").isEqualTo(actual)
    }

    private companion object {
        const val LIFECYCLE_DURATION_MILLIS: Long = 1_000
    }
}
