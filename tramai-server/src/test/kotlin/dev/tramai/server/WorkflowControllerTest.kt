package dev.tramai.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import kotlinx.coroutines.Job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import javax.sql.DataSource

@SpringBootTest(
    classes = [
        TramaiServerApplication::class,
        WorkflowControllerTest.TestWorkflowConfiguration::class,
    ],
)
@AutoConfigureMockMvc
class WorkflowControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `run workflow with valid JSON starts workflow and returns workflow id`() {
        mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"invoiceId":"inv-1","amount":125}"""
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.workflowId") { exists() }
                jsonPath("$.status") { value("running") }
                jsonPath("$.definitionVersion") { value("1.0.0") }
            }
            .andReturn()
            .response
            .contentAsString
            .let { objectMapper.readTree(it).get("workflowId").asText() }
            .also { waitForStatus("invoice", it, "completed") }
    }

    @Test
    fun `run workflow with invalid JSON returns problem detail`() {
        mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"invoiceId":"""
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.type") { value("https://tramai.dev/problems/400") }
                jsonPath("$.title") { value("Invalid workflow request") }
                jsonPath("$.status") { value(400) }
                jsonPath("$.detail") { exists() }
            }
    }

    @Test
    fun `list workflow runs returns paginated run summaries`() {
        mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"invoiceId":"inv-list","amount":42}"""
        }.andExpect {
            status { isOk() }
        }

        mockMvc.get("/workflows/invoice/runs") {
            param("offset", "0")
            param("limit", "10")
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.workflowName") { value("invoice") }
                jsonPath("$.runs[0].workflowId") { exists() }
            }
    }

    @Test
    fun `delete workflow run cancels workflow and returns accepted`() {
        val workflowId = startDelayWorkflow("delay-cancel")

        mockMvc.delete("/workflows/resume-delay/runs/$workflowId")
            .andExpect {
                status { isAccepted() }
                jsonPath("$.workflowId") { value(workflowId) }
                jsonPath("$.status") { value("cancelled") }
            }
    }

    @Test
    fun `cancel transitions dispatch through event for sse subscribers`() {
        val store = WorkflowRunStore(maxHistorySize = 10, sseEventBufferSize = 100)
        val workflowId = "cancel-sse-dispatch"
        store.create(
            workflowName = "invoice",
            workflowId = workflowId,
            definitionVersion = "1.0.0",
            idempotencyKey = null,
        )
        store.event("invoice", workflowId, "tramai.workflow.running", status = WorkflowRunStatus.RUNNING)

        store.cancel("invoice", workflowId)

        val run = store.get("invoice", workflowId)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(run.history.map { it.name }).contains(
            "tramai.workflow.cancelling",
            "tramai.workflow.cancelled",
        )
    }

    @Test
    fun `sse payload serializes null step name as json null`() {
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().findAndRegisterModules()
        val event = WorkflowRunEvent(
            sequence = 1,
            name = "tramai.workflow.running",
            stepName = null,
            timestamp = java.time.Instant.now(),
        )
        val json = mapper.writeValueAsString(
            mapOf("stepName" to event.stepName, "timestamp" to event.timestamp.toString())
        )
        assertThat(json).contains("\"stepName\":null")
    }

    @Test
    fun `sse event buffer independent from canonical run history`() {
        val store = WorkflowRunStore(maxHistorySize = 10, sseEventBufferSize = 2)
        val workflowId = "history-buffer"
        store.create(
            workflowName = "invoice",
            workflowId = workflowId,
            definitionVersion = "1.0.0",
            idempotencyKey = null,
        )

        store.event("invoice", workflowId, "event-1")
        store.event("invoice", workflowId, "event-2")
        store.event("invoice", workflowId, "event-3")

        assertThat(store.get("invoice", workflowId).history.map { it.name })
            .containsExactly("event-1", "event-2", "event-3")
    }

    @Test
    fun `idempotency key returns same run id`() {
        val first = mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "invoice-once")
            content = """{"invoiceId":"inv-idempotent","amount":77}"""
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val second = mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "invoice-once")
            content = """{"invoiceId":"inv-idempotent","amount":77}"""
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        assertThat(objectMapper.readTree(second).get("workflowId").asText())
            .isEqualTo(objectMapper.readTree(first).get("workflowId").asText())
    }

    @Test
    fun `two different workflow types register and are accessible`() {
        mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"invoiceId":"inv-two","amount":20}"""
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString
            .let { objectMapper.readTree(it).get("workflowId").asText() }
            .also { waitForStatus("invoice", it, "completed") }

        mockMvc.post("/workflows/refund/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refundId":"ref-1","reason":"duplicate"}"""
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString
            .let { objectMapper.readTree(it).get("workflowId").asText() }
            .also { waitForStatus("refund", it, "completed") }
    }

    @Test
    fun `resume delayed workflow returns running and completes resumed run`() {
        val workflowId = startDelayWorkflow("delay-resume")

        Thread.sleep(30)

        mockMvc.post("/workflows/resume-delay/runs/$workflowId/resume")
            .andExpect {
                status { isOk() }
                jsonPath("$.workflowId") { value(workflowId) }
                jsonPath("$.status") { value("running") }
            }

        waitForStatus("resume-delay", workflowId, "completed")
    }

    @Test
    fun `resume rejects run that is not delayed or waiting for gate`() {
        val workflowId = startInvoiceWorkflow("inv-resume-reject")
        waitForStatus("invoice", workflowId, "completed")

        mockMvc.post("/workflows/invoice/runs/$workflowId/resume")
            .andExpect {
                status { isConflict() }
                jsonPath("$.title") { value("Workflow conflict") }
            }
    }

    @Test
    fun `concurrent resume transition only allows one caller`() {
        val store = WorkflowRunStore(maxHistorySize = 10)
        val workflowId = "resume-race"
        store.create(
            workflowName = "invoice",
            workflowId = workflowId,
            definitionVersion = "1.0.0",
            idempotencyKey = null,
        )
        store.event("invoice", workflowId, "tramai.workflow.delayed", status = WorkflowRunStatus.DELAYED)

        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val resumed = AtomicInteger(0)
        val rejected = AtomicInteger(0)

        val attempts = (1..8).map {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    store.markResuming("invoice", workflowId)
                    resumed.incrementAndGet()
                } catch (_: WorkflowConflictException) {
                    rejected.incrementAndGet()
                }
            }
        }

        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue()
        start.countDown()
        attempts.forEach { it.get(1, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertThat(resumed.get()).isEqualTo(1)
        assertThat(rejected.get()).isEqualTo(7)
        assertThat(store.get("invoice", workflowId).status).isEqualTo(WorkflowRunStatus.RUNNING)
    }

    @Test
    fun `concurrent idempotency reservation creates one run`() {
        val store = WorkflowRunStore(maxHistorySize = 10)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        val creations = (1..8).map {
            executor.submit<WorkflowRunCreation> {
                ready.countDown()
                start.await()
                store.getOrCreate(
                    workflowName = "invoice",
                    workflowId = UUID.randomUUID().toString(),
                    definitionVersion = "1.0.0",
                    idempotencyKey = "same-key",
                )
            }
        }

        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue()
        start.countDown()
        val results = creations.map { it.get(1, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertThat(results.count { it.created }).isEqualTo(1)
        assertThat(results.map { it.record.workflowId }.toSet()).hasSize(1)
    }

    @Test
    fun `delete completed workflow run is rejected`() {
        val workflowId = startInvoiceWorkflow("inv-cancel-completed")
        waitForStatus("invoice", workflowId, "completed")

        mockMvc.delete("/workflows/invoice/runs/$workflowId")
            .andExpect {
                status { isConflict() }
                jsonPath("$.title") { value("Workflow conflict") }
            }
    }

    @Test
    fun `unknown workflow returns not found problem detail`() {
        mockMvc.get("/workflows/missing/runs")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.title") { value("Workflow resource not found") }
            }
    }

    @Test
    fun `unexpected error handler redacts raw exception message`() {
        val response = WorkflowErrorHandler()
            .unexpected(IllegalStateException("database password leaked"))
            .body

        assertThat(response?.detail).isEqualTo("Workflow execution failed unexpectedly")
    }

    @Test
    fun `failed run detail redacts raw exception message`() {
        val store = WorkflowRunStore(maxHistorySize = 10)
        val workflowId = "failed-redacted"
        store.create(
            workflowName = "invoice",
            workflowId = workflowId,
            definitionVersion = "1.0.0",
            idempotencyKey = null,
        )

        val failed = store.fail("invoice", workflowId, IllegalStateException("database password leaked"))

        assertThat(failed.error).isEqualTo("Workflow execution failed")
        assertThat(store.get("invoice", workflowId).error).isEqualTo("Workflow execution failed")
    }

    @Test
    fun `async completion does not overwrite cancelled status`() {
        val store = WorkflowRunStore(maxHistorySize = 10)
        val workflowId = "cancelled-terminal"
        store.create(
            workflowName = "invoice",
            workflowId = workflowId,
            definitionVersion = "1.0.0",
            idempotencyKey = null,
        )
        store.event("invoice", workflowId, "tramai.workflow.running", status = WorkflowRunStatus.RUNNING)
        store.cancel("invoice", workflowId)

        val completed = store.complete("invoice", workflowId, InvoiceResult("inv-cancelled", accepted = true))
        val failed = store.fail("invoice", workflowId, IllegalStateException("late failure"))

        assertThat(completed.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(failed.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(store.get("invoice", workflowId).status).isEqualTo(WorkflowRunStatus.CANCELLED)
    }

    @Test
    fun `cancel signals attached execution job`() {
        val store = WorkflowRunStore(maxHistorySize = 10)
        val workflowId = "cancel-job"
        val job = Job()
        store.create(
            workflowName = "invoice",
            workflowId = workflowId,
            definitionVersion = "1.0.0",
            idempotencyKey = null,
        )
        store.event("invoice", workflowId, "tramai.workflow.running", status = WorkflowRunStatus.RUNNING)
        store.attachExecution("invoice", workflowId, job)

        store.cancel("invoice", workflowId)

        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `oversized request body returns problem detail`() {
        mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            content = "x".repeat(1_048_577)
        }
            .andExpect {
                status { isPayloadTooLarge() }
                jsonPath("$.type") { value("https://tramai.dev/problems/413") }
                jsonPath("$.title") { value("Request body too large") }
                jsonPath("$.status") { value(413) }
                jsonPath("$.detail") { value("Request body is too large") }
            }
    }

    @Test
    fun `completed run stores result accessible via get run detail`() {
        val workflowId = startInvoiceWorkflow("inv-result")
        waitForStatus("invoice", workflowId, "completed")

        mockMvc.get("/workflows/invoice/runs/$workflowId")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("completed") }
                jsonPath("$.result.invoiceId") { value("inv-result") }
                jsonPath("$.result.accepted") { value(true) }
            }
    }

    @Test
    fun `pagination boundary beyond available runs returns empty page`() {
        mockMvc.get("/workflows/invoice/runs") {
            param("offset", "100000")
            param("limit", "1")
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.offset") { value(100000) }
                jsonPath("$.runs.length()") { value(0) }
            }
    }

    @Test
    fun `invalid JSON with idempotency key does not reserve run`() {
        mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "invalid-first")
            content = """{"invoiceId":"""
        }
            .andExpect {
                status { isBadRequest() }
            }

        val workflowId = mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "invalid-first")
            content = """{"invoiceId":"inv-after-invalid","amount":11}"""
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.workflowId") { exists() }
            }
            .andReturn()
            .response
            .contentAsString
            .let { objectMapper.readTree(it).get("workflowId").asText() }

        waitForStatus("invoice", workflowId, "completed")
    }

    @Test
    fun `openapi json returns valid openapi document`() {
        mockMvc.get("/openapi.json")
            .andExpect {
                status { isOk() }
                jsonPath("$.openapi") { value("3.1.0") }
                jsonPath("$.paths['/workflows/invoice/run'].post.summary") { value("Start invoice workflow") }
                jsonPath("$.paths['/workflows/refund/run'].post.summary") { value("Start refund workflow") }
            }
    }

    @Test
    fun `sse events endpoint streams workflow execution`() {
        val workflowId = startInvoiceWorkflow("inv-sse")
        val mvcResult = mockMvc.get("/workflows/invoice/runs/$workflowId/events")
            .andExpect {
                status { isOk() }
                header { string("Content-Type", "text/event-stream") }
            }
            .andReturn()

        waitForStatus("invoice", workflowId, "completed")

        val responseBody = mvcResult.response.contentAsString
        assertThat(responseBody).contains("event:tramai.workflow.started")
        assertThat(responseBody).contains("event:tramai.step.started")
        assertThat(responseBody).contains("event:tramai.step.completed")
        assertThat(responseBody).contains("event:tramai.workflow.completed")

        val lastEventId = responseBody.lines().last { it.startsWith("id:") }.substring(3).trim()

        val reconnMvcResult = mockMvc.get("/workflows/invoice/runs/$workflowId/events") {
            header("Last-Event-ID", lastEventId)
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()

        assertThat(reconnMvcResult.response.contentAsString).doesNotContain("event:tramai.workflow.started")
    }

    private fun startInvoiceWorkflow(invoiceId: String): String {
        val response = mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"invoiceId":"$invoiceId","amount":12}"""
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString
        return objectMapper.readTree(response).get("workflowId").asText()
    }

    private fun startDelayWorkflow(invoiceId: String): String {
        val response = mockMvc.post("/workflows/resume-delay/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"invoiceId":"$invoiceId","amount":12}"""
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("running") }
            }
            .andReturn()
            .response
            .contentAsString
        val workflowId = objectMapper.readTree(response).get("workflowId").asText()
        waitForStatus("resume-delay", workflowId, "delayed")
        return workflowId
    }

    private fun waitForStatus(
        workflowName: String,
        workflowId: String,
        expectedStatus: String,
    ) {
        repeat(50) {
            val response = mockMvc.get("/workflows/$workflowName/runs/$workflowId")
                .andExpect {
                    status { isOk() }
                }
                .andReturn()
                .response
                .contentAsString
            if (objectMapper.readTree(response).get("status").asText() == expectedStatus) {
                return
            }
            Thread.sleep(20)
        }
        error("Workflow '$workflowName' run '$workflowId' did not reach status '$expectedStatus'")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestWorkflowConfiguration {
        @Bean
        fun dataSource(): DataSource {
            val dataSource = org.h2.jdbcx.JdbcDataSource()
            dataSource.setURL("jdbc:h2:mem:tramai-server;DB_CLOSE_DELAY=-1")
            dataSource.user = "sa"
            dataSource.password = ""
            return dataSource
        }

        @Bean
        fun testWorkflows(objectMapper: ObjectMapper): WorkflowRegistration = WorkflowRegistration { registry ->
            val invoiceStore = InMemoryWorkflowCheckpointStore()
            val invoiceCodec = JsonWorkflowStateCodec(objectMapper, InvoiceState::class)
            registry.register(
                workflow = workflow<InvoiceState>(
                    name = "invoice",
                    definitionVersion = "1.0.0",
                ) {
                    localStep("validate") { state, _ ->
                        state.copy(validated = true)
                    }
                }.build { state ->
                    InvoiceResult(
                        invoiceId = state.invoiceId,
                        accepted = state.validated && state.amount > 0,
                    )
                },
                stateCodec = invoiceCodec,
                defaultPersistence = {
                    WorkflowPersistence(
                        checkpointStore = invoiceStore,
                        stateCodec = invoiceCodec,
                    )
                },
            )

            val refundStore = InMemoryWorkflowCheckpointStore()
            val refundCodec = JsonWorkflowStateCodec(objectMapper, RefundState::class)
            registry.register(
                workflow = workflow<RefundState>(
                    name = "refund",
                    definitionVersion = "1.0.0",
                ) {
                    localStep("accept") { state, _ ->
                        state.copy(accepted = state.reason.isNotBlank())
                    }
                }.build { state ->
                    RefundResult(
                        refundId = state.refundId,
                        accepted = state.accepted,
                    )
                },
                stateCodec = refundCodec,
                defaultPersistence = {
                    WorkflowPersistence(
                        checkpointStore = refundStore,
                        stateCodec = refundCodec,
                    )
                },
            )

            val delayStore = InMemoryWorkflowCheckpointStore()
            val delayCodec = JsonWorkflowStateCodec(objectMapper, InvoiceState::class)
            registry.register(
                workflow = workflow<InvoiceState>(
                    name = "resume-delay",
                    definitionVersion = "1.0.0",
                ) {
                    delayStep("pause", 10, TimeUnit.MILLISECONDS)
                    localStep("validate") { state, _ ->
                        state.copy(validated = true)
                    }
                }.build { state ->
                    InvoiceResult(
                        invoiceId = state.invoiceId,
                        accepted = state.validated && state.amount > 0,
                    )
                },
                stateCodec = delayCodec,
                defaultPersistence = {
                    WorkflowPersistence(
                        checkpointStore = delayStore,
                        stateCodec = delayCodec,
                    )
                },
            )
        }
    }
}

data class InvoiceState(
    val invoiceId: String,
    val amount: Int,
    val validated: Boolean = false,
)

data class InvoiceResult(
    val invoiceId: String,
    val accepted: Boolean,
)

data class RefundState(
    val refundId: String,
    val reason: String,
    val accepted: Boolean = false,
)

data class RefundResult(
    val refundId: String,
    val accepted: Boolean,
)

class JsonWorkflowStateCodec<S : Any>(
    private val objectMapper: ObjectMapper,
    private val type: KClass<S>,
) : WorkflowStateCodec<S> {
    override fun encode(state: S): String = objectMapper.writeValueAsString(state)

    override fun decode(payload: String): S = objectMapper.readValue(payload, type.java)
}
