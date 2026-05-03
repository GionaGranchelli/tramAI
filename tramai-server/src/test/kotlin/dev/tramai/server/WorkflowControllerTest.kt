package dev.tramai.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
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
                jsonPath("$.status") { value("completed") }
                jsonPath("$.definitionVersion") { value("1.0.0") }
            }
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
        val workflowId = startInvoiceWorkflow("inv-cancel")

        mockMvc.delete("/workflows/invoice/runs/$workflowId")
            .andExpect {
                status { isAccepted() }
                jsonPath("$.workflowId") { value(workflowId) }
                jsonPath("$.status") { value("cancelled") }
            }
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
                jsonPath("$.result.invoiceId") { value("inv-two") }
            }

        mockMvc.post("/workflows/refund/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refundId":"ref-1","reason":"duplicate"}"""
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.result.refundId") { value("ref-1") }
            }
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
