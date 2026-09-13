package dev.tramai.orchestration

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * 0.7.1d governed-run attribution continuity.
 *
 * One governed execution establishes exactly one [GovernedRunIdentity]; that same
 * immutable identity must survive execution, checkpoint persistence, restart and
 * resume. Every test here fails if an identity check is weakened to a run-id-only
 * comparison, to a subset of the deployment identity, or if a resume regenerates
 * the run id.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernedRunAttributionTest {
    private companion object {
        const val JDBC_URL = "jdbc:h2:mem:governed-attribution;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        const val WORKFLOW_NAME = "claims-triage"
    }

    private val tempRoots = mutableListOf<Path>()
    private val dataSource: DataSource =
        JdbcDataSource().apply {
            setURL(JDBC_URL)
            user = "sa"
            password = ""
        }

    init {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(JdbcWorkflowCheckpointStore(dataSource).createTableSql()) }
        }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching {
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("DROP TABLE IF EXISTS tramai_workflow_checkpoint") }
            }
        }
        tempRoots.forEach { root -> runCatching { root.toFile().deleteRecursively() } }
    }

    // ── Fixtures ────────────────────────────────────────────────────

    private fun deployment(
        workload: String = "claims",
        configurationId: String = "claims-prod",
        configurationVersion: String = "17",
        environment: String = "production",
        deploymentId: String = "eu-west-amsterdam-01",
    ): WorkloadDeploymentIdentity =
        WorkloadDeploymentIdentity(
            workloadId = WorkloadId(workload),
            configuration =
                WorkloadConfigurationIdentity(
                    id = ConfigurationId(configurationId),
                    version = ConfigurationVersion(configurationVersion),
                ),
            environmentId = EnvironmentId(environment),
            deploymentId = DeploymentId(deploymentId),
        )

    private val stateCodec =
        object : WorkflowStateCodec<String> {
            override fun encode(state: String): String = state

            override fun decode(payload: String): String = payload
        }

    private fun checkpoint(
        workflowId: String,
        metadata: Map<String, String> = emptyMap(),
    ): WorkflowCheckpoint =
        WorkflowCheckpoint(
            workflowName = WORKFLOW_NAME,
            workflowId = workflowId,
            nextStepIndex = 1,
            stepExecutions = 1,
            lastCompletedStepName = "one",
            statePayload = "state",
            revision = 0,
            metadata = metadata,
            savedAtEpochMillis = 1_000L,
        )

    private fun stores(): List<Pair<String, WorkflowCheckpointStore>> {
        val fileRoot = Files.createTempDirectory("governed-file-").toAbsolutePath()
        val markdownRoot = Files.createTempDirectory("governed-md-").toAbsolutePath()
        tempRoots.addAll(listOf(fileRoot, markdownRoot))
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM tramai_workflow_checkpoint") }
        }
        return listOf(
            "inMemory" to InMemoryWorkflowCheckpointStore(),
            "file" to FileWorkflowCheckpointStore(fileRoot),
            "markdown" to MarkdownWorkflowCheckpointStore(markdownRoot),
            "jdbc" to JdbcWorkflowCheckpointStore(dataSource),
        )
    }

    // ── The envelope is the boundary ────────────────────────────────

    @Test
    fun `a governed envelope refuses a run id that is not its context workflow id`() {
        assertThatThrownBy {
            GovernedRun(
                context = WorkflowContext(workflowId = "run-a"),
                identity = GovernedRunIdentity(deployment = deployment(), runId = RunId("run-b")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must equal")
    }

    @Test
    fun `start establishes one identity whose run id is the context workflow id`() {
        val run = GovernedRun.start(deployment())

        assertThat(run.identity.runId.value).isEqualTo(run.context.workflowId)
    }

    @Test
    fun `resume never regenerates the run id`() {
        val run = GovernedRun.resume(deployment(), "run-7")

        assertThat(run.identity.runId.value).isEqualTo("run-7")
        assertThat(run.context.workflowId).isEqualTo("run-7")
    }

    // ── Persisted representation ────────────────────────────────────

    @Test
    fun `attribution encodes to reserved keys and decodes unchanged`() {
        val identity = GovernedRunIdentity(deployment = deployment(), runId = RunId("run-1"))

        val encoded = encodeGovernedRunAttribution(identity)

        assertThat(encoded.keys).containsExactlyInAnyOrderElementsOf(
            listOf(
                GOVERNED_RUN_WORKLOAD_KEY,
                GOVERNED_RUN_CONFIGURATION_KEY,
                GOVERNED_RUN_CONFIGURATION_VERSION_KEY,
                GOVERNED_RUN_ENVIRONMENT_KEY,
                GOVERNED_RUN_DEPLOYMENT_KEY,
            ),
        )
        assertThat(decodeGovernedRunAttribution("run-1", encoded)).isEqualTo(identity)
    }

    @Test
    fun `absent attribution decodes as ungoverned`() {
        assertThat(decodeGovernedRunAttribution("run-1", emptyMap())).isNull()
        // Blank values are absent too — never a partially-attributed identity.
        assertThat(
            decodeGovernedRunAttribution(
                "run-1",
                mapOf(
                    GOVERNED_RUN_WORKLOAD_KEY to " ",
                    GOVERNED_RUN_CONFIGURATION_KEY to "",
                    GOVERNED_RUN_CONFIGURATION_VERSION_KEY to "",
                    GOVERNED_RUN_ENVIRONMENT_KEY to "",
                    GOVERNED_RUN_DEPLOYMENT_KEY to "",
                ),
            ),
        ).isNull()
    }

    @Test
    fun `partial attribution fails closed instead of resuming un-attributed`() {
        val partial =
            encodeGovernedRunAttribution(
                GovernedRunIdentity(
                    deployment(),
                    RunId("run-2"),
                ),
            ).minus(GOVERNED_RUN_DEPLOYMENT_KEY)

        assertThatThrownBy { decodeGovernedRunAttribution("run-2", partial) }
            .isInstanceOf(WorkflowCheckpointCorruptionException::class.java)
            .hasMessageContaining("incomplete")
    }

    @Test
    fun `invalid attribution values fail closed`() {
        val identity = GovernedRunIdentity(deployment(), RunId("run-3"))

        assertThatThrownBy {
            decodeGovernedRunAttribution(
                "run-3",
                encodeGovernedRunAttribution(identity) + (GOVERNED_RUN_WORKLOAD_KEY to "  padded  "),
            )
        }.isInstanceOf(WorkflowCheckpointCorruptionException::class.java)
            .hasMessageContaining("invalid")
    }

    @Test
    fun `framework attribution wins over application metadata using the same reserved key`() {
        val identity = GovernedRunIdentity(deployment(), RunId("run-4"))
        val applicationMetadata = mapOf(GOVERNED_RUN_DEPLOYMENT_KEY to "attacker-deployment")

        // The exact composition the persistence session performs: application/step
        // metadata first, framework attribution last.
        val composed = applicationMetadata + encodeGovernedRunAttribution(identity)

        assertThat(decodeGovernedRunAttribution("run-4", composed)).isEqualTo(identity)
    }

    // ── Continuity gate ─────────────────────────────────────────────

    @Test
    fun `continuity holds for intentionally ungoverned runs and for the identical identity`() {
        requireGovernedRunAttributionContinuity(WORKFLOW_NAME, "run-5", persisted = null, requested = null)
        val identity = GovernedRunIdentity(deployment(), RunId("run-5"))
        requireGovernedRunAttributionContinuity(WORKFLOW_NAME, "run-5", persisted = identity, requested = identity)
    }

    @Test
    fun `a governed checkpoint cannot be resumed without attribution`() {
        val persisted = GovernedRunIdentity(deployment(), RunId("run-6"))

        assertThatThrownBy {
            requireGovernedRunAttributionContinuity(WORKFLOW_NAME, "run-6", persisted = persisted, requested = null)
        }.isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("resume carries no governed attribution")
    }

    @Test
    fun `an ungoverned checkpoint cannot be resumed as governed`() {
        assertThatThrownBy {
            requireGovernedRunAttributionContinuity(
                WORKFLOW_NAME,
                "run-6",
                persisted = null,
                requested = GovernedRunIdentity(deployment(), RunId("run-6")),
            )
        }.isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("checkpoint carries no governed attribution")
    }

    @Test
    fun `the same run id pointing at another deployment is rejected`() {
        val persisted = GovernedRunIdentity(deployment(), RunId("run-7"))
        val substituted = GovernedRunIdentity(deployment(deploymentId = "eu-central-frankfurt-01"), RunId("run-7"))

        assertThatThrownBy {
            requireGovernedRunAttributionContinuity(
                WORKFLOW_NAME,
                "run-7",
                persisted = persisted,
                requested = substituted,
            )
        }.isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("deploymentId 'eu-west-amsterdam-01' != 'eu-central-frankfurt-01'")
    }

    @Test
    fun `every substituted identity component is rejected and named`() {
        val persisted = GovernedRunIdentity(deployment(), RunId("run-8"))
        val substitutions =
            mapOf(
                "workloadId" to deployment(workload = "payments"),
                "configurationId" to deployment(configurationId = "claims-staging"),
                "configurationVersion" to deployment(configurationVersion = "18"),
                "environmentId" to deployment(environment = "staging"),
                "deploymentId" to deployment(deploymentId = "eu-central-frankfurt-01"),
            )

        substitutions.forEach { (component, substitutedDeployment) ->
            assertThatThrownBy {
                requireGovernedRunAttributionContinuity(
                    WORKFLOW_NAME,
                    "run-8",
                    persisted = persisted,
                    requested = GovernedRunIdentity(substitutedDeployment, RunId("run-8")),
                )
            }.isInstanceOf(WorkflowResumeException::class.java)
                .withFailMessage("substituting $component must be rejected")
                .hasMessageContaining(component)
        }
    }

    @Test
    fun `two deployments of the same workflow keep distinct identities`() {
        val west = GovernedRun.start(deployment(deploymentId = "eu-west-amsterdam-01"))
        val central = GovernedRun.start(deployment(deploymentId = "eu-central-frankfurt-01"))

        assertThat(west.identity).isNotEqualTo(central.identity)
        assertThat(west.identity.deployment.workloadId).isEqualTo(central.identity.deployment.workloadId)
    }

    // ── Durable round-trip in every supported store ─────────────────

    @Test
    fun `governed attribution round-trips unchanged through every checkpoint store`() =
        runBlocking<Unit> {
            stores().forEach { (label, store) ->
                val identity = GovernedRunIdentity(deployment(), RunId("$label-run"))
                val saved = store.save(checkpoint("$label-run", encodeGovernedRunAttribution(identity)))

                val loaded = store.load(WORKFLOW_NAME, "$label-run")

                assertThat(loaded).withFailMessage("$label: checkpoint must load").isNotNull()
                assertThat(decodeGovernedRunAttribution(loaded!!.workflowId, loaded.metadata))
                    .withFailMessage("$label: attribution must survive the round-trip")
                    .isEqualTo(identity)
                assertThat(saved.workflowId).isEqualTo(identity.runId.value)
            }
        }

    @Test
    fun `ungoverned checkpoints stay un-attributed on every store`() =
        runBlocking<Unit> {
            stores().forEach { (label, store) ->
                store.save(checkpoint("$label-legacy"))

                val loaded = store.load(WORKFLOW_NAME, "$label-legacy")

                assertThat(decodeGovernedRunAttribution(loaded!!.workflowId, loaded.metadata))
                    .withFailMessage("$label: ungoverned checkpoint must stay un-attributed")
                    .isNull()
            }
        }

    // ── Execution continuity through the runner ─────────────────────

    private fun workflow(): Workflow<String, String> =
        workflow<String>(WORKFLOW_NAME) {
            localStep("one") { state, _ -> "$state-one" }
            localStep("two") { state, _ -> "$state-two" }
        }.build { it }

    /** Rewinds a completed run so the next step can execute again. */
    private suspend fun rewind(
        store: InMemoryWorkflowCheckpointStore,
        workflowId: String,
        nextStepIndex: Int,
    ) {
        val current = store.load(WORKFLOW_NAME, workflowId)!!
        store.save(
            current.copy(
                nextStepIndex = nextStepIndex,
                stepExecutions = nextStepIndex,
                lastCompletedStepName = "one",
                statePayload = "seed-one",
            ),
            expectedRevision = current.revision,
        )
    }

    @Test
    fun `a governed run persists its identity and resumes with it unchanged`() =
        runBlocking<Unit> {
            val store = InMemoryWorkflowCheckpointStore()
            val persistence =
                WorkflowPersistence(
                    checkpointStore = store,
                    stateCodec = stateCodec,
                    deleteCheckpointOnCompletion = false,
                )
            val workflow = workflow()
            val run = GovernedRun.start(deployment())

            workflow.run(initialState = "seed", run = run, persistence = persistence)

            val persisted = store.load(WORKFLOW_NAME, run.context.workflowId)!!
            assertThat(decodeGovernedRunAttribution(persisted.workflowId, persisted.metadata)).isEqualTo(run.identity)

            rewind(store, run.context.workflowId, nextStepIndex = 1)
            val resumed =
                workflow.resume(
                    run = GovernedRun.resume(deployment(), run.context.workflowId),
                    persistence = persistence,
                )

            assertThat(resumed).isEqualTo("seed-one-two")
            assertThat(store.load(WORKFLOW_NAME, run.context.workflowId)!!.metadata)
                .containsEntry(GOVERNED_RUN_DEPLOYMENT_KEY, "eu-west-amsterdam-01")
        }

    @Test
    fun `resuming a governed run through a substituted deployment is rejected`() =
        runBlocking<Unit> {
            val store = InMemoryWorkflowCheckpointStore()
            val persistence =
                WorkflowPersistence(
                    checkpointStore = store,
                    stateCodec = stateCodec,
                    deleteCheckpointOnCompletion = false,
                )
            val workflow = workflow()
            val run = GovernedRun.start(deployment())
            workflow.run(initialState = "seed", run = run, persistence = persistence)
            rewind(store, run.context.workflowId, nextStepIndex = 1)

            assertThatThrownBy {
                runBlocking {
                    workflow.resume(
                        run = GovernedRun.resume(deployment(environment = "staging"), run.context.workflowId),
                        persistence = persistence,
                    )
                }
            }.isInstanceOf(WorkflowResumeException::class.java)
                .hasMessageContaining("environmentId")
        }

    @Test
    fun `resuming a governed run through an ungoverned context is rejected`() =
        runBlocking<Unit> {
            val store = InMemoryWorkflowCheckpointStore()
            val persistence =
                WorkflowPersistence(
                    checkpointStore = store,
                    stateCodec = stateCodec,
                    deleteCheckpointOnCompletion = false,
                )
            val workflow = workflow()
            val run = GovernedRun.start(deployment())
            workflow.run(initialState = "seed", run = run, persistence = persistence)
            rewind(store, run.context.workflowId, nextStepIndex = 1)

            assertThatThrownBy {
                runBlocking {
                    workflow.resume(
                        context = WorkflowContext(workflowId = run.context.workflowId),
                        persistence = persistence,
                    )
                }
            }.isInstanceOf(WorkflowResumeException::class.java)
                .hasMessageContaining("resume carries no governed attribution")
        }

    @Test
    fun `legacy ungoverned run and resume stay compatible`() =
        runBlocking<Unit> {
            val store = InMemoryWorkflowCheckpointStore()
            val persistence =
                WorkflowPersistence(
                    checkpointStore = store,
                    stateCodec = stateCodec,
                    deleteCheckpointOnCompletion = false,
                )
            val workflow = workflow()
            val context = WorkflowContext(workflowId = "legacy-run")

            workflow.run(initialState = "seed", context = context, persistence = persistence)

            assertThat(
                decodeGovernedRunAttribution(
                    "legacy-run",
                    store
                        .load(
                            WORKFLOW_NAME,
                            "legacy-run",
                        )!!
                        .metadata,
                ),
            ).isNull()
            rewind(store, "legacy-run", nextStepIndex = 1)

            assertThat(workflow.resume(context = context, persistence = persistence)).isEqualTo("seed-one-two")
        }
}
