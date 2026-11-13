package dev.tramai.scheduler

/**
 * In-memory governed schedule bindings (0.7.1d).
 *
 * Deliberately a separate store rather than an extra interface on
 * [InMemoryWorkflowSchedulerStore]: a schedule binding is its own durable concern, and the
 * scheduler store stays what it always was.
 */
class InMemoryGovernedScheduleBindingStore : GovernedScheduleBindingStore {
    private val bindings = linkedMapOf<String, GovernedScheduleBinding>()

    override suspend fun putGovernedScheduleBinding(binding: GovernedScheduleBinding) {
        synchronized(this) {
            bindings[binding.scheduleId] = binding
        }
    }

    override suspend fun getGovernedScheduleBinding(scheduleId: String): GovernedScheduleBinding? =
        synchronized(this) { bindings[scheduleId] }
}
