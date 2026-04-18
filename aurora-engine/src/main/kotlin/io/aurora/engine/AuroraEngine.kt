package io.aurora.engine

import io.aurora.core.annotations.AiService
import io.aurora.core.annotations.Operation
import io.aurora.core.annotations.SystemPrompt
import io.aurora.core.exception.ConfigurationException
import io.aurora.core.exception.ProviderException
import io.aurora.core.exception.TimeoutException
import io.aurora.core.model.Message
import io.aurora.core.model.MessageRole
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.observation.NoOpOperationObserver
import io.aurora.core.observation.OperationCallContext
import io.aurora.core.observation.OperationObservation
import io.aurora.core.observation.OperationObserver
import io.aurora.core.provider.ModelProvider
import io.aurora.core.provider.ProviderRegistry
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
        operationObserver: OperationObserver = NoOpOperationObserver,
        job: Job = SupervisorJob(),
        scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
    ) : this(
        providerRegistry = ProviderRegistry.singleProvider(provider),
        structuredOutputHandler = structuredOutputHandler,
        operationObserver = operationObserver,
        job = job,
        scope = scope,
    )

    /**
     * Creates a proxy implementation for the given Aurora service interface.
     */
    fun <T : Any> create(serviceType: KClass<T>): T {
        val definition = ServiceDefinition.create(serviceType)
        val handler = AuroraInvocationHandler(
            providerRegistry = providerRegistry,
            structuredOutputHandler = structuredOutputHandler,
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
        }
    }

    private suspend fun executeRaw(
        operation: OperationDefinition,
        arguments: List<Any?>,
    ): String {
        val provider = providerRegistry.resolve(operation.operation)
        val result = callProviderWithRetries(
            provider = provider,
            request = operation.toRequest(arguments),
            operation = operation,
            attemptCounter = AttemptCounter(),
        )

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
        val attemptCounter = AttemptCounter()

        repeat(maxAttempts) { attemptIndex ->
            val provider = providerRegistry.resolve(operation.operation)
            val result = callProviderWithRetries(
                provider = provider,
                request = ModelRequest(
                    model = operation.operation.model,
                    messages = messages.toList(),
                    timeoutMillis = operation.operation.timeoutMillis,
                    operationInterface = operation.method.declaringClass.name,
                    operationMethod = operation.method.name,
                ),
                operation = operation,
                attemptCounter = attemptCounter,
            )

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
                    // The engine owns retries. Structured output only supplies the validation result and feedback text.
                    messages += Message(MessageRole.ASSISTANT, analysis.rawResponse)
                    messages += Message(MessageRole.USER, analysis.feedbackMessage)
                }
            }
        }

        error("Structured retry loop exited without returning or throwing")
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
        fun create(serviceType: KClass<*>): ServiceDefinition {
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
                    OperationDefinition.create(method, operation, systemPrompt)
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
) {
    fun toRequest(arguments: List<Any?>): ModelRequest {
        return ModelRequest(
            model = operation.model,
            messages = initialMessages(arguments),
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
            val classifier = resolveReturnType(method, kotlinFunction).classifier
            return when (classifier) {
                String::class -> ReturnKind.STRING
                Unit::class -> ReturnKind.UNIT
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
}

private const val INITIAL_PROVIDER_RETRY_DELAY_MILLIS = 50L
private const val MAX_PROVIDER_RETRY_DELAY_MILLIS = 1_000L
