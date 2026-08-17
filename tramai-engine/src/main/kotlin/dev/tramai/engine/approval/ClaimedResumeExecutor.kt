package dev.tramai.engine.approval

internal fun interface ClaimedResumeExecutor {
    suspend fun execute(request: ClaimedResumeExecutionRequest): Any?
}
