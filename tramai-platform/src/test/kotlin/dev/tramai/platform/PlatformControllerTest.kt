package dev.tramai.platform

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import dev.tramai.server.WorkflowRegistration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.sql.DataSource

@SpringBootTest(
    classes = [
        TramaiPlatformApplication::class,
        PlatformControllerTest.TestPlatformConfiguration::class,
    ],
    properties = [
        "tramai.platform.plugins.dir=/tmp/tramai-platform-test-plugins",
    ],
)
@AutoConfigureMockMvc
class PlatformControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
    private val apiKeyService: ApiKeyService,
    private val pluginManager: PluginManager,
) {
    private val pluginDirectory = Path.of("/tmp/tramai-platform-test-plugins")

    @BeforeEach
    fun resetPluginDirectory() {
        Files.createDirectories(pluginDirectory)
        Files.list(pluginDirectory).use { entries ->
            entries.forEach { Files.deleteIfExists(it) }
        }
        pluginManager.refresh()
    }

    @Test
    fun `plugin jar in plugins directory adds step type and webhook adapter`() {
        val tenant = bootstrapTenant("plugin")
        createPluginJar(pluginDirectory.resolve("demo-plugin.jar"))
        pluginManager.refresh()

        mockMvc.get("/plugins") {
            header("X-API-Key", tenant.adminKey)
        }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value("demo-plugin") }
                jsonPath("$[0].status") { value("enabled") }
                jsonPath("$[0].stepTypes[0]") { value("demo.echo") }
            }

        val pluginRunId = mockMvc.post("/workflows/plugin-echo/run") {
            contentType = MediaType.APPLICATION_JSON
            header("X-API-Key", tenant.adminKey)
            content = """{"existing":"state"}"""
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("running") }
            }
            .andReturn()
            .response
            .contentAsString
            .let { objectMapper.readTree(it).get("workflowId").asText() }

        val pluginResult = objectMapper.readValue<Map<String, Any?>>(
            waitForResult("plugin-echo", pluginRunId, tenant.adminKey),
        )
        assertThat(pluginResult["pluginMessage"]).isEqualTo("hello-plugin")
        assertThat(pluginResult["executed"]).isEqualTo(true)
        assertThat(pluginResult["existing"]).isEqualTo("state")

        val webhookRunId = mockMvc.post("/webhooks/webhook-ingest") {
            contentType = MediaType.APPLICATION_JSON
            param("teamId", tenant.team.id)
            param("projectId", tenant.project.id)
            param("source", "demo.webhook")
            content = """{"message":"from-webhook"}"""
        }
            .andExpect {
                status { isAccepted() }
                jsonPath("$.status") { value("running") }
            }
            .andReturn()
            .response
            .contentAsString
            .let { objectMapper.readTree(it).get("workflowId").asText() }

        val webhookResult = waitForTypedResult<WebhookState>("webhook-ingest", webhookRunId, tenant.adminKey)
        assertThat(webhookResult.message).isEqualTo("from-webhook")
        assertThat(webhookResult.source).isEqualTo("demo.webhook")
        assertThat(webhookResult.processed).isTrue()
    }

    @Test
    fun `plugin lifecycle endpoints install disable and enable`() {
        val tenant = bootstrapTenant("lifecycle")
        val sourceJar = Files.createTempFile("tramai-plugin-source", ".jar")
        createPluginJar(sourceJar)

        mockMvc.post("/plugins/install") {
            contentType = MediaType.APPLICATION_JSON
            header("X-API-Key", tenant.adminKey)
            content = objectMapper.writeValueAsString(PluginInstallRequest(sourceJar.toString()))
        }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value("demo-plugin") }
                jsonPath("$[0].status") { value("enabled") }
            }

        mockMvc.post("/plugins/demo-plugin/disable") {
            header("X-API-Key", tenant.adminKey)
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("disabled") }
            }

        mockMvc.post("/plugins/demo-plugin/enable") {
            header("X-API-Key", tenant.adminKey)
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("enabled") }
            }
    }

    @Test
    fun `teams have isolated workflow runs and audit logs`() {
        val teamOne = bootstrapTenant("team-one")
        val teamTwo = bootstrapTenant("team-two")

        val runOne = startTenantWorkflow(teamOne.adminKey, "tenant-a")
        val runTwo = startTenantWorkflow(teamTwo.adminKey, "tenant-b")

        waitForTypedResult<TenantState>("tenant-workflow", runOne, teamOne.adminKey)
        waitForTypedResult<TenantState>("tenant-workflow", runTwo, teamTwo.adminKey)

        val teamOneRuns = mockMvc.get("/workflows/tenant-workflow/runs") {
            header("X-API-Key", teamOne.adminKey)
        }.andReturn().response.contentAsString
        val teamTwoRuns = mockMvc.get("/workflows/tenant-workflow/runs") {
            header("X-API-Key", teamTwo.adminKey)
        }.andReturn().response.contentAsString

        assertThat(teamOneRuns).contains(runOne).doesNotContain(runTwo)
        assertThat(teamTwoRuns).contains(runTwo).doesNotContain(runOne)

        val auditLog = mockMvc.get("/audit-log") {
            header("X-API-Key", teamOne.adminKey)
            param("action", "workflow.start")
        }
            .andExpect { status { isOk() } }
            .andReturn()
            .response
            .contentAsString
        val entries = objectMapper.readValue<List<AuditLogEntry>>(auditLog)
        assertThat(entries).isNotEmpty()
        assertThat(entries).allSatisfy { entry ->
            assertThat(entry.teamId).isEqualTo(teamOne.team.id)
            assertThat(entry.actorId).isNotBlank()
            assertThat(entry.timestamp).isNotNull()
            assertThat(entry.action).isEqualTo("workflow.start")
        }
    }

    @Test
    fun `run-only key can start but not list workflows and rate limit returns 429`() {
        val tenant = bootstrapTenant("run-only")
        val runOnlyKey = apiKeyService.create(
            CreateApiKeyRequest(
                teamId = tenant.team.id,
                projectId = tenant.project.id,
                name = "run-only",
                scopes = setOf("run"),
                burstCapacity = 10,
                refillTokensPerSecond = 10.0,
            ),
            actorId = "bootstrap",
        ).key
        val rateLimitedKey = apiKeyService.create(
            CreateApiKeyRequest(
                teamId = tenant.team.id,
                projectId = tenant.project.id,
                name = "rate-limited",
                scopes = setOf("run"),
                burstCapacity = 1,
                refillTokensPerSecond = 0.1,
            ),
            actorId = "bootstrap",
        ).key

        mockMvc.post("/workflows/tenant-workflow/run") {
            contentType = MediaType.APPLICATION_JSON
            header("X-API-Key", runOnlyKey)
            content = """{"value":"can-run"}"""
        }
            .andExpect {
                status { isOk() }
            }

        mockMvc.get("/workflows/tenant-workflow/runs") {
            header("X-API-Key", runOnlyKey)
        }
            .andExpect {
                status { isForbidden() }
            }

        mockMvc.post("/workflows/tenant-workflow/run") {
            contentType = MediaType.APPLICATION_JSON
            header("X-API-Key", rateLimitedKey)
            content = """{"value":"first"}"""
        }
            .andExpect {
                status { isOk() }
            }

        mockMvc.post("/workflows/tenant-workflow/run") {
            contentType = MediaType.APPLICATION_JSON
            header("X-API-Key", rateLimitedKey)
            content = """{"value":"second"}"""
        }
            .andExpect {
                status { isTooManyRequests() }
                header { exists("Retry-After") }
                header { exists("X-RateLimit-Limit") }
                header { exists("X-RateLimit-Remaining") }
                header { exists("X-RateLimit-Reset") }
            }
    }

    private fun bootstrapTenant(name: String): TenantFixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val team = teamRepository.create(Team(id = "team-$name-$suffix", name = "Team $name"))
        val project = projectRepository.create(Project(id = "project-$name-$suffix", teamId = team.id, name = "Project $name"))
        val adminKey = apiKeyService.create(
            CreateApiKeyRequest(
                teamId = team.id,
                projectId = project.id,
                name = "admin-$name",
                scopes = setOf("admin", "read", "run"),
                burstCapacity = 20,
                refillTokensPerSecond = 20.0,
            ),
            actorId = "bootstrap",
        ).key
        return TenantFixture(team, project, adminKey)
    }

    private fun startTenantWorkflow(
        apiKey: String,
        value: String,
    ): String = mockMvc.post("/workflows/tenant-workflow/run") {
        contentType = MediaType.APPLICATION_JSON
        header("X-API-Key", apiKey)
        content = objectMapper.writeValueAsString(TenantState(value = value))
    }
        .andExpect {
            status { isOk() }
        }
        .andReturn()
        .response
        .contentAsString
        .let { objectMapper.readTree(it).get("workflowId").asText() }

    private inline fun <reified T> waitForTypedResult(
        workflowName: String,
        workflowId: String,
        apiKey: String,
    ): T = objectMapper.readValue(waitForResult(workflowName, workflowId, apiKey))

    private fun waitForResult(
        workflowName: String,
        workflowId: String,
        apiKey: String,
    ): String {
        repeat(50) {
            val body = mockMvc.get("/workflows/$workflowName/runs/$workflowId") {
                header("X-API-Key", apiKey)
            }
                .andReturn()
                .response
                .contentAsString
            val json = objectMapper.readTree(body)
            when (json.get("status").asText()) {
                "completed" -> return json.get("result").toString()
                "failed" -> error("Workflow '$workflowName' run '$workflowId' failed: $body")
            }
            Thread.sleep(25)
        }
        error("Workflow '$workflowName' run '$workflowId' did not complete in time")
    }

    private fun createPluginJar(target: Path) {
        Files.newOutputStream(target).use { output ->
            JarOutputStream(output).use { jar ->
                listOf(
                    TestPlatformPlugin::class.java,
                    EchoStepExecutorFactory::class.java,
                    DemoWebhookAdapterFactory::class.java,
                ).forEach { clazz ->
                    val entryName = "${clazz.name.replace('.', '/')}.class"
                    jar.putNextEntry(JarEntry(entryName))
                    clazz.classLoader.getResourceAsStream(entryName).use { input ->
                        requireNotNull(input) { "Missing compiled class resource '$entryName'" }
                        input.copyTo(jar)
                    }
                    jar.closeEntry()
                }
                jar.putNextEntry(JarEntry("META-INF/services/${TramaiPlugin::class.qualifiedName}"))
                jar.write(TestPlatformPlugin::class.qualifiedName!!.toByteArray())
                jar.closeEntry()
            }
        }
    }

    data class TenantFixture(
        val team: Team,
        val project: Project,
        val adminKey: String,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class TestPlatformConfiguration {
        @Bean
        fun dataSource(): DataSource {
            val dataSource = org.h2.jdbcx.JdbcDataSource()
            dataSource.setURL("jdbc:h2:mem:tramai-platform;DB_CLOSE_DELAY=-1")
            dataSource.user = "sa"
            dataSource.password = ""
            return dataSource
        }

        @Bean
        fun testWorkflows(objectMapper: ObjectMapper): WorkflowRegistration = WorkflowRegistration { registry ->
            registry.register(
                workflow = workflow<Map<String, Any?>>(
                    name = "plugin-echo",
                    definitionVersion = "1.0.0",
                ) {
                    pluginStep(
                        name = "echo",
                        type = "demo.echo",
                        config = mapOf("message" to "hello-plugin"),
                    )
                }.build { it },
                stateCodec = MapWorkflowStateCodec(objectMapper),
                defaultPersistence = { null },
            )

            registry.register(
                workflow = workflow<WebhookState>(
                    name = "webhook-ingest",
                    definitionVersion = "1.0.0",
                ) {
                    localStep("mark") { state, _ -> state.copy(processed = true) }
                }.build { it },
                stateCodec = JacksonWorkflowStateCodec(objectMapper, WebhookState::class.java),
                defaultPersistence = { null },
            )

            registry.register(
                workflow = workflow<TenantState>(
                    name = "tenant-workflow",
                    definitionVersion = "1.0.0",
                ) {
                    localStep("mark") { state, _ -> state.copy(processed = true) }
                }.build { it },
                stateCodec = JacksonWorkflowStateCodec(objectMapper, TenantState::class.java),
                defaultPersistence = { null },
            )
        }
    }
}

data class WebhookState(
    val message: String,
    val source: String,
    val processed: Boolean = false,
)

data class TenantState(
    val value: String,
    val processed: Boolean = false,
)

class JacksonWorkflowStateCodec<S : Any>(
    private val objectMapper: ObjectMapper,
    private val type: Class<S>,
) : WorkflowStateCodec<S> {
    override fun encode(state: S): String = objectMapper.writeValueAsString(state)

    override fun decode(payload: String): S = objectMapper.readValue(payload, type)
}

class MapWorkflowStateCodec(
    private val objectMapper: ObjectMapper,
) : WorkflowStateCodec<Map<String, Any?>> {
    override fun encode(state: Map<String, Any?>): String = objectMapper.writeValueAsString(state)

    override fun decode(payload: String): Map<String, Any?> = objectMapper.readValue(
        payload,
        object : TypeReference<Map<String, Any?>>() {},
    )
}
