package dev.tramai.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.orchestration.InMemoryWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import dev.tramai.server.TramaiServerApplication
import dev.tramai.server.WorkflowRegistration
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.reflect.KClass
import javax.sql.DataSource

@SpringBootTest(
    classes = [
        TramaiServerApplication::class,
        TramaiMcpAutoConfiguration::class,
        TramaiMcpServerTest.TestWorkflowConfiguration::class,
    ],
    properties = [
        "tramai.mcp.stdio.enabled=false",
        "tramai.mcp.sse.enabled=false",
    ],
)
@AutoConfigureMockMvc
class TramaiMcpServerTest @Autowired constructor(
    private val server: TramaiMcpServer,
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `mcp client discovers tools and list_workflows tool works`() = runBlocking {
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)

        server.sdkServer().createSession(
            StdioServerTransport(
                serverInput.asSource().buffered(),
                serverToClient.asSink().buffered(),
            ),
        )

        val client = Client(
            clientInfo = Implementation(
                name = "tramai-mcp-test-client",
                version = "1.0.0",
            ),
        )

        client.connect(
            StdioClientTransport(
                input = clientInput.asSource().buffered(),
                output = clientToServer.asSink().buffered(),
            ),
        )

        val tools = client.listTools().tools
        assertThat(tools.map { it.name }).contains(
            "list_workflows",
            "run_workflow",
            "resume_workflow",
            "get_workflow_status",
        )

        val workflows = client.callTool("list_workflows", emptyMap())
        assertThat(workflows.structuredContent.toString()).contains("invoice")
    }

    @Test
    fun `run and get status via REST API completes workflow`() {
        val response = mockMvc.post("/workflows/invoice/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"invoiceId":"inv-mcp-1","amount":14}"""
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.workflowId") { exists() }
                jsonPath("$.status") { value("running") }
            }
            .andReturn()
            .response
            .contentAsString

        val workflowId = objectMapper.readTree(response).get("workflowId").asText()

        waitForStatus("invoice", workflowId, "completed")

        mockMvc.get("/workflows/invoice/runs/$workflowId")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("completed") }
                jsonPath("$.result.invoiceId") { value("inv-mcp-1") }
                jsonPath("$.result.accepted") { value(true) }
            }
    }

    private fun waitForStatus(
        workflowName: String,
        workflowId: String,
        expectedStatus: String,
    ) {
        repeat(100) {
            val response = mockMvc.get("/workflows/$workflowName/runs/$workflowId")
                .andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString
            if (objectMapper.readTree(response).get("status").asText() == expectedStatus) {
                return
            }
            Thread.sleep(50)
        }
        error("Workflow '$workflowName' run '$workflowId' did not reach status '$expectedStatus'")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestWorkflowConfiguration {
        @Bean
        fun dataSource(): DataSource {
            val dataSource = org.h2.jdbcx.JdbcDataSource()
            dataSource.setURL("jdbc:h2:mem:tramai-mcp;DB_CLOSE_DELAY=-1")
            dataSource.user = "sa"
            dataSource.password = ""
            return dataSource
        }

        @Bean
        fun testWorkflows(objectMapper: ObjectMapper): WorkflowRegistration = WorkflowRegistration { registry ->
            val store = InMemoryWorkflowCheckpointStore()
            val codec = JsonWorkflowStateCodec(objectMapper, InvoiceState::class)
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
                stateCodec = codec,
                defaultPersistence = {
                    WorkflowPersistence(
                        checkpointStore = store,
                        stateCodec = codec,
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

class JsonWorkflowStateCodec<S : Any>(
    private val objectMapper: ObjectMapper,
    private val type: KClass<S>,
) : WorkflowStateCodec<S> {
    override fun encode(state: S): String = objectMapper.writeValueAsString(state)

    override fun decode(payload: String): S = objectMapper.readValue(payload, type.java)
}
