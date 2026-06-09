package dev.tramai.examples.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowStateCodec
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowState
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowCheckpointConflictException
import dev.tramai.orchestration.WorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowPersistence
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.Comparator
import kotlin.io.path.createTempDirectory

/**
 * Verifies that cancel() retries checkpoint persistence
 * when the underlying store throws [WorkflowCheckpointConflictException] or
 * [OverlappingFileLockException] on the first CANCELLED save.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(CheckpointConflictRetryConfiguration::class)
class CheckpointConflictRetryTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var checkpointStore: CheckpointConflictRetryStore

    @BeforeEach
    fun resetStore() {
        checkpointStore.reset()
    }

    @Test
    fun `cancellation retries on WorkflowCheckpointConflictException for first CANCELLED save`() {
        checkpointStore.failWith = WorkflowCheckpointConflictException::class.java
        checkpointStore.failOnSaveOfMetadataStatus = "CANCELLED"
        checkpointStore.failOnAttempt = 1

        val workflowId = "wf-cancel-conflict-retry-1001"
        startAndCancel(workflowId)

        verifyCancelledImmediately(workflowId)
        verifySecondCancelIdempotent(workflowId)
        verifyCheckpointStatusCancelled(workflowId)
    }

    @Test
    fun `cancellation retries on OverlappingFileLockException for first CANCELLED save`() {
        checkpointStore.failWith = OverlappingFileLockException::class.java
        checkpointStore.failOnSaveOfMetadataStatus = "CANCELLED"
        checkpointStore.failOnAttempt = 1

        val workflowId = "wf-cancel-lock-retry-1002"
        startAndCancel(workflowId)

        verifyCancelledImmediately(workflowId)
        verifySecondCancelIdempotent(workflowId)
        verifyCheckpointStatusCancelled(workflowId)
    }

    @Test
    fun `immediate cancellation still exposes CANCELLED immediately`() {
        checkpointStore.failWith = null // no failure

        val workflowId = "wf-cancel-immediate-retry-1003"
        startAndCancel(workflowId)

        verifyCancelledImmediately(workflowId)
    }

    @Test
    fun `second cancellation request remains idempotent`() {
        checkpointStore.failWith = null // no failure

        val workflowId = "wf-cancel-idempotent-retry-1004"
        startAndCancel(workflowId)

        verifySecondCancelIdempotent(workflowId)
    }

    @Test
    fun `checkpoint metadata remains CANCELLED after retry success`() {
        checkpointStore.failWith = WorkflowCheckpointConflictException::class.java
        checkpointStore.failOnSaveOfMetadataStatus = "CANCELLED"
        checkpointStore.failOnAttempt = 1

        val workflowId = "wf-cancel-meta-retry-1005"
        startAndCancel(workflowId)

        verifyCheckpointStatusCancelled(workflowId)
    }

    // -- helper methods -------------------------------------------------

    private fun startAndCancel(workflowId: String) {
        mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "workflowId": "$workflowId",
                      "invoiceText": "[[CANCEL_ME]] Vendor: Northwind Power"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.workflowId").value(workflowId))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    private fun verifyCancelledImmediately(workflowId: String) {
        asyncJson(get("/invoice/workflow/result/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    private fun verifySecondCancelIdempotent(workflowId: String) {
        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.workflowId").value(workflowId))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    private fun verifyCheckpointStatusCancelled(workflowId: String) {
        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("CANCELLED"))
    }

    private fun asyncJson(requestBuilder: org.springframework.test.web.servlet.RequestBuilder): ResultActions {
        val mvcResult = mockMvc.perform(requestBuilder)
            .andExpect(request().asyncStarted())
            .andReturn()
        return mockMvc.perform(asyncDispatch(mvcResult))
    }

    companion object {
        private val workflowRoot: Path = createTempDirectory("tramai-example-conflict-retry")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("tramai.example.workflow.persistence-root") { workflowRoot.toString() }
            registry.add("tramai.example.workflow.lease-owner-id") { "conflict-retry-test-node" }
        }

        @JvmStatic
        @AfterAll
        fun cleanupWorkflowRoot() {
            Files.walk(workflowRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder())
                    .forEach(Files::deleteIfExists)
            }
        }
    }
}

/**
 * A [WorkflowCheckpointStore] wrapper that can be configured to throw
 * on a specific save of a checkpoint containing a specific metadata status,
 * then succeed on retry.
 *
 * This only intercepts saves where the checkpoint's metadata contains
 * [failOnSaveOfMetadataStatus] (e.g., "CANCELLED"), so normal workflow
 * checkpoint saves (without status metadata) pass through unimpeded.
 */
class CheckpointConflictRetryStore(
    private val delegate: InMemoryWorkflowCheckpointStore,
) : WorkflowCheckpointStore {

    /** Set to a specific exception class to throw, or null for pass-through. */
    var failWith: Class<out Throwable>? = null

    /**
     * Only fail when the checkpoint being saved has this value in its
     * "workflow_status" metadata entry. Null means fail on ANY save.
     */
    var failOnSaveOfMetadataStatus: String? = "CANCELLED"

    /** The save attempt number (matching the status filter) to fail on (1-based). */
    var failOnAttempt: Int = 1

    private val matchedSaveAttempts = AtomicInteger(0)

    fun reset() {
        matchedSaveAttempts.set(0)
    }

    override suspend fun load(workflowName: String, workflowId: String): WorkflowCheckpoint? =
        delegate.load(workflowName, workflowId)

    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint {
        val targetStatus = failOnSaveOfMetadataStatus
        val shouldCount = targetStatus == null ||
            checkpoint.metadata["workflow_status"] == targetStatus

        if (shouldCount) {
            val attempt = matchedSaveAttempts.incrementAndGet()
            val failClass = failWith
            if (failClass != null && attempt == failOnAttempt) {
                val cause = when (failClass) {
                    WorkflowCheckpointConflictException::class.java -> WorkflowCheckpointConflictException(
                        "Simulated conflict on save attempt $attempt for checkpoint '${checkpoint.workflowName}/${checkpoint.workflowId}'"
                    )
                    OverlappingFileLockException::class.java -> OverlappingFileLockException()
                    else -> throw IllegalArgumentException("Unsupported failure type: $failClass")
                }
                throw cause
            }
        }

        return delegate.save(checkpoint, expectedRevision)
    }

    override suspend fun delete(workflowName: String, workflowId: String, expectedRevision: Long?) {
        delegate.delete(workflowName, workflowId, expectedRevision)
    }
}

/**
 * Test configuration that replaces the persistence bean with one using
 * [CheckpointConflictRetryStore] so tests can induce save failures.
 */
@TestConfiguration
class CheckpointConflictRetryConfiguration {

    @Bean
    @Primary
    fun conflictRetryCheckpointStore(): CheckpointConflictRetryStore {
        return CheckpointConflictRetryStore(InMemoryWorkflowCheckpointStore())
    }

    @Bean
    @Primary
    fun conflictRetryPersistence(
        checkpointStore: CheckpointConflictRetryStore,
        objectMapper: ObjectMapper,
    ): WorkflowPersistence<InvoiceWorkflowState> {
        return WorkflowPersistence(
            checkpointStore = checkpointStore,
            stateCodec = InvoiceWorkflowStateCodec(objectMapper),
            deleteCheckpointOnCompletion = false,
        )
    }
}
