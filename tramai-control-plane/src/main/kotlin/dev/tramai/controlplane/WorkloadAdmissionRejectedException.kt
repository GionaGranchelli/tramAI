package dev.tramai.controlplane

/**
 * Raised when a new governed execution is not authorized by the authoritative
 * registration (0.7.1d).
 *
 * [reason] is a stable machine-readable code, not a message for end users:
 * - `workload-deployment-not-registered`
 * - `workload-deployment-configuration-mismatch`
 * - `workload-deployment-not-active`
 *
 * Failing closed is the point: admitting a run whose deployment is not the
 * authoritative registration would create execution attribution that nothing
 * authoritative backs.
 */
class WorkloadAdmissionRejectedException(
    val reason: String,
) : RuntimeException(reason)
