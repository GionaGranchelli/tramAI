package dev.tramai.standalone

import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ToolInvalidInputException
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.NoOpOperationResponseCache
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.ToolResultFilteringSettings
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.NoOpEngineEventObserver
import dev.tramai.engine.TramaiEngine
import dev.tramai.structured.JacksonStructuredOutputHandler
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

/**
 * Minimal composition module that wires core, engine, and structured output support.
 */
class Tramai private constructor(
    private val providerRegistry: ProviderRegistry,
    private val toolRegistry: ToolRegistry,
    private val operationObserver: OperationObserver,
    private val operationInterceptor: OperationInterceptor,
    private val responseCache: OperationResponseCache,
    private val circuitBreakerSettings: CircuitBreakerSettings,
    private val retryPolicySettings: RetryPolicySettings,
    private val tokenBudgetSettings: TokenBudgetSettings,
    private val dlpInterceptor: DlpInterceptor,
    private val toolResultFilteringSettings: ToolResultFilteringSettings,
    private val engineEventObserver: EngineEventObserver,
    private val promptSanitizer: PromptSanitizer?,
    private val chatMemory: ChatMemory?,
    private val policyDecisionAuditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
    private val policyEngine: PolicyEngine? = null,
) {
    /**
     * Creates a service proxy using the built-in Jackson structured output handler.
     */
    fun <T : Any> create(serviceType: KClass<T>): T = TramaiEngine(
        providerRegistry = providerRegistry,
        structuredOutputHandler = JacksonStructuredOutputHandler(),
        toolRegistry = toolRegistry,
        operationObserver = operationObserver,
        operationInterceptor = operationInterceptor,
        responseCache = responseCache,
        circuitBreakerSettings = circuitBreakerSettings,
        retryPolicySettings = retryPolicySettings,
        tokenBudgetSettings = tokenBudgetSettings,
        dlpInterceptor = dlpInterceptor,
        toolResultFilteringSettings = toolResultFilteringSettings,
        engineEventObserver = engineEventObserver,
        promptSanitizer = promptSanitizer,
        chatMemory = chatMemory,
        policyDecisionAuditEmitter = policyDecisionAuditEmitter,
        policyEngine = policyEngine,
    ).create(serviceType)

    companion object {
        @JvmStatic
        /**
         * Creates a standalone Tramai builder.
         */
        fun builder(): Builder = Builder()
    }

    /**
     * Builder for the standalone Tramai composition module.
     */
    class Builder {
        private val registryBuilder = ProviderRegistry.builder()
        private val tools = mutableMapOf<String, ResolvedTool>()
        private var operationObserver: OperationObserver = NoOpOperationObserver
        private var operationInterceptor: OperationInterceptor = NoOpOperationInterceptor
        private var responseCache: OperationResponseCache = NoOpOperationResponseCache
        private var circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings()
        private var retryPolicySettings: RetryPolicySettings = RetryPolicySettings()
        private var tokenBudgetSettings: TokenBudgetSettings = TokenBudgetSettings()
        private var dlpInterceptor: DlpInterceptor = NoOpDlpInterceptor
        private var toolResultFilteringSettings: ToolResultFilteringSettings = ToolResultFilteringSettings()
        private var engineEventObserver: EngineEventObserver = NoOpEngineEventObserver
        private var promptSanitizer: PromptSanitizer? = null
        private val handler = JacksonStructuredOutputHandler()
        private var chatMemory: ChatMemory? = null
        private var policyDecisionAuditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter
        private var policyEngine: PolicyEngine? = null

        /**
         * Registers a provider with an optional explicit [name].
         */
        fun provider(
            provider: ModelProvider,
            name: String = provider.providerId(),
            default: Boolean = false,
        ): Builder = apply {
            registryBuilder.provider(name, provider, default)
        }

        /**
         * Registers one or more tools with the engine.
         */
        fun tools(vararg tools: TramaiTool<*, *>): Builder = apply {
            tools.forEach { tool ->
                if (this.tools.containsKey(tool.name)) {
                    throw ConfigurationException("Duplicate tool name registered: ${tool.name}")
                }
                this.tools[tool.name] = createResolvedTool(tool, handler)
            }
        }

        fun tools(tools: Iterable<TramaiTool<*, *>>): Builder = apply {
            tools.forEach { tools(it) }
        }

        /**
         * Maps a logical model name to a registered provider.
         */
        fun model(
            modelName: String,
            providerName: String,
        ): Builder = apply {
            registryBuilder.model(modelName, providerName)
        }

        /**
         * Adds an explicit fallback route for a model.
         */
        fun fallbackModel(
            requestedModelName: String,
            fallbackModelName: String,
            providerName: String,
        ): Builder = apply {
            registryBuilder.fallbackModel(requestedModelName, fallbackModelName, providerName)
        }

        /**
         * Adds a fallback route that keeps the same model but uses another provider.
         */
        fun fallbackProvider(
            modelName: String,
            providerName: String,
        ): Builder = apply {
            registryBuilder.fallbackProvider(modelName, providerName)
        }

        /**
         * Selects the default provider used when no explicit mapping applies.
         */
        fun defaultProvider(providerName: String): Builder = apply {
            registryBuilder.defaultProvider(providerName)
        }

        /**
         * Configures the observer used for engine attempts.
         */
        fun observer(observer: OperationObserver): Builder = apply {
            this.operationObserver = observer
        }

        /**
         * Configures the interceptor used for request/response modification.
         */
        fun interceptor(interceptor: OperationInterceptor): Builder = apply {
            this.operationInterceptor = interceptor
        }

        /**
         * Configures the cache used for successful non-streaming operation results.
         */
        fun cache(cache: OperationResponseCache): Builder = apply {
            this.responseCache = cache
        }

        /**
         * Configures the engine-owned circuit breaker used for provider failover.
         */
        fun circuitBreaker(settings: CircuitBreakerSettings): Builder = apply {
            this.circuitBreakerSettings = settings
        }

        /**
         * Configures provider retry pacing for this runtime.
         */
        fun retryPolicy(settings: RetryPolicySettings): Builder = apply {
            this.retryPolicySettings = settings
        }

        /**
         * Configures engine-owned token budget controls for this runtime.
         */
        fun tokenBudget(settings: TokenBudgetSettings): Builder = apply {
            this.tokenBudgetSettings = settings
        }

        /**
         * Configures the DLP interceptor applied to model output before observer, parser, and caller access.
         */
        fun dlp(interceptor: DlpInterceptor): Builder = apply {
            this.dlpInterceptor = interceptor
        }

        /**
         * Configures textual tool-result filtering thresholds.
         */
        fun toolResultFiltering(settings: ToolResultFilteringSettings): Builder = apply {
            this.toolResultFilteringSettings = settings
        }

        /**
         * Configures an observer for engine-level security events (DLP rejection, inspection failure).
         * These events are emitted independently of provider-attempt observations.
         * Defaults to [NoOpEngineEventObserver] when not set.
         */
        fun engineEventObserver(observer: EngineEventObserver): Builder = apply {
            this.engineEventObserver = observer
        }

        /**
         * Configures the sanitizer applied to user-supplied operation arguments before prompt construction.
         */
        fun promptSanitizer(promptSanitizer: PromptSanitizer?): Builder = apply {
            this.promptSanitizer = promptSanitizer
        }

        /**
         * Configures conversational memory for multi-turn interactions.
         *
         * When set, the handler will inject conversation history into each request
         * and persist responses after each invocation.
         *
         * @param chatMemory the memory store (e.g., [dev.tramai.memory.MessageWindowChatMemory])
         */
        fun memory(chatMemory: ChatMemory): Builder = apply {
            this.chatMemory = chatMemory
        }

        /**
         * Configures the audit emitter for policy decisions.
         *
         * When set, every policy evaluation emits a hash-chained audit event
         * through the configured emitter before the decision is enforced.
         * Defaults to [NoOpPolicyDecisionAuditEmitter] when not set.
         */
        fun policyDecisionAudit(emitter: PolicyDecisionAuditEmitter): Builder = apply {
            this.policyDecisionAuditEmitter = emitter
        }

        /**
         * Configures the [PolicyEngine] for explicit policy enforcement.
         *
         * When set, policy decisions at every [dev.tramai.core.policy.EnforcementPoint]
         * are evaluated by the configured engine. When not set, the engine uses
         * [dev.tramai.engine.LegacyPermissivePolicyEngine] for 0.4.x backward
         * compatibility (all operations are allowed; a migration warning is logged).
         */
        fun policyEngine(engine: PolicyEngine): Builder = apply {
            this.policyEngine = engine
        }

        /**
         * Builds an immutable standalone Tramai instance.
         */
        fun build(): Tramai = Tramai(
            providerRegistry = registryBuilder.build(),
            toolRegistry = ToolRegistry(tools.toMap()),
            operationObserver = operationObserver,
            operationInterceptor = operationInterceptor,
            responseCache = responseCache,
            circuitBreakerSettings = circuitBreakerSettings,
            retryPolicySettings = retryPolicySettings,
            tokenBudgetSettings = tokenBudgetSettings,
            dlpInterceptor = dlpInterceptor,
            toolResultFilteringSettings = toolResultFilteringSettings,
            engineEventObserver = engineEventObserver,
            promptSanitizer = promptSanitizer,
            chatMemory = chatMemory,
            policyDecisionAuditEmitter = policyDecisionAuditEmitter,
            policyEngine = policyEngine,
        )
    }
}

/**
 * Reified convenience overload for [Tramai.create].
 */
inline fun <reified T : Any> Tramai.create(): T = create(T::class)

/**
 * Kotlin DSL entry point for constructing a standalone Tramai instance.
 */
fun Tramai(configure: Tramai.Builder.() -> Unit): Tramai = Tramai.builder()
    .apply(configure)
    .build()

/**
 * Creates a [ResolvedTool] that wraps a [TramaiTool] with schema generation and
 * deserialization via a [JacksonStructuredOutputHandler].
 */
private fun createResolvedTool(
    tool: TramaiTool<*, *>,
    handler: JacksonStructuredOutputHandler,
): ResolvedTool = object : ResolvedTool {
    override val name: String = tool.name
    override val description: String = tool.description
    override val inputSchemaJson: String = handler.generateSchema(tool.inputType.createType())
    override val idempotent: Boolean = tool.idempotent
    override val sideEffectLevel: SideEffectLevel = tool.sideEffectLevel

    override suspend fun execute(
        input: Any,
        context: ToolExecutionContext,
    ): ToolResult {
        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as TramaiTool<Any, Any>
        val typedInput = try {
            handler.deserialize(input, tool.inputType.createType())
        } catch (e: ToolInvalidInputException) {
            return ToolResult.InvalidInput(
                e.message ?: "Invalid tool input",
            )
        } catch (e: Exception) {
            return ToolResult.InvalidInput(
                e.message ?: "Invalid tool input",
            )
        }

        return try {
            val result = typedTool.execute(typedInput, context)
            ToolResult.Success(handler.serialize(result))
        } catch (e: ToolInvalidInputException) {
            ToolResult.InvalidInput(e.message ?: "Invalid tool input")
        } catch (e: Exception) {
            if (tool.idempotent) {
                ToolResult.TransientFailure(e)
            } else {
                ToolResult.PermanentFailure(e.message ?: "Tool execution failed")
            }
        }
    }
}
