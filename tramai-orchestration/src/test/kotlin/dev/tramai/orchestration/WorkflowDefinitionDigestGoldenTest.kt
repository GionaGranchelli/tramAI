package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Frozen definition-compatibility golden values (Epic 4.2 guard).
 *
 * The digest and metadata keys below are contractual: they are persisted into
 * durable checkpoints and compared on resume. A file-split refactor must not
 * change them. If this test needs updating, that is a checkpoint-format
 * breaking change, not a refactor side effect.
 */
class WorkflowDefinitionDigestGoldenTest {

    private fun representativeWorkflow(): Workflow<GoldenState, String> = workflow<GoldenState>(
        name = "golden-repr",
        definitionVersion = "golden-v1",
    ) {
        localStep("first") { state, _ -> state.copy(values = state.values + "first") }
        gateStep("gate") { _, _ -> GateDecision.allow() }
        branchStep("branch", select = { "a" }) {
            branch("a") {
                localStep("branch-a") { state, _ -> state.copy(values = state.values + "a") }
            }
            default {
                localStep("branch-default") { state, _ -> state.copy(values = state.values + "d") }
            }
        }
        parallelStep("parallel", items = { listOf(1, 2) }, invoke = { it * 2 }, merge = { s, o -> s.copy(values = s.values + o.map { it.toString() }) })
        delayStep("delay", duration = 5, unit = TimeUnit.SECONDS)
    }.build(stopPolicy = StopPolicy(maxStepExecutions = 10, maxParallelBranches = 4)) {
        it.values.joinToString(",")
    }

    @Test
    fun `representative workflow digest is frozen`() {
        val metadata = representativeWorkflow().checkpointMetadata()
        assertThat(metadata).containsEntry("tramai.workflow.definition.version", "golden-v1")
        assertThat(metadata).containsEntry("tramai.workflow.definition.digest.algorithm", "SHA-256")
        val digest = metadata.getValue("tramai.workflow.definition.digest")
        assertThat(digest).hasSize(64)
        // FROZEN VALUE — see class KDoc
        assertThat(digest).isEqualTo("45936b1234bd699af9a8a409f4d90d06d55cdb206328e69ac23f332fa0aa3bcb")
    }

    @Test
    fun `delay metadata keys are frozen`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = GoldenStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflowId = "golden-delay-1"
        runBlocking {
            try {
                representativeWorkflow().run(
                    initialState = GoldenState(listOf("start")),
                    context = WorkflowContext(workflowId = workflowId),
                    persistence = persistence,
                )
            } catch (_: WorkflowSuspendedException) {
                // expected: delay suspends
            }
        }
        val checkpoint = runBlocking { store.load("golden-repr", workflowId) }
        assertThat(checkpoint).isNotNull
        assertThat(checkpoint!!.metadata).containsKey("tramai.workflow.delay.step")
        assertThat(checkpoint.metadata).containsKey("tramai.workflow.delay.resume_at_epoch_millis")
    }
}

data class GoldenState(
    val values: List<String> = emptyList(),
)

object GoldenStateCodec : WorkflowStateCodec<GoldenState> {
    override fun encode(state: GoldenState): String = state.values.joinToString(",")
    override fun decode(payload: String): GoldenState = GoldenState(payload.split(",").filter { it.isNotBlank() }.map { it })
}
