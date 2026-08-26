package dev.tramai.spring

import dev.tramai.core.annotations.AiTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.support.GenericApplicationContext

class AiToolScannerTest {

    @Test
    fun `scans beans with ai tool methods`() {
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("toolBean", ToolBean())
            refresh()
        }

        val toolNames = AiToolScanner.fromApplicationContext(context).map { it.name }.distinct()

        assertEquals(listOf("lookupInvoice"), toolNames)
    }

    @Test
    fun `legacy ai tool annotation keeps security metadata absent`() {
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("toolBean", ToolBean())
            refresh()
        }

        val tool = AiToolScanner.fromApplicationContext(context).single()

        assertNull(tool.security)
    }

    @Test
    fun `governed ai tool annotation maps strict security metadata`() {
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("governedToolBean", GovernedToolBean())
            refresh()
        }

        val tool = AiToolScanner.fromApplicationContext(context).single()
        val security = requireNotNull(tool.security)

        assertEquals("invoice.read", security.permission)
        assertEquals(RiskLevel.LOW, security.risk)
        assertEquals(ApprovalMode.AUTO, security.approval)
        assertEquals(ManagedNetworkEgress.DENY, security.managedNetworkEgress)
        assertEquals(AuditDetail.FULL, security.audit)
    }

    @Test
    fun `declared permission must not be blank or padded`() {
        val blankContext = GenericApplicationContext().apply {
            beanFactory.registerSingleton("blankPermissionToolBean", BlankPermissionToolBean())
            refresh()
        }
        val paddedContext = GenericApplicationContext().apply {
            beanFactory.registerSingleton("paddedPermissionToolBean", PaddedPermissionToolBean())
            refresh()
        }

        assertThrows(IllegalStateException::class.java) {
            AiToolScanner.fromApplicationContext(blankContext)
        }
        val paddedFailure = assertThrows(IllegalStateException::class.java) {
            AiToolScanner.fromApplicationContext(paddedContext)
        }
        assertTrue(paddedFailure.message.orEmpty().contains("customPaddedTool"))
    }

    @Test
    fun `beans without ai tool methods return empty`() {
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("plainBean", PlainBean())
            refresh()
        }

        val tools = AiToolScanner.fromApplicationContext(context)

        assertEquals(0, tools.size)
    }

    @Test
    fun `proxied beans are scanned through their target class`() {
        val proxiedBean = ProxyFactory(ToolBean()).apply {
            isProxyTargetClass = true
        }.getProxy()
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("proxiedToolBean", proxiedBean)
            refresh()
        }

        val toolNames = AiToolScanner.fromApplicationContext(context).map { it.name }

        assertEquals(listOf("lookupInvoice"), toolNames)
    }

    @Test
    fun `invalid beans are skipped gracefully`() {
        val context = GenericApplicationContext().apply {
            registerBeanDefinition("brokenBean", RootBeanDefinition(BrokenBean::class.java).apply { isLazyInit = true })
            refresh()
        }

        val tools = AiToolScanner.fromApplicationContext(context)

        assertEquals(0, tools.size)
    }

    @Test
    fun `custom tool name is respected`() {
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("customToolBean", CustomToolBean())
            refresh()
        }

        val toolNames = AiToolScanner.fromApplicationContext(context).map { it.name }

        assertEquals(listOf("customName"), toolNames)
    }

    @Test
    fun `bean with multiple ai tool methods returns all tools`() {
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("multiToolBean", MultiToolBean())
            refresh()
        }

        val toolNames = AiToolScanner.fromApplicationContext(context).map { it.name }.sorted()

        assertEquals(listOf("lookupCustomer", "lookupInvoice"), toolNames)
    }

    @Test
    fun `zero parameter tool method is rejected`() {
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("zeroParameterToolBean", ZeroParameterToolBean())
            refresh()
        }

        assertThrows(IllegalStateException::class.java) {
            AiToolScanner.fromApplicationContext(context)
        }
    }

    @Test
    fun `jdk dynamic proxy bean is scanned correctly`() {
        val context = GenericApplicationContext().apply {
            registerBeanDefinition("jdkProxyToolBean", RootBeanDefinition(ToolServiceImpl::class.java))
            beanFactory.addBeanPostProcessor(
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                        if (beanName != "jdkProxyToolBean") {
                            return bean
                        }

                        return ProxyFactory(bean).apply {
                            setInterfaces(ToolService::class.java)
                            isProxyTargetClass = false
                        }.proxy
                    }
                },
            )
            refresh()
        }

        val toolNames = AiToolScanner.fromApplicationContext(context).map { it.name }

        assertEquals(listOf("lookupInvoice"), toolNames)
    }

    @Test
    fun `jdk proxied blocking tool executes through the proxy and preserves advice`() {
        val advice = RecordingMethodInterceptor()
        val context = GenericApplicationContext().apply {
            registerBeanDefinition("jdkProxyToolBean", RootBeanDefinition(ToolServiceImpl::class.java))
            beanFactory.addBeanPostProcessor(
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                        if (beanName != "jdkProxyToolBean") {
                            return bean
                        }

                        return ProxyFactory(bean).apply {
                            setInterfaces(ToolService::class.java)
                            isProxyTargetClass = false
                            addAdvice(advice)
                        }.proxy
                    }
                },
            )
            refresh()
        }

        val tool = AiToolScanner.fromApplicationContext(context).single()
        val result = runBlocking {
            (tool as TramaiTool<ToolInput, Any>).execute(ToolInput("inv-1"), toolExecutionContext())
        }

        assertEquals("inv-1", result)
        assertEquals(listOf("lookupInvoice"), advice.invocations)
    }

    @Test
    fun `jdk proxied suspend tool executes through the proxy and preserves advice`() {
        val advice = RecordingMethodInterceptor()
        val context = GenericApplicationContext().apply {
            registerBeanDefinition("jdkSuspendProxyToolBean", RootBeanDefinition(SuspendToolServiceImpl::class.java))
            beanFactory.addBeanPostProcessor(
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                        if (beanName != "jdkSuspendProxyToolBean") {
                            return bean
                        }

                        return ProxyFactory(bean).apply {
                            setInterfaces(SuspendToolService::class.java)
                            isProxyTargetClass = false
                            addAdvice(advice)
                        }.proxy
                    }
                },
            )
            refresh()
        }

        val tool = AiToolScanner.fromApplicationContext(context).single()
        val result = runBlocking {
            (tool as TramaiTool<ToolInput, Any>).execute(ToolInput("inv-2"), toolExecutionContext())
        }

        assertEquals("inv-2", result)
        assertEquals(listOf("lookupInvoice"), advice.invocations)
    }

    @Test
    fun `cglib proxied tool executes through the proxy and preserves advice`() {
        val advice = RecordingMethodInterceptor()
        val proxiedBean = ProxyFactory(ToolBean()).apply {
            isProxyTargetClass = true
            addAdvice(advice)
        }.getProxy()
        val context = GenericApplicationContext().apply {
            beanFactory.registerSingleton("cglibProxiedToolBean", proxiedBean)
            refresh()
        }

        val tool = AiToolScanner.fromApplicationContext(context).single()
        val result = runBlocking {
            (tool as TramaiTool<ToolInput, Any>).execute(ToolInput("inv-3"), toolExecutionContext())
        }

        assertEquals("inv-3", result)
        assertEquals(listOf("lookupInvoice"), advice.invocations)
    }

    private fun toolExecutionContext() = ToolExecutionContext(
        operationName = "test",
        modelName = "local-model",
        attemptNumber = 1,
        timeout = java.time.Duration.ofSeconds(10),
    )

    data class ToolInput(val invoiceId: String)

    open class ToolBean {
        @AiTool(description = "Looks up an invoice", sideEffectLevel = SideEffectLevel.READ_ONLY)
        open fun lookupInvoice(input: ToolInput): String = input.invoiceId
    }

    open class GovernedToolBean {
        @AiTool(
            description = "Looks up a governed invoice",
            sideEffectLevel = SideEffectLevel.READ_ONLY,
            permission = "invoice.read",
            risk = RiskLevel.LOW,
            approval = ApprovalMode.AUTO,
            managedNetworkEgress = ManagedNetworkEgress.DENY,
            audit = AuditDetail.FULL,
        )
        open fun lookupInvoice(input: ToolInput): String = input.invoiceId
    }

    open class BlankPermissionToolBean {
        @AiTool(description = "Invalid blank permission", permission = "   ")
        open fun lookupInvoice(input: ToolInput): String = input.invoiceId
    }

    open class PaddedPermissionToolBean {
        @AiTool(
            name = "customPaddedTool",
            description = "Invalid padded permission",
            permission = " invoice.read ",
        )
        open fun lookupInvoice(input: ToolInput): String = input.invoiceId
    }

    class PlainBean {
        fun ping(): String = "pong"
    }

    class BrokenBean {
        init {
            error("boom")
        }
    }

    open class CustomToolBean {
        @AiTool(name = "customName", description = "Uses a custom tool name")
        open fun lookupInvoice(input: ToolInput): String = input.invoiceId
    }

    open class MultiToolBean {
        @AiTool(description = "Looks up an invoice")
        open fun lookupInvoice(input: ToolInput): String = input.invoiceId

        @AiTool(description = "Looks up a customer")
        open fun lookupCustomer(input: ToolInput): String = input.invoiceId
    }

    open class ZeroParameterToolBean {
        @AiTool(description = "Invalid zero parameter tool")
        open fun ping(): String = "pong"
    }

    interface ToolService {
        @AiTool(description = "Looks up an invoice", sideEffectLevel = SideEffectLevel.READ_ONLY)
        fun lookupInvoice(input: ToolInput): String
    }

    open class ToolServiceImpl : ToolService {
        @AiTool(description = "Looks up an invoice", sideEffectLevel = SideEffectLevel.READ_ONLY)
        override fun lookupInvoice(input: ToolInput): String = input.invoiceId
    }

    interface SuspendToolService {
        @AiTool(description = "Looks up an invoice", sideEffectLevel = SideEffectLevel.READ_ONLY)
        suspend fun lookupInvoice(input: ToolInput): String
    }

    open class SuspendToolServiceImpl : SuspendToolService {
        @AiTool(description = "Looks up an invoice", sideEffectLevel = SideEffectLevel.READ_ONLY)
        override suspend fun lookupInvoice(input: ToolInput): String = input.invoiceId
    }

    class RecordingMethodInterceptor : org.aopalliance.intercept.MethodInterceptor {
        val invocations = mutableListOf<String>()

        override fun invoke(invocation: org.aopalliance.intercept.MethodInvocation): Any {
            invocations += invocation.method.name
            return invocation.proceed() ?: Unit
        }
    }
}
