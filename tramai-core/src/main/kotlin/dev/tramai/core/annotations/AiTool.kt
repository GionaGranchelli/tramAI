package dev.tramai.core.annotations

import dev.tramai.core.model.SideEffectLevel

/**
 * Marks a method for discovery as a portable tool by framework adapters.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class AiTool(
    /** Explicit tool name; defaults to the method name. */
    val name: String = "",
    /** Tool description injected into model tool definitions. */
    val description: String,
    /** Whether the tool is safe to retry on transient failure. */
    val idempotent: Boolean = false,
    /** Side-effect classification for the tool. */
    val sideEffectLevel: SideEffectLevel = SideEffectLevel.UNKNOWN,
)
