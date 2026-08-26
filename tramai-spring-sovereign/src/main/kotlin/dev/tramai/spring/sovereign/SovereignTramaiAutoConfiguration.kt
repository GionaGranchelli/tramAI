package dev.tramai.spring.sovereign

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTokenDigester
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.security.approval.DefaultApprovalGateCoordinator
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.approval.SecureRandomApprovalTokenGenerator
import dev.tramai.security.approval.Sha256ApprovalTokenDigester
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import dev.tramai.security.approval.UuidApprovalIdGenerator
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.model.InMemoryModelRegistry
import dev.tramai.sovereign.SovereignProfileConfiguration
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.spring.AiToolScanner
import dev.tramai.spring.SpringConfiguredModelProvider
import dev.tramai.spring.SpringProviderResolution
import dev.tramai.standalone.Tramai
import java.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for TramAI sovereign runtime.
 *
 * Creates beans for:
 * - [SovereignProfileConfiguration] from [SovereignTramaiProperties]
 * - [ModelRegistry] derived from `tramai.sovereign.models` (or user-provided)
 * - [AuditStore] (defaults to [InMemoryAuditStore])
 * - Approval stores, gate coordinator, digesters
 * - [SovereignTramai] and [SovereignTramaiRuntime]
 *
 * All beans are [ConditionalOnMissingBean] so users can override any default.
 *
 * `tramai.profile` is the sole runtime selector; this configuration activates
 * through [SovereignTramaiProfileAutoConfiguration]. The legacy
 * `tramai.sovereign.enabled=false` switch is rejected by property validation
 * rather than silently suppressing the runtime (see
 * [SovereignTramaiProperties]).
 */
@AutoConfiguration
@EnableConfigurationProperties(SovereignTramaiProperties::class)
class SovereignTramaiAutoConfiguration {

    @field:Autowired
    private lateinit var applicationContext: ApplicationContext

    companion object {
        private const val DEFAULT_REVISION = "0.0.1"
    }

    data class SovereignTramaiInfrastructure(
        val approvalGateCoordinator: ApprovalGateCoordinator,
        val approvalContinuationStore: ApprovalContinuationStore,
        val suspendedInvocationStore: SuspendedInvocationStore?,
        val toolArgumentsDigester: ToolArgumentsDigester?,
        val clock: Clock,
    )

    // ── Sovereign profile configuration ──────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun sovereignProfileConfiguration(
        properties: SovereignTramaiProperties,
    ): SovereignProfileConfiguration {
        val zones = properties.resolveProviderTrustZones()
        return SovereignProfileConfiguration(
            allowedModels = properties.allowedModels,
            allowedProviders = properties.allowedProviders,
            allowedTools = properties.allowedTools,
            allowedPermissions = properties.allowedPermissions,
            providerZones = zones,
        )
    }

    // ── Model registry (derived from properties.models) ───────────────────

    @Bean
    @ConditionalOnMissingBean
    fun modelRegistry(
        properties: SovereignTramaiProperties,
    ): ModelRegistry {
        val builder = InMemoryModelRegistry.builder()
        for ((modelName, providerName) in properties.models) {
            builder.register(
                RegisteredModel(
                    registryEntryId = modelName,
                    providerId = providerName,
                    modelName = modelName,
                    revision = DEFAULT_REVISION,
                ),
            )
        }
        return builder.build()
    }

    // ── Audit store ──────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun auditStore(): AuditStore =
        InMemoryAuditStore()

    // ── Approval stores ──────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun approvalStore(clock: Clock): ApprovalStore =
        InMemoryApprovalStore(clock = clock)

    @Bean
    @ConditionalOnMissingBean
    fun approvalContinuationStore(
        clock: Clock,
    ): ApprovalContinuationStore =
        InMemoryApprovalContinuationStore(clock = clock)

    // ── Approval gate coordinator ────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun approvalGateCoordinator(
        approvalStore: ApprovalStore,
        approvalTokenDigester: ApprovalTokenDigester,
        clock: Clock,
    ): ApprovalGateCoordinator =
        DefaultApprovalGateCoordinator(
            store = approvalStore,
            approvalIdGenerator = UuidApprovalIdGenerator(),
            approvalTokenGenerator = SecureRandomApprovalTokenGenerator(),
            approvalTokenDigester = approvalTokenDigester,
            clock = clock,
        )

    // ── Digesters ────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun toolArgumentsDigester(): ToolArgumentsDigester =
        Sha256ToolArgumentsDigester()

    @Bean
    @ConditionalOnMissingBean
    fun approvalTokenDigester(): ApprovalTokenDigester =
        Sha256ApprovalTokenDigester()

    // ── Clock ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun sovereignClock(): Clock = Clock.systemUTC()

    // ── Sovereign runtime ────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun sovereignTramaiInfrastructure(
        approvalGateCoordinator: ApprovalGateCoordinator,
        approvalContinuationStore: ApprovalContinuationStore,
        suspendedInvocationStore: ObjectProvider<SuspendedInvocationStore>,
        toolArgumentsDigester: ToolArgumentsDigester?,
        clock: Clock,
    ): SovereignTramaiInfrastructure =
        SovereignTramaiInfrastructure(
            approvalGateCoordinator = approvalGateCoordinator,
            approvalContinuationStore = approvalContinuationStore,
            suspendedInvocationStore = suspendedInvocationStore.ifAvailable,
            toolArgumentsDigester = toolArgumentsDigester,
            clock = clock,
        )

    @Bean
    @ConditionalOnMissingBean
    fun sovereignTramai(
        profile: SovereignProfileConfiguration,
        modelRegistry: ModelRegistry,
        auditStore: AuditStore,
        modelProviders: ObjectProvider<ModelProvider>,
        springConfiguredProviders: ObjectProvider<SpringConfiguredModelProvider>,
        toolProviders: ObjectProvider<TramaiTool<*, *>>,
        properties: SovereignTramaiProperties,
        infrastructure: SovereignTramaiInfrastructure,
    ): SovereignTramai {
        // Exactly one runtime authority: selecting the sovereign profile while a
        // plain Tramai bean exists (user-supplied, since standard auto-config is
        // profile-exclusive) is an ambiguous configuration and must fail loudly.
        val manualTramaiBeans = applicationContext.getBeanNamesForType(Tramai::class.java, false, false)
        check(manualTramaiBeans.isEmpty()) {
            "tramai.profile=sovereign is incompatible with a plain Tramai bean " +
                "(found: ${manualTramaiBeans.joinToString()}). tramai.profile is the sole runtime " +
                "selector and exactly one runtime authority is allowed."
        }

        val builder = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(modelRegistry)
            .auditStore(auditStore)
            .approvalGateCoordinator(infrastructure.approvalGateCoordinator)
            .approvalContinuationStore(infrastructure.approvalContinuationStore)
            .clock(infrastructure.clock)

        infrastructure.suspendedInvocationStore?.let { builder.suspendedInvocationStore(it) }
        infrastructure.toolArgumentsDigester?.let { builder.toolArgumentsDigester(it) }

        // Same provider-resolution semantics as the standard profile: adapter
        // modules contribute SpringConfiguredModelProvider descriptors and
        // explicit ModelProvider beans override property-backed providers.
        // A selected profile must never produce a runtime with zero providers.
        val resolvedProviders = SpringProviderResolution.resolve(
            springConfiguredProviders = springConfiguredProviders,
            beanProviders = modelProviders,
        )
        check(resolvedProviders.isNotEmpty()) {
            "tramai.profile=sovereign requires at least one model provider: add a " +
                "tramai-spring-provider-* adapter with its properties or a ModelProvider bean."
        }
        resolvedProviders.forEach { (providerId, provider) ->
            builder.provider(provider, name = providerId)
        }

        // Preserve explicit TramaiTool beans and add the same @AiTool method
        // discovery used by the standard Spring runtime. Duplicate names are
        // intentionally passed through so the engine remains the single
        // fail-loud authority for tool identity collisions.
        val explicitTools = toolProviders.orderedStream().toList()
        val annotatedTools = AiToolScanner.fromApplicationContext(applicationContext)
        builder.tools(explicitTools + annotatedTools)

        // Register model routes from properties.
        for ((modelName, providerName) in properties.models) {
            builder.model(modelName, providerName)
        }

        return builder.build()
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(SovereignTramai::class)
    fun sovereignTramaiRuntime(
        sovereignTramai: SovereignTramai,
    ): SovereignTramaiRuntime = sovereignTramai.runtime()
}
