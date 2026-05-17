package dev.tramai.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.server.BadWorkflowRequestException
import dev.tramai.server.WorkflowConflictException
import dev.tramai.server.WorkflowController
import dev.tramai.server.WorkflowNotRegisteredException
import dev.tramai.server.WorkflowRegistry
import dev.tramai.server.WorkflowRunDetail
import dev.tramai.server.WorkflowRunNotFoundException
import dev.tramai.server.WorkflowRunStore
import dev.tramai.structured.JacksonStructuredOutputHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class McpToolHandlers(
    private val registry: WorkflowRegistry,
    private val runStore: WorkflowRunStore,
    private val workflowController: WorkflowController,
    private val objectMapper: ObjectMapper,
    private val structuredOutputHandler: JacksonStructuredOutputHandler,
) {
    fun listWorkflows(): JsonObject = buildJsonObject {
        putJsonArray("workflows") {
            registry.list().forEach { entry ->
                add(
                    buildJsonObject {
                        put("name", entry.workflow.name)
                        put("definitionVersion", entry.workflow.definitionVersion)
                        put("inputSchema", schemaObject(entry.stateType))
                        put("outputSchema", schemaObject(entry.resultType))
                    },
                )
            }
        }
    }

    fun runWorkflow(
        workflowName: String,
        state: JsonElement,
        idempotencyKey: String?,
    ): JsonObject = try {
        responseJson(
            workflowController.runWorkflow(
                name = workflowName,
                body = objectMapper.writeValueAsString(fromJson(state)),
                idempotencyKey = idempotencyKey,
            ),
        )
    } catch (error: RuntimeException) {
        throw toolFailure(error)
    }

    fun resumeWorkflow(
        workflowId: String,
        workflowName: String? = null,
    ): JsonObject {
        val resolvedWorkflowName = resolveWorkflowName(workflowId, workflowName)
        return try {
            responseJson(
                workflowController.resumeWorkflow(
                    name = resolvedWorkflowName,
                    id = workflowId,
                ),
            )
        } catch (error: RuntimeException) {
            throw toolFailure(error)
        }
    }

    fun getWorkflowStatus(
        workflowId: String,
        workflowName: String? = null,
    ): JsonObject {
        val resolvedWorkflowName = resolveWorkflowName(workflowId, workflowName)
        return try {
            detailJson(
                workflowController.getRun(
                    name = resolvedWorkflowName,
                    id = workflowId,
                ),
            )
        } catch (error: RuntimeException) {
            throw toolFailure(error)
        }
    }

    private fun resolveWorkflowName(
        workflowId: String,
        workflowName: String?,
    ): String {
        if (!workflowName.isNullOrBlank()) {
            // S6518 false positive — WorkflowRegistry.get() lacks operator modifier
            @Suppress("kotlin:S6518")
            registry.get(workflowName)
            return workflowName
        }
        return registry.list()
            .firstNotNullOfOrNull { entry ->
                // S6518 false positive — WorkflowRunStore.get() lacks operator modifier
                @Suppress("kotlin:S6518")
                runCatching { runStore.get(entry.workflow.name, workflowId) }
                    .getOrNull()
                    ?.workflowName
            }
            ?: throw ToolExecutionException(
                message = "Workflow run '$workflowId' was not found",
                errorType = "not_found",
            )
    }

    private fun responseJson(response: Any): JsonObject = toJsonObject(response)

    private fun detailJson(detail: WorkflowRunDetail): JsonObject = toJsonObject(detail)

    private fun schemaObject(type: kotlin.reflect.KType): JsonObject =
        Json.parseToJsonElement(structuredOutputHandler.generateSchema(type)).jsonObject

    private fun toJsonObject(value: Any): JsonObject =
        Json.parseToJsonElement(objectMapper.writeValueAsString(value)).jsonObject

    private fun fromJson(element: JsonElement): JsonNode =
        objectMapper.readTree(Json.encodeToString(JsonElement.serializer(), element))

    private fun toolFailure(error: RuntimeException): ToolExecutionException = when (error) {
        is WorkflowNotRegisteredException -> ToolExecutionException(error.message ?: "Workflow was not found", "not_found")
        is WorkflowRunNotFoundException -> ToolExecutionException(error.message ?: "Workflow run was not found", "not_found")
        is WorkflowConflictException -> ToolExecutionException(error.message ?: "Workflow request conflicts with current state", "conflict")
        is BadWorkflowRequestException,
        is IllegalArgumentException,
        -> ToolExecutionException(error.message ?: "Workflow request is invalid", "invalid_request")
        else -> ToolExecutionException("Workflow execution failed unexpectedly", "internal_error", error)
    }
}

class ToolExecutionException(
    override val message: String,
    val errorType: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    fun asResult(): JsonObject = buildJsonObject {
        put("error", JsonPrimitive(message))
        put("errorType", JsonPrimitive(errorType))
    }
}
