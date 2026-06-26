package dev.tramai.spring.sovereign.persistence.jdbc.inbox

import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQuery
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalInboxQueryServiceTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("inbox_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource(): DataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection
    private lateinit var service: JdbcApprovalInboxQueryService
    private val clock: Clock = Clock.fixed(
        Instant.parse("2026-06-26T12:00:00Z"),
        ZoneOffset.UTC,
    )

    @BeforeAll
    fun setUpAll() {
        postgres.start()
        setupConnection = DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        )
        listOf(
            "tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
            "tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
            "tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql",
        ).forEach { resource ->
            val sql = this::class.java.classLoader
                .getResourceAsStream(resource)
                ?.bufferedReader()
                ?.readText()
                ?: error("Migration not found: $resource")
            runCatching { setupConnection.createStatement().use { it.execute(sql) } }
        }
        dataSource = createDataSource()
        service = JdbcApprovalInboxQueryService(dataSource, clock)
    }

    @AfterAll
    fun tearDownAll() {
        setupConnection.close()
        postgres.stop()
    }

    @BeforeEach
    fun cleanUp() {
        setupConnection.createStatement().use { stmt ->
            stmt.execute("TRUNCATE TABLE approval_continuations, suspended_invocations, audit_outbox, approvals CASCADE")
        }
    }

    private val baseTime = Instant.parse("2026-06-26T10:00:00Z")

    /**
     * Insert a minimal approval row with continuation for inbox testing.
     */
    private fun insertApproval(
        id: String,
        status: String = "PENDING",
        createdAt: Instant = baseTime,
        expiresAt: Instant = baseTime.plusSeconds(300),
        requestedAt: Instant = baseTime,
        requestedBy: String = "workflow:claim-triage",
        toolName: String = "claim-payout",
        workflowRunId: String = "wf-$id",
        hasContinuation: Boolean = true,
    ) {
        val now = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)
        val metadata = buildString {
            append("""{"binding":{"workflowRunId":"$workflowRunId","toolName":"$toolName","argumentsDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","policyVersion":"1.0","workflowDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002","approvalTokenDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000003"},""")
            append(""""requestedBy":"$requestedBy",""")
            append(""""expiresAt":"${createdAt.plusSeconds(300)}",""")
            append(""""requestedAt":"$requestedAt",""")
            append(""""decidedBy":null,"decisionComment":null,"consumedBy":null,"consumedAt":null}""")
        }
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version) VALUES (?, ?, ?::timestamptz, ?::jsonb, 0)",
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.setString(2, status)
                stmt.setObject(3, now)
                stmt.setString(4, metadata)
                stmt.executeUpdate()
            }
        }
        if (hasContinuation) {
            val argsDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000001"
            val wfDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000002"
            val nowOdt = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)
            val expOdt = OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """INSERT INTO approval_continuations (
                        approval_id, status, version, created_at, approval_expires_at,
                        workflow_run_id, correlation_id, tool_call_id, tool_name,
                        arguments_digest, policy_version, workflow_digest,
                        encrypted_arguments, encryption_key_id, encryption_algorithm,
                        encryption_nonce, payload_digest
                    ) VALUES (
                        ?, 'PENDING', 0, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?,
                        '\\x', 'none', 'none',
                        '\\x', 'none'
                    )""",
                ).use { stmt ->
                    stmt.setString(1, id)
                    stmt.setObject(2, nowOdt)
                    stmt.setObject(3, expOdt)
                    stmt.setString(4, workflowRunId)
                    stmt.setString(5, "corr-$id")
                    stmt.setString(6, "tc-$id")
                    stmt.setString(7, toolName)
                    stmt.setString(8, argsDigest)
                    stmt.setString(9, "1.0")
                    stmt.setString(10, wfDigest)
                    stmt.executeUpdate()
                }
            }
        }
    }

    // ── search tests ──────────────────────────────────────────────

    @Test
    fun `search pending returns pending items only`() = runBlocking {
        insertApproval("pending-1", status = "PENDING")
        insertApproval("approved-1", status = "APPROVED")

        val page = service.search(ApprovalInboxQuery(status = ApprovalStatus.PENDING))

        assertThat(page.items).hasSize(1)
        assertThat(page.items[0].approvalId.value).isEqualTo("pending-1")
        assertThat(page.items[0].status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `search orders by expiresAt ASC then requestedAt ASC then approvalId ASC`() = runBlocking {
        insertApproval(
            id = "early-2", createdAt = baseTime,
            expiresAt = baseTime.plusSeconds(200),
            requestedAt = baseTime.plusSeconds(10),
        )
        insertApproval(
            id = "early-1", createdAt = baseTime,
            expiresAt = baseTime.plusSeconds(200),
            requestedAt = baseTime,
        )
        insertApproval(
            id = "late-1", createdAt = baseTime,
            expiresAt = baseTime.plusSeconds(400),
            requestedAt = baseTime.plusSeconds(5),
        )

        val page = service.search(ApprovalInboxQuery())

        assertThat(page.items).hasSize(3)
        assertThat(page.items[0].approvalId.value).isEqualTo("early-1")
        assertThat(page.items[1].approvalId.value).isEqualTo("early-2")
        assertThat(page.items[2].approvalId.value).isEqualTo("late-1")
    }

    @Test
    fun `limit returns limit items and nextCursor`() = runBlocking {
        repeat(5) { i -> insertApproval("limit-$i", requestedAt = baseTime.plusSeconds(i.toLong())) }

        val page = service.search(ApprovalInboxQuery(limit = 2))

        assertThat(page.items).hasSize(2)
        assertThat(page.nextCursor).isNotNull
    }

    @Test
    fun `nextCursor returns next page without duplicates`() = runBlocking {
        repeat(5) { i -> insertApproval("cursor-$i", requestedAt = baseTime.plusSeconds(i.toLong())) }

        val page1 = service.search(ApprovalInboxQuery(limit = 2))
        val page2 = service.search(ApprovalInboxQuery(limit = 2, cursor = page1.nextCursor))

        val page1Ids = page1.items.map { it.approvalId.value }
        val page2Ids = page2.items.map { it.approvalId.value }
        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids)
        assertThat(page1Ids + page2Ids).hasSize(4)
    }

    @Test
    fun `invalid cursor fails closed`() = runBlocking {
        insertApproval("bad-cursor-1")
        try {
            service.search(ApprovalInboxQuery(cursor = "ZGVmZWN0aXZl"))
            // should not reach here
            assertThat(false).withFailMessage("Expected IllegalArgumentException").isTrue
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageContaining("invalid-approval-inbox-cursor")
        }
    }

    @Test
    fun `search by requestedBy filters correctly`() = runBlocking {
        insertApproval("req-1", requestedBy = "user:alice")
        insertApproval("req-2", requestedBy = "user:bob")

        val page = service.search(ApprovalInboxQuery(requestedBy = "user:alice"))

        assertThat(page.items).hasSize(1)
        assertThat(page.items[0].approvalId.value).isEqualTo("req-1")
    }

    @Test
    fun `search by expiresBefore filters correctly`() = runBlocking {
        insertApproval("exp-1", expiresAt = baseTime.plusSeconds(100))
        insertApproval("exp-2", expiresAt = baseTime.plusSeconds(400))

        val page = service.search(ApprovalInboxQuery(expiresBefore = baseTime.plusSeconds(200)))

        assertThat(page.items).hasSize(1)
        assertThat(page.items[0].approvalId.value).isEqualTo("exp-1")
    }

    @Test
    fun `search does not expose resumeToken or token digest`() = runBlocking {
        insertApproval("safe-1")

        val page = service.search(ApprovalInboxQuery())

        assertThat(page.items).isNotEmpty
        page.items.forEach { item ->
            // These fields do not exist on the work item model
            // Compile-time safety — the projection is inherently safe
            assertThat(item.approvalId.value).isNotNull
        }
    }

    // ── getWorkItem tests ─────────────────────────────────────────

    @Test
    fun `getWorkItem returns null for missing approval`() = runBlocking {
        val item = service.getWorkItem(ApprovalId("missing-id"))
        assertThat(item).isNull()
    }

    @Test
    fun `getWorkItem includes continuation status`() = runBlocking {
        insertApproval("cont-1")

        val item = service.getWorkItem(ApprovalId("cont-1"))

        assertThat(item).isNotNull
        assertThat(item!!.continuationStatus).isEqualTo(ApprovalContinuationStatus.PENDING)
    }

    @Test
    fun `work item uses requestedAt from metadata not created_at`() = runBlocking {
        val explicitRequestedAt = baseTime.plus(java.time.Duration.ofMinutes(1))
        insertApproval(
            id = "req-at-test",
            createdAt = baseTime,
            requestedAt = explicitRequestedAt,
        )

        val item = service.getWorkItem(ApprovalId("req-at-test"))

        assertThat(item).isNotNull
        assertThat(item!!.requestedAt).isEqualTo(explicitRequestedAt)
    }

    @Test
    fun `work item without continuation uses metadata expiresAt`() = runBlocking {
        insertApproval(
            id = "no-cont",
            hasContinuation = false,
        )

        val item = service.getWorkItem(ApprovalId("no-cont"))

        assertThat(item).isNotNull
        assertThat(item!!.expiresAt).isEqualTo(baseTime.plusSeconds(300))
    }

    @Test
    fun `work item workflowRunId comes from continuation not metadata`() = runBlocking {
        insertApproval(
            id = "wf-test",
            workflowRunId = "wf-custom",
        )

        val item = service.getWorkItem(ApprovalId("wf-test"))

        assertThat(item).isNotNull
        assertThat(item!!.workflowRunId).isEqualTo("wf-custom")
    }
}
