package dev.tramai.examples.approval

import com.zaxxer.hikari.HikariDataSource
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.workflow.SovereignWorkflowResult
import dev.tramai.engine.approval.testing.TestApprovalGatewayRequestFactory
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAutoConfiguration
import dev.tramai.spring.sovereign.persistence.jdbc.SovereignJdbcPersistenceAutoConfiguration
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.Base64
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Minimal approval resume example test.
 *
 * Proves that a workflow can suspend for approval, then resume after
 * approval or stop after denial — deterministically, with no real model calls.
 *
 * Uses embedded PostgreSQL (no Docker required) and Spring auto-configuration.
 */
@Tag("e2e")
class ApprovalResumeExampleTest {

    companion object {
        private var pg: EmbeddedPostgres? = null

        @JvmStatic
        @BeforeAll
        fun startPg() {
            pg = EmbeddedPostgres.start()
        }

        @JvmStatic
        @AfterAll
        fun stopPg() {
            pg?.close()
        }
    }

    @TempDir
    lateinit var tempDir: Path

    private lateinit var keyFile: Path

    @BeforeEach
    fun setUp() {
        keyFile = tempDir.resolve("encryption-key.b64")
        keyFile.toFile().writeText(
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        )
    }

    private val jdbcUrl: String get() = pg!!.getJdbcUrl("postgres", "postgres")

    private fun createRunner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignJdbcPersistenceAutoConfiguration::class.java,
                    ApprovalGatewayAutoConfiguration::class.java,
                    ApprovalDecisionControlPlaneAutoConfiguration::class.java,
                    ApprovalResumeControlPlaneAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(
                TestDataSourceConfig::class.java,
                TestGatewayConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.enabled=true",
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    fun `low value expense completes without approval`() {
        createRunner().run { ctx ->
            val workflow = ctx.getBean(ExpenseApprovalWorkflow::class.java)

            val result = runBlocking {
                workflow.process(ExpenseClaim("e-1", "emp-1", 500, "Office supplies"))
            }

            assertThat(result).isInstanceOf(SovereignWorkflowResult.Completed::class.java)
            assertThat((result as SovereignWorkflowResult.Completed).result)
                .isEqualTo("EXPENSE_REIMBURSED")
        }
    }

    @Test
    fun `high value expense suspends for approval`() {
        createRunner().run { ctx ->
            val workflow = ctx.getBean(ExpenseApprovalWorkflow::class.java)

            val result = runBlocking {
                workflow.process(ExpenseClaim("e-2", "emp-1", 1500, "Conference travel"))
            }

            assertThat(result).isInstanceOf(SovereignWorkflowResult.SuspendedForApproval::class.java)
            val suspended = result as SovereignWorkflowResult.SuspendedForApproval
            assertThat(suspended.approvalId.value).isNotBlank()
            assertThat(suspended.resumeToken.value).isNotBlank()
        }
    }

    @Test
    fun `approved expense resumes and reimburses once`() {
        createRunner().run { ctx ->
            val ledger = ctx.getBean(InMemoryExpenseLedger::class.java)
            val workflow = ctx.getBean(ExpenseApprovalWorkflow::class.java)
            val decisionPlane = ctx.getBean(ApprovalDecisionControlPlane::class.java)
            val resumePlane = ctx.getBean(ApprovalResumeControlPlane::class.java)

            // 1. Submit expense — suspends for approval
            val suspended = runBlocking {
                workflow.process(ExpenseClaim("e-3", "emp-1", 1500, "Laptop"))
            } as SovereignWorkflowResult.SuspendedForApproval

            assertThat(ledger.executionCount).isZero()

            // 2. Manager approves
            val approveResult = runBlocking {
                decisionPlane.approve(
                    ApprovalDecisionCommand(
                        approvalId = suspended.approvalId,
                        actorId = "manager-1",
                        actorRole = ApproverRole("manager"),
                        comment = "Approved",
                    ),
                )
            }
            assertThat(approveResult).isInstanceOf(ApprovalDecisionResult.Approved::class.java)

            // 3. Resume the workflow
            val resumeResult = runBlocking {
                resumePlane.resume(
                    ApprovalResumeCommand(
                        approvalId = suspended.approvalId,
                        resumeToken = suspended.resumeToken,
                        resumedBy = "system",
                    ),
                )
            }
            assertThat(resumeResult).isInstanceOf(ApprovalResumeResult.Resumed::class.java)
            val resumed = resumeResult as ApprovalResumeResult.Resumed
            assertThat(resumed.result).isEqualTo("EXPENSE_REIMBURSED")

            // 4. Side effect executed exactly once
            assertThat(ledger.executionCount).isOne
            assertThat(ledger.isReimbursed("e-3")).isTrue
        }
    }

    @Test
    fun `denied expense does not reimburse`() {
        createRunner().run { ctx ->
            val ledger = ctx.getBean(InMemoryExpenseLedger::class.java)
            val workflow = ctx.getBean(ExpenseApprovalWorkflow::class.java)
            val decisionPlane = ctx.getBean(ApprovalDecisionControlPlane::class.java)
            val resumePlane = ctx.getBean(ApprovalResumeControlPlane::class.java)

            // 1. Submit expense — suspends for approval
            val suspended = runBlocking {
                workflow.process(ExpenseClaim("e-4", "emp-1", 2000, "Team dinner"))
            } as SovereignWorkflowResult.SuspendedForApproval

            assertThat(ledger.executionCount).isZero()

            // 2. Manager denies
            val denyResult = runBlocking {
                decisionPlane.deny(
                    ApprovalDecisionCommand(
                        approvalId = suspended.approvalId,
                        actorId = "manager-1",
                        actorRole = ApproverRole("manager"),
                        comment = "Denied — over budget",
                    ),
                )
            }
            assertThat(denyResult).isInstanceOf(ApprovalDecisionResult.Denied::class.java)

            // 3. Attempt resume — should fail because approval is denied
            val resumeResult = runBlocking {
                resumePlane.resume(
                    ApprovalResumeCommand(
                        approvalId = suspended.approvalId,
                        resumeToken = suspended.resumeToken,
                        resumedBy = "system",
                    ),
                )
            }
            assertThat(resumeResult).isInstanceOf(ApprovalResumeResult.NotApproved::class.java)

            // 4. Side effect was NOT executed
            assertThat(ledger.executionCount).isZero
            assertThat(ledger.isReimbursed("e-4")).isFalse
        }
    }

    // ── Configuration ──────────────────────────────────────────────────

    @Configuration
    class TestDataSourceConfig {

        @Bean(destroyMethod = "close")
        fun testDataSource(): DataSource {
            val ds = HikariDataSource()
            ds.jdbcUrl = jdbcUrl
            ds.username = "postgres"
            ds.password = "postgres"
            ds.maximumPoolSize = 3
            // Apply JDBC schema migrations
            applyMigrations(ds)
            return ds
        }

        /**
         * Minimal schema migration runner. Applies the TramAI JDBC schema
         * migrations to the embedded PostgreSQL instance.
         */
        private fun applyMigrations(ds: DataSource) {
            val migrations = listOf(
                "/tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
                "/tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
                "/tramai/persistence/jdbc/postgres/V3__audit_events_hardening.sql",
                "/tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql",
                "/tramai/persistence/jdbc/postgres/V5__worker_leases_hardening.sql",
                "/tramai/persistence/jdbc/postgres/V6__approval_resume_credential_custody.sql",
            )
            for (resource in migrations) {
                val sql = TestDataSourceConfig::class.java.getResourceAsStream(resource)
                    ?.bufferedReader()?.readText()
                    ?: throw IllegalStateException("Migration not found: $resource")
                ds.connection.use { conn ->
                    conn.autoCommit = false
                    try {
                        for (statement in sql.split(";")) {
                            if (statement.isNotBlank() && !statement.trimStart().startsWith("--")) {
                                conn.createStatement().execute(statement.trim())
                            }
                        }
                        conn.commit()
                    } catch (e: SQLException) {
                        conn.rollback()
                        throw e
                    }
                }
            }
        }
    }

    @Configuration
    class TestGatewayConfig {

        @Bean
        fun expenseLedger(): InMemoryExpenseLedger = InMemoryExpenseLedger()

        @Bean
        fun testApprovalGatewayRequestFactory(): TestApprovalGatewayRequestFactory =
            TestApprovalGatewayRequestFactory(clock = Clock.systemUTC())

        @Bean
        fun expenseApprovalWorkflow(
            gateway: ApprovalGateway,
            ledger: InMemoryExpenseLedger,
        ): ExpenseApprovalWorkflow = ExpenseApprovalWorkflow(gateway, ledger)
    }
}
