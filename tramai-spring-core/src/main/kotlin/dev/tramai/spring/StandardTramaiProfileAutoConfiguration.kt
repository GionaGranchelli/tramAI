package dev.tramai.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Import

/**
 * Selects the standard TramAI runtime for Spring Boot applications.
 *
 * `standard` remains the default when no explicit profile is provided, preserving
 * existing `tramai-spring` behavior. The sovereign starter contributes a low-
 * precedence default of `tramai.profile=sovereign`, so adding that starter cannot
 * accidentally activate both runtimes.
 */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "tramai",
    name = ["profile"],
    havingValue = "standard",
    matchIfMissing = true,
)
@Import(TramaiAutoConfiguration::class)
internal class StandardTramaiProfileAutoConfiguration
