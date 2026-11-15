package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.orchestration.JdbcWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowAdmissionRequest
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowLifecycleStatus
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.OperatorWorkflowCancellationPolicy
import dev.tramai.spring.sovereign.ops.SovereignOpsWorkflowCancellationControlPlane
import dev.tramai.spring.sovereign.ops.WorkflowCancellationCommand
import dev.tramai.spring.sovereign.ops.WorkflowCancellationControlPlane
import dev.tramai.spring.sovereign.ops.WorkflowCancellationControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.WorkflowCancellationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(classes = [PrivilegedWorkflowCancellationConcurrencyQualificationTest.TestConfig::class])
@Tag("jdbc")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivilegedWorkflowCancellationConcurrencyQualificationTest {
    @Autowired
    private lateinit var checkpointStore: JdbcWorkflowCheckpointStore

    @Autowired
    private lateinit var controlPlane: WorkflowCancellationControlPlane

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-25T10:15:30Z"), ZoneOffset.UTC)

    private val cancelleeAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "canceller-operator",
                issuer = "https://idp.example.com",
                authenticatedAt = Instant.parse("2026-06-25T09:00:00Z"),
                capabilities = setOf(OperatorCapability.CANCEL_WORKFLOW),
                approverRoles = setOf(ApproverRole("deploy-admin")),
            ),
        )

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres =
            PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("sovereign_ops_wf_concurrency_test")
                .withUsername("test")
                .withPassword("test")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val keyBase64 = Base64.getEncoder().encodeToString(key)
            val keyFile = Files.createTempFile("sovereign-wf-key", ".key")
            Files.write(keyFile, keyBase64.toByteArray())
            keyFile.toFile().deleteOnExit()

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("tramai.sovereign.persistence.type") { "jdbc" }
            registry.add("tramai.sovereign.persistence.encryption.key-file") { keyFile.toString() }
            registry.add("tramai.sovereign.ops.mutations-enabled") { "true" }
        }
    }

    @BeforeAll
    fun runMigrations() {
        listOf(
            "tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
            "tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql",
            "tramai/persistence/jdbc/postgres/V10__approval_actor_provenance.sql",
        ).forEach { resource ->
            val sql =
                javaClass.classLoader
                    .getResourceAsStream(resource)
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("Migration not found: $resource")
            jdbcTemplate.execute(sql)
        }
        jdbcTemplate.execute(checkpointStore.createTableSql())
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_outbox, tramai_workflow_checkpoint CASCADE")
    }

    @Test
    @Suppress("LongMethod")
    fun `concurrent cancellations at barrier commit exactly one linearization point and one outbox row`() {
        runBlocking {
            val workflowName = "deploy-pipeline"
            val runId = "wf-race-1"
            saveInitialCheckpoint(workflowName, runId)

            val threadCount = 5
            val barrier = CyclicBarrier(threadCount)
            val pool = Executors.newFixedThreadPool(threadCount)

            try {
                val futures =
                    (1..threadCount).map { i ->
                        pool.submit(
                            Callable {
                                barrier.await(10, TimeUnit.SECONDS)
                                runBlocking {
                                    controlPlane.cancel(
                                        WorkflowCancellationCommand(
                                            workflowName = workflowName,
                                            runId = runId,
                                            expectedRevision = 1L,
                                            reason = "Concurrent race $i",
                                            correlationId = "corr-$i",
                                        ),
                                        cancelleeAuthority,
                                    )
                                }
                            },
                        )
                    }

                val results = futures.map { it.get(10, TimeUnit.SECONDS) }

                // Exactly one winning execution committed the initial cancellation
                val winners =
                    results
                        .filterIsInstance<WorkflowCancellationResult.Cancelled>()
                        .filter { !it.alreadyCancelled }
                assertThat(winners).hasSize(1)
                val winner = winners.first()
                assertThat(winner.revision).isEqualTo(2L)
                assertThat(winner.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)

                // Other 4 results must be either alreadyCancelled=true or Stale
                val nonWinners = results.filter { it != winner }
                assertThat(nonWinners).hasSize(4)
                nonWinners.forEach { result ->
                    val isAlreadyCancelled =
                        result is WorkflowCancellationResult.Cancelled && result.alreadyCancelled
                    val isStale =
                        result is WorkflowCancellationResult.Stale &&
                            result.expectedRevision == 1L &&
                            result.currentRevision == 2L
                    assertThat(isAlreadyCancelled || isStale).isTrue()
                }

                // Verify DB row
                val finalCheckpoint = checkpointStore.load(workflowName, runId)
                assertThat(finalCheckpoint?.revision).isEqualTo(2L)
                val lifecycle = checkpointStore.lifecycle(workflowName, runId)
                assertThat(lifecycle?.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)
                assertThat(lifecycle?.revision).isEqualTo(2L)

                // Verify outbox: exactly ONE outbox row created
                val outboxCount =
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_outbox",
                        Int::class.java,
                    ) ?: 0
                assertThat(outboxCount).isEqualTo(1)

                val outboxStatus =
                    jdbcTemplate.queryForObject(
                        "SELECT status FROM audit_outbox",
                        String::class.java,
                    ) ?: ""
                assertThat(outboxStatus).isEqualTo("PENDING")
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `post-cancellation admission is strictly fenced`() {
        runBlocking {
            val workflowName = "deploy-pipeline"
            val runId = "wf-fence-1"
            saveInitialCheckpoint(workflowName, runId)

            val cancelResult =
                controlPlane.cancel(
                    WorkflowCancellationCommand(
                        workflowName = workflowName,
                        runId = runId,
                        expectedRevision = 1L,
                        reason = "Fence test cancellation",
                    ),
                    cancelleeAuthority,
                )
            assertThat(cancelResult).isInstanceOf(WorkflowCancellationResult.Cancelled::class.java)

            // Attempt to admit work on the cancelled run
            val current = checkpointStore.load(workflowName, runId)!!
            val admission =
                checkpointStore.admit(
                    WorkflowAdmissionRequest(
                        workflowName = workflowName,
                        workflowId = runId,
                        expectedRevision = current.revision,
                        expectedGeneration = current.checkpointGeneration,
                    ),
                )

            // Post-cancellation admission must be refused
            assertThat(admission.admitted).isFalse()
            assertThat(admission.cancellation?.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)
            assertThat(admission.sequence).isEqualTo(0L)
        }
    }

    @Test
    fun `cancellation after admitted effects derives CANCELLED_UNCERTAIN`() {
        runBlocking {
            val workflowName = "deploy-pipeline"
            val runId = "wf-uncertain-1"
            val initial = saveInitialCheckpoint(workflowName, runId)

            // Admit effect
            val admission =
                checkpointStore.admit(
                    WorkflowAdmissionRequest(
                        workflowName = workflowName,
                        workflowId = runId,
                        expectedRevision = initial.revision,
                        expectedGeneration = initial.checkpointGeneration,
                    ),
                )
            assertThat(admission.admitted).isTrue()

            // Cancel
            val cancelResult =
                controlPlane.cancel(
                    WorkflowCancellationCommand(
                        workflowName = workflowName,
                        runId = runId,
                        expectedRevision = initial.revision,
                        reason = "Cancelled after admission",
                    ),
                    cancelleeAuthority,
                )

            assertThat(cancelResult).isInstanceOf(WorkflowCancellationResult.Cancelled::class.java)
            val cancelled = cancelResult as WorkflowCancellationResult.Cancelled
            assertThat(cancelled.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN)
            assertThat(cancelled.revision).isEqualTo(2L)

            val lifecycle = checkpointStore.lifecycle(workflowName, runId)
            assertThat(lifecycle?.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN)

            val outboxStatus =
                jdbcTemplate.queryForObject(
                    "SELECT status FROM audit_outbox",
                    String::class.java,
                ) ?: ""
            assertThat(outboxStatus).isEqualTo("PENDING")
        }
    }

    private suspend fun saveInitialCheckpoint(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint {
        val checkpoint =
            WorkflowCheckpoint(
                workflowName = workflowName,
                workflowId = workflowId,
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = "{}",
                savedAtEpochMillis = clock.millis(),
            )
        return checkpointStore.save(checkpoint)
    }

    @Configuration(proxyBeanMethods = false)
    open class DataSourceConfig {
        @Bean
        open fun dataSource(
            @Value("\${spring.datasource.url}") url: String,
            @Value("\${spring.datasource.username}") username: String,
            @Value("\${spring.datasource.password}") password: String,
        ): DataSource =
            PGSimpleDataSource().apply {
                setUrl(url)
                setUser(username)
                setPassword(password)
            }

        @Bean
        open fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

        @Bean
        open fun operatorWorkflowCancellationPolicy(): OperatorWorkflowCancellationPolicy =
            OperatorWorkflowCancellationPolicy(
                mapOf("deploy-pipeline" to setOf(ApproverRole("deploy-admin"))),
            )
    }

    @SpringBootConfiguration
    @Import(
        DataSourceConfig::class,
        SovereignJdbcPersistenceAutoConfiguration::class,
        WorkflowCancellationControlPlaneAutoConfiguration::class,
    )
    open class TestConfig
}
