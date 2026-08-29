package dev.tramai.orchestration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.3d — lease capability authority.
 *
 * Every newly-created [WorkflowLease.leaseId] must originate from the single
 * [LeaseIdentitySource]; no store may manufacture lease identity itself.
 * Sentinel injection proves the exact token propagates into claimed leases
 * across all three store implementations.
 */
class LeaseIdentityAuthorityDiscriminatorTest {

    private val sentinelSource = LeaseIdentitySource { "sentinel-lease-id" }

    @Test
    fun `InMemory store claims leases with the source-issued leaseId`() {
        val store = InMemoryWorkflowLeaseStore({ 0L }, sentinelSource)

        val lease = runBlockingTest { store.claim("wf", "w1", "owner", checkpointRevision = null, leaseDurationMillis = 60_000) }

        assertThat(lease.leaseId).isEqualTo("sentinel-lease-id")
    }

    @Test
    fun `File store claims leases with the source-issued leaseId`() {
        val root = java.nio.file.Files.createTempDirectory("8d-lease")
        val store = FileWorkflowLeaseStore(root, DefaultWorkflowCheckpointPathStrategy("lease.properties"), { 0L }, sentinelSource)

        val lease = runBlockingTest { store.claim("wf", "w1", "owner", checkpointRevision = null, leaseDurationMillis = 60_000) }

        assertThat(lease.leaseId).isEqualTo("sentinel-lease-id")
        root.toFile().deleteRecursively()
    }

    @Test
    fun `Jdbc store claims leases with the source-issued leaseId`() {
        val ds = org.h2.jdbcx.JdbcDataSource()
        ds.setURL("jdbc:h2:mem:lease_identity;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
        ds.user = "sa"
        ds.password = ""
        val store = JdbcWorkflowLeaseStore(ds, JdbcWorkflowLeaseTable(), { 0L }, sentinelSource)
        java.sql.DriverManager.getConnection("jdbc:h2:mem:lease_identity;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
            .use { conn -> conn.createStatement().use { it.execute(store.createTableSql()) } }

        val lease = runBlockingTest { store.claim("wf", "w1", "owner", checkpointRevision = null, leaseDurationMillis = 60_000) }

        assertThat(lease.leaseId).isEqualTo("sentinel-lease-id")
    }

    private fun runBlockingTest(block: suspend () -> WorkflowLease): WorkflowLease =
        kotlinx.coroutines.runBlocking { block() }
}
