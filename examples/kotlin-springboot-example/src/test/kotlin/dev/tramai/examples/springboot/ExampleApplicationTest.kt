package dev.tramai.examples.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.nativeimage.NativeImageProxyConfig
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.StreamCapable
import dev.tramai.examples.springboot.ai.InvoiceAnalyzer
import dev.tramai.examples.springboot.workflow.InvoiceWorkflowCoordinator
import dev.tramai.testing.MockAiProvider
import dev.tramai.testing.RecordedRequestProvider
import dev.tramai.testing.RecordingOperationObserver
import dev.tramai.testing.SimulatedFailureProvider
import dev.tramai.testing.TramaiAssertions
import dev.tramai.standalone.Tramai
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createTempDirectory

/**
 * Verifies that the example application exposes the documented TramAI features end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(ExampleApplicationTest.TestProviderConfiguration::class)
class ExampleApplicationTest {
    @Autowired
    private lateinit var analyzer: InvoiceAnalyzer

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var testProvider: ExampleTestProvider

    @Autowired
    private lateinit var observer: RecordingOperationObserver

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun resetProvider() {
        testProvider.reset()
    }

    @Test
    fun `context loads with tramai ai service bean`() {
        assertThat(analyzer).isNotNull
    }

    @Test
    fun `summary endpoint returns raw text response`() {
        asyncJson(
            post("/invoice/summary")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"invoiceText":"Vendor: Northwind Power\nInvoice: INV-1042"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary").value("Northwind Power invoice INV-1042 needs review."))

        TramaiAssertions.assertThat(testProvider, observer)
            .whenCalled("summarize")
            .wasCalledTimes(1)
    }

    @Test
    fun `streaming service returns incremental summary chunks`() {
        val chunks = runBlocking {
            analyzer.streamSummarize("Vendor: Northwind Power\nInvoice: INV-1042").toList()
        }

        assertThat(chunks).containsExactly(
            StreamChunk.Token("Northwind Power "),
            StreamChunk.Token("invoice INV-1042 needs review."),
            StreamChunk.Complete(
                "Northwind Power invoice INV-1042 needs review.",
                UsageMetrics(inputTokens = 12, outputTokens = 7),
            ),
        )
        assertThat(testProvider.streamRequests).hasSize(1)
        assertThat(testProvider.streamRequests.single().operationMethod).isEqualTo("streamSummarize")
    }

    @Test
    fun `tool-enabled endpoint executes a tool loop before returning the answer`() {
        asyncJson(
            post("/invoice/enrich")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "invoiceText": "Vendor: Acme\nInvoice: INV-123\nAmount: 1200 USD\nPlease verify terms."
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enrichment").value("Vendor Acme Corp is highly reliable (4.8/5) and typically works on NET-30 terms."))

        TramaiAssertions.assertThat(testProvider, observer)
            .whenCalled("enrich")
            .wasCalledTimes(2)
            .andCalledTool("vendor_lookup")

        val enrichRequests = testProvider.requests.filter { it.operationMethod == "enrich" }
        assertThat(enrichRequests.first().messages.any { it.role == MessageRole.TOOL }).isFalse()
        assertThat(enrichRequests.last().messages.any { it.role == MessageRole.TOOL }).isTrue()
    }

    @Test
    fun `triage endpoint returns typed structured response`() {
        asyncJson(
            post("/invoice/triage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30"
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary").value("Invoice INV-1042 from Northwind Power is overdue."))
            .andExpect(jsonPath("$.status").value("OVERDUE"))
            .andExpect(jsonPath("$.priority").value("HIGH"))
            .andExpect(jsonPath("$.needsImmediateAttention").value(true))
            .andExpect(jsonPath("$.riskScore").value(4))
            .andExpect(jsonPath("$.facts.invoiceId").value("INV-1042"))
            .andExpect(jsonPath("$.facts.vendor").value("Northwind Power"))
            .andExpect(jsonPath("$.facts.amountDueText").value("4820 USD"))
            .andExpect(jsonPath("$.facts.dueDate").value("2026-04-30"))
            .andExpect(jsonPath("$.nextStep").value("ESCALATE"))

        TramaiAssertions.assertThat(testProvider, observer)
            .whenCalled("triage")
            .wasCalledTimes(1)
    }

    @Test
    fun `workflow endpoint composes raw structured and tool-enabled steps with persisted checkpoint`() {
        val workflowId = "wf-1042"

        asyncJson(
            post("/invoice/workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "workflowId": "$workflowId",
                          "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workflowId").value(workflowId))
            .andExpect(jsonPath("$.result.summary").value("Northwind Power invoice INV-1042 needs review."))
            .andExpect(jsonPath("$.result.triage.status").value("OVERDUE"))
            .andExpect(jsonPath("$.result.handlingLane").value("ESCALATION"))
            .andExpect(jsonPath("$.result.enrichment").value("Vendor Northwind Power is stable and usually works on NET-30 (Standard) terms."))
            .andExpect(jsonPath("$.result.operatorBrief").value(org.hamcrest.Matchers.containsString("ESCALATION")))

        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workflowName").value(InvoiceWorkflowCoordinator.WORKFLOW_NAME))
            .andExpect(jsonPath("$.workflowId").value(workflowId))
            .andExpect(jsonPath("$.nextStepIndex").value(4))
            .andExpect(jsonPath("$.lastCompletedStepName").value("finalize"))
            .andExpect(jsonPath("$.metadata.workflow_status").value("COMPLETED"))

        asyncJson(post("/invoice/workflow/resume/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workflowId").value(workflowId))
            .andExpect(jsonPath("$.result.handlingLane").value("ESCALATION"))

        val workflowDirectory = workflowRoot
            .resolve(InvoiceWorkflowCoordinator.WORKFLOW_NAME)
            .resolve(workflowId)
        assertThat(Files.exists(workflowDirectory.resolve("checkpoint.md"))).isTrue()
        assertThat(Files.exists(workflowDirectory.resolve("lease.properties"))).isFalse()
    }

    @Test
    fun `workflow start endpoint returns accepted and result endpoint reaches completed`() {
        val workflowId = "wf-start-2001"

        val startResponse = mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "workflowId": "$workflowId",
                          "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue"
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)
            .andReturn()
            .response
            .contentAsString

        val started = objectMapper.readTree(startResponse)
        assertThat(started["workflowId"].asText()).isEqualTo(workflowId)
        assertThat(started["status"].asText()).isEqualTo("PENDING")

        var completedPayload: String? = null
        repeat(200) {
            val resultPayload = asyncJson(get("/invoice/workflow/result/$workflowId"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
            val resultNode = objectMapper.readTree(resultPayload)
            val statusValue = resultNode["status"].asText()
            if (statusValue == "COMPLETED") {
                completedPayload = resultPayload
                return@repeat
            }
            Thread.sleep(50)
        }

        val completed = objectMapper.readTree(completedPayload ?: error("workflow did not complete in time"))
        assertThat(completed["status"].asText()).isEqualTo("COMPLETED")
        assertThat(completed["result"]["handlingLane"].asText()).isEqualTo("ESCALATION")
        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("COMPLETED"))
    }

    @Test
    fun `workflow start rejects duplicate workflow id while first run is active`() {
        val workflowId = "wf-duplicate-3001"
        val slowInvoice = "[[SLOW_SUMMARY]] Vendor: Northwind Power\nInvoice: INV-1042"
        val slowInvoiceJson = slowInvoice.replace("\n", "\\n")

        mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "workflowId": "$workflowId",
                          "invoiceText": "$slowInvoiceJson"
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)

        mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "workflowId": "$workflowId",
                          "invoiceText": "$slowInvoiceJson"
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("workflow_already_running"))
    }

    @Test
    fun `workflow start reports failed status and persisted error when a step fails`() {
        val workflowId = "wf-failed-4001"
        val failingInvoice = "[[FAIL_TIMEOUT]] Vendor: Northwind Power\nInvoice: INV-1042"
        val failingInvoiceJson = failingInvoice.replace("\n", "\\n")

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

        var failedPayload: String? = null
        repeat(200) {
            val resultPayload = asyncJson(get("/invoice/workflow/result/$workflowId"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
            val resultNode = objectMapper.readTree(resultPayload)
            val statusValue = resultNode["status"].asText()
            if (statusValue == "FAILED") {
                failedPayload = resultPayload
                return@repeat
            }
            Thread.sleep(50)
        }

        val failed = objectMapper.readTree(failedPayload ?: error("workflow did not fail in time"))
        assertThat(failed["status"].asText()).isEqualTo("FAILED")
        assertThat(failed["errorMessage"].asText()).contains("simulated timeout")
        assertThat(failed["result"].isNull).isTrue()

        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("FAILED"))
            .andExpect(jsonPath("$.metadata.workflow_error").value(org.hamcrest.Matchers.containsString("simulated timeout")))
    }

    @Test
    fun `workflow list includes recent workflow runs`() {
        val completedWorkflowId = "wf-list-completed-5001"

        asyncJson(
            post("/invoice/workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "workflowId": "$completedWorkflowId",
                          "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue"
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)

        asyncJson(get("/invoice/workflow/list?limit=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.workflowId=='$completedWorkflowId')].status").value(org.hamcrest.Matchers.hasItem("COMPLETED")))
    }

    @Test
    fun `workflow cancel stops active run and persists cancelled status`() {
        val workflowId = "wf-cancel-6001"
        val cancellableInvoice = "[[CANCEL_ME]] Vendor: Northwind Power\nInvoice: INV-1042"
        val cancellableInvoiceJson = cancellableInvoice.replace("\n", "\\n")

        mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "workflowId": "$workflowId",
                          "invoiceText": "$cancellableInvoiceJson"
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)

        asyncJson(post("/invoice/workflow/cancel/$workflowId"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.workflowId").value(workflowId))
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        var cancelledPayload: String? = null
        repeat(200) {
            val resultPayload = asyncJson(get("/invoice/workflow/result/$workflowId"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
            val resultNode = objectMapper.readTree(resultPayload)
            if (resultNode["status"].asText() == "CANCELLED") {
                cancelledPayload = resultPayload
                return@repeat
            }
            Thread.sleep(50)
        }

        val cancelled = objectMapper.readTree(cancelledPayload ?: error("workflow did not cancel in time"))
        assertThat(cancelled["status"].asText()).isEqualTo("CANCELLED")
        assertThat(cancelled["result"].isNull).isTrue()

        asyncJson(get("/invoice/workflow/checkpoint/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.metadata.workflow_status").value("CANCELLED"))
    }

    @Test
    fun `workflow events endpoint returns lifecycle events for async run`() {
        val workflowId = "wf-events-7001"

        mockMvc.perform(
            post("/invoice/workflow/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "workflowId": "$workflowId",
                          "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue"
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)

        var completed = false
        repeat(200) {
            val resultPayload = asyncJson(get("/invoice/workflow/result/$workflowId"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
            if (objectMapper.readTree(resultPayload)["status"].asText() == "COMPLETED") {
                completed = true
                return@repeat
            }
            Thread.sleep(50)
        }
        assertThat(completed).isTrue()

        asyncJson(get("/invoice/workflow/events/$workflowId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.type=='ACCEPTED')]").isNotEmpty)
            .andExpect(jsonPath("$[?(@.type=='RUN_STARTED')]").isNotEmpty)
            .andExpect(jsonPath("$[?(@.type=='RUN_COMPLETED')]").isNotEmpty)
            .andExpect(jsonPath("$[*].status").value(org.hamcrest.Matchers.hasItem("COMPLETED")))
    }

    @Test
    fun `workflow events endpoint returns not found for unknown workflow`() {
        asyncJson(get("/invoice/workflow/events/does-not-exist"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("workflow_not_found"))
    }

    @Test
    fun `health endpoint describes the example routes`() {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.product").value("TramAI"))
            .andExpect(jsonPath("$.capabilities[?(@.capability=='structured-output')]").isNotEmpty)
            .andExpect(jsonPath("$.docs[?(@.label=='Manual')]").isNotEmpty)
            .andExpect(jsonPath("$.streamEndpoint").value("POST /invoice/summary/stream"))
            .andExpect(jsonPath("$.workflowEndpoint").value("POST /invoice/workflow"))
            .andExpect(jsonPath("$.workflowStartEndpoint").value("POST /invoice/workflow/start"))
            .andExpect(jsonPath("$.workflowResultEndpoint").value("GET /invoice/workflow/result/{workflowId}"))
            .andExpect(jsonPath("$.workflowListEndpoint").value("GET /invoice/workflow/list"))
            .andExpect(jsonPath("$.workflowCancelEndpoint").value("POST /invoice/workflow/cancel/{workflowId}"))
            .andExpect(jsonPath("$.workflowEventsEndpoint").value("GET /invoice/workflow/events/{workflowId}"))
    }

    @Test
    fun `native image proxy config stays in sync with the ai service contract`() {
        val proxyConfigPath = Path.of(
            "src/main/resources/META-INF/native-image/dev.tramai.examples/kotlin-springboot-example/proxy-config.json",
        )

        assertThat(Files.readString(proxyConfigPath).trim())
            .isEqualTo(NativeImageProxyConfig.json(InvoiceAnalyzer::class).trim())
    }

    @TestConfiguration(proxyBeanMethods = false)
    open class TestProviderConfiguration {
        @Bean
        open fun testProvider(): ExampleTestProvider = ExampleTestProvider()

        @Bean
        open fun recordingOperationObserver(): RecordingOperationObserver = RecordingOperationObserver()
    }

    companion object {
        private val workflowRoot: Path = createTempDirectory("tramai-example-workflows")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("tramai.example.workflow.persistence-root") { workflowRoot.toString() }
            registry.add("tramai.example.workflow.lease-owner-id") { "example-test-node" }
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

    private fun asyncJson(requestBuilder: org.springframework.test.web.servlet.RequestBuilder): ResultActions {
        val mvcResult = mockMvc.perform(requestBuilder)
            .andExpect(request().asyncStarted())
            .andReturn()
        return mockMvc.perform(asyncDispatch(mvcResult))
    }
}

/**
 * Composite test provider that uses [MockAiProvider] and [SimulatedFailureProvider]
 * internally for standard completions, with custom streaming, tool loop, and
 * special marker support for workflow tests.
 */
class ExampleTestProvider : ModelProvider, StreamCapable, RecordedRequestProvider {
    /** All [ModelRequest] objects received via [complete], in invocation order. */
    override val requests = mutableListOf<ModelRequest>()

    /** All [ModelRequest] objects received via [stream], in invocation order. */
    val streamRequests = mutableListOf<ModelRequest>()

    private var nextToolCallId = 1

    /** Mock provider for standard text completions (summarize, triage). */
    private val mockProvider = MockAiProvider {
        onMethod("summarize") respondWith "Northwind Power invoice INV-1042 needs review."
        onMethod("triage") respondWith """
            {
              "summary": "Invoice INV-1042 from Northwind Power is overdue.",
              "status": { "name": "OVERDUE", "ordinal": 2 },
              "priority": { "name": "HIGH", "ordinal": 2 },
              "needsImmediateAttention": true,
              "riskScore": 4,
              "facts": {
                "invoiceId": "INV-1042",
                "vendor": "Northwind Power",
                "amountDueText": "4820 USD",
                "dueDate": "2026-04-30"
              },
              "nextStep": { "name": "ESCALATE", "ordinal": 4 }
            }
        """.trimIndent()
    }

    /** Failure provider for retryable failure scenarios. */
    private val failProvider = SimulatedFailureProvider {
        onMethod("summarize").retryableFailure("simulated timeout while summarizing", statusCode = 504)
    }

    fun reset() {
        requests.clear()
        streamRequests.clear()
        nextToolCallId = 1
    }

    override fun providerId(): String = "ollama"

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request

        val promptInput = request.messages.last().content

        // Special markers for workflow tests
        if (request.operationMethod == "summarize") {
            if (promptInput.contains("[[FAIL_TIMEOUT]]")) {
                // Delegate to SimulatedFailureProvider which throws ProviderException
                return failProvider.complete(request)
            }
            if (promptInput.contains("[[SLOW_SUMMARY]]")) {
                delay(500)
            }
            if (promptInput.contains("[[CANCEL_ME]]")) {
                delay(15_000)
            }
        }

        return when (request.operationMethod) {
            "summarize" -> mockProvider.complete(request)
            "triage" -> triageResponse(request)
            "enrich" -> enrichResponse(request)
            else -> error("Unsupported operation method '${request.operationMethod}' in example test provider")
        }
    }

    override suspend fun stream(request: ModelRequest): Flow<StreamChunk> {
        streamRequests += request
        return flow {
            emit(StreamChunk.Token("Northwind Power "))
            emit(StreamChunk.Token("invoice INV-1042 needs review."))
            emit(
                StreamChunk.Complete(
                    fullText = "Northwind Power invoice INV-1042 needs review.",
                    usage = UsageMetrics(inputTokens = 12, outputTokens = 7),
                ),
            )
        }
    }

    private suspend fun triageResponse(request: ModelRequest): ModelResponse {
        val promptInput = request.messages.last().content
        val disputed = promptInput.contains("Blue Harbor Logistics")

        return if (disputed) {
            ModelResponse(
                content = """
                    {
                      "summary": "Invoice BH-7781 from Blue Harbor Logistics is disputed.",
                      "status": { "name": "DISPUTED", "ordinal": 3 },
                      "priority": { "name": "MEDIUM", "ordinal": 1 },
                      "needsImmediateAttention": false,
                      "riskScore": 3,
                      "facts": {
                        "invoiceId": "BH-7781",
                        "vendor": "Blue Harbor Logistics",
                        "amountDueText": "1299 EUR",
                        "dueDate": "2026-05-12"
                      },
                      "nextStep": { "name": "INVESTIGATE", "ordinal": 1 }
                    }
                """.trimIndent(),
            )
        } else {
            mockProvider.complete(request)
        }
    }

    private fun enrichResponse(request: ModelRequest): ModelResponse {
        val hasToolMessage = request.messages.any { it.role == MessageRole.TOOL }
        if (!hasToolMessage) {
            val invoiceText = request.messages.last().content
            val vendorName = when {
                invoiceText.contains("Acme", ignoreCase = true) -> "Acme"
                else -> "Northwind Power"
            }
            return ModelResponse(
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tool-${nextToolCallId++}",
                        name = "vendor_lookup",
                        argumentsJson = """{"vendorName":"$vendorName"}""",
                    ),
                ),
            )
        }

        val toolPayload = request.messages.last { it.role == MessageRole.TOOL }.content
        return when {
            toolPayload.contains("Acme Corp") -> ModelResponse(
                content = "Vendor Acme Corp is highly reliable (4.8/5) and typically works on NET-30 terms.",
            )
            else -> ModelResponse(
                content = "Vendor Northwind Power is stable and usually works on NET-30 (Standard) terms.",
            )
        }
    }
}
