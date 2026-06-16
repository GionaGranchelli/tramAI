package dev.tramai.spring.sovereign

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalTokenDigester
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.provider.ModelProvider
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
import java.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for TramAI sovereign runtime.
 *
 * Creates beans for:
 * - [SovereignProfileConfiguration] from [SovereignTramaiProperties]
 * - [ModelRegistry] (defaults to empty [InMemoryModelRegistry])
 * - [AuditStore] (defaults to [InMemoryAuditStore])
 * - Approval stores, gate coordinator, digesters
 * - [SovereignTramai] and [SovereignTramaiRuntime]
 *
 * All beans are [ConditionalOnMissingBean] so users can override any default.
 * Set `tramai.sovereign.enabled=false` to disable all sovereign auto-configuration.
 */
@AutoConfiguration
@EnableConfigurationProperties(SovereignTramaiProperties::class)
@ConditionalOnProperty(
    prefix = "tramai.sovereign",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SovereignTramaiAutoConfiguration {

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

    // ── Model registry ───────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun modelRegistry(): ModelRegistry =
        InMemoryModelRegistry.builder().build()

    // ── Audit store ──────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun auditStore(): AuditStore =
        InMemoryAuditStore()

    // ── Approval stores ──────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    fun approvalStore(clock: Clock): InMemoryApprovalStore =
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
        approvalStore: InMemoryApprovalStore,
        clock: Clock,
    ): ApprovalGateCoordinator =
        DefaultApprovalGateCoordinator(
            store = approvalStore,
            approvalIdGenerator = UuidApprovalIdGenerator(),
            approvalTokenGenerator = SecureRandomApprovalTokenGenerator(),
            approvalTokenDigester = Sha256ApprovalTokenDigester(),
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
    @ConditionalOnBean(ModelProvider::class)
    fun sovereignTramai(
        profile: SovereignProfileConfiguration,
        modelRegistry: ModelRegistry,
        auditStore: AuditStore,
        modelProviders: ObjectProvider<ModelProvider>,
        approvalGateCoordinator: ApprovalGateCoordinator,
        approvalContinuationStore: ApprovalContinuationStore,
        toolArgumentsDigester: ToolArgumentsDigester?,
        clock: Clock,
        properties: SovereignTramaiProperties,
    ): SovereignTramai {
        val builder = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(modelRegistry)
            .auditStore(auditStore)
            .approvalGateCoordinator(approvalGateCoordinator)
            .approvalContinuationStore(approvalContinuationStore)
            .clock(clock)

        toolArgumentsDigester?.let { builder.toolArgumentsDigester(it) }

        // Register provider beans from the application context.
        // Users provide ModelProvider beans and the starter wires them in.
        modelProviders.orderedStream().forEach { provider ->
            builder.provider(provider, name = provider.providerId())
        }

        // Register model routes from properties.
        for ((modelName, providerName) in properties.models) {
            builder.model(modelName, providerName)
        }

        // Register allowed tools from properties.
        // Users must also provide actual TramaiTool beans for tools to function.
        // The SovereignTramai builder accepts tool beans via its .tools() method,
        // but tool auto-discovery is intentionally out of scope for the starter MVP.

        return builder.build()
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(SovereignTramai::class)
    fun sovereignTramaiRuntime(
        sovereignTramai: SovereignTramai,
    ): SovereignTramaiRuntime = sovereignTramai.runtime()
}
