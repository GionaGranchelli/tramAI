package dev.tramai.spring.compatibilityfixture

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.AiTool
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.provider.ModelProvider
import dev.tramai.spring.EnableTramai
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Service

/**
 * THE single application fixture for the S7 profile compatibility TCK.
 *
 * The same classes run under `tramai.profile=standard` and
 * `tramai.profile=sovereign`. The fixture contains no profile-specific
 * types, no `if (sovereign)` branches, no manual `runtime.create(...)` —
 * only configuration changes between the two contexts.
 */
@Configuration(proxyBeanMethods = false)
class TckCompatibilityFixture {

    @Bean
    fun tckProvider(): ModelProvider = object : ModelProvider {
        override fun providerId(): String = "local-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "TCK_OK")
    }

    @Bean
    fun tckPaymentTool(): TckPaymentTool = TckPaymentTool()

    @Bean
    fun tckLegacyPaymentTool(): TckLegacyPaymentTool = TckLegacyPaymentTool()

    /** Constructor-style injection of the [TckAiService] into application code. */
    @Bean
    fun tckConsumer(ai: TckAiService): TckConsumer = TckConsumer(ai)
}

/** Same-fixture variant that additionally opts into the annotation-driven model. */
@Configuration(proxyBeanMethods = false)
@EnableTramai
@Import(TckCompatibilityFixture::class)
class TckEnableTramaiFixture

@AiService
interface TckAiService {
    @Operation(
        model = "local-model",
        prompt = "Return the TCK result.",
    )
    suspend fun analyze(input: String): String
}

@Service
class TckConsumer(private val ai: TckAiService) {
    fun invokeAi(input: String): String = runBlocking { ai.analyze(input) }
}

data class TckPaymentInput(val invoiceId: String)

/** Governed tool: identical discovery + metadata mapping under both profiles. */
open class TckPaymentTool {
    @AiTool(
        name = "tck-schedule-payment",
        description = "Schedules a payment for an invoice",
        idempotent = true,
        sideEffectLevel = SideEffectLevel.WRITE,
        permission = "payment.schedule",
        risk = RiskLevel.LOW,
        approval = ApprovalMode.AUTO,
        managedNetworkEgress = ManagedNetworkEgress.DENY,
        audit = AuditDetail.FULL,
    )
    open suspend fun schedulePayment(input: TckPaymentInput): String =
        "PAID-${input.invoiceId}"
}

/**
 * Legacy metadata-less tool: discovered identically at the programming-model
 * level under both profiles. Enforcement differs by design — standard allows
 * it, sovereign fail-closes with `tool-metadata-missing` (covered by
 * SovereignAiToolScanningTest; this TCK pins discovery parity only).
 */
open class TckLegacyPaymentTool {
    @AiTool(
        name = "tck-legacy-payment",
        description = "Legacy annotation without governance metadata",
        idempotent = true,
        sideEffectLevel = SideEffectLevel.WRITE,
    )
    open suspend fun schedulePayment(input: TckPaymentInput): String =
        "PAID-${input.invoiceId}"
}
