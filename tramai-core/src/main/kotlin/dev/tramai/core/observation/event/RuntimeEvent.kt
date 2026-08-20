package dev.tramai.core.observation.event

/**
 * Validated runtime event payload. Constructed through [of] with a typed
 * builder; schema violations (unknown attributes, attributes not allowed for
 * the event, wrong value types, missing required attributes) fail fast.
 */
class RuntimeEvent internal constructor(
    val definition: RuntimeEventDefinition,
    private val attributeValues: Map<String, Any?>,
) {
    val name: String get() = definition.name

    fun attribute(key: RuntimeAttributeKey<*>): Any? = attributeValues[key.name]

    fun attributes(): Map<String, Any?> = attributeValues

    companion object {
        /**
         * Build a validated event from an arbitrary [definition].
         *
         * Custom third-party event definitions are intentionally supported for
         * extension outside the catalogue. The catalogue remains the
         * authoritative owner of `tramai.*` identifiers: the architecture test
         * rejects any `tramai.*` literal outside [RuntimeEventCatalogue], so a
         * custom definition must use a non-`tramai.` namespace. Validation is
         * identical to catalogue events (allowed keys, required keys, value
         * types).
         */
        fun of(
            definition: RuntimeEventDefinition,
            configure: RuntimeEventBuilder.() -> Unit,
        ): RuntimeEvent {
            val builder = RuntimeEventBuilder(definition)
            builder.configure()
            return builder.build()
        }

        fun of(
            name: String,
            configure: RuntimeEventBuilder.() -> Unit,
        ): RuntimeEvent = of(RuntimeEventCatalogue.event(name), configure)
    }
}

class RuntimeEventBuilder internal constructor(
    private val definition: RuntimeEventDefinition,
) {
    private val values = mutableMapOf<String, Any?>()

    fun <T : Any> set(key: RuntimeAttributeKey<T>, value: T) {
        if (key !in definition.allowedAttributes) {
            error(
                "Attribute '${key.name}' is not allowed for event '${definition.name}'; " +
                    "allowed: ${definition.allowedAttributes.map { it.name }}",
            )
        }
        // Generics are erased on the public JVM API: enforce the canonical value
        // type at runtime too, so Java raw/unsafe callers cannot build invalid
        // payloads (e.g. an Int where the catalogue declares Long).
        require(key.valueType.isInstance(value)) {
            "Attribute '${key.name}' on event '${definition.name}' has value of type " +
                "${value::class.simpleName}, expected ${key.valueType.simpleName}"
        }
        values[key.name] = value
    }

    fun build(): RuntimeEvent {
        val missing = definition.requiredAttributes.map { it.name }.filterNot { it in values }
        require(missing.isEmpty()) {
            "Runtime event '${definition.name}' is missing required attributes: $missing"
        }
        return RuntimeEvent(definition, values.toMap())
    }
}

/**
 * Pure schema validation for [RuntimeEvent] payloads. The type of every
 * attribute value is checked against the canonical type declared on its key.
 */
object RuntimeEventValidator {
    fun validateTypes(event: RuntimeEvent) {
        val attributeKeys = RuntimeEventCatalogue.allEvents
            .flatMap { it.allowedAttributes }
            .distinctBy { it.name }
            .associateBy { it.name }
        for ((name, value) in event.attributes()) {
            if (value == null) continue
            val key = attributeKeys[name] ?: continue
            if (!key.valueType.isInstance(value)) {
                error(
                    "Attribute '$name' on event '${event.definition.name}' has value of type " +
                        "${value::class.simpleName}, expected ${key.valueType.simpleName}",
                )
            }
        }
    }
}
