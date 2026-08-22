package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.reflect.jvm.javaType

/**
 * Entry point for compiling a target [kotlin.reflect.KType] into a
 * language-neutral [StructuredTypeDescriptor].
 *
 * This facade owns the [ObjectMapper] (configuration identity for JavaBean
 * introspection) and both language-specific compilers. It performs the
 * language-neutral dispatch (scalars, lists, maps, enums) and routes object
 * types to the Kotlin or JavaBean compiler based on runtime class metadata.
 * Recursion context is shared across both compilers so a Kotlin object that
 * contains a JavaBean (or vice versa) is still cycle-detected.
 */
internal class StructuredTypeCompiler(
    private val objectMapper: ObjectMapper,
) {
    private val javaBeanCompiler = JacksonJavaBeanStructuredTypeCompiler(objectMapper)

    private val kotlinCompiler = KotlinStructuredTypeCompiler(
        typeDispatcher = { targetType, context -> compileType(targetType, context) },
    )

    fun compile(targetType: kotlin.reflect.KType): StructuredTypeDescriptor =
        compileType(targetType, CompileContext())

    private fun compileType(
        targetType: kotlin.reflect.KType,
        context: CompileContext,
    ): StructuredTypeDescriptor {
        val nullable = targetType.isMarkedNullable
        val classifier = targetType.classifier
        return when (classifier) {
            String::class -> StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable)
            Int::class, Long::class, Short::class ->
                StructuredTypeDescriptor.Scalar(ScalarKind.INTEGER, nullable)
            Float::class, Double::class -> StructuredTypeDescriptor.Scalar(ScalarKind.NUMBER, nullable)
            Boolean::class -> StructuredTypeDescriptor.Scalar(ScalarKind.BOOLEAN, nullable)
            List::class, MutableList::class -> {
                val itemType = targetType.arguments.firstOrNull()?.type
                    ?: error("List structured output type must declare an item type: $targetType")
                StructuredTypeDescriptor.Collection(
                    item = compileType(itemType, context),
                    minItems = null,
                    nullable = nullable,
                )
            }
            Map::class, MutableMap::class -> error("Unsupported structured output type: $targetType")
            is kotlin.reflect.KClass<*> -> {
                val klass = classifier as kotlin.reflect.KClass<*>
                if (klass.java.isEnum) {
                    StructuredTypeDescriptor.Enum(
                        values = klass.java.enumConstants.map { (it as Enum<*>).name },
                        nullable = nullable,
                    )
                } else if (isKotlinClass(klass)) {
                    kotlinCompiler.compileObject(klass, nullable, context)
                } else {
                    val javaType = objectMapper.typeFactory.constructType(targetType.javaType)
                    javaBeanCompiler.compile(javaType, nullable, context)
                }
            }
            else -> error("Unsupported structured output type: $targetType")
        }
    }

    /**
     * Detects whether [type] was compiled by Kotlin by checking for the
     * `kotlin.Metadata` annotation on the underlying Java class.
     */
    private fun isKotlinClass(type: kotlin.reflect.KClass<*>): Boolean =
        type.java.getAnnotation(kotlin.Metadata::class.java) != null
}
