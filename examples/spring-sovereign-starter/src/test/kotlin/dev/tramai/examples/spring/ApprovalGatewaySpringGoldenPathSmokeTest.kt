package dev.tramai.examples.spring

import com.zaxxer.hikari.HikariDataSource
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.workflow.SovereignWorkflowResult
import dev.tramai.core.workflow.toWorkflowResult
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAutoConfiguration
import dev.tramai.spring.sovereign.persistence.jdbc.SovereignJdbcPersistenceAutoConfiguration
import java.time.Clock
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
import java.nio.file.Path
import java.util.Base64

/**
 * Minimal Spring/JDBC smoke proof for the ergonomic golden path.
 *
 * Proves that a sovereign workflow can use [ApprovalGateway] together
 * with [ApprovalRequestResult.toWorkflowResult] without wiring
 * low-level stores — while still persisting all required records.
 *
 * This is intentionally much smaller than [RegulatedClaimTriageJdbcE2ETest].
 * It proves the ergonomic path, not the full scenario runtime.
 *
 * All supporting types are inner classes to avoid being picked up
 * by component scanning in other Spring Boot tests in this package.
 */
@Tag("e2e")
class ApprovalGatewaySpringGoldenPathSmokeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun startPg() {
            PgEmbeddedTestSupport.start()
        }

        @JvmStatic
        @AfterAll
        fun stopPg() {
            PgEmbeddedTestSupport.stop()
        }
    }

    @TempDir
    lateinit var tempDir: Path

    private lateinit var keyFile: Path

    @BeforeEach
    fun setUp() {
        keyFile = tempDir.resolve("smoke-key.b64")
        keyFile.toFile().writeText(
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        )
    }

    private fun createJdbcRunner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignJdbcPersistenceAutoConfiguration::class.java,
                    ApprovalGatewayAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(
                SmokeTestDataSourceConfig::class.java,
                SmokeTestGatewayConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.enabled=true",
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )

    // ── The proof ──────────────────────────────────────────────────

    @Test
    fun `spring workflow can suspend through approval gateway without wiring low level stores`() {
        createJdbcRunner().run { ctx ->
            val workflow = ctx.getBean(ExampleApprovalWorkflow::class.java)

            val result = runBlocking {
                workflow.triage(
                    ClaimInput(
                        claimId = "smoke-claim-1",
                        workflowRunId = "smoke-run-1",
                    ),
                )
            }

            // 1. Result is SuspendedForApproval
            assertThat(result)
                .isInstanceOf(SovereignWorkflowResult.SuspendedForApproval::class.java)

            val suspended = result as SovereignWorkflowResult.SuspendedForApproval
            assertThat(suspended.approvalId.value).isNotBlank()
            assertThat(suspended.workflowRunId).isEqualTo(WorkflowRunId("smoke-run-1"))
            assertThat(suspended.resumeToken.value).isNotBlank()

            // 2. Approval request was actually persisted
            runBlocking {
                val approvalStore = ctx.getBean(ApprovalStore::class.java)
                val persisted = approvalStore.get(suspended.approvalId.value)
                assertThat(persisted).isNotNull
                assertThat(persisted!!.status).isEqualTo(ApprovalStatus.PENDING)
            }

            // 3. Suspended invocation was persisted
            runBlocking {
                val suspendedInvocationStore = ctx.getBean(SuspendedInvocationStore::class.java)
                val suspendedInvocation = suspendedInvocationStore.get(suspended.approvalId.value)
                assertThat(suspendedInvocation).isNotNull
            }

            // 4. Continuation was persisted
            runBlocking {
                val continuationStore = ctx.getBean(ApprovalContinuationStore::class.java)
                val continuation = continuationStore.get(suspended.approvalId.value)
                assertThat(continuation).isNotNull
                assertThat(continuation!!.workflowRunId).isEqualTo("smoke-run-1")
            }
        }
    }

    // ── Inner types (scoped to this test, invisible to component scan) ──

    /**
     * Minimal workflow that demonstrates the golden-path ergonomic shape.
     *
     * This class must NOT reference any low-level store
     * (ApprovalStore, SuspendedInvocationStore, ApprovalContinuationStore,
     * or any Jdbc* variant). See the source guard in build.gradle.kts.
     */
    class ExampleApprovalWorkflow(
        private val approvalGateway: ApprovalGateway,
    ) {
        suspend fun triage(input: ClaimInput): SovereignWorkflowResult<String> =
            approvalGateway.requestApproval(
                subject = ApprovalSubject(input.claimId),
                recommendation = ApprovalRecommendation(
                    type = "claim-triage",
                    summary = "Claim requires medical review",
                ),
                requiredRole = ApproverRole("medical-reviewer"),
                workflowRunId = WorkflowRunId(input.workflowRunId),
            ).toWorkflowResult { decision ->
                "approved-by-${decision.decidedBy}"
            }
    }

    data class ClaimInput(
        val claimId: String,
        val workflowRunId: String,
    )

    @Configuration
    class SmokeTestDataSourceConfig {

        @Bean(destroyMethod = "close")
        fun smokeTestDataSource(): DataSource {
            val ds = HikariDataSource()
            ds.jdbcUrl = PgEmbeddedTestSupport.jdbcUrl
            ds.username = PgEmbeddedTestSupport.username
            ds.password = PgEmbeddedTestSupport.password
            ds.maximumPoolSize = 3
            return ds
        }
    }

    @Configuration
    class SmokeTestGatewayConfig {

        @Bean
        fun smokeTestApprovalGatewayRequestFactory(): ApprovalGatewayRequestFactory =
            SmokeTestApprovalGatewayRequestFactory(clock = Clock.systemUTC())

        @Bean
        fun exampleApprovalWorkflow(gateway: ApprovalGateway): ExampleApprovalWorkflow =
            ExampleApprovalWorkflow(gateway)
    }
}
