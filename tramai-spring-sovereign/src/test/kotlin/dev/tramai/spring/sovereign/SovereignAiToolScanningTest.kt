package dev.tramai.spring.sovereign

import dev.tramai.core.annotations.AiTool
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean

class SovereignAiToolScanningTest {

    private val governedToolProperties = arrayOf(
        "tramai.sovereign.allowed-models[0]=local-invoice-model",
        "tramai.sovereign.allowed-providers[0]=local-provider",
        "tramai.sovereign.provider-zones.local-provider=LOCAL",
        "tramai.sovereign.models.local-invoice-model=local-provider",
        "tramai.sovereign.allowed-tools[0]=schedule-payment",
        "tramai.sovereign.allowed-permissions[0]=payment.schedule",
    )

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SovereignTramaiAutoConfiguration::class.java))

    @Test
    fun `governed AiTool method is discovered and callable through sovereign runtime`() {
        contextRunner
            .withUserConfiguration(
                ProviderConfiguration::class.java,
                GovernedAnnotatedToolConfiguration::class.java,
            )
            .withPropertyValues(*governedToolProperties)
            .run { context ->
                val runtime = context.getBean(dev.tramai.sovereign.SovereignTramaiRuntime::class.java)
                val service = runtime.create(SingleToolInvoiceAi::class)

                val result = runBlocking { service.analyze("inv-101") }

                assertThat(result).isEqualTo("FINAL_OK")
                val toolBean = context.getBean(GovernedAnnotatedPaymentTool::class.java)
                assertThat(toolBean.executions)
                    .containsExactly(SchedulePaymentInput("inv-101", 100))
            }
    }

    @Test
    fun `legacy AiTool without governance metadata remains denied by sovereign policy`() {
        contextRunner
            .withUserConfiguration(
                ProviderConfiguration::class.java,
                LegacyAnnotatedToolConfiguration::class.java,
            )
            .withPropertyValues(*governedToolProperties)
            .run { context ->
                val runtime = context.getBean(dev.tramai.sovereign.SovereignTramaiRuntime::class.java)
                val service = runtime.create(SingleToolInvoiceAi::class)

                val thrown = catchThrowable {
                    runBlocking { service.analyze("inv-102") }
                }

                assertThat(thrown).isInstanceOf(PolicyViolationException::class.java)
                val violation = thrown as PolicyViolationException
                assertThat(violation.decision.reasonCode).isEqualTo("tool-metadata-missing")
                assertThat(context.getBean(LegacyAnnotatedPaymentTool::class.java).executions).isEmpty()
            }
    }

    @Test
    fun `explicit and annotated tools with the same name fail loudly`() {
        contextRunner
            .withUserConfiguration(
                ProviderConfiguration::class.java,
                SingleToolConfiguration::class.java,
                GovernedAnnotatedToolConfiguration::class.java,
            )
            .withPropertyValues(*governedToolProperties)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(requireNotNull(context.startupFailure))
                    .hasMessageContaining("Duplicate tool name registered")
                    .hasMessageContaining("schedule-payment")
            }
    }
}

open class GovernedAnnotatedToolConfiguration {
    @Bean
    open fun governedAnnotatedPaymentTool(): GovernedAnnotatedPaymentTool =
        GovernedAnnotatedPaymentTool()
}

open class GovernedAnnotatedPaymentTool {
    val executions = mutableListOf<SchedulePaymentInput>()

    @AiTool(
        name = "schedule-payment",
        description = "Schedules a payment for an invoice",
        idempotent = true,
        sideEffectLevel = SideEffectLevel.WRITE,
        permission = "payment.schedule",
        risk = RiskLevel.LOW,
        approval = ApprovalMode.AUTO,
        managedNetworkEgress = ManagedNetworkEgress.DENY,
        audit = AuditDetail.FULL,
    )
    open suspend fun schedulePayment(input: SchedulePaymentInput): String {
        executions += input
        return "PAID-${input.invoiceId}"
    }
}

open class LegacyAnnotatedToolConfiguration {
    @Bean
    open fun legacyAnnotatedPaymentTool(): LegacyAnnotatedPaymentTool =
        LegacyAnnotatedPaymentTool()
}

open class LegacyAnnotatedPaymentTool {
    val executions = mutableListOf<SchedulePaymentInput>()

    @AiTool(
        name = "schedule-payment",
        description = "Legacy annotation without governance metadata",
        idempotent = true,
        sideEffectLevel = SideEffectLevel.WRITE,
    )
    open suspend fun schedulePayment(input: SchedulePaymentInput): String {
        executions += input
        return "PAID-${input.invoiceId}"
    }
}
