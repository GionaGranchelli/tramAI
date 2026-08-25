package dev.tramai.spring.sovereign

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.sovereign.SovereignTramaiRuntime
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import kotlin.test.Test

/**
 * Characterization tests for TramaiTool bean collection by the sovereign
 * starter: Spring ApplicationContext -> TramaiTool beans -> sovereign
 * starter collects them -> SovereignTramai.builder().tools(...).
 */
class SovereignTramaiToolCollectionTest {

    private val toolProperties = mapOf(
        "tramai.sovereign.allowed-models[0]" to "local-invoice-model",
        "tramai.sovereign.allowed-providers[0]" to "local-provider",
        "tramai.sovereign.provider-zones.local-provider" to "LOCAL",
        "tramai.sovereign.models.local-invoice-model" to "local-provider",
        "tramai.sovereign.allowed-tools[0]" to "schedule-payment",
        "tramai.sovereign.allowed-tools[1]" to "schedule-refund",
        "tramai.sovereign.allowed-permissions[0]" to "payment.schedule",
        "tramai.sovereign.allowed-permissions[1]" to "payment.refund",
    )

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SovereignTramaiAutoConfiguration::class.java))

    // ── zero tool beans → current behavior unchanged ─────────────────────

    @Test
    fun `zero tool beans keep current behavior - operation tool resolution still fails loudly`() {
        contextRunner
            .withUserConfiguration(ProviderConfiguration::class.java)
            .withPropertyValues(*toolProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
                val runtime = context.getBean(SovereignTramaiRuntime::class.java)
                assertThatThrownBy { runtime.create(ToolInvoiceAi::class) }
                    .isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
                    .hasMessageContaining("schedule-payment")
                    .hasMessageContaining("not registered")
            }
    }

    // ── one tool bean → registered and callable ──────────────────────────

    @Test
    fun `single TramaiTool bean is registered and callable through the sovereign runtime`() {
        contextRunner
            .withUserConfiguration(
                ProviderConfiguration::class.java,
                SingleToolConfiguration::class.java,
            )
            .withPropertyValues(*toolProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val runtime = context.getBean(SovereignTramaiRuntime::class.java)
                val service = runtime.create(SingleToolInvoiceAi::class)
                val result = runBlocking { service.analyze("inv-001") }
                assertThat(result).isEqualTo("FINAL_OK")
                val tool = context.getBean("schedulePaymentTool") as SchedulePaymentTestTool
                assertThat(tool.executions).containsExactly(SchedulePaymentInput("inv-001", 100))
            }
    }

    // ── multiple tool beans → all registered and callable ────────────────

    @Test
    fun `multiple TramaiTool beans are all registered and callable`() {
        contextRunner
            .withUserConfiguration(
                MultiToolProviderConfiguration::class.java,
                MultiToolConfiguration::class.java,
            )
            .withPropertyValues(*toolProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val runtime = context.getBean(SovereignTramaiRuntime::class.java)
                // Both tools are registered in the SAME runtime; each
                // operation exposes the tool it declares.
                val scheduleService = runtime.create(SingleToolInvoiceAi::class)
                val refundService = runtime.create(RefundInvoiceAi::class)
                assertThat(runBlocking { scheduleService.analyze("inv-a") }).isEqualTo("FINAL_OK")
                assertThat(runBlocking { refundService.analyze("inv-b") }).isEqualTo("FINAL_OK")
                val schedule = context.getBean("schedulePaymentTool") as SchedulePaymentTestTool
                val refund = context.getBean("scheduleRefundTool") as ScheduleRefundTestTool
                assertThat(schedule.executions).containsExactly(SchedulePaymentInput("inv-a", 100))
                assertThat(refund.executions).containsExactly(ScheduleRefundInput("inv-b", 100))
            }
    }

    // ── user tool metadata survives unchanged ────────────────────────────

    @Test
    fun `user tool metadata survives registration unchanged`() {
        contextRunner
            .withUserConfiguration(
                ProviderConfiguration::class.java,
                SingleToolConfiguration::class.java,
            )
            .withPropertyValues(*toolProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val tool = context.getBean("schedulePaymentTool") as SchedulePaymentTestTool
                // The bean instance itself is registered — metadata is not
                // copied, rewritten, or wrapped away.
                assertThat(tool.name).isEqualTo("schedule-payment")
                assertThat(tool.description).isEqualTo("Schedules a payment for an invoice")
                assertThat(tool.idempotent).isTrue()
                assertThat(tool.sideEffectLevel).isEqualTo(SideEffectLevel.WRITE)
                assertThat(tool.security).isNotNull()
                assertThat(tool.security!!.permission).isEqualTo("payment.schedule")
                assertThat(tool.security!!.risk).isEqualTo(RiskLevel.LOW)
                assertThat(tool.security!!.approval).isEqualTo(ApprovalMode.AUTO)
                assertThat(tool.security!!.managedNetworkEgress).isEqualTo(ManagedNetworkEgress.DENY)
                assertThat(tool.security!!.audit).isEqualTo(AuditDetail.FULL)
            }
    }

    // ── tool goes through sovereign execution policy ─────────────────────

    @Test
    fun `tool execution is denied when its permission is not granted`() {
        contextRunner
            .withUserConfiguration(
                ProviderConfiguration::class.java,
                SingleToolConfiguration::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.allowed-models[0]=local-invoice-model",
                "tramai.sovereign.allowed-providers[0]=local-provider",
                "tramai.sovereign.provider-zones.local-provider=LOCAL",
                "tramai.sovereign.models.local-invoice-model=local-provider",
                "tramai.sovereign.allowed-tools[0]=schedule-payment",
                // permission deliberately NOT granted
            )
            .run { context ->
                val runtime = context.getBean(SovereignTramaiRuntime::class.java)
                val service = runtime.create(SingleToolInvoiceAi::class)
                val thrown = org.assertj.core.api.Assertions.catchThrowable {
                    runBlocking { service.analyze("inv-003") }
                }
                assertThat(thrown)
                    .isInstanceOf(dev.tramai.core.exception.PolicyViolationException::class.java)
                val violation = thrown as dev.tramai.core.exception.PolicyViolationException
                assertThat(violation.decision.reasonCode).isEqualTo("tool-exposure-permission-denied")
            }
    }

    // ── unknown / disallowed tool still denied ───────────────────────────

    @Test
    fun `model requesting an unknown tool is denied and the invocation recovers via re-prompt`() {
        contextRunner
            .withUserConfiguration(
                UnknownToolProviderConfiguration::class.java,
                MultiToolConfiguration::class.java,
            )
            .withPropertyValues(*toolProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val runtime = context.getBean(SovereignTramaiRuntime::class.java)
                val service = runtime.create(ToolInvoiceAi::class)
                // The engine denies the unknown-tool call before execution and
                // injects the failure as a tool-result message, then re-prompts.
                val result = runBlocking { service.analyze("inv-004") }
                assertThat(result).isEqualTo("FINAL_OK")
                val provider = context.getBean("unknownToolCallingProvider") as ToolCallingProvider
                assertThat(provider.toolResultMessages)
                    .anyMatch { it.contains("Tool execution failed") }
                // Only the REGISTERED tool ever executed; the unknown request
                // never reached any tool implementation.
                val schedule = context.getBean("schedulePaymentTool") as SchedulePaymentTestTool
                assertThat(schedule.executions).containsExactly(SchedulePaymentInput("inv-004", 100))
                val refund = context.getBean("scheduleRefundTool") as ScheduleRefundTestTool
                assertThat(refund.executions).isEmpty()
            }
    }

    // ── no duplicate registration ────────────────────────────────────────

    @Test
    fun `duplicate tool names fail loudly at context startup`() {
        contextRunner
            .withUserConfiguration(
                ProviderConfiguration::class.java,
                DuplicateToolConfiguration::class.java,
            )
            .withPropertyValues(*toolProperties.entries.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(requireNotNull(context.startupFailure))
                    .hasMessageContaining("Duplicate tool name registered")
                    .hasMessageContaining("schedule-payment")
            }
    }
}

// ── Fixtures ─────────────────────────────────────────────────────────────

@AiService
interface SingleToolInvoiceAi {
    @Operation(
        prompt = "Analyze the invoice and take the required action.",
        model = "local-invoice-model",
        tools = ["schedule-payment"],
    )
    suspend fun analyze(invoiceId: String): String
}

@AiService
interface RefundInvoiceAi {
    @Operation(
        prompt = "Analyze the invoice and take the required action.",
        model = "local-invoice-model",
        tools = ["schedule-refund"],
    )
    suspend fun analyze(invoiceId: String): String
}

@AiService
interface ToolInvoiceAi {
    @Operation(
        prompt = "Analyze the invoice and take the required action.",
        model = "local-invoice-model",
        tools = ["schedule-payment", "schedule-refund"],
    )
    suspend fun analyze(invoiceId: String): String
}

data class SchedulePaymentInput(val invoiceId: String, val amountCents: Long)

open class SchedulePaymentTestTool : TramaiTool<SchedulePaymentInput, String> {
    override val name: String = "schedule-payment"
    override val description: String = "Schedules a payment for an invoice"
    override val inputType = SchedulePaymentInput::class
    override val idempotent: Boolean = true
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.WRITE
    override val security: ToolSecurityMetadata? = ToolSecurityMetadata(
        permission = "payment.schedule",
        risk = RiskLevel.LOW,
        approval = ApprovalMode.AUTO,
        managedNetworkEgress = ManagedNetworkEgress.DENY,
        audit = AuditDetail.FULL,
    )
    val executions = mutableListOf<SchedulePaymentInput>()

    override suspend fun execute(input: SchedulePaymentInput, context: ToolExecutionContext): String {
        executions += input
        return "PAID-${input.invoiceId}"
    }
}

data class ScheduleRefundInput(val invoiceId: String, val amountCents: Long)

open class ScheduleRefundTestTool : TramaiTool<ScheduleRefundInput, String> {
    override val name: String = "schedule-refund"
    override val description: String = "Schedules a refund for an invoice"
    override val inputType = ScheduleRefundInput::class
    override val idempotent: Boolean = true
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.WRITE
    override val security: ToolSecurityMetadata? = ToolSecurityMetadata(
        permission = "payment.refund",
        risk = RiskLevel.LOW,
        approval = ApprovalMode.AUTO,
        managedNetworkEgress = ManagedNetworkEgress.DENY,
        audit = AuditDetail.FULL,
    )
    val executions = mutableListOf<ScheduleRefundInput>()

    override suspend fun execute(input: ScheduleRefundInput, context: ToolExecutionContext): String {
        executions += input
        return "REFUNDED-${input.invoiceId}"
    }
}

open class ProviderConfiguration {
    @Bean
    open fun toolCallingProvider(): ModelProvider =
        ToolCallingProvider { _, toolResults -> if (toolResults == 0) "schedule-payment" else null }
}

open class MultiToolProviderConfiguration {
    @Bean
    open fun multiToolCallingProvider(): ModelProvider =
        ToolCallingProvider { invoiceId, toolResults ->
            if (toolResults == 0) {
                when (invoiceId) {
                    "inv-a" -> "schedule-payment"
                    "inv-b" -> "schedule-refund"
                    else -> null
                }
            } else {
                null
            }
        }
}

open class UnknownToolProviderConfiguration {
    @Bean
    open fun unknownToolCallingProvider(): ModelProvider =
        ToolCallingProvider { _, toolResults ->
            when (toolResults) {
                0 -> "schedule-payment"
                1 -> "unknown-tool"
                else -> null
            }
        }
}

open class SingleToolConfiguration {
    @Bean
    open fun schedulePaymentTool(): SchedulePaymentTestTool = SchedulePaymentTestTool()
}

open class MultiToolConfiguration {
    @Bean
    open fun schedulePaymentTool(): SchedulePaymentTestTool = SchedulePaymentTestTool()

    @Bean
    open fun scheduleRefundTool(): ScheduleRefundTestTool = ScheduleRefundTestTool()
}

open class DuplicateToolConfiguration {
    @Bean
    open fun schedulePaymentTool(): SchedulePaymentTestTool = SchedulePaymentTestTool()

    @Bean
    open fun duplicateSchedulePaymentTool(): SchedulePaymentTestTool = SchedulePaymentTestTool()
}

/**
 * Input-driven provider: the [script] decides which tool to request on each
 * turn (or null to return the final answer). The invoice id is threaded from
 * the operation argument (user message) into every tool call, mirroring how
 * a real model would act. "unknown-tool" in a script exercises the
 * unknown-tool denial path.
 */
class ToolCallingProvider(
    private val script: (invoiceId: String, toolResults: Int) -> String?,
) : ModelProvider {

    /** Tool-role message contents seen by the provider (failures surface here). */
    val toolResultMessages = mutableListOf<String>()

    override fun providerId(): String = "local-provider"

    override fun supportsCapability(capability: ProviderCapability): Boolean =
        capability == ProviderCapability.TOOL_CALLING || capability == ProviderCapability.STRUCTURED_OUTPUT

    override suspend fun complete(request: ModelRequest): ModelResponse {
        request.messages.filter { it.role == MessageRole.TOOL }.forEach { toolResultMessages += it.content }
        val invoiceId = INVOICE_ID.find(request.messages.filter { it.role == MessageRole.USER }
            .joinToString("\n") { it.content })?.value ?: "inv-unknown"
        val toolResults = request.messages.count { it.role == MessageRole.TOOL }
        val toolName = script(invoiceId, toolResults)
            ?: return ModelResponse(content = "FINAL_OK", finishReason = FinishReason.STOP)
        return ModelResponse(
            content = "I need to invoke $toolName.",
            toolCalls = listOf(
                ToolCall(
                    id = "call-$toolName-$toolResults",
                    name = toolName,
                    argumentsJson = """{"invoiceId":"$invoiceId","amountCents":100}""",
                ),
            ),
            finishReason = FinishReason.OTHER,
        )
    }

    companion object {
        private val INVOICE_ID = Regex("inv-\\d+|inv-[ab]")
    }
}
