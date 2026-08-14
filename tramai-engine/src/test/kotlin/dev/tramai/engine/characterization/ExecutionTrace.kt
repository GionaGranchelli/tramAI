package dev.tramai.engine.characterization

internal data class TraceEvent(
    val type: String,
    val attributes: Map<String, String> = emptyMap(),
)

internal class ExecutionTrace {
    private val events = mutableListOf<TraceEvent>()

    fun record(type: String, vararg attributes: Pair<String, String>) {
        events += TraceEvent(type, attributes.toMap())
    }

    fun snapshot(): List<TraceEvent> = events.toList()
}
