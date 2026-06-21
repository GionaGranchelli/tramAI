package dev.tramai.spring.sovereign.ops

/**
 * Operations for checking sovereign runtime health and persistence state.
 *
 * Reports which store beans are available in the Spring context and
 * whether stores appear to be file-backed or in-memory.
 */
fun interface SovereignRuntimeOperations {

    /**
     * Returns the current runtime status, including store availability
     * and detected persistence mode.
     */
    fun status(): SovereignRuntimeStatus
}
