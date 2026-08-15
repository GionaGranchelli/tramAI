package dev.tramai.engine.planning

import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System as SystemMessage
import dev.tramai.core.annotations.User as UserMessage
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ToolDefinition
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ToolRegistry
import java.lang.reflect.Method

/** Compiles resolved tool metadata and reflection-derived operation metadata into a plan. */
internal class OperationDefinitionCompiler(
    private val toolRegistry: ToolRegistry,
    private val promptSanitizer: PromptSanitizer?,
    private val fingerprintFactory: OperationFingerprintFactory,
) {
    fun compile(
        javaType: Class<*>,
        method: Method,
        classLevelSystemPrompt: String?,
    ): OperationExecutionPlan {
        val operation = method.getAnnotation(Operation::class.java)
            ?: throw ConfigurationException("${javaType.name}.${method.name} must be annotated with @Operation")
        val toolDefinitions = resolveToolDefinitions(method, operation)
        val definition = OperationDefinition.create(
            method = method,
            operation = operation,
            classLevelSystemPrompt = classLevelSystemPrompt,
            systemAnnotations = method.getAnnotationsByType(SystemMessage::class.java).map { it.value },
            userAnnotations = method.getAnnotationsByType(UserMessage::class.java).map { it.value },
            toolDefinitions = toolDefinitions,
            promptSanitizer = promptSanitizer,
        )
        return OperationExecutionPlan(
            definition = definition,
            fingerprint = fingerprintFactory.create(toolDefinitions, operation),
            serviceInterface = method.declaringClass.name,
            methodName = method.name,
        )
    }

    private fun resolveToolDefinitions(method: Method, operation: Operation): List<ToolDefinition> =
        operation.tools.map { toolName ->
            val tool = toolRegistry.resolve(toolName)
                ?: throw ConfigurationException("Tool '$toolName' requested by ${method.name} is not registered in the engine")
            ToolDefinition(tool.name, tool.description, tool.inputSchemaJson)
        }
}
