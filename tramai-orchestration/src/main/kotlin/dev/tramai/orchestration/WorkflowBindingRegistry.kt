package dev.tramai.orchestration

/**
 * Immutable, instance-scoped registry of typed workflow bindings for [TramaiWorker].
 *
 * A binding pairs a [Workflow] with the parts of its [WorkflowPersistence] that the
 * worker consumes: state codec, delay-wakeup scheduler, and delete-on-completion.
 * The checkpoint and lease stores inside a [WorkflowPersistence] are ignored by
 * worker bindings — [TramaiWorker] owns its fenced checkpoint store and lease
 * lifecycle exclusively.
 *
 * The relationship between workflow definition, state codec, and persistence
 * behaviour is established once, by the compiler, and never reconstructed through
 * name-only lookup or unchecked casts at execution time.
 *
 * Usage:
 *
 * ```
 * val bindings = WorkflowBindingRegistry {
 *     bind(orderWorkflow, orderPersistence)
 *     bind(refundWorkflow, refundPersistence)
 * }
 *
 * val worker = TramaiWorker(
 *     ...
 *     workflowBindings = bindings,
 * )
 * ```
 *
 * Registration semantics are deterministic: the same workflow name may be bound under
 * multiple definition versions, but a given name/version maps to exactly one
 * state/result identity and one workflow definition. Conflicting or duplicate
 * registrations are rejected when the registry is built, never silently overwritten.
 */
class WorkflowBindingRegistry internal constructor(
    internal val bindings: Map<WorkflowBindingKey, WorkflowBinding<*, *>>,
) {
    /**
     * Resolves the binding for [workflowName] at [definitionVersion], or null when
     * this registry does not handle that workflow.
     */
    internal fun resolve(
        workflowName: String,
        definitionVersion: String,
    ): WorkflowBinding<*, *>? = bindings[WorkflowBindingKey(workflowName, definitionVersion)]

    companion object {
        /**
         * Builds an immutable [WorkflowBindingRegistry] from [configure].
         *
         * Validation runs here, before any worker is constructed: conflicting or
         * duplicate registrations fail deterministically and cannot surface later
         * as runtime side effects.
         */
        operator fun invoke(
            configure: WorkflowBindingRegistryBuilder.() -> Unit,
        ): WorkflowBindingRegistry = WorkflowBindingRegistryBuilder().apply(configure).build()
    }
}

/**
 * Composition-time builder for [WorkflowBindingRegistry].
 *
 * The constructor is internal: instances are only created through
 * [WorkflowBindingRegistry.invoke]; callers receive this builder as the lambda receiver.
 */
class WorkflowBindingRegistryBuilder internal constructor() {
    private val bindings = linkedMapOf<WorkflowBindingKey, WorkflowBinding<*, *>>()

    /**
     * Binds [workflow] with its [persistence] configuration.
     *
     * Only the worker-consumed persistence fields are captured: [WorkflowPersistence.stateCodec],
     * [WorkflowPersistence.delayWakeupScheduler], and
     * [WorkflowPersistence.deleteCheckpointOnCompletion]. Checkpoint and lease stores
     * supplied in [persistence] are ignored by the binding — the worker owns them.
     *
     * The generic relationship between workflow state type and state codec is enforced
     * here by the compiler: [WorkflowPersistence] is parameterized by the same state
     * type as [Workflow], so a mismatched pairing cannot be expressed.
     */
    fun <S, R> bind(
        workflow: Workflow<S, R>,
        persistence: WorkflowPersistence<S>,
    ) {
        val key = WorkflowBindingKey(workflow.name, workflow.definitionVersion)
        val existing = bindings[key]
        if (existing != null) {
            require(
                existing.workflow.stateType == workflow.stateType &&
                    existing.workflow.resultType == workflow.resultType,
            ) {
                "Workflow '${workflow.name}' definition version '${workflow.definitionVersion}' is already bound " +
                    "with state/result types (${existing.workflow.stateType}, ${existing.workflow.resultType}); " +
                    "refusing to re-bind with (${workflow.stateType}, ${workflow.resultType})"
            }
            require(existing.workflow.definitionDigest() == workflow.definitionDigest()) {
                "Workflow '${workflow.name}' definition version '${workflow.definitionVersion}' is already bound " +
                    "to a different workflow definition " +
                    "(digest ${existing.workflow.definitionDigest()} vs ${workflow.definitionDigest()}); " +
                    "same name/version/types must identify the same definition"
            }
            error(
                "Workflow '${workflow.name}' definition version '${workflow.definitionVersion}' " +
                    "is registered more than once",
            )
        }
        bindings[key] = WorkflowBinding(
            workflow = workflow,
            stateCodec = persistence.stateCodec,
            delayWakeupScheduler = persistence.delayWakeupScheduler,
            deleteCheckpointOnCompletion = persistence.deleteCheckpointOnCompletion,
        )
    }

    internal fun build(): WorkflowBindingRegistry = WorkflowBindingRegistry(bindings.toMap())
}

/**
 * Durable identity of a workflow binding: workflow name plus definition version.
 *
 * The same name may be bound under multiple versions (orders/v1 and orders/v2 can
 * coexist); a name/version pair maps to exactly one binding.
 */
internal data class WorkflowBindingKey(
    val workflowName: String,
    val definitionVersion: String,
)

/**
 * A workflow paired with its persistence configuration.
 *
 * The generic relationship is preserved on this class: the state codec decodes the
 * exact state type the workflow consumes. The erased [erased] view is the only place
 * where type erasure is bridged, and it is contained inside the generically
 * constructed binding rather than leaking casts into worker orchestration.
 */
internal class WorkflowBinding<S, R>(
    val workflow: Workflow<S, R>,
    val stateCodec: WorkflowStateCodec<S>,
    val delayWakeupScheduler: WorkflowDelayWakeupScheduler? = null,
    val deleteCheckpointOnCompletion: Boolean = true,
) {
    /**
     * Erased execution view. Safe by construction: S and R are subtypes of Any?,
     * and the compiler has already verified that the codec and workflow share S.
     */
    @Suppress("UNCHECKED_CAST")
    val erased: ErasedWorkflowBinding = ErasedWorkflowBinding(
        workflow = workflow as Workflow<Any?, Any?>,
        stateCodec = stateCodec as WorkflowStateCodec<Any?>,
        delayWakeupScheduler = delayWakeupScheduler,
        deleteCheckpointOnCompletion = deleteCheckpointOnCompletion,
    )
}

/**
 * Erased execution view of a [WorkflowBinding], used by the worker execution path.
 *
 * This type is non-generic by design: the worker resolves checkpoints and drives
 * execution without performing any generic recovery. The type boundary was crossed
 * once, at composition time, inside [WorkflowBinding.erased].
 */
internal class ErasedWorkflowBinding(
    val workflow: Workflow<Any?, Any?>,
    val stateCodec: WorkflowStateCodec<Any?>,
    val delayWakeupScheduler: WorkflowDelayWakeupScheduler?,
    val deleteCheckpointOnCompletion: Boolean,
) {
    suspend fun replayDescriptor(
        checkpoint: WorkflowCheckpoint,
        context: WorkflowContext,
    ): WorkflowStepReplayDescriptor? = workflow.replayDescriptorAt(
        stepIndex = checkpoint.nextStepIndex,
        state = stateCodec.decode(checkpoint.statePayload),
        context = context,
    )
}
