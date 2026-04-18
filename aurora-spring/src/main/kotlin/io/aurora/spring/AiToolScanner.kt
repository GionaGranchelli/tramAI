package io.aurora.spring

import io.aurora.core.annotations.AiTool
import io.aurora.core.model.AuroraTool
import io.aurora.core.model.SideEffectLevel
import io.aurora.core.model.ToolExecutionContext
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
     * Discovers and wraps `@AiTool` methods into [AuroraTool] instances.
     */
    fun fromApplicationContext(context: ApplicationContext): List<AuroraTool<*, *>> {
        val tools = mutableListOf<AuroraTool<*, *>>()
        val factory = (context as? org.springframework.beans.factory.ListableBeanFactory) 
            ?: return emptyList()

        factory.getBeanNamesForType(Any::class.java, false, false).forEach { beanName ->
            // Skip the aurora bean itself to avoid circular dependency
            if (beanName == "aurora") return@forEach

            val beanType = try {
                factory.getType(beanName, false)
            } catch (e: Exception) {
                null
            } ?: return@forEach
            
            val beanClass = beanType.kotlin
            
            // Check if any function has @AiTool before instantiating
            val hasTool = try {
                beanClass.functions.any { it.findAnnotation<AiTool>() != null }
            } catch (e: Throwable) {
                // Some beans might fail introspection (e.g. dynamic proxies)
                false
            }
            if (!hasTool) return@forEach

            val bean = try {
                context.getBean(beanName)
            } catch (e: Exception) {
                // Skip beans that cannot be instantiated right now (e.g. circular dependency)
                return@forEach
            }

            beanClass.functions.forEach { function ->
                val annotation = try { function.findAnnotation<AiTool>() } catch (e: Exception) { null } ?: return@forEach
                
                // Validate tool method signature: exactly one data class parameter
                val parameters = function.valueParameters
                if (parameters.size != 1) {
                    throw IllegalStateException("Tool method ${function.name} in bean $beanName must have exactly one parameter")
                }
                
                val inputType = parameters.first().type.classifier as? KClass<*>
                    ?: throw IllegalStateException("Could not resolve input type for tool ${function.name}")
                
                if (!inputType.isData) {
                    throw IllegalStateException("Tool input type ${inputType.qualifiedName} must be a data class")
                }

                val toolName = annotation.name.takeIf { it.isNotBlank() } ?: function.name
                
                tools.add(MethodBackedAuroraTool(
                    bean = bean,
                    function = function,
                    name = toolName,
                    description = annotation.description,
                    inputType = inputType as KClass<Any>,
                    idempotent = annotation.idempotent,
                    sideEffectLevel = annotation.sideEffectLevel
                ))
            }
        }
        return tools
    }

    private class MethodBackedAuroraTool(
        private val bean: Any,
        private val function: KFunction<*>,
        override val name: String,
        override val description: String,
        override val inputType: KClass<Any>,
        override val idempotent: Boolean,
        override val sideEffectLevel: SideEffectLevel
    ) : AuroraTool<Any, Any> {
        
        override suspend fun execute(input: Any, context: ToolExecutionContext): Any {
            return if (function.isSuspend) {
                function.callSuspend(bean, input) ?: Unit
            } else {
                function.call(bean, input) ?: Unit
            }
        }
    }
}
