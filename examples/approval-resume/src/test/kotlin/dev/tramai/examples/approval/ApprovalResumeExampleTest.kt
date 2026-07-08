package dev.tramai.examples.approval

import com.zaxxer.hikari.HikariDataSource
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.User
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.workflow.SovereignWorkflowResult
import dev.tramai.engine.approval.testing.TestApprovalGatewayRequestFactory
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.persistence.jdbc.SovereignJdbcPersistenceAutoConfiguration
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Path
import java.sql.SQLException
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
        @JvmStatic
        @BeforeAll
        fun startPg() {
            EmbeddedPgHolder.start()
        }

        @JvmStatic
        @AfterAll
        fun stopPg() {
            EmbeddedPgHolder.stop()
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

    private fun createRunner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignJdbcPersistenceAutoConfiguration::class.java,
                    SovereignTramaiAutoConfiguration::class.java,
                    ApprovalGatewayAutoConfiguration::class.java,
                    SovereignOpsAutoConfiguration::class.java,
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
                "tramai.sovereign.ops.mutations-enabled=true",
                "tramai.sovereign.ops.resume-enabled=true",
                "tramai.sovereign.ops.enabled=true",
                "tramai.sovereign.allowed-models=expense-model",
                "tramai.sovereign.allowed-providers=expense-provider",
                "tramai.sovereign.allowed-tools=expense-reimbursement",
                "tramai.sovereign.allowed-permissions=expense.reimburse",
                "tramai.sovereign.models[expense-model]=expense-provider",
                "tramai.sovereign.provider-zones[expense-provider]=LOCAL",
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
            assertThat(result.toString()).contains("EXPENSE_REIMBURSED")
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
            val decisionPlane = ctx.getBean(ApprovalDecisionControlPlane::class.java)
            val resumePlane = ctx.getBean(ApprovalResumeControlPlane::class.java)
            val runtime = ctx.getBean(SovereignTramaiRuntime::class.java)

            // 1. Create service proxy (this registers the operation in ResumeOperationRegistry)
            val service = runtime.create(ExpenseApprovalResumeService::class)

            // 2. Submit expense via service proxy — triggers approval-gated tool → suspension
            val suspension = try {
                runBlocking { service.reviewAndReimburse("e-3") }
                error("Expected approval suspension")
            } catch (e: ApprovalSuspendedException) {
                e
            }

            assertThat(ledger.executionCount).isZero()

            // 3. Manager approves
            val approvalId = dev.tramai.core.approval.gateway.ApprovalId(suspension.approvalId)
            val approveResult = runBlocking {
                decisionPlane.approve(
                    ApprovalDecisionCommand(
                        approvalId = approvalId,
                        actorId = "manager-1",
                        actorRole = ApproverRole("manager"),
                        comment = "Approved",
                    ),
                )
            }
            assertThat(approveResult).isInstanceOf(ApprovalDecisionResult.Approved::class.java)

            // 4. Resume the workflow
            val resumeResult = runBlocking {
                resumePlane.resume(
                    ApprovalResumeCommand(
                        approvalId = approvalId,
                        resumeToken = dev.tramai.core.approval.gateway.ResumeToken(suspension.challenge.token.reveal()),
                        resumedBy = "system",
                    ),
                )
            }
            assertThat(resumeResult).isInstanceOf(ApprovalResumeResult.Resumed::class.java)

            // 5. Side effect executed exactly once
            assertThat(ledger.executionCount).isOne
            assertThat(ledger.isReimbursed("e-3")).isTrue
        }
    }

    @Test
    fun `denied expense does not reimburse`() {
        createRunner().run { ctx ->
            val ledger = ctx.getBean(InMemoryExpenseLedger::class.java)
            val decisionPlane = ctx.getBean(ApprovalDecisionControlPlane::class.java)
            val resumePlane = ctx.getBean(ApprovalResumeControlPlane::class.java)
            val runtime = ctx.getBean(SovereignTramaiRuntime::class.java)

            // 1. Create service proxy (registers the operation in ResumeOperationRegistry)
            val service = runtime.create(ExpenseApprovalResumeService::class)

            // 2. Submit expense via service proxy — triggers approval-gated tool → suspension
            val suspension = try {
                runBlocking { service.reviewAndReimburse("e-4") }
                error("Expected approval suspension")
            } catch (e: ApprovalSuspendedException) {
                e
            }

            assertThat(ledger.executionCount).isZero()

            // 3. Manager denies
            val approvalId = dev.tramai.core.approval.gateway.ApprovalId(suspension.approvalId)
            val denyResult = runBlocking {
                decisionPlane.deny(
                    ApprovalDecisionCommand(
                        approvalId = approvalId,
                        actorId = "manager-1",
                        actorRole = ApproverRole("manager"),
                        comment = "Denied — over budget",
                    ),
                )
            }
            assertThat(denyResult).isInstanceOf(ApprovalDecisionResult.Denied::class.java)

            // 4. Attempt resume — should fail because approval is denied
            val resumeResult = runBlocking {
                resumePlane.resume(
                    ApprovalResumeCommand(
                        approvalId = approvalId,
                        resumeToken = dev.tramai.core.approval.gateway.ResumeToken(suspension.challenge.token.reveal()),
                        resumedBy = "system",
                    ),
                )
            }
            assertThat(resumeResult).isInstanceOf(ApprovalResumeResult.NotApproved::class.java)

            // 5. Side effect was NOT executed
            assertThat(ledger.executionCount).isZero
            assertThat(ledger.isReimbursed("e-4")).isFalse
        }
    }
}

// ── @AiService for the runtime-backed resume path ────────────────────

@AiService
fun interface ExpenseApprovalResumeService {
    @Operation(
        model = "expense-model",
        tools = ["expense-reimbursement"],
    )
    @User(
        """
        Review the expense and execute reimbursement when approved.
        Expense ID: {expenseId}
        """
    )
    suspend fun reviewAndReimburse(expenseId: String): String
}

// ── Deterministic provider (produces tool call on first invocation) ──

class ExpenseResumeDemoProvider : ModelProvider {
    private val calls = java.util.concurrent.atomic.AtomicInteger(0)

    override fun providerId(): String = "expense-provider"

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val attempt = calls.incrementAndGet()

        if (attempt == 1) {
            val expenseId = extractExpenseId(request)
            return ModelResponse(
                content = "Expense reimbursement requires tool execution.",
                toolCalls = listOf(
                    ToolCall(
                        id = "call-expense-reimbursement-001",
                        name = "expense-reimbursement",
                        argumentsJson = """{"expenseId":"$expenseId"}""",
                    ),
                ),
                finishReason = FinishReason.OTHER,
            )
        }

        return ModelResponse(
            content = "EXPENSE_REIMBURSED",
            finishReason = FinishReason.STOP,
        )
    }

    private fun extractExpenseId(request: ModelRequest): String {
        val content = request.messages.lastOrNull()?.content.orEmpty()
        return Regex("Expense ID: ([^\\s]+)")
            .find(content)
            ?.groupValues
            ?.get(1)
            ?: "expense-unknown"
    }
}

// ── Approval-gated reimbursement tool ────────────────────────────────

data class ExpenseReimbursementInput(val expenseId: String)
data class ExpenseReimbursementResult(val expenseId: String, val status: String)

class ExpenseReimbursementTool(
    private val ledger: InMemoryExpenseLedger,
) : TramaiTool<ExpenseReimbursementInput, ExpenseReimbursementResult> {
    override val name: String = "expense-reimbursement"
    override val description: String = "Reimburse an approved expense"
    override val inputType = ExpenseReimbursementInput::class
    override val idempotent: Boolean = true
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.WRITE
    override val security: ToolSecurityMetadata = ToolSecurityMetadata(
        permission = "expense.reimburse",
        risk = RiskLevel.HIGH,
        approval = ApprovalMode.HUMAN_REQUIRED,
        managedNetworkEgress = ManagedNetworkEgress.DENY,
        audit = AuditDetail.FULL,
    )

    override suspend fun execute(
        input: ExpenseReimbursementInput,
        context: ToolExecutionContext,
    ): ExpenseReimbursementResult {
        ledger.reimburse(input.expenseId)
        return ExpenseReimbursementResult(
            expenseId = input.expenseId,
            status = "REIMBURSED",
        )
    }
}

// ── Spring configuration ─────────────────────────────────────────────

@Configuration
class TestDataSourceConfig {

    @Bean(destroyMethod = "close")
    fun testDataSource(): javax.sql.DataSource {
        val ds = com.zaxxer.hikari.HikariDataSource()
        ds.jdbcUrl = EmbeddedPgHolder.jdbcUrl
        ds.username = "postgres"
        ds.password = "postgres"
        ds.maximumPoolSize = 3
        return ds
    }
}

@Configuration
class TestGatewayConfig {

    @Bean
    fun expenseLedger(): InMemoryExpenseLedger = InMemoryExpenseLedger()

    @Bean
    fun testApprovalGatewayRequestFactory(): TestApprovalGatewayRequestFactory =
        TestApprovalGatewayRequestFactory(clock = java.time.Clock.systemUTC())

    @Bean
    fun expenseApprovalWorkflow(
        gateway: ApprovalGateway,
        ledger: InMemoryExpenseLedger,
    ): ExpenseApprovalWorkflow = ExpenseApprovalWorkflow(gateway, ledger)

    @Bean
    fun sovereignTramai(
        profile: dev.tramai.sovereign.SovereignProfileConfiguration,
        modelRegistry: ModelRegistry,
        auditStore: dev.tramai.security.audit.AuditStore,
        infrastructure: SovereignTramaiAutoConfiguration.SovereignTramaiInfrastructure,
        clock: java.time.Clock,
        ledger: InMemoryExpenseLedger,
    ): SovereignTramai {
        val builder = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(modelRegistry)
            .auditStore(auditStore)
            .provider(ExpenseResumeDemoProvider(), name = "expense-provider", default = true)
            .model("expense-model", "expense-provider")
            .tools(ExpenseReimbursementTool(ledger))
            .approvalContinuationStore(infrastructure.approvalContinuationStore)
            .approvalGateCoordinator(infrastructure.approvalGateCoordinator)
            .clock(clock)

        infrastructure.suspendedInvocationStore?.let { builder.suspendedInvocationStore(it) }
        infrastructure.toolArgumentsDigester?.let { builder.toolArgumentsDigester(it) }

        return builder.build()
    }
}

// ── Embedded PostgreSQL holder ─────────────────────────────────────────────

object EmbeddedPgHolder {
    private var pg: EmbeddedPostgres? = null
    private var _jdbcUrl: String? = null

    val jdbcUrl: String
        get() = _jdbcUrl ?: error("EmbeddedPostgres not started. Call start() first.")

    fun start() {
        if (pg != null) return
        synchronized(this) {
            if (pg != null) return
            val instance = EmbeddedPostgres.start()
            val url = instance.getJdbcUrl("postgres", "postgres")
            HikariDataSource().use { ds ->
                ds.jdbcUrl = url
                ds.username = "postgres"
                ds.password = "postgres"
                ds.maximumPoolSize = 1
                applyMigrations(ds)
            }
            pg = instance
            _jdbcUrl = url
        }
    }

    fun stop() {
        pg?.close()
        pg = null
        _jdbcUrl = null
    }

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
            val sql = EmbeddedPgHolder::class.java.getResourceAsStream(resource)
                ?.bufferedReader()?.readText()
                ?: throw IllegalStateException("Migration not found: $resource")
            ds.connection.use { conn ->
                conn.autoCommit = false
                try {
                    for (statement in splitStatements(sql)) {
                        if (statement.isNotBlank()) {
                            conn.createStatement().execute(statement.trim())
                        }
                    }
                    conn.commit()
                } catch (e: SQLException) {
                    conn.rollback()
                    throw IllegalStateException(
                        "Migration failed on resource [$resource]: ${e.message}",
                        e,
                    )
                }
            }
        }
    }

    private fun splitStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inDollarQuote = false
        for (line in sql.lines()) {
            val trimmed = line.trim()
            if (!inDollarQuote && trimmed.startsWith("DO $$ BEGIN")) inDollarQuote = true
            if (!inDollarQuote && (trimmed.isEmpty() || trimmed.startsWith("--"))) continue
            current.append(line).append("\n")
            if (inDollarQuote && trimmed == "END $$;") {
                inDollarQuote = false
                statements.add(current.toString())
                current.clear()
            } else if (!inDollarQuote && trimmed.endsWith(";")) {
                statements.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotBlank()) statements.add(current.toString())
        return statements
    }
}
