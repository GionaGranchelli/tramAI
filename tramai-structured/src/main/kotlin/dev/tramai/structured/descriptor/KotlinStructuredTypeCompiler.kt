package dev.tramai.structured.descriptor

import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Immutable recursion bookkeeping shared across Kotlin and JavaBean
 * descriptor compilation. Tracks the active path (ancestors of the type
 * currently being compiled) as a copy-on-write set, so sibling properties of
 * the same type both compile fully while genuine cycles (A → List<A>) are
 * rejected deterministically.
 */
internal data class CompileContext(
    private val activeTypes: Set<KClass<*>> = emptySet(),
) {
    /** Returns a context with [type] added, or throws if [type] is already active. */
    fun entering(type: KClass<*>): CompileContext {
        require(type !in activeTypes) {
            "Recursive structured output type is unsupported: ${type.qualifiedName ?: type.java.name}"
        }
        return copy(activeTypes = activeTypes + type)
    }
}

/**
 * Compiles Kotlin object types (classes, not scalars/enums/collections) into
 * [StructuredTypeDescriptor.Object].
 *
 * All Kotlin-specific reflection lives here: public property discovery,
 * property sorting, nullability, `@AiDescription`, `@AiRange`, `@AiMinItems`,
 * and property accessor construction. The [typeDispatcher] handles nested
 * types; consumers of the compiled descriptor never see [KType] again.
 */
internal class KotlinStructuredTypeCompiler(
    private val typeDispatcher: (KType, CompileContext) -> StructuredTypeDescriptor,
) {

    fun compileObject(
        type: KClass<*>,
        nullable: Boolean,
        context: CompileContext,
    ): StructuredTypeDescriptor.Object {
        val childContext = context.entering(type)
        val properties = type.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .sortedBy { it.name }
            .map { property -> compileProperty(property, childContext) }

        return StructuredTypeDescriptor.Object(
            typeName = type.qualifiedName ?: type.java.name,
            properties = properties,
            nullable = nullable,
        )
    }

    private fun compileProperty(
        property: KProperty1<out Any, *>,
        context: CompileContext,
    ): StructuredPropertyDescriptor {
        val propertyType = typeDispatcher(property.returnType, context)

        val minItems = property.findAnnotation<AiMinItems>()?.value
        val withMinItems = if (minItems != null) {
            // @AiMinItems is a collection-only constraint by contract. A
            // non-collection annotated property would previously emit a
            // meaningless minItems key and fail at runtime; fail loudly at
            // compile time instead.
            require(propertyType is StructuredTypeDescriptor.Collection) {
                "Property '${property.name}' is annotated @AiMinItems but is not a collection"
            }
            propertyType.copy(minItems = minItems)
        } else {
            propertyType
        }

        return StructuredPropertyDescriptor(
            name = property.name,
            type = withMinItems,
            required = !property.returnType.isMarkedNullable,
            description = property.findAnnotation<AiDescription>()?.value,
            range = property.findAnnotation<AiRange>()?.let { NumericRange(it.min, it.max) },
            accessor = ValueAccessor { target -> readPropertyValue(property, target) },
        )
    }

    private fun readPropertyValue(property: KProperty1<out Any, *>, target: Any): Any? {
        @Suppress("UNCHECKED_CAST")
        val cast = property as KProperty1<Any, *>
        cast.isAccessible = true
        return cast.get(target)
    }
}
