package dev.tramai.engine.characterization

internal class ExecutionTraceSink(private val trace: ExecutionTrace) {
    fun record(type: String, vararg attributes: Pair<String, String>) = trace.record(type, *attributes)
}
