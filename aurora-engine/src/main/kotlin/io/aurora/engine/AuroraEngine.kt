package io.aurora.engine

import io.aurora.core.annotations.AiService
import io.aurora.core.annotations.Operation
import io.aurora.core.annotations.SystemPrompt
import io.aurora.core.exception.ConfigurationException
import io.aurora.core.exception.ProviderException
import io.aurora.core.exception.TimeoutException
import io.aurora.core.model.FinishReason
import io.aurora.core.model.Message
import io.aurora.core.model.MessageRole
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.model.StreamChunk
import io.aurora.core.model.ToolCall
import io.aurora.core.model.ToolDefinition
import io.aurora.core.model.ToolExecutionContext
import io.aurora.core.model.ToolResult
import io.aurora.core.model.ResolvedTool
import io.aurora.core.observation.NoOpOperationObserver
import io.aurora.core.observation.OperationCallContext
import io.aurora.core.observation.OperationObservation
import io.aurora.core.observation.OperationObserver
import io.aurora.core.provider.ModelProvider
import io.aurora.core.provider.ProviderRegistry
import io.aurora.core.provider.StreamCapable
import io.aurora.core.structured.StructuredOutputHandler
import io.aurora.core.structured.StructuredOutputResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

/**
 * Runtime engine that turns annotated service interfaces into AI-backed proxies.
 */
class AuroraEngine(
    private val providerRegistry: ProviderRegistry,
    private val structuredOutputHandler: StructuredOutputHandler? = null,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val operationObserver: OperationObserver = NoOpOperationObserver,
    private val job: Job = SupervisorJob(),
    private val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
) : AutoCloseable {

    /**
     * Creates an engine backed by a single provider.
     */
    constructor(
        provider: ModelProvider,
        structuredOutputHandler: StructuredOutputHandler? = null,
        toolRegistry: ToolRegistry = ToolRegistry(),
        operationObserver: OperationObserver = NoOpOperationObserver,
        job: Job = SupervisorJob(),
        scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
    ) : this(
        providerRegistry = ProviderRegistry.singleProvider(provider),
        structuredOutputHandler = structuredOutputHandler,
        toolRegistry = toolRegistry,
        operationObserver = operationObserver,
        job = job,
        scope = scope,
    )

    /**
     * Creates a proxy implementation for the given Aurora service interface.
     */
    fun <T : Any> create(serviceType: KClass<T>): T {
        val definition = ServiceDefinition.create(serviceType, toolRegistry)
        val handler = AuroraInvocationHandler(
            providerRegistry = providerRegistry,
            structuredOutputHandler = structuredOutputHandler,
            toolRegistry = toolRegistry,
            operationObserver = operationObserver,
            scope = scope,
            serviceDefinition = definition,
        )

        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            serviceType.java.classLoader,
            arrayOf(serviceType.java),
            handler,
        ) as T
    }

    /**
     * Cancels the engine-owned coroutine job hierarchy.
     */
    override fun close() {
        job.cancel()
    }
}

/**
 * Reified convenience overload for [AuroraEngine.create].
 */
inline fun <reified T : Any> AuroraEngine.create(): T = create(T::class)

private class AuroraInvocationHandler(
    private val providerRegistry: ProviderRegistry,
    private val structuredOutputHandler: StructuredOutputHandler?,
    private val toolRegistry: ToolRegistry,
    private val operationObserver: OperationObserver,
    private val scope: CoroutineScope,
    private val serviceDefinition: ServiceDefinition,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return handleObjectMethod(proxy, method, args.orEmpty())
        }

        val operation = serviceDefinition.operations[method]
            ?: throw ConfigurationException("No operation metadata registered for ${method.name}")

        return if (operation.isSuspend) {
            invokeSuspend(operation, args.orEmpty())
        } else {
            runBlocking {
                execute(operation, args.orEmpty().toList())
            }
        }
    }

    private fun invokeSuspend(
        operation: OperationDefinition,
        args: Array<out Any?>,
    ): Any {
        // Kotlin suspend proxies receive the continuation as the last JVM argument.
        @Suppress("UNCHECKED_CAST")
        val continuation = args.lastOrNull() as? Continuation<Any?>
            ?: throw ConfigurationException("Suspend invocation for ${operation.method.name} is missing its continuation")

        val callArguments = args.dropLast(1)
        scope.launch(continuation.context) {
            runCatching { execute(operation, callArguments) }
                .onSuccess { continuation.resumeWith(Result.success(it)) }
                .onFailure { continuation.resumeWith(Result.failure(it)) }
        }
        return COROUTINE_SUSPENDED
    }

    private suspend fun execute(
        operation: OperationDefinition,
        arguments: List<Any?>,
    ): Any? {
        return when (operation.returnKind) {
            ReturnKind.STRING -> executeRaw(operation, arguments)
            ReturnKind.UNIT -> {
                executeRaw(operation, arguments)
                Unit
            }
            ReturnKind.STRUCTURED -> executeStructured(operation, arguments)
            ReturnKind.STREAMING -> executeStreaming(operation, arguments)
        }
    }

    private suspend fun executeStreaming(
        operation: OperationDefinition,
        arguments: List<Any?>,
    ): kotlinx.coroutines.flow.Flow<StreamChunk> {
        val provider = providerRegistry.resolve(operation.operation)
        val streamCapable = provider as? StreamCapable
            ?: throw io.aurora.core.exception.ProviderCapabilityException(provider.providerId(), "streaming")

        return streamCapable.stream(operation.toRequest(arguments))
    }

    private suspend fun executeRaw(
        operation: OperationDefinition,
        arguments: List<Any?>,
    ): String {
        val messages = operation.initialMessages(arguments).toMutableList()
        val result = executeWithTools(operation, messages)
        result.observation.onCallCompleted(parseSuccess = null)
        return result.response.content
    }

    private suspend fun executeStructured(
        operation: OperationDefinition,
        arguments: List<Any?>,
    ): Any {
        val handler = structuredOutputHandler ?: throw ConfigurationException(
            "Structured return type ${operation.returnTypeDescription} requires a StructuredOutputHandler implementation from aurora-structured",
        )
        val contract = operation.structuredContract(handler)
        val messages = operation.initialMessages(arguments, contract.schemaJson).toMutableList()
        val maxAttempts = operation.operation.maxRetries + 1

        repeat(maxAttempts) { attemptIndex ->
            val result = executeWithTools(operation, messages)
            when (
                val analysis = handler.analyze(
                    rawResponse = result.response.content,
                    targetType = operation.returnType,
                )
            ) {
                is StructuredOutputResult.Success -> {
                    result.observation.onCallCompleted(parseSuccess = true)
                    return analysis.value
                }
                is StructuredOutputResult.Failure -> {
                    result.observation.onStructuredParseFailure(
                        rawResponse = analysis.rawResponse,
                        errorSummary = analysis.errorSummary,
                    )
                    if (attemptIndex == maxAttempts - 1) {
                        result.observation.onCallCompleted(parseSuccess = false)
                        throw io.aurora.core.exception.StructuredOutputException(
                            message = "Structured output parsing failed after $maxAttempts attempt(s)",
                            originalPrompt = operation.operation.prompt,
                            lastRawResponse = analysis.rawResponse,
                            validationError = analysis.errorSummary,
                            attemptCount = maxAttempts,
                        )
                    }

                    result.observation.onCallCompleted(parseSuccess = false)
                    messages += Message(MessageRole.ASSISTANT, analysis.rawResponse)
                    messages += Message(MessageRole.USER, analysis.feedbackMessage)
                }
            }
        }

        error("Structured retry loop exited without returning or throwing")
    }

    private suspend fun executeWithTools(
        operation: OperationDefinition,
        messages: MutableList<Message>,
    ): ProviderCallResult {
        val maxToolLoops = 5 // Guard against infinite tool loops
        repeat(maxToolLoops) {
            val provider = providerRegistry.resolve(operation.operation)
            val result = callProviderWithRetries(
                provider = provider,
                request = ModelRequest(
                    model = operation.operation.model,
                    messages = messages.toList(),
                    tools = operation.toolDefinitions.takeIf { it.isNotEmpty() },
                    timeoutMillis = operation.operation.timeoutMillis,
                    operationInterface = operation.method.declaringClass.name,
                    operationMethod = operation.method.name,
                ),
                operation = operation,
                attemptCounter = AttemptCounter(),
            )

            val toolCalls = result.response.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                return result
            }

            // End the observation of the call that produced tool calls
            result.observation.onCallCompleted(parseSuccess = null)

            // Append assistant message with tool calls
            messages += Message(
                role = MessageRole.ASSISTANT,
                content = result.response.content,
                toolCalls = toolCalls,
            )

            // Execute each tool and append results
            for (toolCall in toolCalls) {
                val tool = toolRegistry.resolve(toolCall.name)
                val toolResult = if (tool == null) {
                    ToolResult.PermanentFailure("Tool '${toolCall.name}' not found")
                } else {
                    executeTool(tool, toolCall, operation)
                }

                messages += when (toolResult) {
                    is ToolResult.Success -> Message(
                        role = MessageRole.TOOL,
                        content = toolResult.value.toString(),
                        toolCallId = toolCall.id,
                    )
                    is ToolResult.InvalidInput -> Message(
                        role = MessageRole.TOOL,
                        content = "Error: ${toolResult.message}",
                        toolCallId = toolCall.id,
                    )
                    is ToolResult.PermanentFailure -> Message(
                        role = MessageRole.TOOL,
                        content = "Permanent error: ${toolResult.message}",
                        toolCallId = toolCall.id,
                    )
                    is ToolResult.TransientFailure -> {
                        // For now, we surface transient failures to the model as well, or we could throw?
                        // Plan says: "TransientFailure, engine retries up to policy"
                        // I'll implement internal retry for tools here or surface it as an error message.
                        Message(
                            role = MessageRole.TOOL,
                            content = "Transient error: ${toolResult.cause.message}. Please retry.",
                            toolCallId = toolCall.id,
                        )
                    }
                }
            }
        }
        error("Exceeded maximum tool call loops ($maxToolLoops)")
    }

    private suspend fun executeTool(
        tool: ResolvedTool,
        toolCall: ToolCall,
        operation: OperationDefinition,
    ): ToolResult {
        val input = toolCall.argumentsJson

        val context = ToolExecutionContext(
            operationName = operation.method.name,
            modelName = operation.operation.model,
            attemptNumber = 0, // TODO: track tool attempts
            timeout = java.time.Duration.ofMillis(operation.operation.timeoutMillis),
        )

        return try {
            tool.execute(input, context)
        } catch (e: io.aurora.core.exception.ToolInvalidInputException) {
            ToolResult.InvalidInput(e.message ?: "Invalid tool input")
        } catch (e: Exception) {
            // Check idempotency for transient failure classification
            if (tool.idempotent) {
                ToolResult.TransientFailure(e)
            } else {
                ToolResult.PermanentFailure(e.message ?: "Tool execution failed")
            }
        }
    }

    private suspend fun callProviderWithRetries(
        provider: ModelProvider,
        request: ModelRequest,
        operation: OperationDefinition,
        attemptCounter: AttemptCounter,
    ): ProviderCallResult {
        val maxAttempts = operation.operation.providerRetries + 1

        repeat(maxAttempts) { retryIndex ->
            val observation = startObservation(
                provider = provider,
                operation = operation,
                attempt = attemptCounter.next(),
            )

            try {
                val response = callProviderOnce(provider, request, operation)
                observation.onProviderResponse(response)
                return ProviderCallResult(response = response, observation = observation)
            } catch (error: Throwable) {
                observation.onProviderFailure(error)
                observation.onCallCompleted(parseSuccess = null)

                if (!shouldRetryProviderCall(error, retryIndex, maxAttempts)) {
                    throw error
                }

                delay(providerRetryDelayMillis(retryIndex))
            }
        }

        error("Provider retry loop exited without returning or throwing")
    }

    private suspend fun callProviderOnce(
        provider: ModelProvider,
        request: ModelRequest,
        operation: OperationDefinition,
    ): ModelResponse = try {
        val timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis
        withTimeout(timeoutMillis) {
            provider.complete(request)
        }
    } catch (error: Throwable) {
        throw when (error) {
            is TimeoutCancellationException -> TimeoutException(
                message = buildTimeoutMessage(
                    provider = provider,
                    operation = operation,
                    timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis,
                ),
                cause = error,
            )

            is ProviderException -> error

            else -> ProviderException(
                message = "Provider ${provider.providerId()} failed while invoking ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}",
                cause = error,
            )
        }
    }

    private fun shouldRetryProviderCall(
        error: Throwable,
        retryIndex: Int,
        maxAttempts: Int,
    ): Boolean {
        if (retryIndex >= maxAttempts - 1) {
            return false
        }

        return when (error) {
            is TimeoutException -> true
            is ProviderException -> error.retryable
            else -> false
        }
    }

    private fun providerRetryDelayMillis(retryIndex: Int): Long {
        val unboundedDelay = INITIAL_PROVIDER_RETRY_DELAY_MILLIS shl retryIndex
        return minOf(unboundedDelay, MAX_PROVIDER_RETRY_DELAY_MILLIS)
    }

    private fun buildTimeoutMessage(
        provider: ModelProvider,
        operation: OperationDefinition,
        timeoutMillis: Long,
    ): String = "Provider ${provider.providerId()} timed out after ${timeoutMillis}ms while invoking ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}"

    private fun startObservation(
        provider: ModelProvider,
        operation: OperationDefinition,
        attempt: Int,
    ): OperationObservation = operationObserver.onCallStarted(
        OperationCallContext(
            serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            methodName = operation.method.name,
            providerId = provider.providerId(),
            requestedModel = operation.operation.model,
            attempt = attempt,
        ),
    )

    private fun handleObjectMethod(
        proxy: Any,
        method: Method,
        args: Array<out Any?>,
    ): Any? = when (method.name) {
        "toString" -> "AuroraProxy(${serviceDefinition.serviceType.qualifiedName})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args.firstOrNull()
        else -> throw UnsupportedOperationException("Unsupported Object method: ${method.name}")
    }
}

private data class ServiceDefinition(
    val serviceType: KClass<*>,
    val systemPrompt: String?,
    val operations: Map<Method, OperationDefinition>,
) {
    companion object {
        fun create(serviceType: KClass<*>, toolRegistry: ToolRegistry): ServiceDefinition {
            val javaType = serviceType.java
            if (!javaType.isInterface) {
                throw ConfigurationException("${serviceType.qualifiedName} must be an interface")
            }
            if (serviceType.annotations.none { it is AiService }) {
                throw ConfigurationException("${serviceType.qualifiedName} must be annotated with @AiService")
            }

            val systemPrompt = serviceType.java.getAnnotation(SystemPrompt::class.java)?.value?.takeIf { it.isNotBlank() }
            val operations = javaType.methods
                .filterNot { it.declaringClass == Any::class.java }
                .associateWith { method ->
                    val operation = method.getAnnotation(Operation::class.java)
                        ?: throw ConfigurationException("${serviceType.qualifiedName}.${method.name} must be annotated with @Operation")

                    val toolDefinitions = operation.tools.map { toolName ->
                        val tool = toolRegistry.resolve(toolName)
                            ?: throw ConfigurationException("Tool '$toolName' requested by ${method.name} is not registered in the engine")
                        ToolDefinition(tool.name, tool.description, tool.inputSchemaJson)
                    }

                    OperationDefinition.create(method, operation, systemPrompt, toolDefinitions)
                }

            return ServiceDefinition(
                serviceType = serviceType,
                systemPrompt = systemPrompt,
                operations = operations,
            )
        }
    }
}

private data class OperationDefinition(
    val method: Method,
    val operation: Operation,
    val systemPrompt: String?,
    val isSuspend: Boolean,
    val parameterNames: List<String>,
    val returnKind: ReturnKind,
    val returnType: kotlin.reflect.KType,
    val returnTypeDescription: String,
    val toolDefinitions: List<ToolDefinition>,
) {
    fun toRequest(arguments: List<Any?>): ModelRequest {
        return ModelRequest(
            model = operation.model,
            messages = initialMessages(arguments),
            tools = toolDefinitions.takeIf { it.isNotEmpty() },
            timeoutMillis = operation.timeoutMillis,
            operationInterface = method.declaringClass.name,
            operationMethod = method.name,
        )
    }

    fun initialMessages(
        arguments: List<Any?>,
        schemaJson: String? = null,
    ): List<Message> {
        val messages = buildList {
            if (!systemPrompt.isNullOrBlank()) {
                add(Message(role = MessageRole.SYSTEM, content = systemPrompt))
            }

            val userMessage = buildString {
                append(operation.prompt)
                if (!schemaJson.isNullOrBlank()) {
                    append("\n\nRespond only with valid JSON matching this schema:")
                    append("\n")
                    append(schemaJson)
                }
                if (arguments.isNotEmpty()) {
                    append("\n\nArguments:")
                    arguments.forEachIndexed { index, value ->
                        append("\n- ")
                        append(parameterNames.getOrElse(index) { "arg$index" })
                        append(": ")
                        append(value)
                    }
                }
            }
            add(Message(role = MessageRole.USER, content = userMessage))
        }

        return messages
    }

    fun structuredContract(handler: StructuredOutputHandler) = handler.createContract(returnType)

    companion object {
        fun create(
            method: Method,
            operation: Operation,
            systemPrompt: String?,
            toolDefinitions: List<ToolDefinition> = emptyList(),
        ): OperationDefinition {
            require(operation.maxRetries >= 0) {
                "@Operation(maxRetries) must be zero or greater for ${method.declaringClass.name}.${method.name}"
            }
            require(operation.providerRetries >= 0) {
                "@Operation(providerRetries) must be zero or greater for ${method.declaringClass.name}.${method.name}"
            }
            require(operation.timeoutMillis > 0) {
                "@Operation(timeoutMillis) must be greater than zero for ${method.declaringClass.name}.${method.name}"
            }

            val kotlinFunction = method.kotlinFunction
            val isSuspend = kotlinFunction?.isSuspend == true
            val parameterNames = resolveParameterNames(method, kotlinFunction)
            val returnType = resolveReturnType(method, kotlinFunction)
            val returnKind = resolveReturnKind(method, kotlinFunction)
            val returnTypeDescription = resolveReturnTypeDescription(method, kotlinFunction)

            return OperationDefinition(
                method = method,
                operation = operation,
                systemPrompt = systemPrompt,
                isSuspend = isSuspend,
                parameterNames = parameterNames,
                returnKind = returnKind,
                returnType = returnType,
                returnTypeDescription = returnTypeDescription,
                toolDefinitions = toolDefinitions,
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
            kotlinFunction: KFunction<*>?,
        ): ReturnKind {
            val type = resolveReturnType(method, kotlinFunction)
            val classifier = type.classifier
            return when (classifier) {
                String::class -> ReturnKind.STRING
                Unit::class -> ReturnKind.UNIT
                kotlinx.coroutines.flow.Flow::class -> ReturnKind.STREAMING
                null -> when (method.returnType) {
                    String::class.java -> ReturnKind.STRING
                    Void.TYPE -> ReturnKind.UNIT
                    else -> ReturnKind.STRUCTURED
                }
                else -> ReturnKind.STRUCTURED
            }
        }

        private fun resolveReturnType(
            method: Method,
            kotlinFunction: KFunction<*>?,
        ) = kotlinFunction?.returnType
            ?: throw ConfigurationException("Method ${method.name} must be a Kotlin-declared function so Aurora can inspect its return type")

        private fun resolveReturnTypeDescription(
            method: Method,
            kotlinFunction: KFunction<*>?,
        ): String = resolveReturnType(method, kotlinFunction).toString()
    }
}

private data class ProviderCallResult(
    val response: ModelResponse,
    val observation: OperationObservation,
)

private class AttemptCounter {
    private var attempt = 0

    fun next(): Int = attempt++
}

private enum class ReturnKind {
    STRING,
    UNIT,
    STRUCTURED,
    STREAMING,
}

private const val INITIAL_PROVIDER_RETRY_DELAY_MILLIS = 50L
private const val MAX_PROVIDER_RETRY_DELAY_MILLIS = 1_000L
