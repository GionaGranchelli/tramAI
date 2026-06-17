package dev.tramai.spring.sovereign

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.provider.ModelProvider
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.model.InMemoryModelRegistry
import dev.tramai.sovereign.SovereignProfileConfiguration
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.spring.AiServiceBeanDefinitionRegistrar
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import kotlin.test.Test

class SovereignTramaiAutoConfigurationTest {

    private val minimalProperties = mapOf(
        "tramai.sovereign.allowed-models[0]" to "local-invoice-model",
        "tramai.sovereign.allowed-providers[0]" to "local-provider",
        "tramai.sovereign.provider-zones.local-provider" to "LOCAL",
        "tramai.sovereign.models.local-invoice-model" to "local-provider",
    )

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignTramaiAutoConfiguration::class.java),
        )

    // ── Happy path ───────────────────────────────────────────────────────

    @Test
    fun `creates SovereignProfileConfiguration from valid properties`() {
        contextRunner
            .withPropertyValues(*minimalProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                assertThat(context).hasSingleBean(SovereignProfileConfiguration::class.java)
                val profile = context.getBean(SovereignProfileConfiguration::class.java)
                assertThat(profile.allowedModels).containsExactly("local-invoice-model")
                assertThat(profile.allowedProviders).containsExactly("local-provider")
                assertThat(profile.providerZones).containsKey("local-provider")
            }
    }

    @Test
    fun `model registry is derived from properties dot models`() {
        contextRunner
            .withPropertyValues(*minimalProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                assertThat(context).hasSingleBean(ModelRegistry::class.java)
                val registry = context.getBean(ModelRegistry::class.java)
                assertThat(registry).isInstanceOf(InMemoryModelRegistry::class.java)
            }
    }

    @Test
    fun `creates SovereignTramai and SovereignTramaiRuntime when providers are available`() {
        contextRunner
            .withUserConfiguration(MinimalProviderConfiguration::class.java)
            .withPropertyValues(*minimalProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
            }
    }

    @Test
    fun `sovereign runtime creates an AiService and invokes it`() {
        contextRunner
            .withUserConfiguration(
                MinimalProviderConfiguration::class.java,
                AiServiceRegistrarConfiguration::class.java,
            )
            .withPropertyValues(*minimalProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
                val runtime = context.getBean(SovereignTramaiRuntime::class.java)
                val service = runtime.create(TestInvoiceAi::class)
                val result = runBlocking { service.analyze("invoice-001") }
                assertThat(result).isEqualTo("stub response")
            }
    }

    // ── enabled=false ────────────────────────────────────────────────────

    @Test
    fun `sovereign profile beans are not created when enabled is false`() {
        contextRunner
            .withPropertyValues("tramai.sovereign.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(SovereignProfileConfiguration::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramaiRuntime::class.java)
            }
    }

    // ── User beans are respected ─────────────────────────────────────────

    @Test
    fun `user-provided ModelRegistry bean is respected`() {
        contextRunner
            .withUserConfiguration(
                MinimalProviderConfiguration::class.java,
                ModelRegistrySupplierConfiguration::class.java,
            )
            .withPropertyValues(*minimalProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val registry = context.getBean(ModelRegistry::class.java)
                assertThat(registry).isInstanceOf(InMemoryModelRegistry::class.java)
            }
    }

    @Test
    fun `user-provided AuditStore bean is respected`() {
        contextRunner
            .withUserConfiguration(
                MinimalProviderConfiguration::class.java,
                CustomAuditStoreConfiguration::class.java,
            )
            .withPropertyValues(*minimalProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val store = context.getBean(AuditStore::class.java)
                assertThat(store).isInstanceOf(CustomAuditStore::class.java)
            }
    }

    @Test
    fun `user-provided ApprovalStore bean is respected`() {
        contextRunner
            .withUserConfiguration(
                MinimalProviderConfiguration::class.java,
                CustomApprovalStoreConfiguration::class.java,
            )
            .withPropertyValues(*minimalProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val store = context.getBean(ApprovalStore::class.java)
                assertThat(store).isInstanceOf(CustomApprovalStore::class.java)
            }
    }

    @Test
    fun `user-provided Clock bean is respected`() {
        contextRunner
            .withUserConfiguration(
                MinimalProviderConfiguration::class.java,
                CustomClockConfiguration::class.java,
            )
            .withPropertyValues(*minimalProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val clock = context.getBean(Clock::class.java)
                assertThat(clock.instant()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
            }
    }

    // ── Validation: missing allowed models ───────────────────────────────

    @Test
    fun `missing allowed models fails`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.allowed-providers[0]=local-provider",
                "tramai.sovereign.provider-zones.local-provider=LOCAL",
            )
            .run { context ->
                assertThat(context).hasFailed()
                val failure = requireNotNull(context.startupFailure)
                assertThat(failure).hasMessageContaining("tramai-sovereign-spring-missing-allowed-models")
            }
    }

    // ── Validation: missing allowed providers ───────────────────────────

    @Test
    fun `missing allowed providers fails`() {
        contextRunner
            .withPropertyValues("tramai.sovereign.allowed-models[0]=local-invoice-model")
            .run { context ->
                assertThat(context).hasFailed()
                val failure = requireNotNull(context.startupFailure)
                assertThat(failure).hasMessageContaining("tramai-sovereign-spring-missing-allowed-providers")
            }
    }

    // ── Validation: missing provider zone ────────────────────────────────

    @Test
    fun `missing provider zone fails`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.allowed-models[0]=local-invoice-model",
                "tramai.sovereign.allowed-providers[0]=local-provider",
            )
            .run { context ->
                assertThat(context).hasFailed()
                val failure = requireNotNull(context.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-spring-provider-zone-missing")
                    .hasMessageContaining("local-provider")
            }
    }

    // ── Validation: unknown provider zone ────────────────────────────────

    @Test
    fun `unknown provider zone fails`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.allowed-models[0]=local-invoice-model",
                "tramai.sovereign.allowed-providers[0]=local-provider",
                "tramai.sovereign.provider-zones.local-provider=LOCAL",
                "tramai.sovereign.provider-zones.extra-provider=LOCAL",
                "tramai.sovereign.models.local-invoice-model=local-provider",
            )
            .run { context ->
                assertThat(context).hasFailed()
                val failure = requireNotNull(context.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-spring-provider-zone-unknown-provider")
            }
    }

    // ── Validation: invalid provider zone value ──────────────────────────

    @Test
    fun `invalid provider zone value fails`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.allowed-models[0]=local-invoice-model",
                "tramai.sovereign.allowed-providers[0]=local-provider",
                "tramai.sovereign.provider-zones.local-provider=NOT_A_ZONE",
            )
            .run { context ->
                assertThat(context).hasFailed()
                val failure = requireNotNull(context.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-spring-invalid-provider-zone")
            }
    }
}

// ── Test interfaces ─────────────────────────────────────────────────────

@AiService
interface TestInvoiceAi {
    @Operation(
        prompt = "Analyze the invoice",
        model = "local-invoice-model",
    )
    suspend fun analyze(invoiceId: String): String
}

// ── User Configuration classes (top-level) ───────────────────────────────

open class MinimalProviderConfiguration {
    @Bean
    open fun stubModelProvider(): StubModelProvider = StubModelProvider()
}

open class ModelRegistrySupplierConfiguration {
    @Bean
    @Primary
    open fun customModelRegistry(): InMemoryModelRegistry =
        InMemoryModelRegistry.builder().build()
}

open class AiServiceRegistrarConfiguration {
    @Bean
    open fun aiServiceBeanDefinitionRegistrar(
        beanFactory: ConfigurableListableBeanFactory,
    ): AiServiceBeanDefinitionRegistrar = AiServiceBeanDefinitionRegistrar(beanFactory)
}

open class CustomApprovalStoreConfiguration {
    @Bean
    open fun customApprovalStore(): ApprovalStore = CustomApprovalStore()
}

open class CustomAuditStoreConfiguration {
    @Bean
    open fun customAuditStore(): AuditStore = CustomAuditStore()
}

open class CustomClockConfiguration {
    @Bean
    open fun sovereignClock(): Clock =
        Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneId.of("UTC"))
}

// ── Stub implementations ─────────────────────────────────────────────────

class StubModelProvider : ModelProvider {
    override fun providerId(): String = "local-provider"
    override suspend fun complete(request: dev.tramai.core.model.ModelRequest): dev.tramai.core.model.ModelResponse =
        dev.tramai.core.model.ModelResponse(content = "stub response")
}

class CustomAuditStore : AuditStore {
    override suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (AuditEvent?) -> AuditEvent,
    ): AuditEvent = eventFactory(
        AuditEvent(
            schemaVersion = 1,
            hashAlgorithm = dev.tramai.security.audit.AuditHashAlgorithm.SHA_256,
            auditStreamId = auditStreamId,
            eventId = "test-event",
            sequenceNumber = 1,
            workflowRunId = null,
            correlationId = null,
            actor = "test",
            enforcementPoint = "test",
            decision = "permit",
            policyVersion = null,
            workflowDigest = null,
            previousEventHash = null,
            eventHash = "a".repeat(64),
            timestamp = Instant.now(),
            reasonCode = null,
        )
    )

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> = emptyList()
    override suspend fun latestEvent(auditStreamId: String): AuditEvent? = null
}

class CustomApprovalStore : ApprovalStore {
    private var created = false

    override suspend fun create(request: dev.tramai.core.approval.ApprovalRequest): dev.tramai.core.approval.ApprovalRequest {
        created = true
        return request
    }

    override suspend fun get(approvalId: String): dev.tramai.core.approval.ApprovalRequest? = null

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: dev.tramai.core.approval.ApprovalTransition,
    ): dev.tramai.core.approval.ApprovalRequest {
        throw UnsupportedOperationException("custom stub")
    }

    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: dev.tramai.core.approval.Sha256Digest,
        consumedBy: String,
    ): dev.tramai.core.approval.ApprovalConsumptionReceipt {
        throw UnsupportedOperationException("custom stub")
    }
}
