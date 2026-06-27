package dev.tramai.spring.sovereign.ops

/**
 * Contribution wrapper for [ApprovedContinuationResumeWorkerObserver] instances.
 *
 * Modules like Micrometer and OpenTelemetry contribute their observers
 * through this type instead of registering a final
 * [ApprovedContinuationResumeWorkerObserver] bean. The base ops auto-config
 * collects all contributions and composes them into a single delegate chain
 * behind the recording observer.
 *
 * This type intentionally does NOT extend
 * [ApprovedContinuationResumeWorkerObserver] to prevent Spring bean type
 * collisions.
 */
data class ApprovedContinuationResumeWorkerObserverContribution(
    val observer: ApprovedContinuationResumeWorkerObserver,
)
