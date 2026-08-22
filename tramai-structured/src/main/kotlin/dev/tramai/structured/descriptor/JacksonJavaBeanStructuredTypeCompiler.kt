package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange

/**
 * Compiles Jackson [JavaType] introspection of JavaBean classes into a
 * language-neutral [StructuredTypeDescriptor].
 *
 * All Jackson-specific introspection lives here: property discovery via
 * [ObjectMapper.deserializationConfig], writable/readable filtering,
 * setter/field/constructor/getter type resolution, annotation discovery,
 * getter/field accessor construction, collection item resolution, primitive
 * wrapper normalization, enum detection, and recursion detection. Consumers
 * of the compiled descriptor never see [JavaType] again.
 */
internal class JacksonJavaBeanStructuredTypeCompiler(
    private val objectMapper: ObjectMapper,
) {

    fun compile(
        javaType: JavaType,
        nullable: Boolean,
        context: CompileContext,
    ): StructuredTypeDescriptor {
        val rawClass = javaType.rawClass

        return when {
            isScalarJavaType(rawClass) -> StructuredTypeDescriptor.Scalar(
                kind = scalarKind(rawClass),
                nullable = nullable,
            )
            java.util.Map::class.java.isAssignableFrom(rawClass) ->
                error("Unsupported structured output type: $javaType")
            java.util.Collection::class.java.isAssignableFrom(rawClass) -> {
                val itemType = javaType.contentType
                    ?: error("List structured output type must declare an item type: $javaType")
                StructuredTypeDescriptor.Collection(
                    item = compile(itemType, nullable = false, context),
                    minItems = null,
                    nullable = nullable,
                )
            }
            rawClass.isEnum -> StructuredTypeDescriptor.Enum(
                values = rawClass.enumConstants.map { (it as Enum<*>).name },
                nullable = nullable,
            )
            isJavaLangObject(rawClass) -> compileJavaBeanObject(javaType, nullable, context)
            else -> error("Unsupported structured output type: $javaType")
        }
    }

    private fun compileJavaBeanObject(
        javaType: JavaType,
        nullable: Boolean,
        context: CompileContext,
    ): StructuredTypeDescriptor.Object {
        val rawClass = javaType.rawClass
        val childContext = context.entering(rawClass.kotlin)

        val properties = javaBeanProperties(javaType)
            .map { prop -> compileProperty(prop, childContext) }

        return StructuredTypeDescriptor.Object(
            typeName = rawClass.name,
            properties = properties,
            nullable = nullable,
        )
    }

    private fun compileProperty(
        prop: JavaBeanProperty,
        context: CompileContext,
    ): StructuredPropertyDescriptor {
        val propertyType = compile(prop.type, nullable = false, context)

        val withMinItems = prop.minItems?.let { minItems ->
            require(propertyType is StructuredTypeDescriptor.Collection) {
                "Property '${prop.name}' is annotated @AiMinItems but is not a collection"
            }
            propertyType.copy(minItems = minItems.value)
        } ?: propertyType

        return StructuredPropertyDescriptor(
            name = prop.name,
            type = withMinItems,
            required = prop.required,
            description = prop.description?.value,
            range = prop.range?.let { NumericRange(it.min, it.max) },
            accessor = prop.read,
        )
    }

    /**
     * Internal model for a single JavaBean output property.
     */
    private data class JavaBeanProperty(
        val name: String,
        val type: JavaType,
        val required: Boolean,
        val description: AiDescription?,
        val range: AiRange?,
        val minItems: AiMinItems?,
        val read: ValueAccessor,
    )

    /**
     * Discovers JavaBean properties from Jackson's deserialization introspection.
     *
     * Only properties that are both writable (setter or writable field)
     * AND readable (getter or readable field) are included.
     * Getter-only calculated and setter-only write-only properties are excluded.
     */
    private fun javaBeanProperties(javaType: JavaType): List<JavaBeanProperty> {
        val beanDescription =
            objectMapper.deserializationConfig.introspect(javaType)

        return beanDescription.findProperties()
            .filter { it.couldDeserialize() }
            .filterNot { it.name == "class" }
            // Exclude write-only (setter-only) properties that validation cannot read
            .filter { it.getter != null || it.field != null }
            .sortedBy { it.name }
            .map { property ->
                val propType = resolveJavaPropertyType(property)
                JavaBeanProperty(
                    name = property.name,
                    type = propType,
                    required = true, // every discovered JavaBean property is required
                    description = findJavaAnnotation(property, AiDescription::class.java),
                    range = findJavaAnnotation(property, AiRange::class.java),
                    minItems = findJavaAnnotation(property, AiMinItems::class.java),
                    read = ValueAccessor { target -> javaPropertyRead(target, property) },
                )
            }
    }

    /**
     * Resolves the [JavaType] for a discovered bean property.
     *
     * Priority: setter parameter → backing field → constructor parameter → getter return type.
     */
    private fun resolveJavaPropertyType(property: BeanPropertyDefinition): JavaType {
        return when {
            property.setter != null ->
                property.setter.getParameterType(0)
            property.field != null ->
                property.field.type
            property.constructorParameter != null ->
                property.constructorParameter.type
            property.getter != null ->
                property.getter.type
            else ->
                error("No resolvable type for property '${property.name}'")
        }
    }

    /**
     * Reads a property value from a JavaBean instance through its getter
     * or accessible field, using Java reflection to handle package-private
     * classes that Jackson's AnnotatedMethod.getValue() cannot access.
     */
    private fun javaPropertyRead(target: Any, property: BeanPropertyDefinition): Any? {
        val getter = property.getter
        if (getter != null) {
            val method = getter.annotated
            if (method != null) {
                method.isAccessible = true
                return method.invoke(target)
            }
            return getter.getValue(target)
        }
        val field = property.field
        if (field != null) {
            val jField = field.annotated
            if (jField != null) {
                jField.isAccessible = true
                return jField.get(target)
            }
            return field.getValue(target)
        }
        return null
    }

    /**
     * Searches for a TramAI annotation on a JavaBean property.
     *
     * Order: backing field → setter parameter → constructor parameter
     * → getter or setter method.
     *
     * Backing fields are the primary target because TramAI's
     * `@Target(FIELD)` annotations are placed on Java fields.
     * Setter parameters are checked next, corresponding to
     * `AnnotationTarget.VALUE_PARAMETER`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <A : Annotation> findJavaAnnotation(
        property: BeanPropertyDefinition,
        annotationType: Class<A>,
    ): A? {
        // 1. Backing field (primary target)
        property.field?.let { field ->
            field.getAnnotation(annotationType)?.let { return it }
        }
        // 2. Setter parameter (VALUE_PARAMETER target)
        property.setter?.let { setter ->
            val method = setter.annotated
            if (method != null && method.parameterCount > 0) {
                val paramAnnotations = method.parameterAnnotations
                if (paramAnnotations.isNotEmpty()) {
                    for (ann in paramAnnotations[0]) {
                        if (annotationType.isInstance(ann)) return ann as A
                    }
                }
            }
        }
        // 3. Constructor parameter
        property.constructorParameter?.let { param ->
            try {
                param.getAnnotation(annotationType)?.let { return it }
            } catch (_: Exception) {
                // Fall through
            }
        }
        // 4. Getter method
        property.getter?.let { getter ->
            getter.getAnnotation(annotationType)?.let { return it }
        }
        // 5. Setter method
        property.setter?.let { setter ->
            setter.getAnnotation(annotationType)?.let { return it }
        }
        return null
    }

    private fun isScalarJavaType(rawClass: Class<*>): Boolean {
        return rawClass == String::class.java ||
            rawClass == CharSequence::class.java ||
            rawClass == Int::class.java || rawClass == java.lang.Integer::class.java ||
            rawClass == Long::class.java || rawClass == java.lang.Long::class.java ||
            rawClass == Short::class.java || rawClass == java.lang.Short::class.java ||
            rawClass == Byte::class.java || rawClass == java.lang.Byte::class.java ||
            rawClass == Float::class.java || rawClass == java.lang.Float::class.java ||
            rawClass == Double::class.java || rawClass == java.lang.Double::class.java ||
            rawClass == Boolean::class.java || rawClass == java.lang.Boolean::class.java
    }

    private fun scalarKind(rawClass: Class<*>): ScalarKind = when {
        rawClass == String::class.java || rawClass == CharSequence::class.java -> ScalarKind.STRING
        rawClass == Int::class.java || rawClass == java.lang.Integer::class.java ||
            rawClass == Long::class.java || rawClass == java.lang.Long::class.java ||
            rawClass == Short::class.java || rawClass == java.lang.Short::class.java ||
            rawClass == Byte::class.java || rawClass == java.lang.Byte::class.java -> ScalarKind.INTEGER
        rawClass == Float::class.java || rawClass == java.lang.Float::class.java ||
            rawClass == Double::class.java || rawClass == java.lang.Double::class.java -> ScalarKind.NUMBER
        else -> ScalarKind.BOOLEAN
    }

    /**
     * A POJO (non-primitive, non-collection, non-map user-defined type) is
     * recognised as any class that is deserializable by Jackson into an object.
     */
    private fun isJavaLangObject(rawClass: Class<*>): Boolean {
        return rawClass != Any::class.java &&
            !rawClass.isEnum &&
            !rawClass.isPrimitive &&
            rawClass.name.startsWith("java.").not() &&
            rawClass.name.startsWith("javax.").not() &&
            rawClass.name.startsWith("jakarta.").not()
    }
}
