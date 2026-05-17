package dev.tramai.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

class TramaiMcpServer(
    private val handlers: McpToolHandlers,
) : AutoCloseable {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private var sseServer: EmbeddedServer<*, *>? = null
    private val mcpServer: Server = Server(
        serverInfo = Implementation(
            name = "tramai-mcp",
            version = VERSION,
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    ) {
        addTool(workflowListingTool()) {
            handleCall { handlers.listWorkflows() }
        }
        addTool(runWorkflowTool()) { request ->
            handleCall {
                handlers.runWorkflow(
                    workflowName = request.requiredString("workflowName"),
                    state = request.requiredObject("state"),
                    idempotencyKey = request.optionalString("idempotencyKey"),
                )
            }
        }
        addTool(resumeWorkflowTool()) { request ->
            handleCall {
                handlers.resumeWorkflow(
                    workflowId = request.requiredString("workflowId"),
                    workflowName = request.optionalString("workflowName"),
                )
            }
        }
        addTool(statusTool()) { request ->
            handleCall {
                handlers.getWorkflowStatus(
                    workflowId = request.requiredString("workflowId"),
                    workflowName = request.optionalString("workflowName"),
                )
            }
        }
    }

    fun sdkServer(): Server = mcpServer

    fun startStdio() {
        scope.launch {
            val transport = StdioServerTransport(
                System.`in`.asSource().buffered(),
                System.out.asSink().buffered(),
            )
            mcpServer.createSession(transport)
        }
    }

    fun startSse(
        host: String,
        port: Int,
        path: String,
    ) {
        sseServer = embeddedServer(
            factory = CIO,
            host = host,
            port = port,
        ) {
            routing {
                mcp(path) { mcpServer }
            }
        }.start(wait = false)
    }

    fun stopSse() {
        sseServer?.stop(gracePeriodMillis = 1_000, timeoutMillis = 1_000)
        sseServer = null
    }

    override fun close() {
        stopSse()
        scope.cancel()
        runBlocking {
            mcpServer.close()
        }
    }

    private fun workflowListingTool(): Tool = Tool(
        name = "list_workflows",
        description = "Lists registered Tramai workflows with JSON Schema for input and output payloads.",
        inputSchema = schema(
            """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
        outputSchema = schema(
            """
            {
              "type": "object",
              "properties": {
                "workflows": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": { "type": "string" },
                      "definitionVersion": { "type": "string" },
                      "inputSchema": { "type": "object" },
                      "outputSchema": { "type": "object" }
                    },
                    "required": ["name", "definitionVersion", "inputSchema", "outputSchema"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["workflows"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
    )

    private fun runWorkflowTool(): Tool = Tool(
        name = "run_workflow",
        description = "Starts a registered Tramai workflow from a JSON state object and returns the created workflow run ID.",
        inputSchema = schema(
            """
            {
              "type": "object",
              "properties": {
                "workflowName": { "type": "string" },
                "state": { "type": "object" },
                "idempotencyKey": { "type": "string" }
              },
              "required": ["workflowName", "state"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
        outputSchema = responseSchema(),
    )

    private fun resumeWorkflowTool(): Tool = Tool(
        name = "resume_workflow",
        description = "Resumes a suspended Tramai workflow run by workflow ID.",
        inputSchema = schema(
            """
            {
              "type": "object",
              "properties": {
                "workflowId": { "type": "string" },
                "workflowName": { "type": "string" }
              },
              "required": ["workflowId"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
        outputSchema = responseSchema(),
    )

    private fun statusTool(): Tool = Tool(
        name = "get_workflow_status",
        description = "Returns the current status, active step, history, result, and error state for a workflow run.",
        inputSchema = schema(
            """
            {
              "type": "object",
              "properties": {
                "workflowId": { "type": "string" },
                "workflowName": { "type": "string" }
              },
              "required": ["workflowId"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
        outputSchema = schema(
            """
            {
              "type": "object",
              "properties": {
                "workflowId": { "type": "string" },
                "status": { "type": "string" },
                "definitionVersion": { "type": "string" },
                "currentStep": { "type": "string", "nullable": true },
                "history": { "type": "array" },
                "result": { "type": "object", "nullable": true },
                "error": { "type": "string", "nullable": true }
              },
              "required": ["workflowId", "status", "definitionVersion", "history"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
    )

    private fun responseSchema(): ToolSchema = schema(
        """
        {
          "type": "object",
          "properties": {
            "workflowId": { "type": "string" },
            "status": { "type": "string" },
            "definitionVersion": { "type": "string" },
            "result": { "type": "object", "nullable": true }
          },
          "required": ["workflowId", "status", "definitionVersion"],
          "additionalProperties": false
        }
        """.trimIndent(),
    )

    private fun schema(raw: String): ToolSchema {
        val schema = Json.parseToJsonElement(raw).jsonObject
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        return ToolSchema(
            properties = schema["properties"]?.jsonObject ?: buildJsonObject {},
            required = required,
            defs = schema["\$defs"]?.jsonObject,
        )
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.requiredString(name: String): String =
        params.arguments?.get(name)?.jsonPrimitive?.content
            ?: throw ToolExecutionException("Missing required argument '$name'", "invalid_request")

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalString(name: String): String? =
        params.arguments?.get(name)
            ?.takeUnless { it == JsonNull }
            ?.jsonPrimitive
            ?.content

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.requiredObject(name: String): JsonElement =
        params.arguments?.get(name)
            ?: throw ToolExecutionException("Missing required argument '$name'", "invalid_request")

    private fun handleCall(action: () -> JsonObject): CallToolResult = try {
        val payload = action()
        CallToolResult(
            content = listOf(TextContent(Json.encodeToString(JsonObject.serializer(), payload))),
            structuredContent = payload,
        )
    } catch (error: ToolExecutionException) {
        val structured = error.asResult()
        CallToolResult(
            content = listOf(TextContent(error.message)),
            isError = true,
            structuredContent = structured,
        )
    }
}

/** @see TramaiMcpServer */
private const val VERSION = "0.2.0"
