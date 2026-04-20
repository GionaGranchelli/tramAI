package dev.tramai.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

internal fun Map<String, Any?>.toOpenTelemetryAttributes(): Attributes {
    val builder = Attributes.builder()
    for ((key, value) in this) {
        when (value) {
            is String -> builder.put(AttributeKey.stringKey(key), value)
            is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
            is Int -> builder.put(AttributeKey.longKey(key), value.toLong())
            is Long -> builder.put(AttributeKey.longKey(key), value)
            is Double -> builder.put(AttributeKey.doubleKey(key), value)
            is Float -> builder.put(AttributeKey.doubleKey(key), value.toDouble())
            null -> Unit
            else -> builder.put(AttributeKey.stringKey(key), value.toString())
        }
    }
    return builder.build()
}
