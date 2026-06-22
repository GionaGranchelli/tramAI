package dev.tramai.engine

fun interface EngineEventObserver {
    fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    )
}

val NoOpEngineEventObserver: EngineEventObserver = EngineEventObserver { _, _ -> Unit }
