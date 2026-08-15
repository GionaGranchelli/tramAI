package dev.tramai.engine.planning

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.exception.ConfigurationException
import kotlin.reflect.KClass
import java.lang.reflect.Method

internal data class ServiceDefinition(
    val serviceType: KClass<*>,
    val systemPrompt: String?,
    val operations: Map<Method, OperationExecutionPlan>,
)

/** Compiles a validated service interface into its immutable operation plans. */
internal class ServiceDefinitionCompiler(
    private val operationCompiler: OperationDefinitionCompiler,
) {
    fun compile(serviceType: KClass<*>): ServiceDefinition {
        val javaType = validateServiceType(serviceType)
        val systemPrompt = javaType.getAnnotation(SystemPrompt::class.java)?.value?.takeIf { it.isNotBlank() }
        val operations = javaType.methods
            .filterNot { it.declaringClass == Any::class.java }
            .associateWith { method -> operationCompiler.compile(javaType, method, systemPrompt) }
        return ServiceDefinition(serviceType, systemPrompt, operations)
    }

    private fun validateServiceType(serviceType: KClass<*>): Class<*> {
        val javaType = serviceType.java
        if (!javaType.isInterface) {
            throw ConfigurationException("${javaType.name} must be an interface")
        }
        if (!javaType.isAnnotationPresent(AiService::class.java)) {
            throw ConfigurationException("${javaType.name} must be annotated with @AiService")
        }
        return javaType
    }
}
