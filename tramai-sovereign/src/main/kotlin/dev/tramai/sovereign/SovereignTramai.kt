package dev.tramai.sovereign

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.ModelArtifactVerifier
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.VerifiedLocalModelArtifact
import dev.tramai.core.model.TramaiTool
import dev.tramai.sovereign.evidence.AttestationEvidenceV1
import dev.tramai.sovereign.evidence.AuditChainEvidenceV1
import dev.tramai.sovereign.evidence.ReleaseBundleEvidenceV1
import dev.tramai.sovereign.evidence.SovereignEvidencePackGenerator
import dev.tramai.sovereign.evidence.SovereignEvidencePackV1
import dev.tramai.sovereign.evidence.SupplyChainEvidenceV1
import dev.tramai.sovereign.evidence.ZeroEgressEvidenceV1
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolResultFilteringSettings
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEngineApprovalLifecycleAuditEmitter
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.AuditStore
import dev.tramai.standalone.Tramai
import dev.tramai.standalone.TramaiRuntime
import java.time.Clock
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

/**
 * Secure-by-default embedded runtime profile for sovereign TramAI deployments.
 *
 * Wraps [Tramai] with mandatory security configuration:
 * - Deny-by-default policy engine
 * - Approved-model registry enforcement (always enabled, non-disableable)
 * - Classification-aware provider routing (always enabled)
 * - Hash-chained policy-decision audit emission
 * - Approval lifecycle audit emission wired to the sovereign audit engine
 * - Explicit provider trust zones
 * - Fail-fast build-time provider and route validation
 *
 * `BEFORE_WORKFLOW_RESUME` is intentionally allowed by the sovereign policy
 * engine because resume authorization is enforced earlier by the configured
 * [ApprovalGateCoordinator], which validates token binding and expected-version
 * checks before the workflow can resume.
 *
 * Builder requires a [SovereignProfileConfiguration], [ModelRegistry], [AuditStore],
 * and at least one provider with a trust zone.
 *
 * Usage:
 * ```
 * val tramai = SovereignTramai.builder()
 *     .profile(configuration)
 *     .modelRegistry(registry)
 *     .auditStore(auditStore)
 *     .provider(ollamaProvider, name = "ollama", default = true)
 *     .model("llama3.2", "ollama")
 *     .build()
 * ```
 */
class SovereignTramai private constructor(
    private val delegate: Tramai,
    verificationReceipts: List<VerifiedLocalModelArtifact>,
    private val profile: SovereignProfileConfiguration,
    private val verificationSettings: ModelArtifactVerificationSettings,
) {
    private val verificationReceipts: List<VerifiedLocalModelArtifact> =
        Collections.unmodifiableList(ArrayList(verificationReceipts))
    /**
     * Creates a service proxy for the given service type.
     */
    fun <T : Any> create(serviceType: KClass<T>): T = delegate.create(serviceType)

    /**
     * Creates a [SovereignTramaiRuntime] that owns exactly one engine and exposes
     * both service creation and approval-resume operations.
     */
    fun runtime(): SovereignTramaiRuntime = SovereignTramaiRuntime(delegate.runtime())

    /**
     * Returns immutable verification receipts from build-time artifact verification.
     */
    fun verificationReceipts(): List<VerifiedLocalModelArtifact> = verificationReceipts

    /**
     * Generates a deterministic [SovereignEvidencePackV1] summarising the
     * current deployment's security posture.
     *
     * Safe for auditor review — contains no secrets, tokens, prompts,
     * stack traces, or filesystem paths.
     *
     * @param zeroEgress Optional zero-egress verification subsection.
     * @param auditChain Optional audit-chain validation subsection.
     * @param supplyChain Optional supply-chain SBOM linkage subsection.
     * @param releaseBundle Optional release-bundle artifact manifest subsection.
     * @param attestation Optional CI/CD attestation subsection.
     */
    fun evidencePack(
        zeroEgress: ZeroEgressEvidenceV1? = null,
        auditChain: AuditChainEvidenceV1? = null,
        supplyChain: SupplyChainEvidenceV1? = null,
        releaseBundle: ReleaseBundleEvidenceV1? = null,
        attestation: AttestationEvidenceV1? = null,
    ): SovereignEvidencePackV1 = SovereignEvidencePackGenerator.generate(
        SovereignEvidencePackGenerator.GenerationParams(
            deploymentMode = profile.deploymentMode,
            allowedModels = profile.allowedModels,
            allowedProviders = profile.allowedProviders,
            providerZones = profile.providerZones.mapValues { it.value.name },
            verification = SovereignEvidencePackGenerator.VerificationEvidence(
                verificationSettings = verificationSettings,
                verificationReceipts = verificationReceipts,
            ),
            optionalEvidence = SovereignEvidencePackGenerator.OptionalEvidence(
                zeroEgress = zeroEgress,
                auditChain = auditChain,
                supplyChain = supplyChain,
                releaseBundle = releaseBundle,
                attestation = attestation,
            ),
        ),
    )

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * Describes a fallback route configured during builder assembly.
     */
    private data class FallbackRoute(
        val requestedModelName: String,
        val fallbackModelName: String,
        val providerName: String,
    )

    class Builder {
        private var profileConfiguration: SovereignProfileConfiguration? = null
        private var modelRegistry: ModelRegistry? = null
        private var auditStore: AuditStore? = null
        private val standaloneBuilder = Tramai.builder()

        // Tracking state for build-time validation
        private val registeredProviders = linkedSetOf<String>()
        private val primaryModelRoutes = linkedMapOf<String, String>()
        private val fallbackRoutes = mutableListOf<FallbackRoute>()
        private var defaultProviderName: String? = null
        private var clock: Clock = Clock.systemUTC()
        private var modelArtifactVerifier: ModelArtifactVerifier? = null
        private var verificationSettings: ModelArtifactVerificationSettings =
            ModelArtifactVerificationSettings()

        // --- Required inputs ---

        /**
         * Sets the sovereign profile configuration.
         */
        fun profile(configuration: SovereignProfileConfiguration): Builder = apply {
            this.profileConfiguration = configuration
        }

        /**
         * Sets the approved-model registry.
         */
        fun modelRegistry(registry: ModelRegistry): Builder = apply {
            this.modelRegistry = registry
        }

        /**
         * Sets the audit store for hash-chained policy-decision audit events.
         */
        fun auditStore(store: AuditStore): Builder = apply {
            this.auditStore = store
        }

        // --- Delegated standalone builder methods with tracking ---

        /**
         * Registers a provider with an optional explicit [name].
         *
         * @throws IllegalArgumentException if the provider name is blank,
         *   has surrounding whitespace, or is a duplicate.
         */
        fun provider(
            provider: ModelProvider,
            name: String = provider.providerId(),
            default: Boolean = false,
        ): Builder = apply {
            require(name.isNotBlank()) { "Provider name must not be blank" }
            require(name == name.trim()) { "Provider name must not have surrounding whitespace" }
            require(name !in registeredProviders) { "Duplicate provider registration: $name" }
            registeredProviders.add(name)
            if (default) defaultProviderName = name
            standaloneBuilder.provider(provider, name, default)
        }

        /**
         * Maps a logical model name to a registered provider.
         */
        fun model(
            modelName: String,
            providerName: String,
        ): Builder = apply {
            primaryModelRoutes[modelName] = providerName
            standaloneBuilder.model(modelName, providerName)
        }

        /**
         * Registers one or more tools.
         */
        fun tools(vararg tools: TramaiTool<*, *>): Builder = apply {
            standaloneBuilder.tools(*tools)
        }

        /**
         * Registers tools from an iterable.
         */
        fun tools(tools: Iterable<TramaiTool<*, *>>): Builder = apply {
            standaloneBuilder.tools(tools)
        }

        // --- Optional delegation ---

        fun fallbackModel(
            requestedModelName: String,
            fallbackModelName: String,
            providerName: String,
        ): Builder = apply {
            fallbackRoutes.add(FallbackRoute(requestedModelName, fallbackModelName, providerName))
            standaloneBuilder.fallbackModel(requestedModelName, fallbackModelName, providerName)
        }

        fun fallbackProvider(
            modelName: String,
            providerName: String,
        ): Builder = fallbackModel(
            requestedModelName = modelName,
            fallbackModelName = modelName,
            providerName = providerName,
        )

        fun defaultProvider(providerName: String): Builder = apply {
            this.defaultProviderName = providerName
            standaloneBuilder.defaultProvider(providerName)
        }

        fun observer(observer: OperationObserver): Builder = apply {
            standaloneBuilder.observer(observer)
        }

        fun interceptor(interceptor: OperationInterceptor): Builder = apply {
            standaloneBuilder.interceptor(interceptor)
        }

        fun cache(cache: OperationResponseCache): Builder = apply {
            standaloneBuilder.cache(cache)
        }

        fun circuitBreaker(settings: CircuitBreakerSettings): Builder = apply {
            standaloneBuilder.circuitBreaker(settings)
        }

        fun retryPolicy(settings: RetryPolicySettings): Builder = apply {
            standaloneBuilder.retryPolicy(settings)
        }

        fun tokenBudget(settings: TokenBudgetSettings): Builder = apply {
            standaloneBuilder.tokenBudget(settings)
        }

        fun dlp(interceptor: DlpInterceptor): Builder = apply {
            standaloneBuilder.dlp(interceptor)
        }

        fun dlpRedactionAudit(emitter: DlpRedactionAuditEmitter): Builder = apply {
            standaloneBuilder.dlpRedactionAudit(emitter)
        }

        fun toolResultFiltering(settings: ToolResultFilteringSettings): Builder = apply {
            standaloneBuilder.toolResultFiltering(settings)
        }

        fun engineEventObserver(observer: EngineEventObserver): Builder = apply {
            standaloneBuilder.engineEventObserver(observer)
        }

        fun modelArtifactVerifier(verifier: ModelArtifactVerifier): Builder = apply {
            this.modelArtifactVerifier = verifier
        }

        fun modelArtifactVerificationSettings(
            settings: ModelArtifactVerificationSettings,
        ): Builder = apply {
            this.verificationSettings = settings
        }

        // --- Approval suspension delegation ---

        /**
         * Configures the store for suspended invocation metadata and sensitive context.
         * Defaults to the engine's in-memory implementation when not set.
         */
        fun suspendedInvocationStore(
            store: dev.tramai.engine.SuspendedInvocationStore,
        ): Builder = apply {
            standaloneBuilder.suspendedInvocationStore(store)
        }

        /**
         * Configures the store for approval continuations (persistent tool arguments
         * and binding metadata).
         */
        fun approvalContinuationStore(
            store: ApprovalContinuationStore,
        ): Builder = apply {
            standaloneBuilder.approvalContinuationStore(store)
        }

        /**
         * Configures the digester for tool arguments, used to compute the
         * deterministic hash bound into the approval challenge.
         */
        fun toolArgumentsDigester(
            digester: ToolArgumentsDigester,
        ): Builder = apply {
            standaloneBuilder.toolArgumentsDigester(digester)
        }

        /**
         * Configures the coordinator that creates and authorizes approval requests.
         */
        fun approvalGateCoordinator(
            coordinator: ApprovalGateCoordinator,
        ): Builder = apply {
            standaloneBuilder.approvalGateCoordinator(coordinator)
        }

        /**
         * Configures the clock used for approval expiry and audit timestamps.
         */
        fun clock(clock: Clock): Builder = apply {
            this.clock = clock
            standaloneBuilder.clock(clock)
        }

        // --- Build ---

        /**
         * Builds the [SovereignTramai] instance with fail-fast validation.
         *
         * @throws IllegalStateException if required inputs are missing.
         * @throws IllegalArgumentException if provider or route validation fails.
         */
        fun build(): SovereignTramai {
            val profile = checkNotNull(profileConfiguration) {
                "SovereignProfileConfiguration is required"
            }
            checkNotNull(auditStore) {
                "AuditStore is required for sovereign profile"
            }
            checkNotNull(modelRegistry) {
                "ModelRegistry is required for sovereign profile"
            }

            // Build-time provider and route validation
            require(registeredProviders.isNotEmpty()) {
                "At least one provider must be registered"
            }

            // Every registered provider must be explicitly allowed
            for (p in registeredProviders) {
                require(p in profile.allowedProviders) {
                    "Registered provider '$p' is not in allowedProviders"
                }
            }

            // Every allowed provider must be registered
            for (p in profile.allowedProviders) {
                require(p in registeredProviders) {
                    "Allowed provider '$p' has not been registered"
                }
            }

            // Every registered provider must have an explicit trust zone
            for (p in registeredProviders) {
                require(p in profile.providerZones) {
                    "Registered provider '$p' has no trust zone configured"
                }
            }

            // Every allowed model must have an explicit primary route
            for (m in profile.allowedModels) {
                require(m in primaryModelRoutes) {
                    "Allowed model '$m' has no primary route"
                }
            }

            // Every primary route must target a registered allowed provider
            for ((modelName, providerName) in primaryModelRoutes) {
                require(modelName in profile.allowedModels) {
                    "Primary route for '$modelName' routes a model not in allowedModels"
                }
                require(providerName in registeredProviders) {
                    "Model '$modelName' routes to unknown provider '$providerName'"
                }
                require(providerName in profile.allowedProviders) {
                    "Model '$modelName' routes to non-allowed provider '$providerName'"
                }
            }

            // Fallback routes must target registered providers
            for (fb in fallbackRoutes) {
                require(fb.requestedModelName in profile.allowedModels) {
                    "Fallback source model '${fb.requestedModelName}' is not in allowedModels"
                }
                require(fb.providerName in registeredProviders) {
                    "Fallback route for '${fb.requestedModelName}' targets unknown provider '${fb.providerName}'"
                }
                require(fb.providerName in profile.allowedFallbackProviders) {
                    "Fallback provider '${fb.providerName}' is not in allowedFallbackProviders"
                }
                require(fb.fallbackModelName in profile.allowedModels) {
                    "Fallback model '${fb.fallbackModelName}' is not in allowedModels"
                }
            }

            // Default provider must be registered and allowed
            val defaultName = defaultProviderName
            if (defaultName != null) {
                require(defaultName in registeredProviders) {
                    "Default provider '$defaultName' is not registered"
                }
                require(defaultName in profile.allowedProviders) {
                    "Default provider '$defaultName' is not in allowedProviders"
                }
            }

            // Offline deployment validation — before registry lookup
            validateOfflineDeployment(profile)

            val verificationReceipts = verifyLocalModelArtifacts(
                profile = profile,
                modelRegistry = modelRegistry!!,
            )

            val policyConfig: PolicyConfiguration = profile.toPolicyConfiguration()
            val policyEngine = DefaultPolicyEngine(policyConfig)
            val auditEng = AuditEngine(store = auditStore!!, clock = clock)
            val policyAuditEmitter = AuditEnginePolicyDecisionAuditEmitter(auditEng)
            val approvalLifecycleEmitter = AuditEngineApprovalLifecycleAuditEmitter(auditEng)

            val tramai = standaloneBuilder
                .policyEngine(policyEngine)
                .policyDecisionAudit(policyAuditEmitter)
                .modelRegistry(modelRegistry!!)
                .modelRegistrySettings(ModelRegistrySettings(enabled = true))
                .approvalLifecycleAudit(approvalLifecycleEmitter)
                .build()

            return SovereignTramai(
                delegate = tramai,
                verificationReceipts = verificationReceipts,
                profile = profile,
                verificationSettings = verificationSettings,
            )
        }

        private fun verifyLocalModelArtifacts(
            profile: SovereignProfileConfiguration,
            modelRegistry: ModelRegistry,
        ): List<VerifiedLocalModelArtifact> {
            if (!verificationSettings.enabled) {
                return emptyList()
            }

            val verifier = checkNotNull(modelArtifactVerifier) {
                "artifact-verification-not-configured"
            }

            // Collect unique (providerName, modelName) targets from primary AND fallback routes
            val verificationTargets = buildSet {
                primaryModelRoutes.forEach { (modelName, providerName) ->
                    add(providerName to modelName)
                }
                fallbackRoutes.forEach { route ->
                    add(route.providerName to route.fallbackModelName)
                }
            }

            val safeCodes = setOf(
                "artifact-manifest-not-found",
                "artifact-manifest-identity-drift",
                "artifact-aggregate-digest-mismatch",
                "artifact-file-not-found",
                "artifact-file-symlink-rejected",
                "artifact-file-size-mismatch",
                "artifact-file-digest-mismatch",
                "artifact-file-access-failed",
                "artifact-traversal-rejected",
                "artifact-directory-substituted-for-file",
                "artifact-not-a-regular-file",
                "artifact-total-size-overflow",
            )

            fun sanitizedArtifactReason(exception: Exception): String =
                exception.message?.takeIf { it in safeCodes }
                    ?: "artifact-verification-failed"

            return runBlocking {
                val receipts = mutableListOf<VerifiedLocalModelArtifact>()
                for ((providerName, modelName) in verificationTargets) {
                    val trustZone = profile.providerZones.getValue(providerName)
                    if (trustZone != ProviderTrustZone.LOCAL) {
                        continue
                    }

                    val registeredModel = try {
                        modelRegistry.findApprovedModel(providerName, modelName)
                    } catch (exception: kotlinx.coroutines.CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        throw IllegalStateException(
                            "artifact-approved-model-lookup-failed",
                        )
                    } ?: throw IllegalStateException("artifact-approved-model-not-found")

                    check(
                        !verificationSettings.requireDigestForLocalModels ||
                            registeredModel.artifactDigest != null,
                    ) {
                        "artifact-digest-required-for-local-model"
                    }

                    val receipt = try {
                        verifier.verify(registeredModel)
                    } catch (exception: kotlinx.coroutines.CancellationException) {
                        throw exception
                    } catch (exception: IllegalStateException) {
                        throw IllegalStateException(
                            sanitizedArtifactReason(exception),
                        )
                    } catch (exception: IllegalArgumentException) {
                        throw IllegalStateException(
                            sanitizedArtifactReason(exception),
                        )
                    } catch (exception: Exception) {
                        throw IllegalStateException("artifact-verification-failed")
                    } ?: throw IllegalStateException("artifact-manifest-not-found")

                    receipts += receipt
                }
                receipts.toList()
            }
        }

        private fun validateOfflineDeployment(
            profile: SovereignProfileConfiguration,
        ) {
            if (profile.deploymentMode != SovereignDeploymentMode.OFFLINE) {
                return
            }

            for (providerName in registeredProviders) {
                require(profile.providerZones.getValue(providerName) == ProviderTrustZone.LOCAL) {
                    "offline-profile-non-local-provider-rejected"
                }
            }

            for ((_, providerName) in primaryModelRoutes) {
                require(profile.providerZones.getValue(providerName) == ProviderTrustZone.LOCAL) {
                    "offline-profile-non-local-primary-route-rejected"
                }
            }

            for (fallback in fallbackRoutes) {
                require(profile.providerZones.getValue(fallback.providerName) == ProviderTrustZone.LOCAL) {
                    "offline-profile-non-local-fallback-rejected"
                }
            }

            defaultProviderName?.let { providerName ->
                require(profile.providerZones.getValue(providerName) == ProviderTrustZone.LOCAL) {
                    "offline-profile-non-local-default-provider-rejected"
                }
            }
        }
    }
}

/**
 * Reified convenience overload for [SovereignTramai.create].
 */
inline fun <reified T : Any> SovereignTramai.create(): T = create(T::class)

/**
 * Runtime session owning exactly one engine for sovereign TramAI deployments.
 *
 * Wraps [TramaiRuntime] to prevent unsafe standalone methods from leaking
 * into the sovereign API.
 */
class SovereignTramaiRuntime internal constructor(
    private val delegate: TramaiRuntime,
) : AutoCloseable by delegate {

    /**
     * Creates a service proxy for the given service type.
     */
    fun <T : Any> create(serviceType: KClass<T>): T =
        delegate.create(serviceType)

    /**
     * Registers a service type without creating a proxy.
     *
     * Delegates to [TramaiRuntime.registerService].
     *
     * Use after runtime restart before calling [resumeApproval]:
     * ```
     * runtime.registerService<InvoiceIntelligenceService>()
     * runtime.resumeApprovalTyped<InvoiceAssessment>(command)
     * ```
     */
    fun registerService(serviceType: KClass<*>) {
        delegate.registerService(serviceType)
    }

    /**
     * Resumes an approval-suspended tool execution.
     */
    suspend fun resumeApproval(command: ResumeApprovalCommand): Any? =
        delegate.resumeApproval(command)

    /**
     * Typed convenience overload for [resumeApproval].
     */
    @Suppress("UNCHECKED_CAST")
    suspend inline fun <reified R> resumeApprovalTyped(
        command: ResumeApprovalCommand,
    ): R = resumeApproval(command) as R

}

/**
 * Reified convenience overload for [SovereignTramaiRuntime.create].
 */
inline fun <reified T : Any> SovereignTramaiRuntime.create(): T = create(T::class)

/**
 * Reified convenience overload for [SovereignTramaiRuntime.registerService].
 */
inline fun <reified T : Any> SovereignTramaiRuntime.registerService(): Unit =
    registerService(T::class)
