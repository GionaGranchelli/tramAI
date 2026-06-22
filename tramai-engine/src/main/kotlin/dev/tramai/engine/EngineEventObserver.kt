package dev.tramai.engine

interface EngineEventObserver {
    fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    )
}

object NoOpEngineEventObserver : EngineEventObserver {
    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) = Unit
}
