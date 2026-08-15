package dev.tramai.engine.planning

import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System as SystemMessage
import dev.tramai.core.annotations.User as UserMessage
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ToolDefinition
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ReturnKind
import dev.tramai.engine.ToolRegistry
import java.lang.reflect.Method
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

/**
 * Compiles a service method into an immutable [OperationExecutionPlan].
 *
 * Owns ALL reflection → metadata work: Kotlin reflection lookup, suspend
 * detection, parameter-name discovery, @System/@User annotation processing,
 * return-kind resolution, structured return-type metadata, tool-definition
 * resolution, operation annotation interpretation/validation, and the cache
 * fingerprint. The public [OperationDefinition.create] delegates here, so the
 * planning package is the single owner of operation compilation.
 */
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
        val definition = compileDefinition(
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

    internal companion object {
        /**
         * Builds an [OperationDefinition] from method annotations and resolved tool metadata.
         *
         * This is the single implementation of operation reflection/validation. The public
         * [OperationDefinition.create] façade delegates here; the engine execution path
         * reaches it through [compile].
         */
        fun compileDefinition(
            method: Method,
            operation: Operation,
            classLevelSystemPrompt: String?,
            systemAnnotations: List<String> = emptyList(),
            userAnnotations: List<String> = emptyList(),
            toolDefinitions: List<ToolDefinition> = emptyList(),
            promptSanitizer: PromptSanitizer? = null,
        ): OperationDefinition {
            validateOperationAnnotation(method, operation)
            warnOnSystemPromptShadowing(method, systemAnnotations, classLevelSystemPrompt)

            val kotlinFunction = runCatching { method.kotlinFunction }.getOrNull()
            val isSuspend = kotlinFunction?.isSuspend ?: method.isSuspendSignature()
            val parameterNames = resolveParameterNames(method, kotlinFunction)
            val returnType = resolveReturnType(kotlinFunction)
            val returnKind = resolveReturnKind(method, isSuspend, returnType)
            val returnTypeDescription = resolveReturnTypeDescription(method, returnType)

            return OperationDefinition(
                method = method,
                operation = operation,
                classLevelSystemPrompt = classLevelSystemPrompt,
                systemAnnotations = systemAnnotations,
                userAnnotations = userAnnotations,
                isSuspend = isSuspend,
                parameterNames = parameterNames,
                returnKind = returnKind,
                returnType = returnType,
                returnTypeDescription = returnTypeDescription,
                toolDefinitions = toolDefinitions,
                promptSanitizer = promptSanitizer,
            )
        }

        /**
         * Validates operation annotation values before building executable metadata.
         */
        private fun validateOperationAnnotation(method: Method, operation: Operation) {
            require(operation.maxRetries >= 0) {
                "@Operation(maxRetries) must be zero or greater for ${method.declaringClass.name}.${method.name}"
            }
            require(operation.providerRetries >= 0) {
                "@Operation(providerRetries) must be zero or greater for ${method.declaringClass.name}.${method.name}"
            }
            require(operation.timeoutMillis > 0) {
                "@Operation(timeoutMillis) must be greater than zero for ${method.declaringClass.name}.${method.name}"
            }
            require(!operation.cacheable || operation.cacheTtlMillis > 0) {
                "@Operation(cacheTtlMillis) must be greater than zero when caching is enabled for ${method.declaringClass.name}.${method.name}"
            }
        }

        /**
         * Emits the precedence warning when method-level system messages shadow the class prompt.
         */
        private fun warnOnSystemPromptShadowing(
            method: Method,
            systemAnnotations: List<String>,
            classLevelSystemPrompt: String?,
        ) {
            if (systemAnnotations.isEmpty() || classLevelSystemPrompt.isNullOrBlank()) {
                return
            }
            val logger = System.getLogger("dev.tramai.engine.OperationDefinition")
            logger.log(
                System.Logger.Level.WARNING,
                "@System on ${method.declaringClass.name}.${method.name} takes precedence over @SystemPrompt on the class",
            )
        }

        private fun resolveParameterNames(
            method: Method,
            kotlinFunction: KFunction<*>?,
        ): List<String> {
            val valueParameters = kotlinFunction?.parameters
                ?.filter { it.kind == KParameter.Kind.VALUE }
                ?.map { it.name ?: "arg${it.index}" }
            if (valueParameters != null) {
                return valueParameters
            }

            return method.parameters.mapIndexed { index, parameter ->
                parameter.name?.takeIf { it.isNotBlank() } ?: "arg$index"
            }
        }

        private fun resolveReturnKind(
            method: Method,
            isSuspend: Boolean,
            returnType: kotlin.reflect.KType?,
        ): ReturnKind {
            val classifier = returnType?.classifier
            return when (classifier) {
                String::class -> ReturnKind.STRING
                Unit::class -> ReturnKind.UNIT
                kotlinx.coroutines.flow.Flow::class -> ReturnKind.STREAMING
                null -> when {
                    isSuspend -> throw ConfigurationException(
                        "Suspend method ${method.declaringClass.name}.${method.name} requires Kotlin reflection metadata to inspect its return type",
                    )
                    method.returnType == String::class.java -> ReturnKind.STRING
                    method.returnType == Void.TYPE -> ReturnKind.UNIT
                    kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(method.returnType) -> ReturnKind.STREAMING
                    else -> ReturnKind.STRUCTURED
                }
                else -> ReturnKind.STRUCTURED
            }
        }

        private fun resolveReturnType(
            kotlinFunction: KFunction<*>?,
        ) = kotlinFunction?.returnType

        private fun resolveReturnTypeDescription(
            method: Method,
            returnType: kotlin.reflect.KType?,
        ): String = returnType?.toString() ?: method.genericReturnType.typeName

        private fun Method.isSuspendSignature(): Boolean =
            parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
    }
}
