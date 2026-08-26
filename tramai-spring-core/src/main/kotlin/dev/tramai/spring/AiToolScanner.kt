package dev.tramai.spring

import dev.tramai.core.annotations.AiTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.policy.ToolSecurityMetadata
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.context.ApplicationContext
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
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

            // A JDK interface proxy resolves its bean type to the $Proxy class,
            // whose synthesized methods carry no annotations; the @AiTool may
            // live on an interface the proxy implements. Check both the target
            // class and the declared interfaces before deciding to scan.
            val hasTool = try {
                targetClass.functions.any { it.findAnnotation<AiTool>() != null } ||
                    beanType.interfaces.any { interfaceType ->
                        interfaceType.kotlin.functions.any { it.findAnnotation<AiTool>() != null }
                    }
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
        val annotation = try { function.findAnnotation<AiTool>() } catch (e: Exception) { null }
            ?: findInterfaceAnnotation(bean, function)
            ?: return null

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
        val security = resolveSecurityMetadata(beanName, function.name, toolName, annotation)

        return MethodBackedTramaiTool(
            bean = bean,
            function = function,
            name = toolName,
            description = annotation.description,
            inputType = inputType,
            idempotent = annotation.idempotent,
            sideEffectLevel = annotation.sideEffectLevel,
            security = security,
        )
    }

    /**
     * Exact overload-safe signature match. Name + arity alone is not enough:
     * `fun lookup(InvoiceInput)` and `fun lookup(CustomerInput)` share both.
     * Compare name, suspend-ness, and the full parameter type shapes.
     */
    private fun KFunction<*>.sameSignatureAs(other: KFunction<*>): Boolean =
        name == other.name &&
            isSuspend == other.isSuspend &&
            valueParameters.size == other.valueParameters.size &&
            valueParameters.zip(other.valueParameters).all { (a, b) -> a.type.sameShape(b.type) }

    /** Structural type comparison: classifier + nullability + type arguments. */
    private fun KType.sameShape(other: KType): Boolean {
        if (classifier != other.classifier || isMarkedNullable != other.isMarkedNullable) return false
        if (arguments.size != other.arguments.size) return false
        return arguments.zip(other.arguments).all { (a, b) ->
            a.variance == b.variance &&
                when {
                    a.type == null && b.type == null -> true
                    a.type != null && b.type != null -> a.type!!.sameShape(b.type!!)
                    else -> false
                }
        }
    }

    /**
     * Falls back to an interface-declared [AiTool] when the implementation
     * method carries no annotation (annotations on overrides are not inherited).
     * The interface's Kotlin function also preserves suspend metadata, which is
     * what [MethodBackedTramaiTool] dispatches through for proxied beans.
     */
    private fun findInterfaceAnnotation(bean: Any, function: KFunction<*>): AiTool? {
        val declaredInterfaces = bean::class.java.interfaces
        if (declaredInterfaces.isEmpty()) return null
        return declaredInterfaces.asSequence()
            .flatMap { it.kotlin.functions.asSequence() }
            .firstOrNull { candidate -> candidate.sameSignatureAs(function) }
            ?.findAnnotation<AiTool>()
    }

    private fun resolveSecurityMetadata(
        beanName: String,
        functionName: String,
        toolName: String,
        annotation: AiTool,
    ): ToolSecurityMetadata? {
        val permission = annotation.permission
        if (permission.isEmpty()) {
            return null
        }

        check(permission.isNotBlank()) {
            "Tool '$toolName' (method $functionName in bean $beanName) declares a blank permission"
        }
        check(permission == permission.trim()) {
            "Tool '$toolName' (method $functionName in bean $beanName) permission must not have surrounding whitespace"
        }

        return ToolSecurityMetadata(
            permission = permission,
            risk = annotation.risk,
            approval = annotation.approval,
            managedNetworkEgress = annotation.managedNetworkEgress,
            audit = annotation.audit,
        )
    }

    private class MethodBackedTramaiTool<I : Any>(
        private val bean: Any,
        private val function: KFunction<*>,
        override val name: String,
        override val description: String,
        override val inputType: KClass<I>,
        override val idempotent: Boolean,
        override val sideEffectLevel: SideEffectLevel,
        override val security: ToolSecurityMetadata?,
    ) : TramaiTool<I, Any> {

        /**
         * The discovered [function] belongs to the tool's target class. For a
         * JDK interface proxy the bean is NOT an instance of that target class,
         * so invoking the target-class function on the proxy receiver throws a
         * receiver-type mismatch. Resolve the same signature against an
         * interface the bean actually implements (Kotlin metadata is preserved
         * there, so suspend functions stay suspend) — invocation then
         * dispatches through the proxy and Spring AOP advice runs. Plain
         * objects and CGLIB proxies fall back to the discovered target-class
         * function. The proxy/function relationship is immutable for the
         * lifetime of the tool, so the resolution is done once.
         */
        private val invocable: KFunction<*> = run {
            val declaredInterfaces = bean::class.java.interfaces
            if (declaredInterfaces.isNotEmpty()) {
                for (interfaceType in declaredInterfaces) {
                    val match = interfaceType.kotlin.functions.firstOrNull { candidate ->
                        candidate.sameSignatureAs(function)
                    }
                    if (match != null) return@run match
                }
            }
            function
        }

        override suspend fun execute(input: I, context: ToolExecutionContext): Any {
            return if (invocable.isSuspend) {
                invocable.callSuspend(bean, input) ?: Unit
            } else {
                invocable.call(bean, input) ?: Unit
            }
        }
    }
}
