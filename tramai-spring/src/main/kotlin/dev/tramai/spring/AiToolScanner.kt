package dev.tramai.spring

import dev.tramai.core.annotations.AiTool
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.context.ApplicationContext
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.valueParameters

/**
 * Scans an [ApplicationContext] for beans containing methods annotated with `@AiTool`.
 */
object AiToolScanner {
    /**
     * Discovers and wraps `@AiTool` methods into [TramaiTool] instances.
     */
    fun fromApplicationContext(context: ApplicationContext): List<TramaiTool<*, *>> {
        val factory = (context as? ListableBeanFactory)
            ?: return emptyList()

        return findAnnotatedBeans(factory).flatMap { annotatedBean ->
            annotatedBean.targetClass.kotlin.functions.mapNotNull { function ->
                validateAndCreateTool(annotatedBean.beanName, annotatedBean.bean, function)
            }
        }
    }

    /**
     * Finds all beans with at least one @AiTool method and instantiates them.
     * Returns a list of (beanName, bean) pairs.
     */
    private fun findAnnotatedBeans(factory: ListableBeanFactory): List<AnnotatedBean> {
        val result = mutableListOf<AnnotatedBean>()

        factory.getBeanNamesForType(Any::class.java, false, false).forEach { beanName ->
            if (beanName == "tramai") return@forEach

            val beanType = try {
                factory.getType(beanName, false)
            } catch (e: Exception) {
                null
            } ?: return@forEach

            val targetClass: KClass<*> = org.springframework.util.ClassUtils.getUserClass(beanType).kotlin

            val hasTool = try {
                targetClass.functions.any { it.findAnnotation<AiTool>() != null }
            } catch (e: Throwable) {
                false
            }
            if (!hasTool) return@forEach

            val bean = try {
                factory.getBean(beanName)
            } catch (e: Exception) {
                null
            } ?: return@forEach

            val actualTargetClass = try {
                AopUtils.getTargetClass(bean)
            } catch (e: Throwable) {
                beanType
            }

            result.add(AnnotatedBean(beanName, bean, actualTargetClass))
        }

        return result
    }

    private data class AnnotatedBean(
        val beanName: String,
        val bean: Any,
        val targetClass: Class<*>,
    )

    /**
     * Validates that [function] is a valid @AiTool method and creates a [MethodBackedTramaiTool].
     * Returns null if the function does not have the @AiTool annotation.
     */
    private fun validateAndCreateTool(
        beanName: String,
        bean: Any,
        function: KFunction<*>,
    ): TramaiTool<*, *>? {
        val annotation = try { function.findAnnotation<AiTool>() } catch (e: Exception) { null } ?: return null

        val parameters = function.valueParameters
        check(parameters.size == 1) {
            "Tool method ${function.name} in bean $beanName must have exactly one parameter"
        }

        val inputType = parameters.first().type.classifier as? KClass<*>
            ?: throw IllegalStateException("Could not resolve input type for tool ${function.name}")

        check(inputType.isData) {
            "Tool input type ${inputType.qualifiedName} must be a data class"
        }

        val toolName = annotation.name.takeIf { it.isNotBlank() } ?: function.name

        return MethodBackedTramaiTool(
            bean = bean,
            function = function,
            name = toolName,
            description = annotation.description,
            inputType = inputType as KClass<Any>,
            idempotent = annotation.idempotent,
            sideEffectLevel = annotation.sideEffectLevel
        )
    }

    private class MethodBackedTramaiTool(
        private val bean: Any,
        private val function: KFunction<*>,
        override val name: String,
        override val description: String,
        override val inputType: KClass<Any>,
        override val idempotent: Boolean,
        override val sideEffectLevel: SideEffectLevel
    ) : TramaiTool<Any, Any> {

        override suspend fun execute(input: Any, context: ToolExecutionContext): Any {
            return if (function.isSuspend) {
                function.callSuspend(bean, input) ?: Unit
            } else {
                function.call(bean, input) ?: Unit
            }
        }
    }
}
