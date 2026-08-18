package dev.tramai.engine.invocation

import dev.tramai.core.exception.ConfigurationException
import dev.tramai.engine.planning.ServiceDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * JVM proxy adapter. Responsibility is JVM adaptation only:
 *
 * - Object methods (toString/hashCode/equals)
 * - engine-open check
 * - method → [dev.tramai.engine.planning.OperationExecutionPlan] resolution
 * - pre-dispatch context creation (conversation ID)
 * - blocking/suspend JVM adaptation, including the frozen suspend lifecycle
 *   bridge (synchronized invocation registry, exactly-once resume)
 * - delegation to [InvocationExecutionCoordinator]
 *
 * It does NOT implement provider, tool, approval, DLP, cache, memory, or
 * streaming algorithms.
 */
internal class TramaiInvocationHandler(
    private val serviceDefinition: ServiceDefinition,
    private val lifecycleJob: Job,
    private val lifecycleScope: CoroutineScope,
    private val isClosed: AtomicBoolean,
    private val engineThreadMarker: ThreadLocal<Boolean>,
    private val activeInvocationJobs: MutableSet<Job>,
    private val contextFactory: InvocationContextFactory,
    private val executionCoordinator: InvocationExecutionCoordinator,
) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return handleObjectMethod(proxy, method, args.orEmpty())
        }

        check(!isClosed.get()) { "Tramai runtime is closed" }

        val plan = serviceDefinition.operations[method]
            ?: throw ConfigurationException("No operation metadata registered for ${method.name}")

        // Conversation-ID resolution happens here — before the suspend/blocking
        // dispatch — exactly as in the monolithic handler, so timing/error
        // semantics for suspend methods are unchanged.
        val context = contextFactory.create(plan, method, args.orEmpty())

        return if (plan.definition.isSuspend) {
            invokeSuspend(context, args.orEmpty())
        } else {
            invokeBlocking(context)
        }
    }

    private fun invokeBlocking(context: InvocationExecutionContext): Any? {
        // Run the blocking call as a child of the engine's OWN lifecycle
        // job (not the caller-supplied job/scope): close() cancels and
        // joins lifecycleJob, so it can terminate a blocking provider
        // that is still executing when the engine is closed. The thread
        // marker marks this coroutine as engine-owned so a blocking call
        // that itself invokes close() skips the join (avoiding a
        // self-deadlock on lifecycleJob).
        val result = runBlocking(lifecycleJob + engineThreadMarker.asContextElement(true)) {
            executionCoordinator.execute(context)
        }
        // The engine may have closed while this blocking call was in
        // flight. Never deliver a result computed against a closed engine:
        // the caller sees the fixed lifecycle error instead.
        check(!isClosed.get()) { "Tramai runtime is closed" }
        return result
    }
    private fun invokeSuspend(
        context: InvocationExecutionContext,
        args: Array<out Any?>,
    ): Any {
        // Kotlin suspend proxies receive the continuation as the last JVM argument.
        @Suppress("UNCHECKED_CAST")
        val continuation = args.lastOrNull() as? Continuation<Any?>
            ?: throw ConfigurationException("Suspend invocation for ${context.plan.definition.method.name} is missing its continuation")

        val callArguments = args.dropLast(1)
        // Launch as a child of the CALLER's job (continuation.context, with the
        // caller's Job element retained) so parent cancellation propagates
        // synchronously into the in-flight invocation (validated by the
        // ToolSafeFailureContract / StructuredOutputFailureBoundary
        // parent-cancellation tests). The invocation RUNS on the engine's own
        // dispatcher (lifecycleScope), NOT the caller's: if it ran on the
        // caller's single-threaded dispatcher, close() joining it could
        // deadlock when that thread is blocked inside close(). Engine close()
        // owns the work: the launch+add is synchronized with close()'s cancel
        // snapshot, the closed flag is re-checked INSIDE the lock, and close()
        // cancels AND joins every tracked invocation. Exactly-once resume: the
        // block records the outcome BEFORE resuming the continuation, and
        // invokeOnCompletion resumes with a cancellation when the block never
        // ran (job cancelled pre-start by close()) — otherwise the caller's
        // suspension would freeze forever.
        val resumed = java.util.concurrent.atomic.AtomicReference<Result<Any?>?>(null)
        val launched = synchronized(activeInvocationJobs) {
            check(!isClosed.get()) { "Tramai runtime is closed" }
            val job = lifecycleScope.launch(
                continuation.context.minusKey(kotlin.coroutines.ContinuationInterceptor) +
                    engineThreadMarker.asContextElement(true),
            ) {
                var result = runCatching { executionCoordinator.execute(context.copy(arguments = callArguments)) }
                // Never deliver a success computed against a closed engine: the
                // engine may have closed while the invocation was in flight.
                // The caller sees the fixed lifecycle error instead (mirrors
                // the blocking path).
                if (isClosed.get() && result.isSuccess) {
                    result = Result.failure(IllegalStateException("Tramai runtime is closed"))
                }
                resumed.set(result)
                continuation.resumeWith(result)
            }
            activeInvocationJobs += job
            job
        }
        launched.invokeOnCompletion { cause ->
            // Registry mutations obey the same monitor: launch+add (above) and
            // close()'s snapshot (in close()) are synchronized on
            // activeInvocationJobs, so removal must be too. An unsynchronized
            // removal can race Kotlin's toList() size-1 fast path inside the
            // snapshot (size()==1, then remove, then iterator().next() throws
            // NoSuchElementException), making close() throw and the closer
            // thread die silently — the original CI hang.
            synchronized(activeInvocationJobs) {
                activeInvocationJobs -= launched
            }
            if (resumed.get() == null) {
                // Block never ran (e.g. cancelled before the dispatcher started
                // it): resume so the caller's suspension does not freeze.
                continuation.resumeWith(
                    Result.failure(
                        cause as? CancellationException ?: CancellationException("Engine closed", cause),
                    ),
                )
            }
        }
        return COROUTINE_SUSPENDED
    }
    private fun handleObjectMethod(
        proxy: Any,
        method: Method,
        args: Array<out Any?>,
    ): Any? = when (method.name) {
        "toString" -> "TramaiProxy(${serviceDefinition.serviceType.qualifiedName})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args.firstOrNull()
        else -> throw UnsupportedOperationException("Unsupported Object method: ${method.name}")
    }
}

