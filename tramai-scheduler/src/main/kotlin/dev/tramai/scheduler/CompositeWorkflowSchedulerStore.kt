package dev.tramai.scheduler

/**
 * Scheduler store that also carries the governed schedule-binding capability (0.7.1d).
 *
 * It exists so a timer can discover the durable binding authority from its STORE rather than
 * from the current in-memory registration. A registration only declares intent, so reading
 * the binding through the registration let an ordinary re-registration hide an existing
 * durable governed binding and run the next tick unattributed.
 *
 * Both legs stay the released implementations: this composes them (Kotlin interface
 * delegation) instead of reimplementing either, and it adds no state of its own.
 */
class CompositeWorkflowSchedulerStore(
    private val schedules: WorkflowSchedulerStore,
    private val bindings: GovernedScheduleBindingStore,
) : WorkflowSchedulerStore by schedules,
    GovernedScheduleBindingStore by bindings
