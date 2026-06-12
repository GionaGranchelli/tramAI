package dev.tramai.standalone

import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.TramaiEngine
import kotlin.reflect.KClass

/**
 * Runtime session owning exactly one [TramaiEngine] instance for service creation
 * and approval-resume operations.
 *
 * Created via [Tramai.runtime] or [SovereignTramai.runtime].
 * Must be closed after use to release engine-level coroutine resources.
 */
class TramaiRuntime internal constructor(
    private val engine: TramaiEngine,
) : AutoCloseable {

    /**
     * Creates a service proxy for the given service type.
     */
    fun <T : Any> create(serviceType: KClass<T>): T =
        engine.create(serviceType)

    /**
     * Registers a service type without creating a proxy, so that its operations
     * are available for resume via the trusted [ResumeOperationRegistry].
     *
     * Repeated identical registration is allowed (idempotent).
     * Conflicting registration (same service/method with different definition)
     * fails closed.
     *
     * Use after runtime restart before calling [resumeApproval]:
     * ```
     * runtime.registerService<InvoiceIntelligenceService>()
     * runtime.resumeApprovalTyped<InvoiceAssessment>(command)
     * ```
     */
    fun registerService(serviceType: KClass<*>) {
        engine.registerService(serviceType)
    }

    /**
     * Resumes an approval-suspended tool execution.
     */
    suspend fun resumeApproval(command: ResumeApprovalCommand): Any? =
        engine.resumeApproval(command)

    /**
     * Typed convenience overload for [resumeApproval].
     */
    @Suppress("UNCHECKED_CAST")
    suspend inline fun <reified R> resumeApprovalTyped(
        command: ResumeApprovalCommand,
    ): R = resumeApproval(command) as R

    override fun close() {
        engine.close()
    }
}

/**
 * Reified convenience overload for [TramaiRuntime.create].
 */
inline fun <reified T : Any> TramaiRuntime.create(): T = create(T::class)

/**
 * Reified convenience overload for [TramaiRuntime.registerService].
 */
inline fun <reified T : Any> TramaiRuntime.registerService(): Unit =
    registerService(T::class)
