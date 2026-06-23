package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseAcquisition
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseHeartbeat
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseRelease
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsWorkerLeaseStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("worker_lease_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource(): DataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }

        private val BASE_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private val LEASE_DURATION: Duration = Duration.ofMinutes(2)
    }

    private lateinit var dataSource: DataSource

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource = createDataSource()
        runMigrations()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        truncateTables()
    }

    private fun store(): JdbcSovereignOpsWorkerLeaseStore =
        JdbcSovereignOpsWorkerLeaseStore(dataSource)

    // ── Acquisition ────────────────────────────────────────────────────

    @Test
    fun `acquire missing lease — acquired by owner A`() = runBlocking {
        val result = store().tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
        assertThat(result).isInstanceOf(SovereignOpsWorkerLeaseAcquisition.Acquired::class.java)
        val acquired = result as SovereignOpsWorkerLeaseAcquisition.Acquired
        assertThat(acquired.lease.ownerId).isEqualTo("worker-a")
        assertThat(acquired.lease.leaseName).isEqualTo("my-lease")
        assertThat(acquired.lease.expiresAt).isEqualTo(BASE_NOW.plus(LEASE_DURATION))
        assertThat(acquired.lease.version).isEqualTo(2)
    }

    @Test
    fun `acquire same lease same owner — already owned`() = runBlocking {
        val s = store()
        s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
        val result = s.tryAcquire("my-lease", "worker-a", BASE_NOW.plusSeconds(10), LEASE_DURATION)
        assertThat(result).isInstanceOf(SovereignOpsWorkerLeaseAcquisition.AlreadyOwned::class.java)
    }

    @Test
    fun `acquire active lease different owner — held by other`() = runBlocking {
        val s = store()
        s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
        val result = s.tryAcquire("my-lease", "worker-b", BASE_NOW.plusSeconds(10), LEASE_DURATION)
        assertThat(result).isInstanceOf(SovereignOpsWorkerLeaseAcquisition.HeldByOther::class.java)
        val held = result as SovereignOpsWorkerLeaseAcquisition.HeldByOther
        assertThat(held.lease.ownerId).isEqualTo("worker-a")
    }

    @Test
    fun `acquire expired lease different owner — stolen by owner B`() = runBlocking {
        val s = store()
        s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
        // Move past expiry
        val afterExpiry = BASE_NOW.plus(LEASE_DURATION).plusSeconds(1)
        val result = s.tryAcquire("my-lease", "worker-b", afterExpiry, LEASE_DURATION)
        assertThat(result).isInstanceOf(SovereignOpsWorkerLeaseAcquisition.Acquired::class.java)
        val acquired = result as SovereignOpsWorkerLeaseAcquisition.Acquired
        assertThat(acquired.lease.ownerId).isEqualTo("worker-b")
    }

    @Test
    fun `concurrent acquire same lease — exactly one owner wins`() = runBlocking {
        coroutineScope {
            val s = store()
            val a = async { s.tryAcquire("race-lease", "worker-a", BASE_NOW, LEASE_DURATION) }
            val b = async { s.tryAcquire("race-lease", "worker-b", BASE_NOW, LEASE_DURATION) }
            val results = awaitAll(a, b)

            assertThat(results.count { it is SovereignOpsWorkerLeaseAcquisition.Acquired }).isEqualTo(1)
            assertThat(results.count { it is SovereignOpsWorkerLeaseAcquisition.HeldByOther }).isEqualTo(1)
        }
    }

    // ── Heartbeat ──────────────────────────────────────────────────────

    @Test
    fun `heartbeat by owner extends expiry`() = runBlocking {
        val s = store()
        val acquired = (s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
            as SovereignOpsWorkerLeaseAcquisition.Acquired)
        val originalVersion = acquired.lease.version
        val later = BASE_NOW.plusSeconds(30)
        val result = s.heartbeat("my-lease", "worker-a", later, LEASE_DURATION)
        assertThat(result).isInstanceOf(SovereignOpsWorkerLeaseHeartbeat.Extended::class.java)
        val extended = result as SovereignOpsWorkerLeaseHeartbeat.Extended
        assertThat(extended.lease.expiresAt).isEqualTo(later.plus(LEASE_DURATION))
        assertThat(extended.lease.version).isGreaterThan(originalVersion)
    }

    @Test
    fun `heartbeat by non-owner rejected`() = runBlocking {
        val s = store()
        s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
        val result = s.heartbeat("my-lease", "worker-b", BASE_NOW.plusSeconds(30), LEASE_DURATION)
        assertThat(result).isEqualTo(SovereignOpsWorkerLeaseHeartbeat.NotOwner)
    }

    @Test
    fun `heartbeat on missing lease returns missing`() = runBlocking {
        val result = store().heartbeat("nonexistent", "worker-a", BASE_NOW, LEASE_DURATION)
        assertThat(result).isEqualTo(SovereignOpsWorkerLeaseHeartbeat.Missing)
    }

    @Test
    fun `heartbeat by owner after expiry is rejected`() = runBlocking {
        val s = store()
        s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
        val afterExpiry = BASE_NOW.plus(LEASE_DURATION).plusSeconds(1)
        val result = s.heartbeat("my-lease", "worker-a", afterExpiry, LEASE_DURATION)
        assertThat(result).isEqualTo(SovereignOpsWorkerLeaseHeartbeat.Expired)
    }

    // ── Release ────────────────────────────────────────────────────────

    @Test
    fun `release by owner clears ownership`() = runBlocking {
        val s = store()
        val firstAcquire = (s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
            as SovereignOpsWorkerLeaseAcquisition.Acquired)
        val versionAfterAcquire = firstAcquire.lease.version
        val result = s.release("my-lease", "worker-a", BASE_NOW.plusSeconds(10))
        assertThat(result).isEqualTo(SovereignOpsWorkerLeaseRelease.Released)

        // Verify version incremented after release
        val afterRelease = s.get("my-lease")
        assertThat(afterRelease).isNotNull
        assertThat(afterRelease!!.version).isGreaterThan(versionAfterAcquire)

        // Verify cleared: another worker can now acquire
        val acquired = s.tryAcquire("my-lease", "worker-b", BASE_NOW.plusSeconds(20), LEASE_DURATION)
        assertThat(acquired).isInstanceOf(SovereignOpsWorkerLeaseAcquisition.Acquired::class.java)
    }

    @Test
    fun `release by non-owner rejected`() = runBlocking {
        val s = store()
        s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
        val result = s.release("my-lease", "worker-b", BASE_NOW.plusSeconds(10))
        assertThat(result).isEqualTo(SovereignOpsWorkerLeaseRelease.NotOwner)
    }

    @Test
    fun `release on missing lease returns missing`() = runBlocking {
        val result = store().release("nonexistent", "worker-a", BASE_NOW)
        assertThat(result).isEqualTo(SovereignOpsWorkerLeaseRelease.Missing)
    }

    // ── Get ────────────────────────────────────────────────────────────

    @Test
    fun `get unknown lease returns null`() = runBlocking {
        val result = store().get("nonexistent")
        assertThat(result).isNull()
    }

    @Test
    fun `get known lease returns lease`() = runBlocking {
        val s = store()
        s.tryAcquire("my-lease", "worker-a", BASE_NOW, LEASE_DURATION)
        val result = s.get("my-lease")
        assertThat(result).isNotNull
        assertThat(result!!.ownerId).isEqualTo("worker-a")
    }

    // ── Restart recovery ───────────────────────────────────────────────

    @Test
    fun `expired lease is recoverable after restart — owner B acquires`() = runBlocking {
        // Acquire with owner A
        store().tryAcquire("recovery-lease", "worker-a", BASE_NOW, LEASE_DURATION)

        // Simulate restart: new store instance, time moved past expiry
        val afterExpiry = BASE_NOW.plus(LEASE_DURATION).plusSeconds(5)
        val newStore = store()
        val result = newStore.tryAcquire("recovery-lease", "worker-b", afterExpiry, LEASE_DURATION)
        assertThat(result).isInstanceOf(SovereignOpsWorkerLeaseAcquisition.Acquired::class.java)
        val acquired = result as SovereignOpsWorkerLeaseAcquisition.Acquired
        assertThat(acquired.lease.ownerId).isEqualTo("worker-b")
    }

    // ── Constraint validation ──────────────────────────────────────────
    //
    // Domain constraints (non-blank lease name, positive version,
    // owner consistency, expiry after acquire) are enforced at the
    // PostgreSQL level via V5 CHECK constraints. Kotlin-level validation
    // in SovereignOpsWorkerLease.init provides defense-in-depth.

    // ── Helpers ────────────────────────────────────────────────────────

    private fun truncateTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE worker_leases CASCADE")
            }
        }
    }

    private fun runMigrations() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val v1 = javaClass.classLoader
                    .getResourceAsStream("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
                    ?.bufferedReader()?.readText()
                    ?: error("V1 migration not found")
                stmt.execute(v1)
                val v5 = javaClass.classLoader
                    .getResourceAsStream("tramai/persistence/jdbc/postgres/V5__worker_leases_hardening.sql")
                    ?.bufferedReader()?.readText()
                    ?: error("V5 migration not found")
                stmt.execute(v5)
            }
        }
    }
}
