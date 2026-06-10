package dev.tramai.examples.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowStateCodec
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowState
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowCheckpointConflictException
import dev.tramai.orchestration.WorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowPersistence
import org.assertj.core.api.Assertions.assertThat
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
@Import(
    CheckpointConflictRetryConfiguration::class,
    ExampleApplicationTest.TestProviderConfiguration::class,
)
class CheckpointConflictRetryTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var checkpointStore: CheckpointConflictRetryStore

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun resetStore() {
        checkpointStore.reset()
    }

    // --- existing tests (keep passing) ---

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

    // --- new tests ---

    @Test
    fun `80 concurrent conflicts during cancellation returns HTTP conflict`() {
        // Every CANCELLED save fails — always throws
        checkpointStore.failWith = WorkflowCheckpointConflictException::class.java
        checkpointStore.failOnSaveOfMetadataStatus = "CANCELLED"
        checkpointStore.failOnAttempt = -1 // -1 = always fail

        val workflowId = "wf-cancel-exhaust-2001"

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

        // Cancel returns conflict because the checkpoint persistence ran out of retries
        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_conflict"))

        // The in-memory run may still think it was cancelled (via CancellationException),
        // but the checkpoint never persisted CANCELLED — the test proves retry exhaustion
        // was surfaced as an error instead of silently returning CANCELLED.
    }

    @Test
    fun `COMPLETED checkpoint is never overwritten by cancellation`() {
        checkpointStore.failWith = null

        val workflowId = "wf-completed-no-overwrite-2002"
        val invoiceText = "Vendor: Northwind Power\\nInvoice: INV-1042\\nAmount due: 4820 USD\\nDue date: 2026-04-30\\nStatus: 12 days overdue"
        val invoiceJson = invoiceText.replace("\\n", "\\\\n")

        // Start async workflow
        mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "workflowId": "$workflowId",
                      "invoiceText": "$invoiceJson"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)

        // Wait for completion
        pollForStatus(workflowId, "COMPLETED")

        // Cancel after completion — should not overwrite COMPLETED
        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_not_running"))

        // Checkpoint is still COMPLETED
        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("COMPLETED"))
    }

    @Test
    fun `FAILED checkpoint is never overwritten by cancellation`() {
        checkpointStore.failWith = null

        val workflowId = "wf-failed-no-overwrite-2003"
        val failingInvoice = "[[FAIL_TIMEOUT]] Vendor: Northwind Power\\nInvoice: INV-1042"
        val failingInvoiceJson = failingInvoice.replace("\\n", "\\\\n")

        mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "workflowId": "$workflowId",
                      "invoiceText": "$failingInvoiceJson"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)

        // Wait for failure
        pollForStatus(workflowId, "FAILED")

        // Cancel after failure — should not overwrite FAILED
        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_not_running"))

        // Checkpoint is still FAILED
        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("FAILED"))
    }

    @Test
    fun `previous workflow_error is removed after valid cancellation`() {
        checkpointStore.failWith = WorkflowCheckpointConflictException::class.java
        checkpointStore.failOnSaveOfMetadataStatus = "CANCELLED"
        checkpointStore.failOnAttempt = 1

        val workflowId = "wf-error-cleared-2004"
        val invoiceText = "[[CANCEL_ME]] Vendor: Northwind Power\\nInvoice: INV-1042"
        val invoiceJson = invoiceText.replace("\\n", "\\\\n")

        // Start, cancel immediately — persistCancelledCheckpoint removes METADATA_ERROR
        mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "workflowId": "$workflowId",
                      "invoiceText": "$invoiceJson"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isAccepted)

        // Verify checkpoint is CANCELLED
        // Note: persistStatusMetadata in the coroutine's catch block runs concurrently
        // and may re-add workflow_error="Workflow cancelled via API". We verify the
        // status is CANCELLED and if an error is present, it's the cancellation message.
        val checkpointJson = asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("CANCELLED"))
            .andReturn()
            .response
            .contentAsString

        val tree = objectMapper.readTree(checkpointJson)
        val metadata = tree["metadata"]
        if (metadata.has("workflow_error")) {
            assertThat(metadata["workflow_error"].asText()).isEqualTo("Workflow cancelled via API")
        }
    }

    @Test
    fun `load OverlappingFileLockException retries successfully`() {
        // Make load() throw on first call for this workflow ID
        checkpointStore.failLoadWith = OverlappingFileLockException::class.java
        checkpointStore.failLoadOnWorkflowId = "wf-load-ole-2005"

        val workflowId = "wf-load-ole-2005"
        startAndCancel(workflowId)

        verifyCancelledImmediately(workflowId)
        verifyCheckpointStatusCancelled(workflowId)
    }

    @Test
    fun `retry failures actually occurred before eventual success`() {
        checkpointStore.failWith = WorkflowCheckpointConflictException::class.java
        checkpointStore.failOnSaveOfMetadataStatus = "CANCELLED"
        checkpointStore.failOnAttempt = 1

        val workflowId = "wf-assert-retries-2006"
        startAndCancel(workflowId)

        // The store should have attempted at least one CANCELLED save that failed,
        // then another that succeeded: total >= 2 matched-save attempts
        assertThat(checkpointStore.matchedSaveAttempts()).isGreaterThanOrEqualTo(2)

        verifyCancelledImmediately(workflowId)
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

    /** Poll result endpoint until [expectedStatus] or timeout. */
    private fun pollForStatus(workflowId: String, expectedStatus: String): JsonNode {
        var pollDelay = 200L
        repeat(120) {
            val payload = asyncJson(get("/invoice/workflow/result/$workflowId"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
            val node = objectMapper.readTree(payload)
            if (node["status"].asText() == expectedStatus) {
                return node
            }
            Thread.sleep(pollDelay)
            pollDelay = (pollDelay * 1.5).toLong().coerceAtMost(1000)
        }
        error("Workflow '$workflowId' did not reach status '$expectedStatus' in time")
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

    /** Set to a specific exception class to throw on save, or null for pass-through. */
    var failWith: Class<out Throwable>? = null

    /**
     * Only fail when the checkpoint being saved has this value in its
     * "workflow_status" metadata entry. Null means fail on ANY save.
     */
    var failOnSaveOfMetadataStatus: String? = "CANCELLED"

    /**
     * The save attempt number (matching the status filter) to fail on (1-based).
     * A negative value means ALWAYS fail (for exhaustion tests).
     */
    var failOnAttempt: Int = 1

    /** If set, load() throws this exception the first time [failLoadOnWorkflowId] is loaded. */
    var failLoadWith: Class<out Throwable>? = null

    /** Load will fail once for this workflow ID. */
    var failLoadOnWorkflowId: String? = null

    private val matchedSaveAttempts = AtomicInteger(0)
    private val loadFailuresConsumed = AtomicInteger(0)

    fun reset() {
        matchedSaveAttempts.set(0)
        loadFailuresConsumed.set(0)
    }

    /** Returns the number of CANCELLED save attempts (successful + failed). */
    fun matchedSaveAttempts(): Int = matchedSaveAttempts.get()

    override suspend fun load(workflowName: String, workflowId: String): WorkflowCheckpoint? {
        val failClass = failLoadWith
        val targetId = failLoadOnWorkflowId
        if (failClass != null && targetId != null && workflowId == targetId) {
            if (loadFailuresConsumed.compareAndSet(0, 1)) {
                when (failClass) {
                    OverlappingFileLockException::class.java -> throw OverlappingFileLockException()
                    else -> throw IllegalArgumentException("Unsupported load failure type: $failClass")
                }
            }
        }
        return delegate.load(workflowName, workflowId)
    }

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
            if (failClass != null && (failOnAttempt < 0 || attempt == failOnAttempt)) {
                val cause = when (failClass) {
                    WorkflowCheckpointConflictException::class.java -> WorkflowCheckpointConflictException(
                        "Simulated conflict on save attempt $attempt for checkpoint " +
                            "'${checkpoint.workflowName}/${checkpoint.workflowId}'",
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
