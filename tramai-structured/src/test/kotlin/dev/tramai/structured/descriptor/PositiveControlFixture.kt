package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import kotlin.reflect.full.memberProperties

/**
 * Deliberate reflection leak used ONLY as a positive control by
 * [StructuredDescriptorArchitectureTest]. This class must keep referencing
 * both Kotlin reflection and Jackson introspection entry points — if the
 * ASM scan ever stops flagging it, the architecture guard has silently
 * broken.
 */
@Suppress("UNUSED")
internal class PositiveControlFixture {
    fun kotlinLeak(value: Any): Set<String> =
        value.javaClass.kotlin.memberProperties.map { it.name }.toSet()

    fun jacksonLeak(property: BeanPropertyDefinition): String = property.name
}
