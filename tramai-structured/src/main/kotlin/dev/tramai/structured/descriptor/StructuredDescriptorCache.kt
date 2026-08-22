package dev.tramai.structured.descriptor

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

/**
 * Instance-scoped, concurrency-safe cache of compiled [StructuredTypeDescriptor]s.
 *
 * Scoped to one handler instance so entries are automatically bound to that
 * handler's [com.fasterxml.jackson.databind.ObjectMapper] configuration —
 * two handlers with differently configured mappers never share descriptor
 * state. Only fully compiled descriptors are stored; compilation failures
 * (unsupported types, recursion) are never cached.
 */
internal class StructuredDescriptorCache {

    private val cache = ConcurrentHashMap<String, StructuredTypeDescriptor>()

    fun getOrCompile(targetType: KType, compile: (KType) -> StructuredTypeDescriptor): StructuredTypeDescriptor {
        val key = cacheKey(targetType)
        return cache.computeIfAbsent(key) { compile(targetType) }
    }

    fun size(): Int = cache.size

    private fun cacheKey(targetType: KType): String = buildString {
        append(targetType.classifier?.let { it.toString() } ?: "?")
        targetType.arguments.forEach { argument ->
            append('<').append(argument.type?.toString() ?: "?").append('>')
        }
        if (targetType.isMarkedNullable) append('?')
    }
}
