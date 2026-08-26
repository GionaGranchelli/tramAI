package dev.tramai.examples.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowCoordinator
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
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

    // ================================================================
    // Retry-success tests
    // ================================================================

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

        assertThat(checkpointStore.matchedSaveAttempts()).isGreaterThanOrEqualTo(2)
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

        assertThat(checkpointStore.matchedSaveAttempts()).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `load OverlappingFileLockException retries successfully`() {
        checkpointStore.failLoadWith = OverlappingFileLockException::class.java
        checkpointStore.failLoadOnWorkflowId = "wf-load-ole-2005"

        val workflowId = "wf-load-ole-2005"
        startAndCancel(workflowId)

        verifyCancelledImmediately(workflowId)
        verifyCheckpointStatusCancelled(workflowId)
    }

    // ================================================================
    // Retry-exhaustion test
    // ================================================================

    @Test
    fun `80 concurrent conflicts during cancellation returns HTTP conflict`() {
        checkpointStore.failWith = WorkflowCheckpointConflictException::class.java
        checkpointStore.failOnSaveOfMetadataStatus = "CANCELLED"
        checkpointStore.failOnAttempt = -1 // always fail

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

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_conflict"))

        assertThat(checkpointStore.matchedSaveAttempts()).isGreaterThanOrEqualTo(80)
    }

    // ================================================================
    // Terminal-state race guard tests  (deterministic via beforeSaveHook)
    // ================================================================

    @Test
    fun `concurrent COMPLETED transition during cancellation throws WorkflowNotRunningException`() {
        val workflowId = "wf-race-completed-3001"

        startWorkflowOnly(workflowId)
        Thread.sleep(200)

        checkpointStore.beforeSaveHook = { checkpoint, expectedRev ->
            if (checkpoint.metadata["workflow_status"] == "CANCELLED") {
                val current = checkpointStore.load(
                    checkpoint.workflowName, checkpoint.workflowId,
                )
                if (current != null) {
                    checkpointStore.saveDirect(
                        current.copy(
                            metadata = linkedMapOf(
                                "workflow_status" to "COMPLETED",
                                "workflow_updated_at" to "2026-06-10T00:00:00Z",
                            ),
                        ),
                        expectedRevision = current.revision,
                    )
                }
            }
        }
        // No failWith — the hook injects COMPLETED on EVERY CANCELLED save,
        // causing delegate.save() to conflict on revision mismatch.
        // persistStatusMetadata also hits the hook, so it cannot silently
        // overwrite the race condition.

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_not_running"))

        assertThat(checkpointStore.matchedSaveAttempts())
            .describedAs("at least one CANCELLED save must have been attempted")
            .isGreaterThanOrEqualTo(1)

        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("COMPLETED"))
    }

    @Test
    fun `concurrent FAILED transition during cancellation throws WorkflowNotRunningException`() {
        val workflowId = "wf-race-failed-3002"

        startWorkflowOnly(workflowId)
        Thread.sleep(200)

        checkpointStore.beforeSaveHook = { checkpoint, expectedRev ->
            if (checkpoint.metadata["workflow_status"] == "CANCELLED") {
                val current = checkpointStore.load(
                    checkpoint.workflowName, checkpoint.workflowId,
                )
                if (current != null) {
                    checkpointStore.saveDirect(
                        current.copy(
                            metadata = linkedMapOf(
                                "workflow_status" to "FAILED",
                                "workflow_updated_at" to "2026-06-10T00:00:00Z",
                                "workflow_error" to "Worker crashed",
                            ),
                        ),
                        expectedRevision = current.revision,
                    )
                }
            }
        }
        // No failWith — hook on every CANCELLED save keeps the race condition
        // from being silently overwritten by persistStatusMetadata.

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_not_running"))

        assertThat(checkpointStore.matchedSaveAttempts())
            .describedAs("at least one CANCELLED save must have been attempted")
            .isGreaterThanOrEqualTo(1)

        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("FAILED"))
    }

    // ================================================================
    // Stale-error cleanup test
    // ================================================================

    @Test
    fun `stale workflow_error is cleared by persistCancelledCheckpoint`() {
        val workflowId = "wf-stale-error-4001"

        // Start the workflow so it creates its own initial checkpoint.
        startWorkflowOnly(workflowId)
        Thread.sleep(200)

        // Stamp a stale workflow_error onto the existing checkpoint by saving
        // a copy with the stale error at the current revision. The revision
        // bump causes the workflow's next save to retry, which reloads and
        // preserves the error. persistCancelledCheckpoint then clears it.
        runBlocking {
            val current = checkpointStore.load(
                InvoiceWorkflowCoordinator.WORKFLOW_NAME, workflowId,
            )
            if (current != null) {
                checkpointStore.saveDirect(
                    current.copy(
                        metadata = current.metadata + mapOf(
                            "workflow_error" to "stale-error-must-be-cleared",
                        ),
                    ),
                    expectedRevision = current.revision,
                )
            }
        }

        // Verify stale error was actually set before cancellation
        val preCancelCheckpoint = runBlocking {
            checkpointStore.load(InvoiceWorkflowCoordinator.WORKFLOW_NAME, workflowId)
        }
        requireNotNull(preCancelCheckpoint) { "checkpoint must exist before cancellation" }
        assertThat(preCancelCheckpoint.metadata["workflow_error"])
            .isEqualTo("stale-error-must-be-cleared")

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isAccepted)

        val checkpointJson = asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("CANCELLED"))
            .andReturn()
            .response
            .contentAsString

        val metadata = objectMapper.readTree(checkpointJson)["metadata"]
        val errorValue = metadata.get("workflow_error")
        if (errorValue != null) {
            assertThat(errorValue.asText())
                .describedAs("stale workflow_error must not survive cancellation")
                .isNotEqualTo("stale-error-must-be-cleared")
        }
    }

    // ================================================================
    // Post-terminal public fallback tests
    // ================================================================

    @Test
    fun `COMPLETED checkpoint is never overwritten by cancellation via public path`() {
        checkpointStore.failWith = null

        val workflowId = "wf-completed-no-overwrite-2002"
        val invoiceText = "Vendor: Northwind Power\\nInvoice: INV-1042\\nAmount due: 4820 USD\\nDue date: 2026-04-30\\nStatus: 12 days overdue"
        val invoiceJson = invoiceText.replace("\\n", "\\\\n")

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

        pollForStatus(workflowId, "COMPLETED")

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_not_running"))

        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("COMPLETED"))
    }

    @Test
    fun `FAILED checkpoint is never overwritten by cancellation via public path`() {
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

        pollForStatus(workflowId, "FAILED")

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_not_running"))

        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("FAILED"))
    }

    @Test
    fun `concurrent CANCELLED transition during COMPLETED persistence stays CANCELLED`() {
        val workflowId = "wf-inverse-race-3003"
        val invoiceText = "Vendor: Northwind Power\\nInvoice: INV-1042\\nAmount due: 4820 USD\\nDue date: 2026-04-30\\nStatus: 12 days overdue"
        val invoiceJson = invoiceText.replace("\\n", "\\\\n")

        // Hook on COMPLETED saves: inject CANCELLED before the save,
        // causing a concurrent-cancellation race instead of a normal COMPLETED.
        val hookFired = AtomicBoolean(false)
        checkpointStore.beforeSaveHook = { checkpoint, expectedRev ->
            if (checkpoint.metadata["workflow_status"] == "COMPLETED" && hookFired.compareAndSet(false, true)) {
                val current = checkpointStore.load(
                    checkpoint.workflowName, checkpoint.workflowId,
                )
                if (current != null) {
                    checkpointStore.saveDirect(
                        current.copy(
                            metadata = linkedMapOf(
                                "workflow_status" to "CANCELLED",
                                "workflow_updated_at" to "2026-06-10T00:00:00Z",
                            ),
                        ),
                        expectedRevision = current.revision,
                    )
                }
            }
        }

        // Start a normal workflow that will complete successfully
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

        // Wait for completion + guard to fire
        // Poll the checkpoint endpoint (not result — loadRun hides CANCELLED
        // as long as the coroutine is still active)
        var pollDelay = 200L
        var checkpointCancelled = false

        for (attempt in 0 until 240) {
            val response = asyncJson(
                get("/invoice/workflow/checkpoint/$workflowId"),
            )
                .andReturn()
                .response

            when (response.status) {
                200 -> {
                    val node = objectMapper.readTree(response.contentAsString)
                    val status = node["metadata"]
                        ?.get("workflow_status")
                        ?.asText()

                    if (status == "CANCELLED") {
                        checkpointCancelled = true
                        break
                    }
                }

                404 -> {
                    // The workflow was accepted, but its initial checkpoint has
                    // not yet been persisted. Retry during startup.
                }

                else -> error(
                    "Unexpected checkpoint response ${response.status}: " +
                        response.contentAsString,
                )
            }

            Thread.sleep(pollDelay)
            pollDelay = (pollDelay * 1.5)
                .toLong()
                .coerceAtMost(1_000)
        }

        assertThat(checkpointCancelled)
            .describedAs("checkpoint should eventually show CANCELLED")
            .isTrue()

        assertThat(hookFired.get()).isTrue()

        // Final durable checkpoint must be CANCELLED
        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("CANCELLED"))
    }

    // ================================================================
    // Idempotent / immediate tests
    // ================================================================

    @Test
    fun `immediate cancellation still exposes CANCELLED immediately`() {
        checkpointStore.failWith = null

        val workflowId = "wf-cancel-immediate-retry-1003"
        startAndCancel(workflowId)

        verifyCancelledImmediately(workflowId)
    }

    @Test
    fun `second cancellation request remains idempotent`() {
        checkpointStore.failWith = null

        val workflowId = "wf-cancel-idempotent-retry-1004"
        startAndCancel(workflowId)

        verifySecondCancelIdempotent(workflowId)
    }

    // ================================================================
    // Helper methods
    // ================================================================

    /** Start a workflow but do NOT cancel it. The coroutine runs in background with [[CANCEL_ME]]. */
    private fun startWorkflowOnly(workflowId: String) {
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
    }

    private fun startAndCancel(workflowId: String) {
        startWorkflowOnly(workflowId)

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

// ====================================================================
// CheckpointConflictRetryStore — injectable failure wrapper
// ====================================================================

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

    /**
     * Optional callback invoked before every save().
     * Receives (checkpoint, expectedRevision). Can mutate state on the delegate
     * store to simulate concurrent transitions.
     */
    var beforeSaveHook: (suspend (WorkflowCheckpoint, Long?) -> Unit)? = null

    private val matchedSaveAttempts = AtomicInteger(0)
    private val loadFailuresConsumed = AtomicInteger(0)

    fun reset() {
        matchedSaveAttempts.set(0)
        loadFailuresConsumed.set(0)

        failWith = null
        failOnSaveOfMetadataStatus = "CANCELLED"
        failOnAttempt = 1

        failLoadWith = null
        failLoadOnWorkflowId = null

        beforeSaveHook = null
    }

    /** Returns the number of CANCELLED save attempts (successful + failed). */
    fun matchedSaveAttempts(): Int = matchedSaveAttempts.get()

    /**
     * Seed a synthetic checkpoint directly on the delegate store.
     * Useful for pre-seeding state before cancellation.
     */
    fun seedCheckpoint(workflowName: String, workflowId: String, metadata: Map<String, String>) {
        runBlocking {
            delegate.save(
                WorkflowCheckpoint(
                    workflowName = workflowName,
                    workflowId = workflowId,
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = "",
                    metadata = metadata,
                ),
                expectedRevision = null,
            )
        }
    }

    /**
     * Save directly on the delegate, bypassing all hooks and failure injection.
     * Used by [beforeSaveHook] to simulate concurrent transitions.
     */
    suspend fun saveDirect(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = delegate.save(checkpoint, expectedRevision)

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
        // Run the before-hook first so it can mutate delegate state
        beforeSaveHook?.invoke(checkpoint, expectedRevision)

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

    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedGeneration: String?,
    ) {
        delegate.delete(workflowName, workflowId, expectedRevision, expectedGeneration)
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
