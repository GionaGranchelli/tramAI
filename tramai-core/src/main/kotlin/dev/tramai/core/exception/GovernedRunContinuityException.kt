package dev.tramai.core.exception

/**
 * Raised when a governed continuation cannot prove identity continuity (0.7.1d).
 *
 * A continuation RECOVERS identity from its durable witness; it never reconstructs or
 * redefines it. This failure means the persisted witness and the execution asking to
 * continue it disagree — for example a suspension created by an ungoverned execution
 * being resumed from a governed one, or a governed suspension being resumed with a
 * different workload/configuration/environment/deployment.
 *
 * Failing closed is the point: continuing with either identity would attribute one
 * execution's work to another.
 */
class GovernedRunContinuityException(
    message: String,
) : RuntimeException(message)
